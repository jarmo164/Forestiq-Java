"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";

export type ContactOutcome = {
  code: string;
  label: string;
  requiresReason: boolean;
  requiresNextActivity: boolean;
  active: boolean;
  sortOrder: number;
};

export type WorkflowAssignee = {
  subject: string;
  username: string;
  name: string;
  roles: string[];
};

export type OpenActivityOption = {
  id: number;
  text: string;
  activityType: string;
  dueTime: string;
  assigneeSubject: string;
  dealId?: string | null;
};

export type ContactRequest = {
  channel: "PHONE" | "EMAIL" | "MEETING" | "OTHER";
  outcomeCode: string;
  reason?: string;
  dealId?: string;
  nextStep?: string;
  nextStepDueAt?: string;
  assigneeSubject?: string;
  existingActivityId?: number;
  priorityReason?: string;
  activityType?: string;
};

export type ContactResult = {
  contactActivityId: string;
  activityId?: number | null;
};

export type WorkflowHistoryItem = {
  id: string;
  activityType: string;
  sourceType: "USER" | "AUTOMATED";
  content: string;
  channel?: string | null;
  outcomeCode?: string | null;
  reason?: string | null;
  createdBy: string;
  createdAt: string;
  relatedActivityId?: number | null;
  dealId?: string | null;
};

export type WorkflowHistoryPage = {
  items: WorkflowHistoryItem[];
  nextCursorCreatedAt?: string | null;
  nextCursorId?: string | null;
};

export type WorkQueueView = "TODAY" | "WEEK" | "OVERDUE";

export type WorkQueueItem = {
  id: number;
  ownerId?: string | null;
  ownerName?: string | null;
  dealId?: string | null;
  relatedDeal?: string | null;
  cadastralCode?: string | null;
  propertyName?: string | null;
  activityType: string;
  dueTime: string;
  priorityReason: string;
  assigneeSubject: string;
  delegated: boolean;
};

export type WorkQueueResponse = {
  view: WorkQueueView;
  items: WorkQueueItem[];
  counts: { today: number; nextSevenDays: number; overdue: number };
};

export type HistoryFilters = {
  activityType?: string;
  outcome?: string;
  author?: string;
  from?: string;
  to?: string;
  cursorCreatedAt?: string;
  cursorId?: string;
  limit?: number;
};

const workflowKeys = {
  all: ["workflow"] as const,
  outcomes: ["workflow", "outcomes"] as const,
  assignees: ["workflow", "assignees"] as const,
  openActivities: (ownerId: string) => ["workflow", "open-activities", ownerId] as const,
  queue: (view: WorkQueueView) => ["workflow", "queue", view] as const,
  ownerHistory: (ownerId: string, filters: HistoryFilters) => ["workflow", "owner-history", ownerId, filters] as const,
  dealHistory: (dealId: string, filters: HistoryFilters) => ["workflow", "deal-history", dealId, filters] as const,
};

function historyQuery(filters: HistoryFilters) {
  const params = new URLSearchParams();
  if (filters.activityType) params.set("activityType", filters.activityType);
  if (filters.outcome) params.set("outcome", filters.outcome);
  if (filters.author) params.set("author", filters.author);
  if (filters.from) params.set("from", filters.from);
  if (filters.to) params.set("to", filters.to);
  if (filters.cursorCreatedAt) params.set("cursorCreatedAt", filters.cursorCreatedAt);
  if (filters.cursorId) params.set("cursorId", filters.cursorId);
  params.set("limit", String(filters.limit ?? 50));
  return params.toString();
}

export function useContactOutcomes() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: workflowKeys.outcomes,
    enabled,
    queryFn: () => apiFetch<ContactOutcome[]>("/v1/workflow/outcomes", accessToken),
  });
}

export function useWorkflowAssignees() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: workflowKeys.assignees,
    enabled,
    queryFn: () => apiFetch<WorkflowAssignee[]>("/v1/workflow/assignees", accessToken),
  });
}

export function useOwnerOpenActivities(ownerId: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: workflowKeys.openActivities(ownerId),
    enabled: enabled && Boolean(ownerId),
    queryFn: () => apiFetch<OpenActivityOption[]>(`/v1/workflow/owners/${ownerId}/open-activities`, accessToken),
  });
}

export function useOwnerWorkflowHistory(ownerId: string, filters: HistoryFilters = {}) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: workflowKeys.ownerHistory(ownerId, filters),
    enabled: enabled && Boolean(ownerId),
    queryFn: () => apiFetch<WorkflowHistoryPage>(`/v1/workflow/owners/${ownerId}/history?${historyQuery(filters)}`, accessToken),
  });
}

export function useDealWorkflowHistory(dealId: string, filters: HistoryFilters = {}) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: workflowKeys.dealHistory(dealId, filters),
    enabled: enabled && Boolean(dealId),
    queryFn: () => apiFetch<WorkflowHistoryPage>(`/v1/workflow/deals/${dealId}/history?${historyQuery(filters)}`, accessToken),
  });
}

export function useWorkQueue(view: WorkQueueView) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: workflowKeys.queue(view),
    enabled,
    queryFn: () => apiFetch<WorkQueueResponse>(`/v1/workflow/work-queue?view=${view}`, accessToken),
  });
}

function useWorkflowMutation<T>(mutationFn: (accessToken?: string) => (data: T) => Promise<unknown>) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: mutationFn(accessToken),
    onSuccess: () => void qc.invalidateQueries({ queryKey: workflowKeys.all }),
  });
}

export function useCreateContact(ownerId: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: ContactRequest) => apiFetch<ContactResult>(`/v1/workflow/owners/${ownerId}/contacts`, accessToken, {
      method: "POST",
      body: JSON.stringify(data),
    }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: workflowKeys.all }),
  });
}

export function useCompleteActivity() {
  return useWorkflowMutation<{ id: number; note?: string }>((accessToken) => ({ id, note }) =>
    apiFetch<void>(`/v1/workflow/activities/${id}/complete`, accessToken, {
      method: "POST",
      body: JSON.stringify({ note: note || null }),
    })
  );
}

export function usePostponeActivity() {
  return useWorkflowMutation<{ id: number; dueTime: string; reason: string }>((accessToken) => ({ id, dueTime, reason }) =>
    apiFetch<void>(`/v1/workflow/activities/${id}/postpone`, accessToken, {
      method: "POST",
      body: JSON.stringify({ dueTime, reason }),
    })
  );
}

export function useDelegateActivity() {
  return useWorkflowMutation<{ id: number; assigneeSubject: string; reason: string }>((accessToken) => ({ id, assigneeSubject, reason }) =>
    apiFetch<void>(`/v1/workflow/activities/${id}/delegate`, accessToken, {
      method: "POST",
      body: JSON.stringify({ assigneeSubject, reason }),
    })
  );
}
