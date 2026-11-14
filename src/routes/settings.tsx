import { createFileRoute, Link } from "@tanstack/react-router";
import { KeyRound, Palette } from "lucide-react";

import { TerminalPanel } from "@/components/terminal/TerminalPanel";
import { AiProviderPanel } from "@/components/settings/AiProviderSettings";
import { AppearanceControls } from "@/components/ThemeSettings";
import { cn } from "@/lib/utils";

const SECTIONS = [
  {
    id: "ai",
    label: "AI providers & API keys",
    icon: KeyRound,
    blurb:
      "Bring your own LLM. Paste a key from Anthropic, OpenAI, Groq, OpenRouter, DeepSeek, Moonshot or any OpenAI-compatible server (Ollama, LM Studio, vLLM), pick a model, and make it Copilot's provider. Keys are stored on your Ananke backend and never shown again.",
  },
  {
    id: "appearance",
    label: "Appearance",
    icon: Palette,
    blurb: "Theme, market colour convention and colorblind-safe colours.",
  },
] as const;

type SectionId = (typeof SECTIONS)[number]["id"];

export const Route = createFileRoute("/settings")({
  // URL = where you are: ?section= deep-links a settings section (and lets the
  // top bar / Copilot / the macOS ⌘, menu land straight on API keys).
  validateSearch: (search: Record<string, unknown>): { section: SectionId } => ({
    section: SECTIONS.some((s) => s.id === search.section) ? (search.section as SectionId) : "ai",
  }),
  head: () => ({ meta: [{ title: "Settings — Ananke Trading" }] }),
  component: SettingsPage,
});

function SettingsPage() {
  const { section } = Route.useSearch();
  const current = SECTIONS.find((s) => s.id === section)!;

  return (
    <div className="flex h-full min-h-0 gap-3 p-3" data-testid="settings-page">
      <nav className="flex w-56 shrink-0 flex-col gap-0.5" aria-label="Settings sections">
        <span className="px-3 pb-2 pt-1 text-[10px] font-medium uppercase tracking-wider text-text-muted">
          Settings
        </span>
        {SECTIONS.map((s) => {
          const Icon = s.icon;
          return (
            <Link
              key={s.id}
              to="/settings"
              search={{ section: s.id }}
              data-testid={`settings-nav-${s.id}`}
              className={cn(
                "flex items-center gap-2 rounded-md px-3 py-2 text-xs transition-colors",
                s.id === section
                  ? "bg-surface-2 text-text-primary"
                  : "text-text-secondary hover:bg-surface-1",
              )}
            >
              <Icon className="h-3.5 w-3.5" />
              {s.label}
            </Link>
          );
        })}
      </nav>

      <TerminalPanel
        tabs={[{ id: current.id, label: current.label }]}
        activeTab={current.id}
        className="min-h-0 flex-1"
        bodyClassName="min-h-0"
      >
        <div className="flex h-full min-h-0 flex-col">
          <p className="border-b border-border-subtle px-5 py-3 text-xs text-text-muted">
            {current.blurb}
          </p>
          {section === "ai" ? (
            <AiProviderPanel bodyClassName="min-h-0 flex-1" />
          ) : (
            <div className="flex max-w-sm flex-col gap-4 p-5">
              <AppearanceControls />
            </div>
          )}
        </div>
      </TerminalPanel>
    </div>
  );
}
