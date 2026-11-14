# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Commands

**Node 24 (`.nvmrc`); minimum 22.12 (`engines`).** vite 7 requires ≥20.19/≥22.12 and Node 18 is
EOL, so an 18.x shell cannot build this repo at all — `npm run build` dies in the vite config load
with an `ERR_REQUIRE_ESM` stack trace that looks like a dependency bug rather than a version
problem. `nvm use` before anything. This bites the Playwright harness hardest: `playwright.config
.ts` has a `webServer` block, so on a stale shell the suite spawns its own dev server, fails at
startup, and reads as a broken harness.

```bash
nvm use              # Node 24, per .nvmrc — do this first in a new shell
npm run dev          # start Vite dev server (defaults to port 3000 or 5173)
npm run build        # production build
npm run build:dev    # development build
npm run lint         # ESLint
npm run format       # Prettier (write)

npm run test:visual        # Playwright visual + behavioural suite (build doc §2 harness)
npm run test:visual:update # …re-record baselines (review every diff in the PR)
npm run test:unit          # plain-node unit tests: chart theme, calendar, correlation, distributions
npm run test:backend       # Java backend tests (JUnit + Testcontainers) via maven:3.9-eclipse-temurin-17 in Docker
npm run test:executor      # stdlib unittest for executor/executor.py
```

There is no local Maven: `test:backend` mounts `Java backend/` and `~/.m2` into a Maven container
and hands it the host Docker socket so Testcontainers can start `postgres:16-alpine`
(`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal` lets the containerised JVM reach it). Run it
from the repo root. Surefire prints "Surefire is going to kill self fork JVM … 30 seconds after
System.exit(0)" at the end — pre-existing, harmless, the build still reports SUCCESS.

The visual harness stubs every `/api/*` call (`tests/visual/fixtures.ts`) and fails a test if any
request escapes the stub set, so the suite never needs the Spring backend running. Beyond
screenshots it asserts the contracts that a screenshot cannot see: the §8.3 scroll/memo budgets,
the §14 "a streamed price re-renders zero rows" rule, and `hydration.spec.ts`, which loads every
route and fails on any React hydration mismatch (§1.3).

To run against the Spring Boot backend:
```bash
VITE_API_BASE_URL=http://localhost:8080 npm run dev
```

### Docker

The full stack (TimescaleDB, Spring backend, Jupyter, frontend) runs via the root `docker-compose.yml`. **The frontend container is behind the `full` profile**:

```bash
docker compose up -d                          # db + backend + jupyter only (daily dev; run frontend on host)
docker compose --profile full up -d --build   # entire stack containerized
docker compose --profile full down            # stop everything (keeps db volume)
```

Notes:
- Builds use BuildKit cache mounts (npm + Maven) and a slim `.dockerignore` context — don't remove the `RUN --mount=type=cache` lines from the Dockerfiles.
- The frontend container serves the **production build** via `wrangler dev` (workerd runs the TanStack Start bundle from `dist/`, glibc `node:22-slim` runtime — workerd has no musl build). It is NOT a vite dev server: assets are content-hashed and immutable, so stale-chunk/dep-reoptimization reload failures can't happen. For frontend work, prefer host `npm run dev` over rebuilding the frontend image.
- Explicitly targeting a service (`docker compose up -d --build frontend`) works without the profile flag.

## Architecture

This is a **desktop-first dark trading terminal** — a TanStack Start SSR app (NOT a plain SPA) that visualises and explores OHLCV market data stored in a Spring Boot + TimescaleDB backend (the `stock-tracker` repo).

### Stack

| Layer | Library |
|---|---|
| Router | TanStack Router (file-based, `src/routes/`) |
| Data fetching | TanStack Query (React Query) |
| UI components | shadcn/ui (Radix UI primitives + Tailwind v4) |
| Charts | `lightweight-charts` v5 (TradingView) |
| Build | Vite via `@lovable.dev/vite-tanstack-config` |
| Deployment | Cloudflare Workers (via `wrangler.jsonc`) |

### Key source layout

