import { apiFetch } from "./client";

/**
 * Data connectors (Connectors roadmap §1, issue #10; backend: ConnectorController).
 * Every connector is a panel feed (`call`) and, once linked and switched on, a set of
 * Copilot tools. Secret field values are write-only: the API returns a last-four hint.
 */

export type ConnectorCategory =
  | "FILINGS"
  | "MACRO"
  | "NEWS"
  | "SOCIAL"
  | "ALT_DATA"
  | "MARKET_DATA"
  | "OPTIONS_FLOW"
  | "GEOPOLITICS"
  | "PREDICTION_MARKETS";

export type ConnectorLicence = "FREE_PUBLIC" | "BYO_PERSONAL" | "BYO_COMMERCIAL" | "AGPL_SIDECAR";

export type ConnectorStatus = "READY" | "NOT_LINKED" | "DISABLED" | "ERROR";

export interface ConnectorField {
  key: string;
  label: string;
  help: string | null;
  /** Secret fields are never echoed back; `hint` is "…1234" or "set". */
  secret: boolean;
  optional: boolean;
  placeholder: string | null;
  set: boolean;
  /** Non-secret fields: the stored value. Secret fields: a last-four hint. */
  hint: string | null;
}

/** JSON-schema subset the backend's ToolArgs emits. */
export interface ToolParamSchema {
  type?: "string" | "integer" | "number" | "boolean" | "array" | "object";
  description?: string;
  enum?: string[];
  items?: { type?: string };
}

export interface ConnectorToolInfo {
  name: string;
  description: string;
  parameters: {
    type?: "object";
    properties?: Record<string, ToolParamSchema>;
    required?: string[];
  };
}

export interface Connector {
  id: string;
  name: string;
  category: ConnectorCategory;
  licence: ConnectorLicence;
  description: string;
  attribution: string | null;
  homepage: string | null;
  fields: ConnectorField[];
  /** e.g. ["8 / second"] — limits Ananke enforces before calling upstream. */
  limits: string[];
  tools: ConnectorToolInfo[];
  keyless: boolean;
  linked: boolean;
  enabled: boolean;
  status: ConnectorStatus;
  linkedAt: string | null;
  lastTestAt: string | null;
  lastTestOk: boolean | null;
  lastTestMessage: string | null;
  lastCallAt: string | null;
  lastErrorAt: string | null;
  lastError: string | null;
}

/** One tool call. `observedAt` is when Ananke fetched it — cached answers keep the original time. */
export interface ConnectorResult<T> {
  connector: string;
  source: string;
  tool: string;
  attribution: string | null;
  observedAt: string;
  cached: boolean;
  payload: T;
}

export const CATEGORY_LABEL: Record<ConnectorCategory, string> = {
  FILINGS: "Filings & insiders",
  MACRO: "Macro & government",
  NEWS: "News",
  SOCIAL: "Social",
  ALT_DATA: "Alternative data",
  MARKET_DATA: "Market data",
  OPTIONS_FLOW: "Options flow",
  GEOPOLITICS: "Geopolitics",
  PREDICTION_MARKETS: "Prediction markets",
};

export const LICENCE_LABEL: Record<ConnectorLicence, { label: string; detail: string }> = {
  FREE_PUBLIC: { label: "Free · public data", detail: "Government or public-domain data." },
  BYO_PERSONAL: {
    label: "Your key · personal use",
    detail: "Uses your own account. The provider's self-serve terms allow personal use only.",
  },
  BYO_COMMERCIAL: {
    label: "Your key · commercial plan",
    detail: "Uses your own account on a plan that allows commercial use.",
  },
  AGPL_SIDECAR: {
    label: "Self-hosted · AGPL",
    detail: "Runs as a separate service you host; Ananke only calls its API.",
  },
};

const enc = encodeURIComponent;

export const connectorsApi = {
  list: () => apiFetch<Connector[]>("/api/connectors"),
  link: (id: string, values: Record<string, string>) =>
    apiFetch<Connector>(`/api/connectors/${enc(id)}/credentials`, {
      method: "PUT",
      body: { values },
    }),
  unlink: (id: string) =>
    apiFetch<Connector>(`/api/connectors/${enc(id)}/credentials`, { method: "DELETE" }),
  setEnabled: (id: string, enabled: boolean) =>
    apiFetch<Connector>(`/api/connectors/${enc(id)}`, { method: "PUT", body: { enabled } }),
  test: (id: string) => apiFetch<Connector>(`/api/connectors/${enc(id)}/test`, { method: "POST" }),
  call: <T>(id: string, tool: string, args: Record<string, unknown>) =>
    apiFetch<ConnectorResult<T>>(`/api/connectors/${enc(id)}/tools/${enc(tool)}`, {
      method: "POST",
      body: args,
    }),
};
