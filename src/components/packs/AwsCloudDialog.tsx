import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CircleAlert, Cloud, Download, Loader2, ShieldAlert, Trash2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  AWS_TEMPLATE_URL,
  cloudApi,
  usd,
  type CloudAccount,
  type Estimate,
  type LinkStart,
  type ProvisionRequest,
} from "@/lib/api/cloud";
import { FAILURE_HELP, type Pack } from "@/lib/api/packs";
import { cn } from "@/lib/utils";

/**
 * Packs build doc §7 — link an AWS account and create GPU machines. The money path is
 * deliberately two explicit steps: "Get estimate" (free, live AWS prices) and then a
 * separate, acknowledged "Create machine". Nothing in this dialog spends money otherwise.
 */

export const CLOUD_ACCOUNTS_KEY = ["cloud", "accounts"] as const;

const SELECT =
  "h-8 rounded-md border border-border-subtle bg-surface-1 px-2 text-xs text-text-primary";

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function errCode(e: unknown): string | null {
  const body = (e as { body?: { code?: unknown } } | null)?.body;
  return body && typeof body.code === "string" ? body.code : null;
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

function Field({
  label,
  children,
  className,
}: {
  label: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("flex flex-col gap-1", className)}>
      <Label className="text-[10px] font-normal text-text-muted">{label}</Label>
      {children}
    </div>
  );
}

export function AwsCloudDialog({
  open,
  onOpenChange,
  packs,
  onMachinesChanged,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  packs: Pack[];
  onMachinesChanged: () => void;
}) {
  const accounts = useQuery({
    queryKey: CLOUD_ACCOUNTS_KEY,
    queryFn: cloudApi.accounts,
    enabled: open,
    retry: false,
  });
  const [linking, setLinking] = useState<LinkStart | null>(null);
  const [selected, setSelected] = useState<number | null>(null);

  useEffect(() => {
    if (!open) setLinking(null);
  }, [open]);

  const linked = accounts.data?.filter((a) => a.status === "LINKED") ?? [];
  const account = linked.find((a) => a.id === selected) ?? linked[0] ?? null;
  const unfinished = accounts.data?.filter((a) => a.status !== "LINKED") ?? [];

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto" data-testid="aws-dialog">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Cloud className="h-4 w-4" /> Cloud GPU machines (AWS)
          </DialogTitle>
          <DialogDescription>
            Ananke creates GPU VMs in your own AWS account through a role you control. Every machine
            starts with a cost estimate you confirm; nothing is created otherwise.
          </DialogDescription>
        </DialogHeader>

        {accounts.isError && <ErrorLine error={accounts.error} />}
        {accounts.isLoading && <Loader2 className="h-4 w-4 animate-spin text-text-muted" />}

        {accounts.data && (
          <div className="flex flex-col gap-5">
            {linking ? (
              <LinkSteps start={linking} onDone={() => setLinking(null)} />
            ) : (
              <>
                {unfinished.map((a) => (
                  <LinkSteps key={a.id} account={a} onDone={() => undefined} />
                ))}
                {linked.length === 0 && unfinished.length === 0 && (
                  <StartLink onStarted={setLinking} />
                )}
              </>
            )}

            {account && !linking && (
              <>
                <div className="flex items-center gap-2 text-xs">
                  <span className="text-text-muted">Account</span>
                  <select
                    className={SELECT}
                    value={account.id}
                    onChange={(e) => setSelected(Number(e.target.value))}
                    data-testid="aws-account"
                  >
                    {linked.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.label} · {a.accountRef} · key {a.keyHint}
                      </option>
                    ))}
                  </select>
                </div>
                <CreateMachine
                  account={account}
                  packs={packs}
                  onCreated={() => {
                    onMachinesChanged();
                    onOpenChange(false);
                  }}
                />
                <Orphans account={account} />
                <UnlinkAccount account={account} />
              </>
            )}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

// ── linking ──────────────────────────────────────────────────────────────────

function StartLink({ onStarted }: { onStarted: (s: LinkStart) => void }) {
  const qc = useQueryClient();
  const [label, setLabel] = useState("");
  const [region, setRegion] = useState("us-east-1");
  const start = useMutation({
    mutationFn: () => cloudApi.linkAws({ label: label || undefined, region }),
    onSuccess: (s) => {
      void qc.invalidateQueries({ queryKey: CLOUD_ACCOUNTS_KEY });
      onStarted(s);
    },
  });
  return (
    <section className="flex flex-col gap-3">
      <h3 className="text-xs font-medium text-text-primary">1 · Link an AWS account</h3>
      <div className="grid grid-cols-2 gap-3">
        <Field label="Label">
          <Input
            value={label}
            onChange={(e) => setLabel(e.target.value)}
            placeholder="My AWS"
            className="h-8 text-xs"
          />
        </Field>
        <Field label="Home region (for the role stack)">
          <Input
            value={region}
            onChange={(e) => setRegion(e.target.value)}
            className="h-8 font-mono text-xs"
          />
        </Field>
      </div>
      {start.isError && <ErrorLine error={start.error} />}
      <Button
        size="sm"
        onClick={() => start.mutate()}
        disabled={start.isPending}
        data-testid="aws-link-start"
      >
        {start.isPending && <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />}
        Start linking
      </Button>
    </section>
  );
}