```
src/
  lib/api/          ← typed HTTP client for the Spring backend
    client.ts       ← apiFetch(), unwraps Spring { data: ... } envelopes
    config.ts       ← VITE_API_BASE_URL / VITE_WS_URL / VITE_WS_ENABLED
    metrics.ts      ← §15.4 B7 latency contract (NOT implemented backend-side — the panel renders `pending`)
    types.ts        ← PriceBar, Trade, Interval — must stay in sync with backend DTOs
    prices.ts       ← pricesApi.range()
    symbols.ts      ← symbolsApi CRUD
  lib/indicators.ts ← pure-JS SMA, EMA, RSI, MACD, Bollinger (no deps, NaN warmup)
  lib/price-bars.ts ← client-side OHLCV aggregation by Interval bucket
  lib/date-range.ts ← preset ranges (1D/5D/1M…), UTC conversion for API params
  lib/datetime.ts   ← datetime-local input → ISO UTC, interpreted in ET (NOT the runtime's zone — see SSR notes)
  lib/correlation.ts ← §15.1 pure correlation math (log returns, timestamp alignment, Pearson matrix)
  lib/pnl-distribution.ts ← §15.4 B9 bindings: PnL by ET hour / ET weekday / outcome bucket
  lib/event-calendar.ts ← §15.3 B5 event feed + `blackoutWindows()` (the machine-readable half the risk manager consumes)
  lib/data/economic-releases.ts ← maintained FOMC/CPI/NFP schedule (ET dates + times, `VERIFIED_THROUGH_DATE`)
  lib/realtime/     ← §14 M5 hot path: socket.ts (flagged transport + backoff + ?wsMock=1), symbol-stores.ts (per-symbol useSyncExternalStore cells), coalesce.ts (module-scope rAF flusher)
  lib/python-export.ts ← generates pandas + SQLAlchemy snippets mirroring the UI query
  lib/chart-primitives/ ← §11 M2 canvas-facing primitives: session-shading.ts (extended-hours bands pane primitive + sun/moon tickMarkFormatter + etDayKey/prevSessionClose), price-pill.ts (direction-colored last-price gutter pill + replay pill), chart-tokens.ts (readCssToken — lazy computed-style reads, never module scope/draw path)
  lib/command-registry.ts ← §17 command registry (registerCommands on mount/cleanup, stable IDs, recents in localStorage) + palette open state + hotkey scope stack
  lib/active-chart-panel.ts ← §17 active-panel signal: palette symbol jumps apply to the last-interacted ChartPanel; off "/", a pending symbol is stashed and consumed by the charts page
  lib/theme.ts        ← §13 theme prefs store (theme/convention/colorblind in the ui.theme cookie), no-flash script source; no localStorage
  components/
    PriceChart.tsx  ← lightweight-charts wrapper; one chart instance with native panes (volume, RSI, MACD) + §11 M2 primitives (session bands, price pill); focusable chart surface with §17 hotkeys
    ChartPanel.tsx  ← chart panel + data panel (react-resizable-panels v4 Group/Panel/Separator; split layout cookie-persisted via useDefaultLayout + §13 cookie storage); ALL chart controls live on the §11 M2 toolbars — top icon row (type, indicators, compare, alerts) and bottom row (Range + Dates + Interval), one state path shared with the §17 hotkeys. The pre-terminal shadcn control bar they replaced is gone (M2a); no `Custom` range segment by design — editing a date sets rangePreset=CUSTOM and deselects every segment. Registers its own palette commands
    CommandPalette.tsx ← §17 ⌘K palette on cmdk (local commands + debounced symbol jump)
    ThemeSettings.tsx ← §13.3 settings popover (4-theme picker + market-convention + colorblind toggles); mounted in TopBar's left slot
    BacktestingChart.tsx ← candlestick chart for backtesting replay; §11 M2 price pill carries the replay position (`Bar X / N`)
    EquityChart.tsx ← equity curve chart
    SymbolPicker.tsx
    DateRangePicker.tsx
    PythonExport.tsx
    metrics/        ← §15 M6–M8 analysis panels (data-fetching hosts for the renderers below): StrategiesPanel (B1), UniversePanel (B2 + promote footer), CorrelationPanel (B4), DistributionsPanel (B9), LatencyPanel (B7)
    terminal/       ← §4/§6 terminal chrome: TerminalPanel, PanelTabs, UnderlineTabs, IconButton, MetaGrid, PanelState, TickerTape (§14.1), CorrelationMatrix (§15.1 canvas), PnlDistribution (§15.4), LatencyWaterfall (§15.4), EventCalendar (§15.3) (+ art/ — 96×96 hand-drawn state SVGs), controls/ (§9 W7 form primitives: SegmentedControl, SemanticToggle, UnitInput, QuickFillRow — Radix ToggleGroup items are role="radio", arrows move focus only, Enter/Space selects), table/ (§8 W6: TerminalTable + TerminalRow memo boundary + cells.tsx + density.tsx + presets/ — new tables are preset arrays, never new components)
    backtesting/    ← backtesting sub-components (TradeLog on §8 TerminalTable via presets/trades.ts — single-line rows, no prices; RunHistory)
    ui/             ← shadcn/ui components (don't edit manually — use shadcn CLI)
  hooks/
    useAgentSkills.ts ← Packs §9 Settings → Agent skills: packs, per-pack toggle, per-agent visibility
    useChartBase.ts ← shared chart lifecycle: chartOptions(), toTs, chart create/destroy; registers each chart with the §13.4 ChartThemeRegistry
    useChartTheme.ts ← reactive §3.4 chart theme for components (re-resolves on theme/convention/cb attribute change)
    useThemePrefs.ts ← useSyncExternalStore binding for the §13 theme store (server snapshot = root loader value)
    useTickerItems.ts ← §14.1 tape source: live positions first, charted symbols after; seeds the realtime stores from the cold snapshot
    useFlashOnChange.ts ← §14.3 flash: classList write in an effect (a ref mutated during render is not idempotent — dev double-render silently swallowed the flash)
    useHotkeys.ts   ← §17 scoped chart single-keys (1/5/15/H/D interval pin, R reset, V volume, / indicators, bare letter → symbol jump)
  routes/
    __root.tsx      ← layout shell: QueryClientProvider + AppSidebarWithHealth (§6.4: health-probe failures dot the owning nav icons) + <Outlet> + global ⌘K handler & CommandPalette; root loader reads ui.theme server-side and the shell stamps data-theme/data-convention/data-cb (+ inline no-flash script)
    index.tsx       → /            Charts page (live)
    data.tsx        → /data        Raw data explorer with virtualised table (live)
    backtesting.tsx → /backtesting Backtesting replay + strategy runner (live; §9 W7: replay speed is a SegmentedControl, starting capital a UnitInput + QuickFillRow presets — both write straight to URL search params; §12 M3: `cursor` + `view` replay params — cursor writes in the rAF flush, view debounced 300ms, both replace-only; view restores via BacktestingChart's initialRange prop, never through visibleRange state)
    live.tsx        → /live        Live portfolio + positions + §15.3 economic calendar (blackout windows next to the book that they constrain)
    metrics.tsx     → /metrics     §15 analysis workspace — PanelTabs over Strategies (B1) / Universe (B2) / Correlation (B4) / Distributions (B9) / Latency (B7); `?tab=` and `?strategy=` are URL state (replace-only)
    models.tsx      → /models      Model registry (live)
    dev/controls.tsx → /dev/controls §9 W7 storyboard: every form primitive × state, DEV-only (production renders a redirect to /); baseline for tests/visual/controls.spec.ts
  routeTree.gen.ts  ← AUTO-GENERATED by TanStack Router plugin — never edit manually
```

