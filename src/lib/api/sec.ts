/**
 * Payload shapes of the SEC EDGAR connector's tools (backend: SecEdgarConnector). Called
 * through `connectorsApi.call("sec_edgar", tool, args)`; the same tools are Copilot's.
 */

export const SEC_CONNECTOR = "sec_edgar";

export interface SecFiling {
  form: string;
  filed: string;
  /** SEC acceptance time, UTC ISO — when the filing became public. */
  accepted_at: string | null;
  period?: string;
  /** 8-K item numbers, comma-separated ("2.02,9.01"). */
  items?: string;
  description?: string;
  accession: string;
  url: string;
}

export interface SecFilingsPayload {
  company: string;
  cik: string;
  count: number;
  filings: SecFiling[];
}

export interface SecInsiderTrade {
  insider: string;
  role: string;
  security: string | null;
  derivative: boolean;
  date: string | null;
  /** P purchase, S sale, A award, M exercise, F tax withholding, G gift… */
  code: string | null;
  acquired_disposed: "A" | "D" | null;
  shares: number | null;
  price: number | null;
  shares_after: number | null;
  ownership: string | null;
  plan_10b5_1: boolean;
  accepted_at: string | null;
  filed: string;
  url: string;
}

export interface SecInsiderPayload {
  company: string;
  filings_read: number;
  transactions: SecInsiderTrade[];
  codes: Record<string, string>;
  unreadable_filings?: string[];
}

export interface SecFinancialValue {
  start?: string;
  end: string;
  value: number;
  fiscal_year: number | null;
  fiscal_period: string;
  form: string;
  /** When this number became public. */
  filed: string;
  accession: string;
  frame?: string;
  restated?: { value: number; filed: string; form: string }[];
}

export interface SecFinancialsPayload {
  company: string;
  concept: string;
  label: string | null;
  unit: string;
  values: SecFinancialValue[];
  note: string;
}

export interface SecSearchHit {
  company: string | null;
  form: string | null;
  file_type: string | null;
  filed: string | null;
  period_ending: string | null;
  items: string[];
  accession: string;
  url?: string;
}

export interface SecSearchPayload {
  query: string;
  total: number;
  hits: SecSearchHit[];
}

/** Concepts offered in the Financials tab; the tool accepts any XBRL concept. */
export const SEC_CONCEPTS = [
  { id: "Revenues", label: "Revenue" },
  { id: "RevenueFromContractWithCustomerExcludingAssessedTax", label: "Revenue (ASC 606)" },
  { id: "NetIncomeLoss", label: "Net income" },
  { id: "OperatingIncomeLoss", label: "Operating income" },
  { id: "EarningsPerShareDiluted", label: "EPS (diluted)", unit: "USD/shares" },
  { id: "NetCashProvidedByUsedInOperatingActivities", label: "Operating cash flow" },
  { id: "CashAndCashEquivalentsAtCarryingValue", label: "Cash" },
  { id: "LongTermDebt", label: "Long-term debt" },
  { id: "Assets", label: "Total assets" },
  { id: "StockholdersEquity", label: "Shareholders' equity" },
] as const;