function LinkSteps({
  start,
  account: given,
  onDone,
}: {
  start?: LinkStart;
  account?: CloudAccount;
  onDone: () => void;
}) {
  const qc = useQueryClient();
  const account = start?.account ?? given!;
  const [roleArn, setRoleArn] = useState(account.roleArn ?? "");
  const [keyId, setKeyId] = useState("");
  const [secret, setSecret] = useState("");
  const verify = useMutation({
    mutationFn: () =>
      cloudApi.verify(account.id, {
        roleArn,
        accessKeyId: keyId || undefined,
        secretAccessKey: secret || undefined,
      }),
    onSuccess: () => {
      setSecret("");
      toast.success("AWS account linked");
      void qc.invalidateQueries({ queryKey: CLOUD_ACCOUNTS_KEY });
      onDone();
    },
    onSettled: () => void qc.invalidateQueries({ queryKey: CLOUD_ACCOUNTS_KEY }),
  });

  return (
    <section
      className="flex flex-col gap-3 rounded-md border border-border-subtle p-3"
      data-testid="aws-link-steps"
    >
      <h3 className="text-xs font-medium text-text-primary">
        Link “{account.label}” ({account.homeRegion}) · {account.status.toLowerCase()}
      </h3>
      {account.lastError && <p className="text-xs text-bear">{account.lastError}</p>}
      <ol className="list-decimal space-y-1 pl-4 text-[11px] text-text-secondary">
        {(
          start?.consoleSteps ?? [
            "Deploy ananke-access.yaml as a CloudFormation stack with the ExternalId below.",
            "Paste the stack's RoleArn output and an access key for the ananke-packs user.",
          ]
        ).map((s) => (
          <li key={s}>{s}</li>
        ))}
      </ol>
      <div className="flex flex-wrap items-center gap-3 text-[11px]">
        <a
          href={AWS_TEMPLATE_URL}
          className="inline-flex items-center gap-1 text-accent-blue hover:underline"
          data-testid="aws-template-download"
        >
          <Download className="h-3 w-3" /> ananke-access.yaml
        </a>
        <span className="text-text-muted">
          ExternalId <code className="font-mono text-text-primary">{account.externalId}</code>
        </span>
      </div>
      {start?.cliCommand && (
        <pre className="overflow-x-auto rounded bg-surface-1 p-2 font-mono text-[10px] text-text-secondary">
          {start.cliCommand}
        </pre>
      )}
      <div className="grid grid-cols-2 gap-3">
        <Field label="RoleArn (stack output)" className="col-span-2">
          <Input
            value={roleArn}
            onChange={(e) => setRoleArn(e.target.value)}
            placeholder="arn:aws:iam::123456789012:role/AnankePacksRole"
            className="h-8 font-mono text-xs"
            data-testid="aws-role-arn"
          />
        </Field>
        <Field
          label={
            account.keyHint
              ? `Access key ID (saved: ${account.keyHint}; leave blank to keep)`
              : "Access key ID (ananke-packs user)"
          }
        >
          <Input
            value={keyId}
            onChange={(e) => setKeyId(e.target.value)}
            className="h-8 font-mono text-xs"
            autoComplete="off"
          />
        </Field>
        <Field label="Secret access key">
          <Input
            type="password"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            className="h-8 font-mono text-xs"
            autoComplete="off"
          />
        </Field>
      </div>
      <p className="text-[10px] text-text-muted">
        The key is stored encrypted on your Ananke backend and never shown again. Verifying runs a
        free dry-run launch; nothing is created.
      </p>
      {verify.isError && <ErrorLine error={verify.error} />}
      <Button
        size="sm"
        onClick={() => verify.mutate()}
        disabled={verify.isPending || !roleArn}
        data-testid="aws-verify"
      >
        {verify.isPending && <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />}
        Verify and link
      </Button>
    </section>
  );
}

// ── create machine: estimate → confirm ───────────────────────────────────────