### SSR notes
- This is TanStack Start SSR — client-only state is handled two ways (build doc §13: "URL = where you are; cookie = how the workspace is arranged; server = what the data is"): workspace arrangement (panel set, chart height) lives in `ui.*` cookies (`src/lib/cookie-state.ts`) and is read server-side by the `/` route loader via `readUiCookieServerFn` (createServerFn + getCookie), so first paint is SSR-correct; per-panel internals restore post-mount behind ChartPanel's `mounted` gate, which renders the geometry-identical `PanelSkeleton` (sizing co-located in `src/components/PanelSkeleton.tsx`) until then. No render-then-snap, no `skipPersist`.
- Location-like view state belongs in `validateSearch` (see `/backtesting`, `/data`). **Relative defaults resolve in the route LOADER, never inside `validateSearch`** — validateSearch runs once on the server and again during hydration, so a `new Date()` in there returns two different answers and any rendered text derived from it (the /data Python snippet did exactly this) is a permanent hydration mismatch. Loader data is computed once server-side and dehydrated, so both renders read the same value.
- For the same reason `lib/datetime.ts` interprets `datetime-local` values in **ET**, not the runtime's local zone: `new Date(y, m, d, h, min)` reads the runtime's timezone, which differs between the SSR process and the browser. ET is also the right product answer — every other timestamp in this app is ET. `tests/visual/hydration.spec.ts` guards every route against regressions here.
- Theme prefs (`ui.theme` cookie) are read by the ROOT route loader via the same `readUiCookieServerFn` path; the shell stamps `data-theme`/`data-convention`/`data-cb` on `<html>` server-side, with an inline pre-paint script (`NOFLASH_SCRIPT` from `lib/theme.ts`) as the fallback that also resolves `prefers-color-scheme`. `<html>` carries `suppressHydrationWarning` for the no-cookie media-default case (next-themes pattern); post-hydration the `useThemePrefs` store re-stamps attributes declaratively.
- `lightweight-charts` at 5.2.x (build doc §10) — minor upgrades require re-running chart visual baselines
- Unused shadcn scaffold deps (`embla-carousel-react`, `vaul`, `input-otp`) are accepted scaffold — leave installed; removing them risks shadcn regen churn for zero runtime win

### Theme system (M4, build doc §13)

