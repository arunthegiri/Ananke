import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot, Check, Loader2, Send, ShieldAlert, X } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  agentApi,
  PENDING_ACTIONS_KEY,
  toolLabel,
  type AgentTurn,
  type AuditEntry,
  type PendingAction,
} from "@/lib/api/agent";
import { cn } from "@/lib/utils";

/**
 * Packs build doc §9.3 surfaces: the confirmations an agent is waiting on, the Pack agent
 * chat, and a Pack's audit trail. Confirming is the ONLY way a billable agent action runs.
 */

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function prettyArgs(json: string): string {
  try {
    const o = JSON.parse(json) as Record<string, unknown>;
    return Object.entries(o)
      .map(([k, v]) => `${k}=${typeof v === "string" ? v : JSON.stringify(v)}`)
      .join(" · ");
  } catch {
    return json;
  }
}

/** Polls while mounted; renders nothing when no agent is waiting. */
export function PendingActions({ className }: { className?: string }) {
  const qc = useQueryClient();
  const pending = useQuery({
    queryKey: PENDING_ACTIONS_KEY,
    queryFn: agentApi.pending,
    refetchInterval: 5000,
    retry: false,
  });
  const refresh = () => {
    void qc.invalidateQueries({ queryKey: PENDING_ACTIONS_KEY });
    void qc.invalidateQueries({ queryKey: ["packs"] });
    void qc.invalidateQueries({ queryKey: ["compute"] });
  };
  const confirm = useMutation({
    mutationFn: (id: string) => agentApi.confirm(id),
    onSuccess: () => toast.success("Confirmed. The action is running."),
    onError: (e) => toast.error(errText(e)),
    onSettled: refresh,
  });
  const reject = useMutation({
    mutationFn: (id: string) => agentApi.reject(id),
    onSettled: refresh,
  });

  if (!pending.data || pending.data.length === 0) return null;
  return (
    <div
      className={cn(
        "flex flex-col gap-2 rounded-md border border-warning bg-surface-1 p-3",
        className,
      )}
      data-testid="pending-actions"
      role="region"
      aria-label="Agent requests awaiting your confirmation"
    >
      <span className="flex items-center gap-2 text-xs font-medium text-warning">
        <ShieldAlert className="h-3.5 w-3.5" />
        {pending.data.length === 1
          ? "An agent is waiting"
          : `${pending.data.length} agent requests are waiting`}{" "}
        for your confirmation. Nothing has been created.
      </span>
      {pending.data.map((a: PendingAction) => (
        <div
          key={a.id}
          className="flex flex-col gap-1 border-t border-border-subtle pt-2 text-xs"
          data-testid="pending-action"
        >
          <span className="text-text-primary">
            <span className="font-mono">{a.agent}</span> asks to{" "}
            <strong>{toolLabel(a.tool)}</strong>
          </span>
          <span className="text-text-secondary">{a.costEstimate}</span>
          <span className="font-mono text-[10px] text-text-muted">{prettyArgs(a.arguments)}</span>
          <span className="text-[10px] text-text-muted">
            Expires {new Date(a.expiresAt).toLocaleTimeString()}.
          </span>
          <span className="flex gap-2">
            <Button
              size="sm"
              onClick={() => {
                if (window.confirm(`Confirm: ${toolLabel(a.tool)}?\n\n${a.costEstimate}`))
                  confirm.mutate(a.id);
              }}
              disabled={confirm.isPending}
              data-testid="pending-confirm"
            >
              {confirm.isPending && confirm.variables === a.id ? (
                <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />
              ) : (
                <Check className="mr-1 h-3.5 w-3.5" />
              )}
              Confirm (billable)
            </Button>
            <Button
              size="sm"
              variant="secondary"
              onClick={() => reject.mutate(a.id)}
              data-testid="pending-reject"
            >
              <X className="mr-1 h-3.5 w-3.5" /> Reject
            </Button>
          </span>
        </div>
      ))}
    </div>
  );
}

