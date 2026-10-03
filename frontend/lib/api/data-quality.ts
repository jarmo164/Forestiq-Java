"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";

export type DataQualityIssueType = "MISSING_CONTACT" | "STALE_OWNER_DATA" | "DUPLICATE_CONTACT" | "MISSING_DEAL_INFO";
export type DataQualityImpact = "CRITICAL" | "HIGH" | "MEDIUM" | "LOW";
export type DataQualityStatus = "OPEN" | "ASSIGNED" | "RESOLVED";

export type DataQualityItem = {
  id: string;
  ownerId: string;
  ownerName: string;
  dealId?: string | null;
  issueType: DataQualityIssueType;
  sourceName: string;
  sourceKey: string;
  summary: string;
  impact: DataQualityImpact;
  recommendedSubject?: string | null;
  assignedSubject?: string | null;
  status: DataQualityStatus;
  firstDetectedAt: string;
  lastDetectedAt: string;
  resolvedAt?: string | null;
  actionReason?: string | null;
  updatedBy: string;
  updatedAt: string;
};

export type DataQualityQueue = {
  items: DataQualityItem[];
  total: number;
  openCount: number;
  assignedCount: number;
  resolvedCount: number;
  criticalCount: number;
};

export type DataQualityEvent = {
  id: string;
  eventType: string;
  fromStatus?: string | null;
  toStatus: string;
  reason?: string | null;
  actorSubject: string;
  snapshot: Record<string, unknown>;
  createdAt: string;
};

export type DataQualityFilters = {
  issueType?: string;
  status?: string;
  impact?: string;
  assignedSubject?: string;
};

function params(filters: DataQualityFilters) {
  const search = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => {
    if (value) search.set(key, value);
  });
  search.set("limit", "200");
  return search.toString();
}

export function useDataQualityQueue(filters: DataQualityFilters = {}) {
  const { accessToken, enabled } = useToken();
  const query = params(filters);
  return useQuery({
    queryKey: ["data-quality", "queue", query],
    enabled,
    queryFn: () => apiFetch<DataQualityQueue>(`/v1/management/data-quality?${query}`, accessToken),
  });
}

export function useDataQualityHistory(issueId?: string, enabledByUi = false) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["data-quality", "history", issueId],
    enabled: enabled && enabledByUi && Boolean(issueId),
    queryFn: () => apiFetch<DataQualityEvent[]>(`/v1/management/data-quality/${issueId}/history`, accessToken),
  });
}

export function useRefreshDataQuality() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch<{ scanId: string; detectedCount: number; autoResolvedCount: number; finishedAt: string }>(
      "/v1/management/data-quality/refresh", accessToken, { method: "POST" }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["data-quality"] }),
  });
}

export function useAssignDataQualityIssue() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ issueId, assigneeSubject, reason }: { issueId: string; assigneeSubject: string; reason?: string }) =>
      apiFetch<DataQualityItem>(`/v1/management/data-quality/${issueId}/assign`, accessToken, {
        method: "PUT", body: JSON.stringify({ assigneeSubject, reason }),
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["data-quality"] }),
  });
}

export function useReleaseDataQualityIssue() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ issueId, reason }: { issueId: string; reason: string }) =>
      apiFetch<DataQualityItem>(`/v1/management/data-quality/${issueId}/release`, accessToken, {
        method: "POST", body: JSON.stringify({ reason }),
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["data-quality"] }),
  });
}

export function useResolveDataQualityIssue() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ issueId, reason }: { issueId: string; reason: string }) =>
      apiFetch<DataQualityItem>(`/v1/management/data-quality/${issueId}/resolve`, accessToken, {
        method: "POST", body: JSON.stringify({ reason }),
      }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["data-quality"] }),
  });
}