Four themes — `terminal-dark` (default), `paper-light`, `high-contrast`, `graphite-neutral` — selected by `data-theme` on `<html>`; token values live in per-theme blocks in `src/styles.css` (plan §5.4, WCAG-verified — don't retune). Two more independent axes compose via small CSS blocks: `data-convention="east-asian"` swaps `--dir-up`/`--dir-down` (red up), `data-cb="on"` swaps in the Okabe-Ito pair; precedence is by specificity (`[data-cb][data-convention]` (0,3,0) > single-axis (0,2,0) > base :root) so **colorblind always wins over convention**, source-order independent. Theme blocks store the raw pairs in `--_dir-*` privates (a swapped `--dir-up: var(--dir-down)` self-map would be a var() cycle). Components only consume tokens — nothing component-side changes per theme.

- Prefs persist in ONE `ui.theme` JSON cookie (`{ theme, convention, cb }`); the `theme` key is omitted until explicitly picked — an unset key is what keeps the `prefers-color-scheme` default + its change listener alive (listener detached on first explicit pick; re-clicking the active tab also counts as a pick).
- `.dark` class is kept on the three dark themes only (shadcn `dark:` variants, e.g. `ui/alert`, gate on it; sonner's `Toaster` theme follows the same `isDarkTheme()`).
- Charts: `chart-theme.ts`'s ChartThemeRegistry — `useChartBase` registers each chart's chart-level `applyOptions(chartOptions(theme))`; a `MutationObserver` on the three `<html>` attributes calls `applyAllChartThemes()` (invalidate → resolve once → apply to all + notify). Series-level colors re-apply via `useChartTheme()` (PriceChart re-runs its theme-dep effects; BacktestingChart/EquityChart `applyOptions` in place). No chart recreation, no state loss, no localStorage.

### Realtime hot path (M5, build doc §14)

Feature-flagged and backend-gated: with `VITE_WS_ENABLED` unset, `startRealtime()` (called once from `__root.tsx`) connects nothing and every table/ticker renders its polled TanStack Query value. The socket is an addition to the cold path, never a replacement for it.

- **Four-channel rule extended.** The transport handler never calls `setState` and never touches the Query cache. It writes into `lib/realtime/symbol-stores.ts` — one store per symbol — and `coalesce.ts` flushes at most one notification round per frame (module-scope rAF, so N symbols cost one callback). Query and the stream meet in exactly one place: `seedQuote()`, the REST snapshot that precedes the deltas.
- **Per-symbol, per-cell subscriptions.** A store per symbol (not one store with a map) is what keeps a NVDA tick from waking every mounted cell to conclude nothing of theirs changed. Cells subscribe through `useSyncExternalStore`; the `live` CellRender variant (`table/cells.tsx`) renders the streamed value when present and the polled `fallback` when not, so hot and cold share one row component and one preset.
- **The row is never involved.** The hot value does not travel through row props, so a tick re-renders exactly one leaf — asserted, not assumed: `tests/visual/realtime.spec.ts` drives the `?wsMock=1` feed at 20 msg/s and fails if `__terminalRowRenders` moves at all.
- **Ticker tape** (`TickerTape.tsx`, middle of the status rail): content duplicated twice inside a wrapper animated `translateX(0 → -50%)` — compositor-only, no JS per frame; duration written once from a ResizeObserver measurement (~60px/s, content-independent); hover pauses via one CSS rule; `prefers-reduced-motion` drops the animation and the duplicate copy and leaves a scrollable strip. Source is positions first, charted symbols after (`useTickerItems`).
- **Flash-on-change** is a classList write in an effect (`useFlashOnChange`), alternating `-a`/`-b` variants bound to *different* keyframe names — an element whose computed `animation-name` is unchanged continues its running animation instead of restarting, and the usual remove/read-`offsetWidth`/re-add fix forces a synchronous layout on the hot path.
- The five-state connection model in `lib/connection-state.ts` finally has its `ws-open`/`ws-close` producer; the status rail consumes it unchanged, as that module predicted.

### Analysis workspace (M6–M8, build doc §15)

`/metrics` is the home of the five data panels. Renderers live in `components/terminal/`, data-fetching hosts in `components/metrics/`, and every table is a **column preset**, not a new component (§8.2 item 3).

- **B1 strategy table** — `GET /api/strategies` carries no metrics, so Sharpe/return/drawdown come from `GET /api/strategies/{name}`'s `latestResults` (an N+1 over single-digit N, cached under the same `["strategy", name]` key `/backtesting` uses). Source badge is inferred from the strategy definition; mode comes from a real `deployMode` only.
- **B2 universe ranking** — ranks by actually running the strategy per symbol (`POST /api/strategies/{name}/run`), through a rolling window of 4 in flight so a large universe fills in progressively instead of hammering the backend. Checkbox column + sticky promote footer; promotion names `POST /api/deploy` (the B6 deploy panel is not scheduled in the build doc).
- **B4 correlation** — hand-rolled canvas (decision D3c), DOM labels, DOM hover crosshair, one paint per data/theme change. Correlation is computed on **log returns aligned by timestamp** (`lib/correlation.ts`); price-level correlation would read ≈ +1 for any two trending symbols. Undefined pairs are `NaN` and paint as background, never as a fabricated 0.
- **B5 event calendar** — `lib/event-calendar.ts` emits `BlackoutWindow[]` (release ± margin, half-open, sorted); the panel on `/live` is a view on that feed. Dates come from a maintained schedule with a `VERIFIED_THROUGH_DATE` horizon the UI warns past; FRED supersedes it when `VITE_FRED_API_KEY` is configured. There is deliberately no "first Friday" fallback — 2026 broke that rule three times.
- **B7 latency / B9 distributions** — token-native DOM, no chart library. B7's endpoint does not exist, so `pending` with the exact contract is its normal state today.
- Pure halves are unit-tested in plain node (`npm run test:unit`): calendar windows, correlation math, distribution bucketing (all ET-anchored).

### Copilot (copilot build doc M0–M8)

`/copilot` turns one prompt into a saved, backtested strategy. The copilot **orchestrates**
Ananke — it never reimplements it. Pipeline, all of it streamed to the browser as SSE:

```
prompt → LLM tool loop (list_symbols / search_symbols / add_symbol / get_price_data)
       → generated Python  → executor/executor.py in the JUPYTER container
       → k.export() POSTs back to /api/strategies  → BacktestEngineService re-run
       → LLM explanation   → stored on backest_results.copilot_explanation
```

"LLM" here is a swappable seam, not one vendor — see the BYOK bullet below.

Why the pieces sit where they do:

- **Code runs in Jupyter, not Java.** The SDK is installed there. `executor/executor.py` is a
  stdlib-only HTTP server (no Flask) on port 5000 alongside JupyterLab, mounted from `./executor`.
  It speaks `POST /execute {code}` → `{stdout, stderr, returncode}`, 504s past 120s, and cleans up
  its temp file even on timeout. It reads **chunked** request bodies — Spring's `RestClient` sends
  `Transfer-Encoding: chunked`, and a Content-Length-only reader sees an empty body.
- **The strategy name comes from stdout.** `k.export()` prints `Kairos '<name>' exported
  successfully`; `CopilotService` parses that rather than guessing, falling back to the `name=`
  literal in the source.
- **The system prompt pins the real SDK contract.** `get_data(symbol, start, end, interval)` is
  positional (`from` is a reserved Python keyword), `k.run()` must precede `k.export()`, and the
  `symbol` column is opt-in via `columns=`. A generic LLM gets all three wrong.
- **Strategy types are constrained to `rsi_crossover` / `ema_crossover`.** Those are the only two
  `BacktestEngineService` can re-run; anything else throws `UnsupportedOperationException`. The
  prompt requires `params={'type': …}` so the strategy is also re-runnable from the Backtesting
  page. If the engine still refuses, the flow **falls back** to the run `k.export()` already
  persisted rather than failing work that already succeeded (`usedExportedResult` in the `done`
  event; the UI badges it "SDK run").
- **The engine never persists.** `BacktestEngineService.run()` returns a DTO and saves nothing, so
  `StrategyService.persistRun()` writes the copilot's run and `attachExplanation()` stores the
  analysis against it.
- **The LLM provider is bring-your-own-key, not hardcoded.** `CopilotService` depends on the
  `LLMClient` interface, never a concrete client, and its tool loop is written once against the
  OpenAI chat-completion wire shape (`choices[0].message`, `finish_reason`, `tool_calls[]`).
  `KimiClient` (name is historical — it's a generic OpenAI-compatible implementation) serves that
  shape directly, which means it also serves OpenAI, Groq, Together, DeepSeek, OpenRouter, and any
  local model server exposing an OpenAI-compatible `/chat/completions` endpoint (Ollama, LM Studio,
  vLLM, llama.cpp server, text-generation-webui, koboldcpp — swap providers with `LLM_BASE_URL`
  alone, and leave `LLM_API_KEY` blank for a local server that doesn't check it).
  `AnthropicClient` implements the same interface for Anthropic's Messages API, translating in both
  directions at its own boundary: system-role turns become the top-level `system` string, an
  assistant turn's `tool_calls` become `tool_use` content blocks, `role:"tool"` results become a
  `tool_result` block inside a `user` message (consecutive results are merged into one user turn —
  Anthropic expects all results for one assistant turn together), and the response's `content`
  blocks / `stop_reason` are synthesized back into `choices[0].message` / `finish_reason` so
  `CopilotService`'s loop never has to know which provider answered. `LlmProviderConfig` picks the
  active `LLMClient` bean from `llm.provider` at startup — single global config (Ananke has no
  user/tenant model yet), so adding real per-user BYOK later is a new resolver in that one class,
  not a rewrite of the tool loop.
- **Results are DB-backed.** `runHistory` in `backtesting.tsx` is in-memory and dies on reload;
  `/backtesting?tab=results&strategy=<name>` hydrates from `GET /api/strategies/{name}/results` and
  merges those rows behind the session's own, which is what makes a copilot run survive a refresh.

Gotchas found while building it, all fixed but easy to reintroduce:

- `CopilotService` must inject **Spring's** `ObjectMapper`. A bare `new ObjectMapper()` has no
  JSR-310 module and every `Instant` in the backtest payload fails to serialise — silently, as an
  `{"error":"serialisation failed"}` frame.
- Return `SseEmitter` **directly** from the controller. Wrapping it in `ResponseEntity` produces
  `No converter for SseEmitter`; the 503 for a missing/misconfigured provider is thrown as
  `LLMClient.LLMUnavailableException` and mapped in `GlobalExceptionHandler` (`KimiClient` still has
  a deprecated `KimiUnavailableException` subclass for source compatibility — nothing should throw
  it anymore).
- Do not send `temperature` when calling Kimi's `k3` model on the coding endpoint — it rejects
  anything but `1`. `KimiClient` (the generic OpenAI-compatible `LLMClient`) deliberately omits it
  entirely rather than special-casing one model.
- The Python SDK posts **snake_case** `from_ts`/`to_ts`; `ApiDto.StrategyRequest` needs
  `@JsonAlias`, and the values are `str(pd.Timestamp)` ("2024-06-03 08:00:00+00:00"), which
  `Instant.parse` rejects — hence `StrategyService.parseFlexible`.
- **There are two `docker-compose.yml` files** (repo root and `Java backend/`). Always build with
  `docker compose -f ./docker-compose.yml --project-directory . build stock-tracker`; a bare
  `docker compose` from the wrong cwd builds `javabackend-stock-tracker` and leaves the running
  `anake-stock-tracker` untouched, so fixes appear to have no effect.

### Ananke Packs (Dev-notes/Ananke Packs Build Doc.md)

`/packs` turns a Hugging Face model into a copilot provider running on the user's own GPU machine.
Backend lives in `com.stocktracker.packs` (resolver → compute provider → remote runtime → service →
controller); the only code that ever runs on a machine is `resources/packs/vllm-bootstrap.sh`, sent
over SSH on stdin with validated args. Load-bearing rules:
- **Secrets** (SSH keys, HF tokens, vLLM API keys) go through `SecretVault` (AES-256-GCM, table
  `ananke_secrets`, master key `ANANKE_SECRET_KEY` or a generated `0600` key file). Never add a
  column, log line or API field that carries one in plaintext. Changing the master key makes stored
  secrets undecryptable (by design; the API says so).
- **Revisions are SHAs.** Profiles are read and weights downloaded at the pinned commit, never `main`.
- **Remote code is opt-in per Pack**, and deploy refuses without it when the repo needs it.
- **vLLM binds 127.0.0.1**; the backend reaches it through an `ssh -L` tunnel. The Pack registers as
  provider `pack:<id>` through `LlmSettingsService.ExternalProviders` — the copilot code is unchanged.
- **Tool calling is probed, not assumed.** "Use for Copilot" is refused unless the live probe passed,
  because the copilot always sends tools.
- The backend image installs `openssh-client` (Dockerfile); Packs use the system `ssh`, not a JVM lib.
- **Cloud machines (build doc §7.5, `com.stocktracker.packs.cloud`)**: AWS is built and verified only
  against emulators. GCP and Azure are listed as `available:false`; don't fake them. Rules:
  - Every AWS call goes through `AwsGateway` (fake in `AwsCloudTest`, moto in `SdkAwsGatewayMotoTest`), using AssumeRole sessions from the linked account's vault-stored key. **Never** use the default credential chain.
  - Nothing billable happens without a fresh, single-use, acknowledged estimate (`provision(estimateId, acknowledgeBilling)`), and every create/start/stop/terminate writes `agent_audit_log` against the Pack.
  - The host key comes from `GetConsoleOutput`, never a network scan, and no key means terminate.
  - Resources must carry `ananke-managed=true` + `ananke-link=<link tag>`; the IAM template only allows touching tagged resources.
  - The orphan finder only lists.
  - `ananke-access.yaml` is the security boundary: change it only with cfn-lint and a re-review.
  - Test literals must not look like AWS keys (GitHub push protection blocks them): build them at runtime.
- Cloud agent skills for Pack operations: see "Agent skills (Packs build doc §9)" below.

### Agent skills (Packs build doc §9)

The official AWS / Google Cloud / Azure `SKILL.md` skills reach the copilot **and every Ananke
agent** through one provider-agnostic kit in `com.stocktracker.agent`. They work with any model
behind `LLMClient`, because they're plain tool calls on the OpenAI-shaped loop and never use a
vendor's native Skills feature. Load-bearing rules:
- **The bundle is pinned and checksummed.**
  - `scripts/skills/sources.json` lists the curated skills: official repos only, each pinned to a commit, with its licence recorded.
  - `node scripts/skills/vendor-skills.mjs` fetches docs only (`.md`/`.txt`; no scripts, IaC, hooks or telemetry). It checks each file's git blob SHA against the pinned tree and writes `Java backend/src/main/resources/skills/` plus `manifest.json` with a sha256 per file.
  - **Never hand-edit or reformat that directory.** `.prettierignore` and `.gitattributes -text` protect it.
  - Bumping a commit means reviewing the upstream diff, then re-running the script.
  - A pack whose licence forbids redistribution is `distribution: "fetch"`: it's checksummed in the manifest but not committed, and installed with `vendor-skills.mjs install <ananke.skills.fetch-dir>`.
- **`SkillRegistry` fails startup.** Any bad frontmatter, checksum mismatch, unpinned commit, duplicate name, unsafe path or missing file throws `SkillBundleException`, and the backend doesn't boot (every problem is listed). `ananke.skills.bundle-dir` swaps in a whole bundle from disk under the same rules.
- **Discovery and loading.**
  - `AgentCapabilities.systemPrompt(base, profile)` appends a one-line-per-skill index, filtered by the `AgentProfile` topics. `COPILOT` sees all enabled skills; `PACK_OPS` sees compute/gpu/cost/identity/cli.
  - `load_skill(name, file?)` returns the SKILL.md body without its frontmatter, or a manifest-listed file. Anything else is refused, including `..`, absolute paths, `%`, backslashes and empty segments.
  - Output is wrapped with a notice that it is untrusted reference text.
- **Skill text can't add tools.** An `AgentToolbox` is built in code per turn and is immutable. Frontmatter like `allowed-tools` is ignored, and an unknown tool name is just an error result.
- **Cloud action tools** come only from `CloudActionToolSource` beans whose cloud is linked (`CloudToolGate`). None exist until §7, so today no agent is offered any. **Billable** tools never run in the loop: `ActionConfirmationService` parks the call with its cost estimate, a person confirms via `POST /api/agent/actions/{id}/confirm`, and every step is written to `agent_audit_log` (V8) against the Pack. Pending actions are in-memory with a 15-minute TTL; a restart drops them, which fails safe.
- **AWS action side (#7):**
  - `AwsActionToolSource` is the first real `CloudActionToolSource`. It's offered only while an AWS account is linked.
  - Read-only tools: `aws_list_gpu_types`, `aws_estimate_gpu_vm`, `aws_list_machines`.
  - **Billable** tools: `aws_provision_gpu_vm` (the person confirms the exact estimate, and the VM is created from that estimate id) and `aws_start_vm`.
  - There are no stop/terminate tools for agents.
  - `AwsMcpKnowledge` is a SigV4 (`aws-mcp`) streamable-HTTP MCP client for the official AWS MCP Server. It's allowlisted to the 5 read-only knowledge tools, so `aws___run_script` and pre-signed URLs are never exposed; the linked role also denies everything `aws:ViaAWSMCPService`.
  - `AwsMcpLiveCheck` is an opt-in (`AWS_MCP_LIVE=1`) unsigned check against the real server.
  - UI: `PendingActions` (Confirm/Reject, polls), `PackAgentDialog` ("Ask agent" on `/packs`) and `PackAudit` (per-Pack trail).
  - `load_skill` accepts `offset` / returns `nextOffset` for files over 60k characters.
- **Who composes it:**
  - `CopilotService` **chat** mode composes the kit.
  - **Research** mode keeps exactly its four data tools and prompt, so the Phase-0 baseline is unchanged; `CopilotResearchFixtureTest` still uses the 7-arg constructor.
  - The read-only Pack agent is `packs/agent/PackOpsAgent` (`POST /api/packs/agent/chat`).
  - A new agent should be `agents.toolbox(profile, packId, ownTools)` + `AgentToolLoop.run(...)`, not a new loop.
- **API and UI:** `GET /api/skills/packs`, `PUT /api/skills/packs/{id} {enabled}` (persisted to `ananke.skills.settings-file`), and `GET /api/skills/agents`. The UI lives in Settings → Agent skills (`?section=skills`).
- **Tests (offline):** `SkillRegistryTest`, `SkillToolsetTest`, `CloudActionGatingTest`, and `AgentSkillsOfflineTest`, where a scripted `LLMClient` calls `load_skill` on the real bundle and answers from it, through both agents.

### Copilot V1 regression baseline (Phase 0)

These contracts are **frozen by tests** as the regression baseline for Phase 1. A Phase 1 change
that alters any of them must update the named test deliberately, in the same commit, and say why.
Everything runs offline (`npm run test:backend`, `npm run test:executor`).

| Test | Freezes |
|---|---|
| `service/CopilotResearchFixtureTest` | `runResearch` end to end: SSE event order, persisted rows, fallback, error paths |
| `controller/CopilotControllerTest` | 503 JSON (`{message, status:503}`) before any stream opens when no provider is configured |
| `service/AnthropicClientTest` | OpenAI ⇄ Anthropic translation (fake Messages API on a JDK `HttpServer`) |
| `service/KimiClientTest` | `llm.*` over `kimi.*` precedence, keyless localhost, verbatim pass-through |
| `service/ResolvingLLMClientTest`, `LlmProviderConfigTest` | preferred → fallback → none; `llm.provider` aliases; unknown provider throws |
| `executor/test_executor.py` | `/execute` HTTP contract, timeout, chunked bodies |

**SSE frames** (`CopilotDto.ResearchEvent`, one JSON object per `data:` frame,
`{type, message, payload}`):

| `type` | `message` | `payload` |
|---|---|---|
| `status` | progress text (`Analysing request…`, `Fetching <symbol as typed> price data…`, `Executing strategy in Jupyter…`, `Running backtest…`, `Analysing results…`) | `null` |
| `code` | `Generated strategy` | the first fenced block's code, trimmed |
| `backtest` | `Backtest complete` | `ApiDto.BacktestResultResponse` (camelCase, ISO-8601 `Instant`s; `id` = persisted row) |
| `explanation` | `Analysis complete` | markdown string (omitted when the explanation call fails/blank) |
| `done` | `Done` | `ResearchSummary {strategyName, symbol, backtestResultId, usedExportedResult}` (research) / `null` (chat) |
| `error` | human-readable reason | `null` |
| `token` | `null` | full chat answer (chat mode only — one frame, not streamed tokens) |

Happy path order: `status, [status per tool call…], code, status, status, backtest, status,
explanation, done`. Every failure ends the stream after a single `error` (no `done`):
no code fence → `The model did not return a Python code block. Try rephrasing the request.`;
executor failure → `Strategy code failed:\n` + stderr tail (1500 chars) or the executor's `error`
when stderr is blank; nothing exported → `The script ran but no strategy was exported. …Output:\n`
+ stdout tail; LLM unavailable mid-run → the exception's root message. The emitter is always
completed.

**Persistence.** `k.export()` (via `POST /api/strategies` → `StrategyService.saveStrategy`) writes
the strategy + an exported `backtest_results` row. For `rsi_crossover`/`ema_crossover`,
`CopilotService` re-runs through the engine over the exported row's symbol/range and persists a
**second** row (`interval='1Min'`) which carries `copilot_explanation` and whose id is in `done`.
Any other type → `UnsupportedOperationException` → the exported row is used and annotated
(`usedExportedResult: true`, one row).

**Executor** (`executor/executor.py`): `POST /execute {code, timeout?}` →
`200 {stdout, stderr, returncode}` for any completed run (non-zero exit is still 200);
`400 {error}` for malformed JSON / missing `code`; `504 {"error":"Execution timed out after Ns"}`;
`404 {"error":"Not found"}`; `GET /health` → `{status:"ok", port}`. Accepts chunked bodies.
`JupyterExecutionService` maps non-2xx → `ExecutionResult.error("Executor returned HTTP <n>: <error>")`
and `returncode != 0` → `success=false, error="Python exited with code <n>"` with stdout/stderr kept.

**`LLMClient` contract.** `chat(messages, tools, maxTokens)` returns OpenAI shape regardless of
provider: `choices[0].message.{role, content, tool_calls[{id, type:"function", function:{name,
arguments: <JSON string>}}]}` and `choices[0].finish_reason` — the loop only branches on
`"tool_calls"` vs anything else. `complete()` = system + user, no tools, returns content text.
Empty `tools` are omitted from the request. Unusable provider → `LLMUnavailableException` (503 at
the controller, `error` frame mid-stream). Anthropic: system turns joined with `\n\n` into
top-level `system`; `stop_reason:"tool_use"` (or any `tool_use` block) → `tool_calls`, **every
other stop reason → `"stop"`**; unparseable tool arguments → `{}` input.

**Known V1 limitations (frozen, not endorsed):**
- **Strategy name is scraped from stdout** (`Kairos '<name>' exported successfully`), falling back
  to the first `name='…'` literal in the code — any `name=` (e.g. a column rename) can win.
- **`usedExportedResult` fallback is silent-ish**: any engine exception (not only unsupported
  types) falls back to the SDK's row; when the exported row lacks symbol/range the re-run is skipped.
- The engine re-run window is the exported row's `[fromTs, toTs]`, not the user's request.
- Anthropic `max_tokens` truncation is reported as `finish_reason:"stop"`, not `"length"`.
- `KimiClient` does not treat `http://[::1]` as local (`URI.getHost()` keeps the brackets), so IPv6
  loopback still needs a key.
- A provider **401** escapes as `RestClientException` (JDK `HttpURLConnection` streaming-mode
  `HttpRetryException`), not `LLMUnavailableException` — it surfaces as an `error` frame, never 503.
- Chat mode emits the whole answer as one `token` frame; there is no token streaming.

### Chart architecture

`PriceChart` manages ONE `lightweight-charts` v5 instance via `useChartBase` with **native panes** (build doc §10):
1. **Price pane (pane 0)** — candlestick + overlay series (SMA, EMA, Bollinger). Chart created once via `useChartBase`; series recreated only on type/compare-mode change; data updates via `setData()`. Series are created with the unified v5 API: `chart.addSeries(CandlestickSeries, opts)` / `LineSeries` / `AreaSeries` / `BarSeries` / `HistogramSeries` (the v4 `addCandlestickSeries`-style methods are gone).
2. **Volume pane (pane 1)** — real pane holding a volume histogram (the v4 blank-`priceScaleId` overlay + `scaleMargins { top: 0.7 }` hack is deleted; the price scale keeps default margins). The series rides a custom overlay scale id (`priceScaleId: "volume"`) so it draws no axis — do NOT hide a pane's price scale with `visible: false`: in 5.2.0 that crashes the layout pass (`adjustSizeImpl` → "Value is null") whenever another pane's scale on the same side is visible.
3. **RSI / MACD panes** — real panes on the same instance, created/destroyed on indicator toggle (`chart.addPane()` + `pane.moveTo()` keep a canonical volume→RSI→MACD order; cleanup is `chart.removePane(pane.paneIndex())`, StrictMode-safe). Crosshair and visible-range sync across panes are native in v5 — the v4 `ChartSyncGroup` (`src/lib/chart-sync.ts`) and the separate sub-chart divs/instances are removed entirely.
4. **Markers** — trade entry/exit markers (PriceChart, BacktestingChart) use the v5 `createSeriesMarkers` plugin, attached LAZILY only while trades exist: in 5.2.0 the plugin's pane view calls `series.data()` on every update cycle (an O(bars) copy per pan/zoom frame even with zero markers), so an idle plugin measurably hurts at high bar counts.
5. **§11 M2 surface primitives** — `SessionBandsPrimitive` (pane primitive on pane 0, `zOrder: "bottom"`, extended-hours bands from ET wall-clock math, intraday-only guard) and `PricePillPrimitive` (series primitive `priceAxisViews`: direction-colored last-price pill vs the previous session's close, plus a replay-position pill stacked flush below it in BacktestingChart — the offset must not exceed the axis-label height or tick-label slivers peek through the gap). Where the pill attaches, the native `lastValueVisible` AND the built-in price line go off — v5.2.0 has no price-line-label switch, so the dotted last-price line is an explicit `createPriceLine({ axisLabelVisible: false })` fed by the pill state effect. Sun/moon day-boundary glyphs ride `timeScale.tickMarkFormatter` in `useChartBase.chartOptions()`. State flows in via `update()`/`setSegments()` field writes + the library's own `requestUpdate`; colors re-resolve on §13.4 theme change.
6. **v5.2.0 gotchas (verified against typings + runtime):** pane 0's default stretch factor is **2**, not 1 (size sub-pane `setStretchFactor` calls against that); `timeScale.enableConflation` defaults to **false** despite the "automatic at high bar counts" docs impression — `useChartBase` opts in explicitly (the 100k+-bar story); `timeVisible`/`secondsVisible` were NOT renamed; `logicalToCoordinate` returns **0, not null, for fractional logical indices** — convert with integer logicals and derive half-bar offsets from the run's average spacing (session-shading renderer).

Client-side interval aggregation (`aggregatePriceBars`) runs on raw 1-minute bars from the API and buckets them into the selected interval. The API always returns 1-minute data; resampling happens entirely in the browser.

### Backend contract

The Spring Boot backend wraps responses as `{ data: [...], count: N }` or `{ content: [...] }`. `apiFetch` in `client.ts` unwraps these envelopes automatically so callers see raw arrays/objects.

Backend endpoints currently implemented:
- `GET /api/symbols`
- `POST /api/symbols`
- `DELETE /api/symbols/{symbol}`
- `GET /api/prices/{symbol}/range?from=&to=`
- `GET /api/strategies`, `GET /api/strategies/{name}`, `POST /api/strategies/{name}/run`
- `GET /api/strategies/{name}/results` (every persisted run — what the Results tab hydrates from)
- `POST /api/copilot/research`, `POST /api/copilot/chat` (SSE; 503 when no LLM provider is configured — see `LLM_PROVIDER` below)
- `GET /api/strategies/active`, `GET /api/account`, `GET /api/positions`, `GET /api/models`

Waiting on the backend (each renders a §6 `PanelState kind="pending"` naming the exact endpoint, never a blank panel — decision D5):
- `GET /api/metrics/engine?from=&to=` → `LatencySample[]` (§15.4 B7; shape proposed in `lib/api/metrics.ts`)
- `GET /api/metrics/strategy?name=&from=&to=`
- `GET /api/orders` (Live → Orders tab)
- `GET /api/trades/range` (probed, flagged)
- `POST /api/deploy` (B2's promote action; the B6 deploy panel itself is not scheduled in the build doc)
- A price WebSocket at `VITE_WS_URL` (§14 — the client is complete and flag-gated)

Two of these (`/api/trades/range`, `/api/metrics/engine`) are stubbed as deliberate 404s in the visual harness so the baselines show the real degraded state.

### Environment variables

| Var | Default | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | Spring backend base URL |
| `VITE_WS_URL` | derived from base URL | Realtime price socket (§14; only dialled when the flag below is on) |
| `VITE_WS_ENABLED` | unset (off) | `1` turns on the §14 hot path. Off = polling exactly as before; the socket is an addition, never a replacement |
| `VITE_JUPYTER_URL` | `http://localhost:8890` | JupyterLab URL for "Open in JupyterLab" (see below) |

Backend-side (`.env`, passed to `stock-tracker` by compose):

| Var | Default | Purpose |
|---|---|---|
| `LLM_PROVIDER` | `openai_compatible` | `openai_compatible` or `anthropic` — see the BYOK bullet above |
| `LLM_API_KEY` | unset | openai_compatible key. Blank is valid for a local server (Ollama, LM Studio, …) that doesn't check it |
| `LLM_BASE_URL` | `https://api.moonshot.ai/v1` | Any OpenAI-compatible base URL — OpenAI, Kimi, Groq, Together, DeepSeek, OpenRouter, or `http://localhost:11434/v1` etc. for a local model |
| `LLM_MODEL` | `kimi-k2-0905-preview` | Model id for the openai_compatible provider |
| `ANTHROPIC_API_KEY` / `ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL` | unset / `https://api.anthropic.com` / `claude-sonnet-4-5` | Used only when `LLM_PROVIDER=anthropic` |
| `KIMI_API_KEY` / `KIMI_BASE_URL` / `KIMI_MODEL` | Moonshot defaults | Legacy names — still read as fallbacks when the `LLM_*` vars above are unset |
| `JUPYTER_EXECUTOR_URL` | `http://jupyter:5000` | Where the Java container reaches `executor.py` |
| `ANANKE_AWS_ENDPOINT` / `ANANKE_AWS_PRICING_ENDPOINT` | unset (real AWS) | Cloud §7: emulator overrides (moto / a fake price server) — tests only |
| `ANANKE_AWS_DLAMI_PARAMETER` | the public DLAMI Ubuntu 22.04 SSM path | Cloud §7: emulators can't hold names under `/aws/` |
| `ANANKE_AWS_BOOT_TIMEOUT_MINUTES` / `ANANKE_AWS_HOST_KEY_TIMEOUT_MINUTES` | 10 / 10 | Cloud §7: provisioning waits |
| `ANANKE_AWS_MCP_ENABLED` / `ANANKE_AWS_MCP_ENDPOINT` | `true` / `https://aws-mcp.us-east-1.api.aws/mcp` | #7: AWS MCP knowledge tools for agents (only while AWS is linked) |
| `ANANKE_EGRESS_IP_URL` | `https://checkip.amazonaws.com` | Cloud §7: the backend's public IP for the VM's SSH rule |
| `ANANKE_SKILLS_FETCH_DIR` | `./data/skills` | Agent skills: install dir for `distribution: "fetch"` packs (none today) |
| `ANANKE_SKILLS_SETTINGS_FILE` | `./data/skills-settings.json` | Agent skills: packs switched off in Settings |
| `ANANKE_SKILLS_BUNDLE_DIR` | unset | Agent skills: load a whole bundle from disk instead of the jar (same checksum rules) |
| `ANANKE_SECRET_KEY` | unset | Packs: base64 32-byte master key for encrypted credentials. Unset = generated key file on the data volume |
| `ANANKE_VLLM_VERSION` | `0.11.0` | Packs: vLLM version installed on machines (pinned) |
| `ANANKE_PACKS_HEALTH_TIMEOUT_MINUTES` / `ANANKE_PACKS_DEPLOY_TIMEOUT_MINUTES` | `30` / `90` | Packs: model-load and install/download limits |

No provider configured (missing key for the selected `LLM_PROVIDER`) = `/api/copilot/*` answers 503
and the tab shows a config notice.

### Open in JupyterLab

The chart/data export panel (`PythonExport.tsx`) has an "Open in JupyterLab" button: `lib/jupyter-export.ts` writes the generated snippet as an `.ipynb` into the Jupyter contents API (`strategies/` → the `Test Trading Strategies` volume) and opens it in JupyterLab. Mechanics:

- Jupyter runs authless for local dev but enforces XSRF — the client warms the `_xsrf` cookie and echoes it as `X-XSRFToken`.
- API calls go through the app-level server route `src/routes/jupyter-api.$.ts`, which rewrites both Host and Origin (jupyter_server 404s writes whose Origin ≠ Host). Works identically under `vite dev` and the production worker. Target: `VITE_JUPYTER_PROXY_TARGET` baked at image build (`http://jupyter:8888` in the container); host dev default `http://localhost:8890`.
- The docker Jupyter is on host port **8890**, not 8888 — a host-local `jupyter-lab` process squats on 8888.
- Feature is local-dev only (the proxy exists in vite dev; there is no production Jupyter).

### Adding a new route

1. Create `src/routes/your-route.tsx` with `export const Route = createFileRoute("/your-route")({...})`.
2. The TanStack Router plugin auto-regenerates `src/routeTree.gen.ts` on the next `npm run dev` / build.
3. Add a nav entry in `src/components/AppSidebar.tsx`.

### Python export

`lib/python-export.ts` generates a self-contained pandas + SQLAlchemy script that reproduces the exact query visible in the UI. It targets the `stock_prices` TimescaleDB hypertable with columns `time, symbol, open, high, low, close, volume, vwap, trade_count`. The generated interval resampling mirrors `aggregatePriceBars` logic.