export function PackAgentDialog({
  open,
  onOpenChange,
  packId,
}: {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  packId: number | null;
}) {
  const qc = useQueryClient();
  const [turns, setTurns] = useState<AgentTurn[]>([]);
  const [draft, setDraft] = useState("");
  const [lastTools, setLastTools] = useState<string[]>([]);
  const send = useMutation({
    mutationFn: (history: AgentTurn[]) => agentApi.packAgentChat(history, packId),
    onSuccess: (r) => {
      setTurns((t) => [...t, { role: "assistant", content: r.answer || "(no answer)" }]);
      setLastTools(r.toolCalls.map((c) => c.status));
      void qc.invalidateQueries({ queryKey: PENDING_ACTIONS_KEY });
    },
  });
  const submit = () => {
    const text = draft.trim();
    if (!text || send.isPending) return;
    const next = [...turns, { role: "user" as const, content: text }];
    setTurns(next);
    setDraft("");
    send.mutate(next);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="flex max-h-[90vh] max-w-2xl flex-col"
        data-testid="pack-agent-dialog"
      >
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Bot className="h-4 w-4" /> Pack agent
          </DialogTitle>
          <DialogDescription>
            Ask about your Packs, GPU sizing or cloud setup. It reads vetted AWS / Google Cloud /
            Azure skills. It can't change anything on its own: anything billable appears below for
            you to confirm.
          </DialogDescription>
        </DialogHeader>
        <div
          className="flex min-h-[200px] flex-1 flex-col gap-3 overflow-y-auto"
          data-testid="pack-agent-log"
        >
          {turns.length === 0 && (
            <p className="text-xs text-text-muted">
              e.g. “Which GPU do I need for Qwen3-8B, and what would it cost on AWS?”
            </p>
          )}
          {turns.map((t, i) => (
            <div
              key={i}
              className={cn(
                "whitespace-pre-wrap rounded-md px-3 py-2 text-xs",
                t.role === "user"
                  ? "self-end bg-surface-2 text-text-primary"
                  : "bg-surface-1 text-text-secondary",
              )}
            >
              {t.content}
            </div>
          ))}
          {send.isPending && <Loader2 className="h-4 w-4 animate-spin text-text-muted" />}
          {send.isError && <p className="text-xs text-bear">{errText(send.error)}</p>}
          {lastTools.length > 0 && !send.isPending && (
            <p className="text-[10px] text-text-muted">Used: {lastTools.join(" · ")}</p>
          )}
        </div>
        <PendingActions />
        <div className="flex gap-2">
          <Textarea
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                submit();
              }
            }}
            placeholder="Ask the Pack agent…"
            className="min-h-[60px] text-xs"
            data-testid="pack-agent-input"
          />
          <Button
            onClick={submit}
            disabled={send.isPending || !draft.trim()}
            data-testid="pack-agent-send"
          >
            <Send className="h-4 w-4" />
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

const EVENT_TONE: Record<AuditEntry["event"], string> = {
  REQUESTED: "text-text-secondary",
  CONFIRMED: "text-text-primary",
  EXECUTED: "text-bull",
  REJECTED: "text-text-muted",
  EXPIRED: "text-text-muted",
  REFUSED: "text-warning",
  FAILED: "text-bear",
};

/** The Pack's cloud/agent action history (§9.3 audit). Hidden when empty. */
export function PackAudit({ packId }: { packId: number }) {
  const audit = useQuery({
    queryKey: ["packs", packId, "audit"],
    queryFn: () => agentApi.audit(packId),
    refetchInterval: 15_000,
    retry: false,
  });
  if (!audit.data || audit.data.length === 0) return null;
  return (
    <ul className="flex flex-col gap-1" data-testid="pack-audit">
      {audit.data
        .slice()
        .reverse()
        .map((e) => (
          <li key={e.id} className="flex gap-2 text-[11px]">
            <span className="w-36 shrink-0 font-mono text-text-muted">
              {new Date(e.createdAt).toLocaleString()}
            </span>
            <span className={cn("w-20 shrink-0 font-mono", EVENT_TONE[e.event])}>{e.event}</span>
            <span className="text-text-secondary">
              {toolLabel(e.tool)} · {e.agent}
              {e.costEstimate ? ` · ${e.costEstimate}` : ""}
              {e.detail ? ` · ${e.detail}` : ""}
            </span>
          </li>
        ))}
    </ul>
  );
}
