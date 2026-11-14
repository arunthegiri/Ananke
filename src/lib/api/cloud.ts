import { apiFetch } from "./client";
import { API_BASE_URL } from "./config";
import type { ComputeTarget } from "./packs";

/**
 * Packs build doc §7 — linked cloud accounts and cloud GPU machines (AWS today).
 * Access keys are write-only: the API returns only a last-four `keyHint`.
 * Nothing billable happens without an estimate the user confirmed (`provision`).
 */

export interface CloudAccount {
  id: number;
  cloud: "aws";
  label: string;
  status: "PENDING" | "LINKED" | "FAILED";
  homeRegion: string;
  /** Goes into the CloudFormation stack's ExternalId parameter. Not a secret. */
  externalId: string;
  roleArn: string | null;
  accountRef: string | null;
  keyHint: string | null;
  lastVerifiedAt: string | null;
  lastError: string | null;
  machines: number;
}

export interface LinkStart {
  account: CloudAccount;
  templateUrl: string;
  cliCommand: string;
  consoleSteps: string[];
}

export interface InstanceTypeInfo {
  type: string;
  gpu: string;
  gpus: number;
  vramGiBPerGpu: number;
  vcpus: number;
  memoryGiB: number;
}

export interface ProvisionRequest {
  label?: string;
  region: string;
  instanceType: string;
  diskGiB: number;
  sshCidr: string;
  idleStopMinutes: number;
  packId?: number | null;
}

export interface Estimate {
  id: string;
  accountId: number;
  request: ProvisionRequest;
  hourlyUsd: number;
  gp3GbMonthUsd: number;
  diskMonthlyUsd: number;
  alwaysOnMonthlyUsd: number;
  source: string;
  createdAt: string;
  expiresAt: string;
  notes: string[];
}

export interface Orphan {
  region: string;
  instanceId: string;
  state: string;
  instanceType: string;
  tags: Record<string, string>;
}

export const AWS_TEMPLATE_URL = `${API_BASE_URL}/api/cloud/aws/template`;

export const cloudApi = {
  accounts: () => apiFetch<CloudAccount[]>("/api/cloud/accounts"),
  linkAws: (body: { label?: string; region: string }) =>
    apiFetch<LinkStart>("/api/cloud/aws/link", { method: "POST", body }),
  verify: (id: number, body: { roleArn: string; accessKeyId?: string; secretAccessKey?: string }) =>
    apiFetch<CloudAccount>(`/api/cloud/accounts/${id}/verify`, { method: "POST", body }),
  unlink: (id: number) => apiFetch<void>(`/api/cloud/accounts/${id}`, { method: "DELETE" }),
  egressIp: () => apiFetch<{ ip?: string; cidr?: string; error?: string }>("/api/cloud/egress-ip"),
  instanceTypes: () => apiFetch<InstanceTypeInfo[]>("/api/cloud/aws/instance-types"),
  estimate: (accountId: number, body: ProvisionRequest) =>
    apiFetch<Estimate>(`/api/cloud/accounts/${accountId}/estimate`, { method: "POST", body }),
  provision: (accountId: number, estimateId: string) =>
    apiFetch<ComputeTarget>(`/api/cloud/accounts/${accountId}/provision`, {
      method: "POST",
      body: { estimateId, acknowledgeBilling: true },
    }),
  stop: (targetId: number) =>
    apiFetch<ComputeTarget>(`/api/cloud/aws/machines/${targetId}/stop`, { method: "POST" }),
  start: (targetId: number) =>
    apiFetch<ComputeTarget>(`/api/cloud/aws/machines/${targetId}/start`, { method: "POST" }),
  terminate: (targetId: number) =>
    apiFetch<ComputeTarget>(`/api/cloud/aws/machines/${targetId}/terminate`, { method: "POST" }),
  orphans: (accountId: number) => apiFetch<Orphan[]>(`/api/cloud/accounts/${accountId}/orphans`),
  terminateOrphan: (accountId: number, region: string, instanceId: string) =>
    apiFetch<void>(`/api/cloud/accounts/${accountId}/orphans/terminate`, {
      method: "POST",
      body: { region, instanceId },
    }),
};

/** Machines whose state is changing server-side; the machine list polls while any exist. */
export const TRANSITIONAL = new Set(["PROVISIONING", "STARTING", "TERMINATING"]);

export function usd(n: number, digits = 2): string {
  return `$${n.toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits })}`;
}
