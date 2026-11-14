import { apiFetch } from "./client";
import type { AssetClass, Instrument } from "@/lib/instruments";

/**
 * Instrument registry (multi-asset P1, issue #8) — backend InstrumentController.
 *
 * Keys always travel as a query parameter or body field, never a path segment:
 * crypto keys contain "/", continuous futures "!", indexes "^".
 */
export const instrumentsApi = {
  /** All registered instruments, optionally one asset class. */
  list: (assetClass?: AssetClass) =>
    apiFetch<Instrument[]>("/api/instruments", { query: { assetClass } }),

  /** One registered instrument; ApiError 404 if not registered, 400 if the key is malformed. */
  lookup: (key: string) => apiFetch<Instrument>("/api/instruments/lookup", { query: { key } }),

  /**
   * Register an instrument from its key (idempotent). Futures roots without a
   * backend contract spec are refused (400) rather than given a guessed multiplier.
   */
  register: (key: string) =>
    apiFetch<Instrument>("/api/instruments", { method: "POST", body: { key } }),
};
