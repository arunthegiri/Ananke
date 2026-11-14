import { useEffect, useState } from "react";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  Check,
  Bot,
  CircleAlert,
  Cloud,
  Cpu,
  Loader2,
  Package,
  Play,
  Plus,
  RefreshCw,
  Server,
  ShieldAlert,
  Sparkles,
  Square,
  Trash2,
} from "lucide-react";
import { toast } from "sonner";

import { TerminalPanel } from "@/components/terminal/TerminalPanel";
import { PanelState } from "@/components/terminal/PanelState";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  FAILURE_HELP,
  packsApi,
  type ComputeTarget,
  type LaunchConfig,
  type ModelProfile,
  type Pack,
} from "@/lib/api/packs";
import { LLM_SETTINGS_KEY } from "@/hooks/useLlmSettings";
import { AwsCloudDialog } from "@/components/packs/AwsCloudDialog";
import { PackAgentDialog, PackAudit, PendingActions } from "@/components/packs/AgentActions";
import { cloudApi, TRANSITIONAL, usd } from "@/lib/api/cloud";
import { cn } from "@/lib/utils";

/**
 * Ananke Packs — bring a Hugging Face model, run it with vLLM on your own GPU
 * machine, and use it as the copilot's provider. Build doc:
 * Dev-notes/Ananke Packs Build Doc.md. URL = which Pack is open (?p=<id>).
 */
export const Route = createFileRoute("/packs")({
  validateSearch: (search: Record<string, unknown>): { p?: number } => {
    const n = Number(search.p);
    return Number.isInteger(n) && n > 0 ? { p: n } : {};
  },
  head: () => ({ meta: [{ title: "Packs — Ananke Trading" }] }),
  component: PacksPage,
});

const PACKS_KEY = ["packs"] as const;
const TARGETS_KEY = ["compute", "targets"] as const;

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function errCode(e: unknown): string | null {
  const body = (e as { body?: { code?: unknown } } | null)?.body;
  return body && typeof body.code === "string" ? body.code : null;
}

// ── page ─────────────────────────────────────────────────────────────────────

