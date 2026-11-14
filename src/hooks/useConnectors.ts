import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { connectorsApi, type Connector } from "@/lib/api/connectors";

export const CONNECTORS_KEY = ["connectors"] as const;

/** Connector catalogue + link state. Client-only fetch — nothing here renders during SSR. */
export function useConnectors() {
  return useQuery({
    queryKey: CONNECTORS_KEY,
    queryFn: connectorsApi.list,
    staleTime: 30_000,
    retry: false,
  });
}

/** One connector from the catalogue (undefined while loading or if the backend has none by that id). */
export function useConnector(id: string) {
  const q = useConnectors();
  return { ...q, connector: q.data?.find((c) => c.id === id) };
}

function useReplaceConnector() {
  const qc = useQueryClient();
  return (c: Connector) => {
    qc.setQueryData<Connector[]>(CONNECTORS_KEY, (old) => old?.map((x) => (x.id === c.id ? c : x)));
    // Panels reading this connector refetch with the new key / switch state.
    void qc.invalidateQueries({ queryKey: ["connector-call", c.id] });
  };
}

export function useLinkConnector() {
  const replace = useReplaceConnector();
  return useMutation({
    mutationFn: ({ id, values }: { id: string; values: Record<string, string> }) =>
      connectorsApi.link(id, values),
    onSuccess: replace,
  });
}

export function useUnlinkConnector() {
  const replace = useReplaceConnector();
  return useMutation({ mutationFn: (id: string) => connectorsApi.unlink(id), onSuccess: replace });
}

export function useSetConnectorEnabled() {
  const replace = useReplaceConnector();
  return useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      connectorsApi.setEnabled(id, enabled),
    onSuccess: replace,
  });
}

export function useTestConnector() {
  const replace = useReplaceConnector();
  return useMutation({ mutationFn: (id: string) => connectorsApi.test(id), onSuccess: replace });
}

/**
 * One connector tool call as a query (the cold path). Only runs when `enabled` — panels pass
 * "connector is READY and the inputs are complete" so a not-linked connector never hits the API.
 */
export function useConnectorCall<T>(
  id: string,
  tool: string,
  args: Record<string, unknown>,
  enabled: boolean,
) {
  return useQuery({
    queryKey: ["connector-call", id, tool, args],
    queryFn: () => connectorsApi.call<T>(id, tool, args),
    enabled,
    staleTime: 5 * 60_000,
    retry: false,
  });
}
