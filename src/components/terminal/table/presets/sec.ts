import type { SecFiling, SecFinancialValue, SecInsiderTrade, SecSearchHit } from "@/lib/api/sec";
import { etDateTimeInputValue } from "@/lib/market-calendar";
import type { ColumnPreset } from "../presets";

/**
 * SEC EDGAR tables for /research (Connectors roadmap §2). Times are the SEC's acceptance
 * time shown in ET, like every other clock in the app — "when it became public" is the
 * number that matters for trading, not the period the filing covers.
 */

/** UTC ISO → "YYYY-MM-DD HH:mm" ET; null-safe. */
export function etStamp(iso: string | null | undefined): string {
  if (!iso) return "—";
  const ms = Date.parse(iso);
  return Number.isNaN(ms) ? "—" : etDateTimeInputValue(ms).replace("T", " ");
}

const FORM_TONE = (form: string) =>
  form.startsWith("8-K")
    ? "info"
    : form.startsWith("10-") || form.startsWith("20-F")
      ? "neutral"
      : form === "4" || form === "4/A"
        ? "warn"
        : "flat";

export const SEC_FILING_COLUMNS: readonly ColumnPreset<SecFiling>[] = [
  {
    id: "form",
    header: "Form",
    width: 84,
    align: "left",
    cell: (f) => ({ type: "badge", value: f.form, tone: FORM_TONE(f.form) }),
    sortValue: (f) => f.form,
  },
  {
    id: "accepted",
    header: "Public (ET)",
    width: 136,
    align: "left",
    cell: (f) => ({ type: "text", value: etStamp(f.accepted_at), mono: true }),
    sortValue: (f) => f.accepted_at ?? f.filed,
  },
  {
    id: "period",
    header: "Period",
    width: 96,
    align: "left",
    cell: (f) => ({ type: "text", value: f.period ?? "—", mono: true }),
    sortValue: (f) => f.period ?? null,
  },
  {
    id: "items",
    header: "8-K items",
    width: 110,
    align: "left",
    cell: (f) => ({ type: "chips", values: f.items ? f.items.split(",") : [] }),
  },
  {
    id: "doc",
    header: "Document",
    width: 320,
    align: "left",
    cell: (f) => ({
      type: "link",
      value: f.description ?? f.url.split("/").pop() ?? "open",
      href: f.url,
    }),
  },
];

const CODE_LABEL: Record<string, string> = {
  P: "BUY",
  S: "SELL",
  A: "AWARD",
  M: "EXERCISE",
  F: "TAX",
  G: "GIFT",
  C: "CONVERT",
  D: "TO ISSUER",
};

export const SEC_INSIDER_COLUMNS: readonly ColumnPreset<SecInsiderTrade & { id: string }>[] = [
  {
    id: "date",
    header: "Trade date",
    width: 96,
    align: "left",
    cell: (t) => ({ type: "text", value: t.date ?? "—", mono: true }),
    sortValue: (t) => t.date,
  },
  {
    id: "insider",
    header: "Insider",
    width: 220,
    align: "left",
    cell: (t) => ({
      type: "text",
      value: `${t.insider}${t.role ? ` · ${t.role}` : ""}`,
      strong: true,
    }),
    sortValue: (t) => t.insider,
  },
  {
    id: "code",
    header: "Type",
    width: 92,
    align: "left",
    cell: (t) => ({
      type: "badge",
      value: (t.code && CODE_LABEL[t.code]) ?? t.code ?? "—",
      // Only open-market buys/sells carry a signal colour; awards, exercises and tax are neutral.
      tone: t.code === "P" ? "up" : t.code === "S" ? "down" : "neutral",
    }),
    sortValue: (t) => t.code,
  },
  {
    id: "shares",
    header: "Shares",
    width: 100,
    align: "right",
    cell: (t) => ({ type: "num", value: t.shares, kind: "size" }),
    sortValue: (t) => t.shares,
  },
  {
    id: "price",
    header: "Price",
    width: 84,
    align: "right",
    cell: (t) => ({ type: "num", value: t.price, kind: "price" }),
    sortValue: (t) => t.price,
  },
  {
    id: "after",
    header: "Held after",
    width: 104,
    align: "right",
    cell: (t) => ({ type: "num", value: t.shares_after, kind: "size" }),
    sortValue: (t) => t.shares_after,
  },
  {
    id: "plan",
    header: "10b5-1",
    width: 64,
    align: "left",
    cell: (t) => ({ type: "text", value: t.plan_10b5_1 ? "plan" : "—" }),
  },
  {
    id: "filed",
    header: "Public (ET)",
    width: 136,
    align: "left",
    cell: (t) => ({ type: "link", value: etStamp(t.accepted_at), href: t.url, mono: true }),
    sortValue: (t) => t.accepted_at,
  },
];

export const SEC_FINANCIAL_COLUMNS: readonly ColumnPreset<SecFinancialValue>[] = [
  {
    id: "period",
    header: "Period",
    width: 176,
    align: "left",
    cell: (v) => ({ type: "text", value: `${v.start ? `${v.start} → ` : ""}${v.end}`, mono: true }),
    sortValue: (v) => v.end,
  },
  {
    id: "fp",
    header: "FY / FP",
    width: 84,
    align: "left",
    cell: (v) => ({
      type: "text",
      value: `${v.fiscal_year ?? "—"} ${v.fiscal_period}`,
      mono: true,
    }),
  },
  {
    id: "value",
    header: "Value",
    width: 150,
    align: "right",
    cell: (v) => ({ type: "num", value: v.value, kind: "size" }),
    sortValue: (v) => v.value,
  },
  {
    id: "filed",
    header: "Public",
    width: 96,
    align: "left",
    cell: (v) => ({ type: "text", value: v.filed, mono: true }),
    sortValue: (v) => v.filed,
  },
  {
    id: "form",
    header: "Form",
    width: 64,
    align: "left",
    cell: (v) => ({ type: "text", value: v.form }),
  },
  {
    id: "restated",
    header: "Restated",
    width: 220,
    align: "left",
    cell: (v) => ({
      type: "chips",
      values: (v.restated ?? []).map((r) => `${r.value.toLocaleString()} (${r.filed})`),
    }),
  },
];

export const SEC_SEARCH_COLUMNS: readonly ColumnPreset<SecSearchHit & { id: string }>[] = [
  {
    id: "filed",
    header: "Filed",
    width: 96,
    align: "left",
    cell: (h) => ({ type: "text", value: h.filed ?? "—", mono: true }),
    sortValue: (h) => h.filed,
  },
  {
    id: "form",
    header: "Form",
    width: 84,
    align: "left",
    cell: (h) => ({ type: "badge", value: h.form ?? "—", tone: FORM_TONE(h.form ?? "") }),
    sortValue: (h) => h.form,
  },
  {
    id: "company",
    header: "Company",
    width: 300,
    align: "left",
    cell: (h) => ({ type: "text", value: h.company ?? "—", strong: true }),
    sortValue: (h) => h.company,
  },
  {
    id: "doc",
    header: "Matched document",
    width: 220,
    align: "left",
    cell: (h) =>
      h.url
        ? { type: "link", value: h.file_type ?? "open", href: h.url }
        : { type: "text", value: h.file_type ?? "—" },
  },
];