function PacksPage() {
  const { p: selectedId } = Route.useSearch();
  const navigate = useNavigate({ from: "/packs" });
  const packs = useQuery({ queryKey: PACKS_KEY, queryFn: packsApi.list, retry: false });
  const targets = useQuery({
    queryKey: TARGETS_KEY,
    queryFn: packsApi.targets,
    retry: false,
    // Cloud machines boot / stop / terminate server-side: poll only while one is changing.
    refetchInterval: (q) => (q.state.data?.some((t) => TRANSITIONAL.has(t.status)) ? 5000 : false),
  });
  const qc = useQueryClient();
  const [newPackOpen, setNewPackOpen] = useState(false);
  const [addMachineOpen, setAddMachineOpen] = useState(false);
  const [cloudOpen, setCloudOpen] = useState(false);
  const [agentOpen, setAgentOpen] = useState(false);

  const select = (id?: number) => navigate({ search: id ? { p: id } : {} });
  const current = packs.data?.find((p) => p.id === selectedId) ?? packs.data?.[0];

  return (
    <div className="flex h-full min-h-0 gap-3 p-3" data-testid="packs-page">
      <div className="flex w-72 shrink-0 flex-col gap-3">
        <TerminalPanel
          tabs={[{ id: "packs", label: "Packs", count: packs.data?.length }]}
          activeTab="packs"
          className="min-h-0 flex-1"
          bodyClassName="min-h-0 overflow-y-auto"
          actions={
            <button
              type="button"
              onClick={() => setNewPackOpen(true)}
              className="flex items-center gap-1 px-2 text-xs text-text-secondary hover:text-text-primary"
              data-testid="new-pack"
            >
              <Plus className="h-3.5 w-3.5" /> New
            </button>
          }
        >
          {packs.isError ? (
            <PanelState
              kind="error"
              art="plug"
              message="Couldn't load Packs"
              detail={[errText(packs.error), "GET /api/packs"]}
            />
          ) : packs.isLoading ? (
            <PanelState kind="loading" art="table" message="Loading Packs…" />
          ) : packs.data!.length === 0 ? (
            <PanelState
              kind="empty"
              art="chart"
              message="No Packs yet. Bring a Hugging Face model to run it on your own GPU."
              action={{ label: "New Pack", onClick: () => setNewPackOpen(true) }}
            />
          ) : (
            <ul className="py-1">
              {packs.data!.map((p) => (
                <li key={p.id}>
                  <button
                    type="button"
                    onClick={() => select(p.id)}
                    className={cn(
                      "flex w-full flex-col gap-0.5 px-3 py-2 text-left transition-colors",
                      p.id === current?.id ? "bg-surface-2" : "hover:bg-surface-2/60",
                    )}
                  >
                    <span className="flex items-center gap-2 text-xs text-text-primary">
                      <Package className="h-3.5 w-3.5 text-text-muted" />
                      <span className="flex-1 truncate">{p.name}</span>
                      <StatusDot status={p.status} />
                    </span>
                    <span className="truncate pl-5 font-mono text-[10px] text-text-muted">
                      {p.hfRepo}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </TerminalPanel>

        <TerminalPanel
          tabs={[{ id: "machines", label: "Machines", count: targets.data?.length }]}
          activeTab="machines"
          className="max-h-[45%] min-h-[180px]"
          bodyClassName="min-h-0 overflow-y-auto"
          actions={
            <span className="flex">
              <button
                type="button"
                onClick={() => setCloudOpen(true)}
                className="flex items-center gap-1 px-2 text-xs text-text-secondary hover:text-text-primary"
                data-testid="cloud-machines"
              >
                <Cloud className="h-3.5 w-3.5" /> Cloud
              </button>
              <button
                type="button"
                onClick={() => setAddMachineOpen(true)}
                className="flex items-center gap-1 px-2 text-xs text-text-secondary hover:text-text-primary"
                data-testid="add-machine"
              >
                <Plus className="h-3.5 w-3.5" /> Add
              </button>
            </span>
          }
        >
          <MachineList targets={targets.data} loading={targets.isLoading} error={targets.error} />
        </TerminalPanel>
      </div>

      <TerminalPanel
        tabs={[{ id: "pack", label: current ? current.name : "Pack" }]}
        activeTab="pack"
        className="min-h-0 flex-1"
        bodyClassName="min-h-0 overflow-y-auto"
        actions={
          <button
            type="button"
            onClick={() => setAgentOpen(true)}
            className="flex items-center gap-1 px-2 text-xs text-text-secondary hover:text-text-primary"
            data-testid="open-pack-agent"
          >
            <Bot className="h-3.5 w-3.5" /> Ask agent
          </button>
        }
      >
        <PendingActions className="m-3" />
        {current ? (
          <PackDetail
            key={current.id}
            packId={current.id}
            targets={targets.data ?? []}
            onRemoved={() => select()}
          />
        ) : (
          <PanelState
            kind="empty"
            art="chart"
            message="Select a Pack, or create one from a Hugging Face model."
          />
        )}
      </TerminalPanel>

      <NewPackDialog
        open={newPackOpen}
        onOpenChange={setNewPackOpen}
        onCreated={(p) => select(p.id)}
      />
      <AddMachineDialog open={addMachineOpen} onOpenChange={setAddMachineOpen} />
      <PackAgentDialog open={agentOpen} onOpenChange={setAgentOpen} packId={current?.id ?? null} />
      <AwsCloudDialog
        open={cloudOpen}
        onOpenChange={setCloudOpen}
        packs={packs.data ?? []}
        onMachinesChanged={() => void qc.invalidateQueries({ queryKey: TARGETS_KEY })}
      />
    </div>
  );
}

function StatusDot({ status }: { status: Pack["status"] }) {
  const cls =
    status === "RUNNING"
      ? "bg-bull"
      : status === "FAILED"
        ? "bg-bear"
        : status === "DEPLOYING"
          ? "bg-warning animate-pulse"
          : "bg-text-muted";
  return <span className={cn("h-1.5 w-1.5 rounded-full", cls)} title={status} />;
}

// ── machines ─────────────────────────────────────────────────────────────────

function MachineList({
  targets,
  loading,
  error,
}: {
  targets: ComputeTarget[] | undefined;
  loading: boolean;
  error: unknown;
}) {
  const qc = useQueryClient();
  const probe = useMutation({
    mutationFn: (id: number) => packsApi.probeTarget(id),
    onSettled: () => qc.invalidateQueries({ queryKey: TARGETS_KEY }),
    onError: (e) => toast.error(errText(e)),
  });
  const remove = useMutation({
    mutationFn: (id: number) => packsApi.removeTarget(id),
    onSettled: () => qc.invalidateQueries({ queryKey: TARGETS_KEY }),
    onError: (e) => toast.error(errText(e)),
  });
  const cloud = useMutation({
    mutationFn: ({ id, action }: { id: number; action: "start" | "stop" | "terminate" }) =>
      cloudApi[action](id),
    onSettled: () => qc.invalidateQueries({ queryKey: TARGETS_KEY }),
    onError: (e) => toast.error(errText(e)),
  });

  if (error)
    return (
      <PanelState
        kind="error"
        art="plug"
        message="Couldn't load machines"
        detail={[errText(error)]}
      />
    );
  if (loading) return <PanelState kind="loading" art="table" message="Loading…" />;
  if (!targets || targets.length === 0) {
    return (
      <PanelState
        kind="empty"
        art="plug"
        message="Add a Linux machine with an NVIDIA GPU that you can SSH into, or create one in your AWS account (Cloud)."
      />
    );
  }
  return (
    <ul className="py-1">
      {targets.map((t) => {
        const gpus = t.lastProbe && "gpus" in t.lastProbe ? (t.lastProbe.gpus ?? []) : [];
        return (
          <li key={t.id} className="group flex flex-col gap-0.5 px-3 py-2 text-xs">
            <span className="flex items-center gap-2 text-text-primary">
              <Server className="h-3.5 w-3.5 text-text-muted" />
              <span className="flex-1 truncate">{t.label}</span>
              <span
                className={cn(
                  "text-[10px]",
                  t.status === "READY"
                    ? "text-bull"
                    : t.status === "ERROR" || t.status === "NO_GPU"
                      ? "text-bear"
                      : "text-warning",
                )}
              >
                {t.status === "HOSTKEY_CONFIRMED" ? "NOT PROBED" : t.status.replaceAll("_", " ")}
              </span>
            </span>
            <span className="truncate pl-5 font-mono text-[10px] text-text-muted">
              {t.provider === "aws"
                ? `${t.cloudMeta?.instanceType ?? ""} · ${t.cloudRegion} · ${t.cloudInstanceId ?? "launching"}`
                : `${t.username}@${t.host}:${t.port}`}
            </span>
            {t.provider === "aws" && (
              <span className="truncate pl-5 text-[10px] text-text-muted">
                {TRANSITIONAL.has(t.status) && t.cloudMeta?.phase
                  ? t.cloudMeta.phase.replaceAll("_", " ").toLowerCase() + "…"
                  : t.cloudMeta?.estimate
                    ? `${usd(t.cloudMeta.estimate.hourlyUsd, 4)}/h while running · disk ${usd(t.cloudMeta.estimate.diskMonthlyUsd)}/mo` +
                      (t.idleStopMinutes ? ` · auto-stop ${t.idleStopMinutes} min` : "")
                    : null}
              </span>
            )}
            {t.provider === "aws" && t.status === "ERROR" && t.cloudMeta?.error && (
              <span className="pl-5 text-[10px] text-bear">{t.cloudMeta.error}</span>
            )}
            {gpus.length > 0 && (
              <span className="truncate pl-5 text-[10px] text-text-muted">
                {gpus.map((g) => `${g.name} · ${Math.round(g.memoryMiB / 1024)} GiB`).join(", ")}
              </span>
            )}
            <span className="flex gap-3 pl-5 text-[10px] text-text-muted">
              {t.provider === "aws" ? (
                <>
                  {["HOSTKEY_CONFIRMED", "READY", "NO_GPU"].includes(t.status) && (
                    <button
                      type="button"
                      className="hover:text-text-primary"
                      onClick={() => probe.mutate(t.id)}
                    >
                      {probe.isPending && probe.variables === t.id ? "Checking…" : "Check machine"}
                    </button>
                  )}
                  {t.cloudMeta?.state === "running" && t.status !== "TERMINATING" && (
                    <button
                      type="button"
                      className="hover:text-text-primary"
                      onClick={() => cloud.mutate({ id: t.id, action: "stop" })}
                    >
                      Stop
                    </button>
                  )}
                  {t.status === "STOPPED" && (
                    <button
                      type="button"
                      className="hover:text-text-primary"
                      onClick={() => cloud.mutate({ id: t.id, action: "start" })}
                    >
                      Start (billable)
                    </button>
                  )}
                  {!["TERMINATING", "PROVISIONING"].includes(t.status) && (
                    <button
                      type="button"
                      className="hover:text-bear"
                      onClick={() => {
                        if (
                          window.confirm(
                            `Terminate ${t.label}? The VM and its disk are deleted in AWS.`,
                          )
                        ) {
                          cloud.mutate({ id: t.id, action: "terminate" });
                        }
                      }}
                    >
                      Terminate
                    </button>
                  )}
                </>
              ) : (
                <>
                  {t.status !== "UNVERIFIED" && (
                    <button
                      type="button"
                      className="hover:text-text-primary"
                      onClick={() => probe.mutate(t.id)}
                    >
                      {probe.isPending && probe.variables === t.id ? "Checking…" : "Check machine"}
                    </button>
                  )}
                  <button
                    type="button"
                    className="hover:text-bear"
                    onClick={() => remove.mutate(t.id)}
                  >
                    Remove
                  </button>
                </>
              )}
            </span>
          </li>
        );
      })}
    </ul>
  );
}

function AddMachineDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
}) {
  const qc = useQueryClient();
  const [form, setForm] = useState({
    label: "",
    host: "",
    port: "22",
    username: "ubuntu",
    privateKey: "",
  });
  const [pending, setPending] = useState<ComputeTarget | null>(null);

  useEffect(() => {
    if (!open) {
      setPending(null);
      setForm({ label: "", host: "", port: "22", username: "ubuntu", privateKey: "" });
    }
  }, [open]);

  const register = useMutation({
    mutationFn: () =>
      packsApi.registerTarget({
        label: form.label || undefined,
        host: form.host,
        port: Number(form.port) || 22,
        username: form.username,
        privateKey: form.privateKey,
      }),
    onSuccess: (t) => {
      setPending(t);
      // The key has been sent once; never keep it in component state longer than needed.
      setForm((f) => ({ ...f, privateKey: "" }));
      qc.invalidateQueries({ queryKey: TARGETS_KEY });
    },
  });
  const confirm = useMutation({
    mutationFn: async () => {
      const t = await packsApi.confirmHostKey(pending!.id, pending!.hostKeyFingerprint!);
      return packsApi.probeTarget(t.id);
    },
    onSuccess: (t) => {
      toast.success(
        t.status === "READY" ? "Machine connected" : `Connected, but status is ${t.status}`,
      );
      onOpenChange(false);
    },
    onSettled: () => qc.invalidateQueries({ queryKey: TARGETS_KEY }),
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg" data-testid="add-machine-dialog">
        <DialogHeader>
          <DialogTitle className="text-sm">Connect a GPU machine</DialogTitle>
          <DialogDescription className="text-xs text-text-muted">
            An existing Linux machine with an NVIDIA driver, python3 and python3-venv. Use a
            dedicated, passphrase-less deploy key. The key is encrypted at rest and never shown
            again.
          </DialogDescription>
        </DialogHeader>

        {!pending ? (
          <form
            className="flex flex-col gap-3"
            onSubmit={(e) => {
              e.preventDefault();
              register.mutate();
            }}
          >
            <div className="grid grid-cols-[1fr_80px] gap-2">
              <Field label="Host">
                <Input
                  value={form.host}
                  onChange={(e) => setForm({ ...form, host: e.target.value })}
                  placeholder="gpu.example.com"
                />
              </Field>
              <Field label="Port">
                <Input
                  value={form.port}
                  onChange={(e) => setForm({ ...form, port: e.target.value })}
                  inputMode="numeric"
                />
              </Field>
            </div>
            <div className="grid grid-cols-2 gap-2">
              <Field label="User">
                <Input
                  value={form.username}
                  onChange={(e) => setForm({ ...form, username: e.target.value })}
                />
              </Field>
              <Field label="Label (optional)">
                <Input
                  value={form.label}
                  onChange={(e) => setForm({ ...form, label: e.target.value })}
                  placeholder="lab A100"
                />
              </Field>
            </div>
            <Field label="Private key">
              <Textarea
                value={form.privateKey}
                onChange={(e) => setForm({ ...form, privateKey: e.target.value })}
                placeholder="-----BEGIN OPENSSH PRIVATE KEY-----"
                className="h-28 font-mono text-[11px]"
                autoComplete="off"
                spellCheck={false}
              />
            </Field>
            {register.isError && <ErrorLine error={register.error} />}
            <div className="flex justify-end">
              <Button
                type="submit"
                size="sm"
                disabled={!form.host || !form.username || !form.privateKey || register.isPending}
              >
                {register.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />} Scan host
                key
              </Button>
            </div>
          </form>
        ) : (
          <div className="flex flex-col gap-3 text-xs">
            <div className="flex items-start gap-2 rounded-md border border-warning/40 bg-warning/10 p-3 text-text-secondary">
              <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-warning" />
              <div className="flex flex-col gap-2">
                <span>
                  Verify this is <b>{pending.host}</b>. On the machine, run{" "}
                  <code className="font-mono">
                    ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
                  </code>{" "}
                  and compare:
                </span>
                <code
                  className="break-all font-mono text-[11px] text-text-primary"
                  data-testid="host-fingerprint"
                >
                  {pending.hostKeyFingerprint}
                </code>
                <span className="text-text-muted">
                  Ananke will only ever connect to the machine holding this key.
                </span>
              </div>
            </div>
            {confirm.isError && <ErrorLine error={confirm.error} />}
            <div className="flex justify-end gap-2">
              <Button variant="ghost" size="sm" onClick={() => onOpenChange(false)}>
                It doesn't match
              </Button>
              <Button size="sm" onClick={() => confirm.mutate()} disabled={confirm.isPending}>
                {confirm.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />} Fingerprint
                matches — connect
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

// ── new pack ─────────────────────────────────────────────────────────────────

function NewPackDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  onCreated: (p: Pack) => void;
}) {
  const qc = useQueryClient();
  const [model, setModel] = useState("");
  const [token, setToken] = useState("");
  const [name, setName] = useState("");
  const [trust, setTrust] = useState(false);
  const [profile, setProfile] = useState<ModelProfile | null>(null);

  useEffect(() => {
    if (!open) {
      setModel("");
      setToken("");
      setName("");
      setTrust(false);
      setProfile(null);
    }
  }, [open]);

  const resolve = useMutation({
    mutationFn: () => packsApi.resolve(model, token || undefined),
    onSuccess: setProfile,
  });
  const create = useMutation({
    mutationFn: () =>
      packsApi.create({
        model: `${profile!.repo}@${profile!.revision}`,
        name: name || undefined,
        hfToken: token || undefined,
        trustRemoteCode: trust,
      }),
    onSuccess: (p) => {
      qc.invalidateQueries({ queryKey: PACKS_KEY });
      onCreated(p);
      onOpenChange(false);
    },
  });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl" data-testid="new-pack-dialog">
        <DialogHeader>
          <DialogTitle className="text-sm">New Pack</DialogTitle>
          <DialogDescription className="text-xs text-text-muted">
            Paste a Hugging Face model. Ananke pins the exact commit so the Pack never changes
            underneath you.
          </DialogDescription>
        </DialogHeader>

        <form
          className="flex flex-col gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            setProfile(null);
            resolve.mutate();
          }}
        >
          <Field label="Model URL or repo id">
            <div className="flex gap-2">
              <Input
                value={model}
                onChange={(e) => {
                  setModel(e.target.value);
                  setProfile(null);
                }}
                placeholder="https://huggingface.co/Qwen/Qwen3-8B  or  Qwen/Qwen3-8B@<revision>"
                className="font-mono text-xs"
                data-testid="pack-model-input"
              />
              <Button type="submit" size="sm" disabled={!model || resolve.isPending}>
                {resolve.isPending ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : "Check"}
              </Button>
            </div>
          </Field>
          <Field label="Hugging Face token (only for gated/private models)">
            <Input
              type="password"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              placeholder="hf_…"
              autoComplete="off"
            />
          </Field>
        </form>

        {resolve.isError && <ErrorLine error={resolve.error} />}

        {profile && (
          <div className="flex flex-col gap-3">
            <ProfileSummary profile={profile} />
            {profile.requiresRemoteCode && (
              <label className="flex items-start gap-2 rounded-md border border-warning/40 bg-warning/10 p-3 text-xs text-text-secondary">
                <Switch checked={trust} onCheckedChange={setTrust} className="mt-0.5" />
                <span>
                  <b className="text-text-primary">Trust remote code.</b> This repo ships Python
                  that vLLM would execute on your machine. Only enable this after reviewing it on
                  Hugging Face. Off by default.
                </span>
              </label>
            )}
            <div className="flex items-end gap-2">
              <Field label="Name (optional)" className="flex-1">
                <Input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder={profile.repo.split("/")[1]}
                />
              </Field>
              <Button
                size="sm"
                onClick={() => create.mutate()}
                disabled={profile.compatibility === "unsupported" || create.isPending}
                data-testid="create-pack"
              >
                {create.isPending && <Loader2 className="h-3.5 w-3.5 animate-spin" />} Create Pack
              </Button>
            </div>
            {create.isError && <ErrorLine error={create.error} />}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

function CompatBadge({ c }: { c: ModelProfile["compatibility"] }) {
  const map = {
    supported: ["text-bull border-bull/40", "vLLM: supported"],
    unverified: ["text-warning border-warning/40", "vLLM: unverified"],
    unsupported: ["text-bear border-bear/40", "Not supported"],
  } as const;
  const [cls, label] = map[c];
  return (
    <span
      className={cn(
        "rounded border px-1.5 py-0.5 text-[10px] font-medium uppercase tracking-wide",
        cls,
      )}
    >
      {label}
    </span>
  );
}

function ProfileSummary({ profile: p }: { profile: ModelProfile }) {
  const rows: [string, string][] = [
    ["Repo", p.repo],
    ["Pinned revision", p.revision],
    ["Task", p.task ?? "—"],
    ["Architecture", p.architectures.join(", ") || "—"],
    ["Context length", p.contextLength?.toLocaleString() ?? "—"],
    ["Parameters", p.parameters ? `${(p.parameters / 1e9).toFixed(2)} B` : "—"],
    ["Weights (est.)", p.estimatedWeightsGiB != null ? `~${p.estimatedWeightsGiB} GiB VRAM` : "—"],
    ["Dtype", p.torchDtype ?? "—"],
    [
      "Quantization",
      p.quantization ? `${p.quantization}${p.quantBits ? ` (${p.quantBits}-bit)` : ""}` : "none",
    ],
    [
      "Chat template",
      p.hasChatTemplate ? (p.chatTemplateMentionsTools ? "yes, with tools" : "yes") : "missing",
    ],
    ["Tool parser", p.suggestedToolParser ?? "none known"],
    ["License", p.license ?? "unknown"],
    ["Access", p.gated !== "false" ? `gated (${p.gated})` : p.isPrivate ? "private" : "public"],
    ["Custom code", p.requiresRemoteCode ? "yes — needs explicit trust" : "no"],
  ];
  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <CompatBadge c={p.compatibility} />
      </div>
      <dl className="grid grid-cols-[140px_1fr] gap-x-3 gap-y-1 text-xs">
        {rows.map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-text-muted">{k}</dt>
            <dd
              className={cn(
                "truncate text-text-primary",
                k === "Pinned revision" && "font-mono text-[11px]",
              )}
              title={v}
            >
              {v}
            </dd>
          </div>
        ))}
      </dl>
      {p.blockers.map((b) => (
        <p key={b} className="flex gap-1.5 text-xs text-bear">
          <CircleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          {b}
        </p>
      ))}
      {p.notes.map((n) => (
        <p key={n} className="flex gap-1.5 text-xs text-text-muted">
          <CircleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0 text-warning" />
          {n}
        </p>
      ))}
    </div>
  );
}

// ── pack detail ──────────────────────────────────────────────────────────────

function PackDetail({
  packId,
  targets,
  onRemoved,
}: {
  packId: number;
  targets: ComputeTarget[];
  onRemoved: () => void;
}) {
  const qc = useQueryClient();
  const pack = useQuery({
    queryKey: [...PACKS_KEY, packId],
    queryFn: () => packsApi.get(packId),
    refetchInterval: (q) => (q.state.data?.status === "DEPLOYING" ? 3000 : false),
  });
  const p = pack.data;

  // Keep the list's status dot in step while a deploy is running.
  useEffect(() => {
    if (p) qc.invalidateQueries({ queryKey: PACKS_KEY, exact: true });
  }, [p?.status, qc]); // eslint-disable-line react-hooks/exhaustive-deps

  const refresh = (next?: Pack) => {
    if (next) qc.setQueryData([...PACKS_KEY, packId], next);
    qc.invalidateQueries({ queryKey: PACKS_KEY });
  };
  const action = useMutation({
    mutationFn: async (kind: "deploy" | "stop" | "health" | "copilot" | "remove") => {
      switch (kind) {
        case "deploy":
          return packsApi.deploy(packId);
        case "stop":
          return packsApi.stop(packId);
        case "copilot":
          return packsApi.useForCopilot(packId);
        case "health":
          await packsApi.health(packId);
          return packsApi.get(packId);
        case "remove":
          await packsApi.remove(packId);
          return null;
      }
    },
    onSuccess: (next, kind) => {
      if (kind === "remove") {
        toast.success("Pack removed");
        qc.invalidateQueries({ queryKey: PACKS_KEY });
        onRemoved();
        return;
      }
      if (kind === "copilot") {
        toast.success("Copilot now uses this Pack");
        qc.invalidateQueries({ queryKey: LLM_SETTINGS_KEY });
      }
      refresh(next ?? undefined);
    },
    onError: (e) => toast.error(errText(e)),
  });

  if (pack.isError)
    return (
      <PanelState
        kind="error"
        art="plug"
        message="Couldn't load this Pack"
        detail={[errText(pack.error)]}
      />
    );
  if (!p) return <PanelState kind="loading" art="table" message="Loading…" />;

  const busy = p.status === "DEPLOYING" || action.isPending;
  const toolOk = p.endpoint?.toolCalling === "verified";

  return (
    <div className="flex flex-col gap-5 p-5">
      {/* status + controls */}
      <div className="flex flex-wrap items-center gap-3">
        <span className="flex items-center gap-2 text-sm text-text-primary">
          <StatusDot status={p.status} />
          {p.status === "DEPLOYING" ? `Deploying · ${humanPhase(p.phase)}` : p.status.toLowerCase()}
        </span>
        <CompatBadge c={p.profile.compatibility} />
        <div className="ml-auto flex flex-wrap gap-2">
          {p.status === "RUNNING" ? (
            <>
              <Button
                size="sm"
                variant="outline"
                onClick={() => action.mutate("health")}
                disabled={busy}
              >
                <Activity className="h-3.5 w-3.5" /> Check health
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => action.mutate("stop")}
                disabled={busy}
              >
                <Square className="h-3.5 w-3.5" /> Stop
              </Button>
              <Button
                size="sm"
                variant="outline"
                onClick={() => action.mutate("deploy")}
                disabled={busy}
              >
                <RefreshCw className="h-3.5 w-3.5" /> Redeploy
              </Button>
              <Button
                size="sm"
                onClick={() => action.mutate("copilot")}
                disabled={busy || !toolOk}
                title={toolOk ? undefined : "Tool calling wasn't verified; the copilot needs it."}
                data-testid="use-for-copilot"
              >
                <Sparkles className="h-3.5 w-3.5" /> Use for Copilot
              </Button>
            </>
          ) : (
            <Button
              size="sm"
              onClick={() => action.mutate("deploy")}
              disabled={
                busy || p.computeTargetId == null || p.profile.compatibility === "unsupported"
              }
              data-testid="deploy-pack"
            >
              {busy ? (
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
              ) : (
                <Play className="h-3.5 w-3.5" />
              )}
              {p.status === "IMPORTED" ? "Deploy" : p.status === "STOPPED" ? "Start" : "Redeploy"}
            </Button>
          )}
          <Button
            size="sm"
            variant="ghost"
            onClick={() => action.mutate("remove")}
            disabled={busy}
            title="Stop, delete from the machine, and forget this Pack"
          >
            <Trash2 className="h-3.5 w-3.5" />
          </Button>
        </div>
      </div>

      {p.status === "FAILED" && p.failureCode && (
        <div
          className="flex gap-2 rounded-md border border-bear/40 bg-bear/10 p-3 text-xs"
          data-testid="pack-failure"
        >
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0 text-bear" />
          <div className="flex min-w-0 flex-col gap-1">
            <span className="font-mono text-[10px] uppercase tracking-wide text-bear">
              {p.failureCode}
            </span>
            <span className="whitespace-pre-wrap break-words text-text-primary">
              {p.failureDetail}
            </span>
            {FAILURE_HELP[p.failureCode] && (
              <span className="text-text-muted">{FAILURE_HELP[p.failureCode]}</span>
            )}
          </div>
        </div>
      )}

      {p.status === "RUNNING" && (
        <Section title="Endpoint">
          <dl className="grid grid-cols-[140px_1fr] gap-x-3 gap-y-1 text-xs">
            <dt className="text-text-muted">Copilot provider</dt>
            <dd className="font-mono text-text-primary">{p.providerId}</dd>
            <dt className="text-text-muted">Served model</dt>
            <dd className="font-mono text-text-primary">{p.endpoint?.servedModel}</dd>
            <dt className="text-text-muted">Transport</dt>
            <dd className="text-text-primary">
              SSH tunnel → {p.endpoint?.remoteHost} 127.0.0.1:{p.endpoint?.remotePort} (not exposed
              publicly)
            </dd>
            <dt className="text-text-muted">Tool calling</dt>
            <dd className={toolOk ? "text-bull" : "text-warning"}>
              {toolOk ? (
                <span className="flex items-center gap-1">
                  <Check className="h-3.5 w-3.5" /> verified ({p.endpoint?.toolParser})
                </span>
              ) : p.endpoint?.toolCalling === "not_enabled" ? (
                "not enabled — the copilot can't use this model"
              ) : (
                "probe failed — the copilot can't use this model"
              )}
            </dd>
            <dt className="text-text-muted">Last health check</dt>
            <dd className={p.lastHealth?.ok ? "text-bull" : "text-bear"}>
              {p.lastHealth
                ? `${p.lastHealth.ok ? "healthy" : p.lastHealth.detail} · ${new Date(p.lastHealth.at).toLocaleTimeString()}`
                : "—"}
            </dd>
          </dl>
        </Section>
      )}

      <DeploySettings pack={p} targets={targets} disabled={busy} onSaved={refresh} />

      <Section title="Model">
        <ProfileSummary profile={p.profile} />
      </Section>

      <Section title="Cloud & agent activity">
        <PackAudit packId={p.id} />
        <p className="text-[10px] text-text-muted">
          Every billable step (requested, confirmed, executed, failed) for this Pack is recorded
          here.
        </p>
      </Section>

      <LogsSection packId={packId} status={p.status} updatedAt={p.updatedAt} />
    </div>
  );
}

function humanPhase(phase: string | null): string {
  switch (phase) {
    case "CHECKING_TARGET":
      return "checking the machine";
    case "INSTALLING":
      return "installing vLLM, downloading weights, launching";
    case "HEALTH_CHECK":
      return "waiting for the model to load";
    case "VERIFYING_TOOLS":
      return "verifying tool calling";
    default:
      return "starting";
  }
}

function DeploySettings({
  pack,
  targets,
  disabled,
  onSaved,
}: {
  pack: Pack;
  targets: ComputeTarget[];
  disabled: boolean;
  onSaved: (p: Pack) => void;
}) {
  const [targetId, setTargetId] = useState<number | null>(pack.computeTargetId);
  const [launch, setLaunch] = useState<LaunchConfig>(pack.launch);
  const [token, setToken] = useState("");
  const [trust, setTrust] = useState(pack.trustRemoteCode);
  const save = useMutation({
    mutationFn: () =>
      packsApi.configure(pack.id, {
        computeTargetId: targetId ?? undefined,
        launch,
        hfToken: token ? token : undefined,
        trustRemoteCode: pack.profile.requiresRemoteCode ? trust : undefined,
      }),
    onSuccess: (p) => {
      setToken("");
      toast.success("Deployment settings saved");
      onSaved(p);
    },
    onError: (e) => toast.error(errText(e)),
  });
  const usable = targets.filter((t) => t.status !== "UNVERIFIED");
  const num = (k: keyof LaunchConfig) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setLaunch({ ...launch, [k]: Number(e.target.value) });

  return (
    <Section title="Deployment">
      <div className="grid grid-cols-3 gap-3">
        <Field label="Machine" className="col-span-3">
          <select
            value={targetId ?? ""}
            onChange={(e) => setTargetId(e.target.value ? Number(e.target.value) : null)}
            className="h-8 rounded-md border border-border-subtle bg-surface-1 px-2 text-xs text-text-primary"
            disabled={disabled}
            data-testid="pack-target"
          >
            <option value="">
              {usable.length ? "Choose a machine…" : "Add a machine first (left)"}
            </option>
            {usable.map((t) => (
              <option key={t.id} value={t.id}>
                {t.label} — {t.username}@{t.host} ({t.status})
              </option>
            ))}
          </select>
        </Field>
        <Field label="Dtype">
          <select
            value={launch.dtype}
            onChange={(e) =>
              setLaunch({ ...launch, dtype: e.target.value as LaunchConfig["dtype"] })
            }
            className="h-8 rounded-md border border-border-subtle bg-surface-1 px-2 text-xs text-text-primary"
            disabled={disabled}
          >
            <option value="auto">auto</option>
            <option value="bfloat16">bfloat16</option>
            <option value="float16">float16</option>
          </select>
        </Field>
        <Field label="Max context (0 = model default)">
          <Input
            value={launch.maxModelLen}
            onChange={num("maxModelLen")}
            inputMode="numeric"
            disabled={disabled}
          />
        </Field>
        <Field label="GPU memory utilization">
          <Input
            value={launch.gpuMemoryUtilization}
            onChange={num("gpuMemoryUtilization")}
            inputMode="decimal"
            disabled={disabled}
          />
        </Field>
        <Field label="Tensor parallel (GPUs)">
          <Input
            value={launch.tensorParallelSize}
            onChange={num("tensorParallelSize")}
            inputMode="numeric"
            disabled={disabled}
          />
        </Field>
        <Field label="Port on the machine (localhost only)">
          <Input
            value={launch.remotePort}
            onChange={num("remotePort")}
            inputMode="numeric"
            disabled={disabled}
          />
        </Field>
        <Field
          label={`HF token ${pack.hfTokenSet ? "(saved — paste to replace)" : "(gated models)"}`}
        >
          <Input
            type="password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="hf_…"
            autoComplete="off"
            disabled={disabled}
          />
        </Field>
        <label className="col-span-3 flex items-center gap-2 text-xs text-text-secondary">
          <Switch
            checked={launch.enableToolCalling}
            onCheckedChange={(v) => setLaunch({ ...launch, enableToolCalling: v })}
            disabled={disabled || !pack.profile.suggestedToolParser}
          />
          Enable tool calling
          {pack.profile.suggestedToolParser
            ? ` (parser: ${pack.profile.suggestedToolParser})`
            : " — no known parser for this model"}
          . Required for the copilot; verified after deploy.
        </label>
        {pack.profile.requiresRemoteCode && (
          <label className="col-span-3 flex items-center gap-2 text-xs text-text-secondary">
            <Switch checked={trust} onCheckedChange={setTrust} disabled={disabled} />
            <span>
              <b className="text-warning">Trust remote code</b> — runs this repo's Python on your
              machine.
            </span>
          </label>
        )}
      </div>
      <div className="flex items-center justify-end gap-3">
        <span className="flex items-center gap-1 text-[10px] text-text-muted">
          <Cpu className="h-3 w-3" /> Runs vLLM in a venv on the machine; no other commands are
          accepted.
        </span>
        <Button
          size="sm"
          variant="outline"
          onClick={() => save.mutate()}
          disabled={disabled || save.isPending}
        >
          Save settings
        </Button>
      </div>
    </Section>
  );
}

function LogsSection({
  packId,
  status,
  updatedAt,
}: {
  packId: number;
  status: Pack["status"];
  updatedAt: string;
}) {
  const [show, setShow] = useState(false);
  const logs = useQuery({
    queryKey: [...PACKS_KEY, packId, "logs", updatedAt],
    queryFn: () => packsApi.logs(packId),
    enabled: show,
    refetchInterval: show && status === "DEPLOYING" ? 5000 : false,
  });
  return (
    <Section
      title="Logs"
      action={
        <button
          type="button"
          className="text-[10px] text-text-muted hover:text-text-primary"
          onClick={() => setShow((s) => !s)}
        >
          {show ? "Hide" : "Show deployment + vLLM logs"}
        </button>
      }
    >
      {show &&
        (logs.isLoading ? (
          <Loader2 className="h-4 w-4 animate-spin text-text-muted" />
        ) : (
          <pre
            className="max-h-80 overflow-auto rounded-md border border-border-subtle bg-surface-0 p-3 font-mono text-[11px] leading-relaxed text-text-secondary"
            data-testid="pack-logs"
          >
            {logs.data?.log || "No logs yet."}
          </pre>
        ))}
    </Section>
  );
}

// ── small pieces ─────────────────────────────────────────────────────────────

function Section({
  title,
  action,
  children,
}: {
  title: string;
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center gap-2 border-b border-border-subtle pb-1.5">
        <h3 className="text-[10px] font-medium uppercase tracking-wider text-text-muted">
          {title}
        </h3>
        <div className="ml-auto">{action}</div>
      </div>
      {children}
    </section>
  );
}

function Field({
  label,
  className,
  children,
}: {
  label: string;
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div className={cn("flex flex-col gap-1", className)}>
      <Label className="text-[10px] font-normal text-text-muted">{label}</Label>
      {children}
    </div>
  );
}

function ErrorLine({ error }: { error: unknown }) {
  const code = errCode(error);
  return (
    <div className="flex gap-2 text-xs text-bear">
      <CircleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
      <span>
        {errText(error)}
        {code && FAILURE_HELP[code] && (
          <span className="block text-text-muted">{FAILURE_HELP[code]}</span>
        )}
      </span>
    </div>
  );
}
