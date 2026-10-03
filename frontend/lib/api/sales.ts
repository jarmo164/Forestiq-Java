"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";
import { queryKeys } from "@/lib/query-keys";
import type { SalesManagementOverview, SalesOutcomeResult, SalesTask } from "@/lib/types";

export type SalesOutcome = "NO_ANSWER" | "CALL_BACK" | "INTERESTED" | "NOT_INTERESTED";

// ===================== Personal sales workspace =====================

export function useSalesWorkspaceQueue() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: queryKeys.salesWorkspace.queue,
    enabled,
    refetchInterval: 60_000,
    queryFn: () => apiFetch<SalesTask[]>("/v1/sales-workspace/queue", accessToken),
  });
}

export function useApplySalesOutcome() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ ownerId, outcome, reason }: { ownerId: string; outcome: SalesOutcome; reason?: string }) =>
      apiFetch<SalesOutcomeResult>(`/v1/sales-workspace/owners/${ownerId}/outcome`, accessToken, { method: "POST", body: JSON.stringify({ outcome, reason }) }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.salesWorkspace.all });
      void qc.invalidateQueries({ queryKey: queryKeys.myWork.all });
      void qc.invalidateQueries({ queryKey: queryKeys.owners.all });
      void qc.invalidateQueries({ queryKey: queryKeys.reminders.all });
    },
  });
}

// ===================== Sales management =====================

export function useSalesManagementOverview() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["sales-management", "overview"],
    enabled,
    refetchInterval: 60_000,
    queryFn: () => apiFetch<SalesManagementOverview>("/v1/sales-management/overview", accessToken),
  });
}