function CreateMachine({
  account,
  packs,
  onCreated,
}: {
  account: CloudAccount;
  packs: Pack[];
  onCreated: () => void;
}) {
  const types = useQuery({
    queryKey: ["cloud", "aws", "types"],
    queryFn: cloudApi.instanceTypes,
    staleTime: Infinity,
  });
  const egress = useQuery({
    queryKey: ["cloud", "egress-ip"],
    queryFn: cloudApi.egressIp,
    staleTime: 300_000,
    retry: false,
  });
  const [req, setReq] = useState<ProvisionRequest>({
    region: account.homeRegion,
    instanceType: "g6.xlarge",
    diskGiB: 150,
    sshCidr: "",
    idleStopMinutes: 60,
    packId: null,
  });
  const [estimate, setEstimate] = useState<Estimate | null>(null);
  const [ack, setAck] = useState(false);

  useEffect(() => {
    if (egress.data?.cidr && !req.sshCidr) setReq((r) => ({ ...r, sshCidr: egress.data!.cidr! }));
  }, [egress.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const change = (patch: Partial<ProvisionRequest>) => {
    setReq((r) => ({ ...r, ...patch }));
    setEstimate(null); // any change invalidates the price the user saw
    setAck(false);
  };

  const getEstimate = useMutation({
    mutationFn: () => cloudApi.estimate(account.id, req),
    onSuccess: (e) => {
      setEstimate(e);
      setAck(false);
    },
  });
  const create = useMutation({
    mutationFn: () => cloudApi.provision(account.id, estimate!.id),
    onSuccess: (t) => {
      toast.success(`Creating ${t.label}. It appears under Machines while it boots.`);
      onCreated();
    },
  });
  const typeInfo = types.data?.find((t) => t.type === req.instanceType);

  return (
    <section className="flex flex-col gap-3" data-testid="aws-create">
      <h3 className="text-xs font-medium text-text-primary">Create a GPU machine</h3>
      <div className="grid grid-cols-3 gap-3">
        <Field label="Instance type">
          <select
            className={SELECT}
            value={req.instanceType}
            onChange={(e) => change({ instanceType: e.target.value })}
            data-testid="aws-type"
          >
            {types.data?.map((t) => (
              <option key={t.type} value={t.type}>
                {t.type} · {t.gpus}× {t.gpu.replace("NVIDIA ", "")} {t.vramGiBPerGpu} GiB
              </option>
            ))}
          </select>
        </Field>
        <Field label="Region">
          <Input
            value={req.region}
            onChange={(e) => change({ region: e.target.value })}
            className="h-8 font-mono text-xs"
          />
        </Field>
        <Field label="Disk (GiB, gp3)">
          <Input
            type="number"
            min={100}
            max={2000}
            value={req.diskGiB}
            onChange={(e) => change({ diskGiB: Number(e.target.value) })}
            className="h-8 text-xs"
          />
        </Field>
        <Field label="SSH allowed from (the backend's IP)">
          <Input
            value={req.sshCidr}
            onChange={(e) => change({ sshCidr: e.target.value })}
            placeholder="203.0.113.7/32"
            className="h-8 font-mono text-xs"
            data-testid="aws-cidr"
          />
        </Field>
        <Field label="Auto-stop when idle (min, 0 = off)">
          <Input
            type="number"
            min={0}
            max={1440}
            value={req.idleStopMinutes}
            onChange={(e) => change({ idleStopMinutes: Number(e.target.value) })}
            className="h-8 text-xs"
          />
        </Field>
        <Field label="For Pack (removing it terminates the VM)">
          <select
            className={SELECT}
            value={req.packId ?? ""}
            onChange={(e) => change({ packId: e.target.value ? Number(e.target.value) : null })}
          >
            <option value="">None</option>
            {packs.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {typeInfo && (
        <p className="text-[10px] text-text-muted">
          {typeInfo.gpus}× {typeInfo.gpu} ({typeInfo.vramGiBPerGpu} GiB each) · {typeInfo.vcpus}{" "}
          vCPU · {typeInfo.memoryGiB} GiB RAM · Deep Learning Base AMI (Ubuntu 22.04)
        </p>
      )}
      {egress.data?.error && (
        <p className="text-[10px] text-warning">{egress.data.error} Enter it manually.</p>
      )}

      {getEstimate.isError && <ErrorLine error={getEstimate.error} />}
      {!estimate ? (
        <Button
          size="sm"
          variant="secondary"
          onClick={() => getEstimate.mutate()}
          disabled={getEstimate.isPending || !req.sshCidr}
          data-testid="aws-estimate"
        >
          {getEstimate.isPending && <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />}
          Get cost estimate (free)
        </Button>
      ) : (
        <div
          className="flex flex-col gap-2 rounded-md border border-warning p-3"
          data-testid="aws-estimate-card"
        >
          <div className="flex flex-wrap gap-x-6 gap-y-1 text-xs">
            <span>
              <span className="text-text-muted">Compute </span>
              <span className="font-mono text-text-primary">{usd(estimate.hourlyUsd, 4)}/hour</span>
            </span>
            <span>
              <span className="text-text-muted">Disk </span>
              <span className="font-mono text-text-primary">
                {usd(estimate.diskMonthlyUsd)}/month
              </span>
            </span>
            <span>
              <span className="text-text-muted">If never stopped </span>
              <span className="font-mono text-text-primary">
                ≈ {usd(estimate.alwaysOnMonthlyUsd)}/month
              </span>
            </span>
          </div>
          <ul className="list-disc space-y-0.5 pl-4 text-[10px] text-text-secondary">
            {estimate.notes.map((n) => (
              <li key={n}>{n}</li>
            ))}
          </ul>
          <p className="text-[10px] text-text-muted">{estimate.source}. Valid for 15 minutes.</p>
          <label className="flex items-start gap-2 text-xs text-text-primary">
            <input
              type="checkbox"
              checked={ack}
              onChange={(e) => setAck(e.target.checked)}
              className="mt-0.5"
              data-testid="aws-ack"
            />
            I understand this creates billable resources in AWS account {account.accountRef},
            charged to me by AWS.
          </label>
          {create.isError && <ErrorLine error={create.error} />}
          <Button
            size="sm"
            onClick={() => create.mutate()}
            disabled={!ack || create.isPending}
            data-testid="aws-create-confirm"
          >
            {create.isPending && <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />}
            <ShieldAlert className="mr-1 h-3.5 w-3.5" /> Create machine (billable)
          </Button>
        </div>
      )}
    </section>
  );
}

// ── orphans + unlink ─────────────────────────────────────────────────────────

function Orphans({ account }: { account: CloudAccount }) {
  const [scan, setScan] = useState(false);
  const orphans = useQuery({
    queryKey: ["cloud", "orphans", account.id],
    queryFn: () => cloudApi.orphans(account.id),
    enabled: scan,
    retry: false,
  });
  const terminate = useMutation({
    mutationFn: (o: { region: string; instanceId: string }) =>
      cloudApi.terminateOrphan(account.id, o.region, o.instanceId),
    onSuccess: () => {
      toast.success("Terminate requested");
      void orphans.refetch();
    },
    onError: (e) => toast.error(errText(e)),
  });
  return (
    <section className="flex flex-col gap-2">
      <h3 className="text-xs font-medium text-text-primary">Orphaned instances</h3>
      <p className="text-[10px] text-text-muted">
        Instances this link created that Ananke no longer tracks (e.g. after a crash). Listing never
        changes anything.
      </p>
      {!scan ? (
        <Button
          size="sm"
          variant="secondary"
          onClick={() => setScan(true)}
          data-testid="aws-orphans-scan"
        >
          Scan all regions
        </Button>
      ) : orphans.isLoading ? (
        <Loader2 className="h-4 w-4 animate-spin text-text-muted" />
      ) : orphans.isError ? (
        <ErrorLine error={orphans.error} />
      ) : orphans.data!.length === 0 ? (
        <p className="text-xs text-text-secondary">None found.</p>
      ) : (
        <ul className="flex flex-col gap-1">
          {orphans.data!.map((o) => (
            <li
              key={o.instanceId}
              className="flex items-center gap-2 font-mono text-[11px] text-text-secondary"
            >
              <span className="flex-1">
                {o.region} · {o.instanceId} · {o.instanceType} · {o.state}
              </span>
              <button
                type="button"
                className="flex items-center gap-1 text-bear hover:underline"
                onClick={() => {
                  if (
                    window.confirm(
                      `Terminate ${o.instanceId} in ${o.region}? This deletes the VM and its disk.`,
                    )
                  ) {
                    terminate.mutate(o);
                  }
                }}
              >
                <Trash2 className="h-3 w-3" /> Terminate
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function UnlinkAccount({ account }: { account: CloudAccount }) {
  const qc = useQueryClient();
  const unlink = useMutation({
    mutationFn: () => cloudApi.unlink(account.id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: CLOUD_ACCOUNTS_KEY }),
    onError: (e) => toast.error(errText(e)),
  });
  return (
    <button
      type="button"
      className="self-start text-[11px] text-text-muted hover:text-bear"
      onClick={() => {
        if (
          window.confirm(
            "Unlink this AWS account? Ananke deletes the stored access key; the IAM stack stays in AWS until you delete it.",
          )
        ) {
          unlink.mutate();
        }
      }}
    >
      Unlink account ({account.machines} machine{account.machines === 1 ? "" : "s"})
    </button>
  );
}
