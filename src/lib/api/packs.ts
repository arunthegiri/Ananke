import { apiFetch } from "./client";

/**
 * Ananke Packs (Dev-notes/Ananke Packs Build Doc.md) — a pinned Hugging Face
 * model deployed with vLLM on a compute target and registered as a copilot
 * provider. Secrets (HF tokens, SSH keys) are write-only: the API only ever
 * reports whether one is set.
 */

export type Compatibility = "supported" | "unverified" | "unsupported";

export interface ModelProfile {
  repo: string;
  revision: string;
  requestedRevision: string;
  task: string | null;
  architectures: string[];
  modelType: string | null;
  license: string | null;
  gated: string;
  isPrivate: boolean;
  contextLength: number | null;
  torchDtype: string | null;
  quantization: string | null;
  quantBits: number | null;
  parameters: number | null;
  estimatedWeightsGiB: number | null;
  hasChatTemplate: boolean;
  chatTemplateMentionsTools: boolean;
  requiresRemoteCode: boolean;
  hasSafetensors: boolean;
  suggestedToolParser: string | null;
  compatibility: Compatibility;
  notes: string[];
  blockers: string[];
}

export interface LaunchConfig {
  dtype: "auto" | "bfloat16" | "float16";
  maxModelLen: number;
  gpuMemoryUtilization: number;
  tensorParallelSize: number;
  remotePort: number;
  enableToolCalling: boolean;
}

export type PackStatus = "IMPORTED" | "DEPLOYING" | "RUNNING" | "FAILED" | "STOPPED";

export interface Pack {
  id: number;
  name: string;
  hfRepo: string;
  hfRevision: string;
  profile: ModelProfile;
  trustRemoteCode: boolean;
  launch: LaunchConfig;
  computeTargetId: number | null;
  status: PackStatus;
  phase: string | null;
  failureCode: string | null;
  failureDetail: string | null;
  endpoint: {
    remoteHost?: string;
    remotePort?: number;
    servedModel?: string;
    transport?: string;
    toolParser?: string;
    toolCalling?: "verified" | "failed" | "not_enabled";
    chat?: string;
  } | null;
  lastHealth: { at: string; ok: boolean; detail: string } | null;
  hfTokenSet: boolean;
  providerId: string;
  createdAt: string;
  updatedAt: string;
}

export interface Gpu {
  name: string;
  memoryMiB: number;
  driver: string;
}

export interface ComputeTarget {
  id: number;
  provider: string;
  label: string;
  host: string;
  port: number;
  username: string;
  hostKeyFingerprint: string | null;
  status: "UNVERIFIED" | "HOSTKEY_CONFIRMED" | "READY" | "NO_GPU" | "ERROR";
  lastProbe:
    | {
        os?: string;
        arch?: string;
        python?: string;
        venv?: boolean;
        diskFreeGiB?: number;
        gpus?: Gpu[];
      }
    | { error: string; detail: string }
    | null;
  lastProbeAt: string | null;
  keyHint: string | null;
}

export interface ComputeProviderInfo {
  id: string;
  label: string;
  canProvision: boolean;
  available: boolean;
  note: string;
}

export const packsApi = {
  resolve: (model: string, hfToken?: string) =>
    apiFetch<ModelProfile>("/api/packs/resolve", { method: "POST", body: { model, hfToken } }),
  list: () => apiFetch<Pack[]>("/api/packs"),
  get: (id: number) => apiFetch<Pack>(`/api/packs/${id}`),
  create: (body: { model: string; name?: string; hfToken?: string; trustRemoteCode?: boolean }) =>
    apiFetch<Pack>("/api/packs", { method: "POST", body }),
  configure: (
    id: number,
    body: {
      computeTargetId?: number;
      launch?: LaunchConfig;
      hfToken?: string;
      trustRemoteCode?: boolean;
    },
  ) => apiFetch<Pack>(`/api/packs/${id}`, { method: "PUT", body }),
  deploy: (id: number) => apiFetch<Pack>(`/api/packs/${id}/deploy`, { method: "POST" }),
  stop: (id: number) => apiFetch<Pack>(`/api/packs/${id}/stop`, { method: "POST" }),
  remove: (id: number) => apiFetch<null>(`/api/packs/${id}`, { method: "DELETE" }),
  health: (id: number) =>
    apiFetch<{ at: string; ok: boolean; detail: string }>(`/api/packs/${id}/health`, {
      method: "POST",
    }),
  logs: (id: number) => apiFetch<{ log: string }>(`/api/packs/${id}/logs`),
  useForCopilot: (id: number) =>
    apiFetch<Pack>(`/api/packs/${id}/use-for-copilot`, { method: "POST" }),

  providers: () => apiFetch<ComputeProviderInfo[]>("/api/compute/providers"),
  targets: () => apiFetch<ComputeTarget[]>("/api/compute/targets"),
  registerTarget: (body: {
    label?: string;
    host: string;
    port?: number;
    username: string;
    privateKey: string;
  }) => apiFetch<ComputeTarget>("/api/compute/targets", { method: "POST", body }),
  confirmHostKey: (id: number, fingerprint: string) =>
    apiFetch<ComputeTarget>(`/api/compute/targets/${id}/confirm-host-key`, {
      method: "POST",
      body: { fingerprint },
    }),
  probeTarget: (id: number) =>
    apiFetch<ComputeTarget>(`/api/compute/targets/${id}/probe`, { method: "POST" }),
  removeTarget: (id: number) => apiFetch<null>(`/api/compute/targets/${id}`, { method: "DELETE" }),
};

/** Human copy for each backend failure code — what happened and what to do. */
export const FAILURE_HELP: Record<string, string> = {
  REPO_NOT_FOUND: "Check the repo id or URL, and the revision if you gave one.",
  REPO_GATED: "Accept the model's terms on Hugging Face, then add a read token that has access.",
  INCOMPATIBLE_MODEL:
    "Packs serve text-generation models on vLLM. This one has a blocker listed above.",
  REMOTE_CODE_UNTRUSTED:
    "The repo ships custom Python. Review it on Hugging Face and tick “Trust remote code” only if you trust the author.",
  SSH_UNREACHABLE:
    "Check the host, port, and that the machine's firewall allows SSH from this computer.",
  SSH_HOSTKEY:
    "The machine's identity changed or wasn't confirmed. Re-add it and compare the fingerprint before trusting it.",
  SSH_AUTH:
    "The machine rejected the key. Add the matching public key to ~/.ssh/authorized_keys for that user.",
  NO_GPU:
    "No NVIDIA GPU was found. Install the NVIDIA driver (nvidia-smi must work) or pick another machine.",
  INSUFFICIENT_VRAM:
    "Use a bigger GPU, more GPUs (tensor parallel), a quantized variant, or a smaller model.",
  MISSING_PREREQ:
    "Install python3 and python3-venv on the machine, and free enough disk for the weights.",
  INSTALL_FAILED:
    "pip couldn't install vLLM. Check the machine's internet access, CUDA driver and Python version (see logs).",
  DOWNLOAD_FAILED:
    "The weights didn't download: gated repo, token without access, or not enough disk (see logs).",
  VLLM_START_FAILED:
    "vLLM exited while loading. Common causes: out of GPU memory, unsupported architecture, context too long.",
  HEALTH_TIMEOUT:
    "vLLM never became healthy. Large models can take a while — check the logs and redeploy.",
  TUNNEL_FAILED: "The SSH tunnel to the model server could not be opened.",
};
