"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";

export type FunnelStep = { code: string; label: string; entered: number; advanced: number; conversionRate: number; medianCycleHours: number };
export type FunnelReport = { from: string; to: string; salesSubject?: string | null; steps: FunnelStep[]; won: number; lost: number; cancelled: number; generatedAt: string };
export type FunnelCase = { dealId: string; ownerId: string; ownerName: string; salesSubject: string; currentStage: string; qualifiedAt?: string | null; evaluationAt?: string | null; offerAt?: string | null; outcomeAt?: string | null; outcome?: string | null };
export type StageProbability = { stage: string; probability: number; versionNo: number; validFrom: string; validTo?: string | null; createdBy: string };
export type ForecastItem = { dealId: string; ownerId: string; ownerName: string; stage: string; valueKind: string; inputValue: number; probability: number; probabilityVersion: number; weightedValue: number; actualWonValue?: number | null; outcome?: string | null };
export type ForecastReport = { from: string; to: string; salesSubject?: string | null; totalInputValue: number; totalWeightedValue: number; totalWonValue: number; wonVariance: number; items: ForecastItem[]; generatedAt: string };
export type SegmentRules = { statuses?: string[]; county?: string; minForestAreaHectares?: number; contactState?: string; signalCode?: string };
export type SegmentSummary = { id: string; name: string; description?: string | null; rules: SegmentRules; recommendedWorkflow?: string | null; active: boolean; lastCalculatedAt?: string | null; lastCalculatedCount?: number | null; createdBy: string; createdAt: string; updatedBy: string; updatedAt: string };
export type SegmentMember = { ownerId: string; ownerName: string; status: string; assignedToSubject?: string | null; forestAreaHectares: number; county?: string | null; hasContact: boolean };
export type SegmentRunResult = { segment: SegmentSummary; total: number; members: SegmentMember[]; calculatedAt: string };
export type AssignmentCandidate = { subject: string; username: string; name?: string | null; roles: string[]; assignedOwners: number; openWork: number; overdueWork: number; dueNextSevenDays: number; recentlyAssigned: number; loadScore: number; explanation: string };
export type AssignmentSuggestion = { ownerId: string; ownerName: string; currentSubject?: string | null; recommendedSubject: string; explanation: string };
export type AssignmentPreview = { segmentId: string; ownerCount: number; candidates: AssignmentCandidate[]; suggestions: AssignmentSuggestion[]; rule: string; rationale: string; generatedAt: string };
export type AssignmentResultItem = { ownerId: string; ownerName: string; fromSubject?: string | null; toSubject: string; result: "ASSIGNED" | "REASSIGNED" | "UNCHANGED" };
export type AssignmentResult = { runId: string; assignedCount: number; reassignedCount: number; unchangedCount: number; skippedCount: number; items: AssignmentResultItem[]; createdAt: string };

function params(filters: { from?: string; to?: string; salesSubject?: string }) {
  const p = new URLSearchParams();
  Object.entries(filters).forEach(([k, v]) => { if (v) p.set(k, v); });
  return p.size ? `?${p.toString()}` : "";
}

export function useFunnel(filters: { from?: string; to?: string; salesSubject?: string } = {}) {
  const { accessToken, enabled } = useToken();
  return useQuery({ queryKey: ["management", "funnel", filters], enabled, queryFn: () => apiFetch<FunnelReport>(`/v1/management-analytics/funnel${params(filters)}`, accessToken) });
}
export function useFunnelCases(filters: { from?: string; to?: string; salesSubject?: string } = {}) {
  const { accessToken, enabled } = useToken();
  return useQuery({ queryKey: ["management", "funnel-cases", filters], enabled, queryFn: () => apiFetch<FunnelCase[]>(`/v1/management-analytics/funnel/cases${params(filters)}`, accessToken) });
}
export function useStageProbabilities(includeHistory = false) {
  const { accessToken, enabled } = useToken();
  return useQuery({ queryKey: ["management", "probabilities", includeHistory], enabled, queryFn: () => apiFetch<StageProbability[]>(`/v1/management-analytics/stage-probabilities?includeHistory=${includeHistory}`, accessToken) });
}
export function useUpdateStageProbability() {
  const { accessToken } = useToken(); const qc = useQueryClient();
  return useMutation({ mutationFn: ({ stage, probability }: { stage: string; probability: number }) => apiFetch<StageProbability>(`/v1/management-analytics/stage-probabilities/${stage}`, accessToken, { method: "PUT", body: JSON.stringify({ probability }) }), onSuccess: () => void qc.invalidateQueries({ queryKey: ["management"] }) });
}
export function useForecast(filters: { from?: string; to?: string; salesSubject?: string } = {}) {
  const { accessToken, enabled } = useToken();
  return useQuery({ queryKey: ["management", "forecast", filters], enabled, queryFn: () => apiFetch<ForecastReport>(`/v1/management-analytics/forecast${params(filters)}`, accessToken) });
}
export function useSegments() {
  const { accessToken, enabled } = useToken();
  return useQuery({ queryKey: ["management", "segments"], enabled, queryFn: () => apiFetch<SegmentSummary[]>("/v1/management-analytics/segments", accessToken) });
}
export function useSaveSegment() {
  const { accessToken } = useToken(); const qc = useQueryClient();
  return useMutation({ mutationFn: ({ id, data }: { id?: string; data: { name: string; description?: string; rules: SegmentRules; recommendedWorkflow?: string; active: boolean } }) => apiFetch<SegmentSummary>(id ? `/v1/management-analytics/segments/${id}` : "/v1/management-analytics/segments", accessToken, { method: id ? "PUT" : "POST", body: JSON.stringify(data) }), onSuccess: () => void qc.invalidateQueries({ queryKey: ["management", "segments"] }) });
}
export function useRunSegment() {
  const { accessToken } = useToken(); const qc = useQueryClient();
  return useMutation({ mutationFn: (id: string) => apiFetch<SegmentRunResult>(`/v1/management-analytics/segments/${id}/run`, accessToken, { method: "POST" }), onSuccess: () => void qc.invalidateQueries({ queryKey: ["management", "segments"] }) });
}
export function useAssignmentPreview() {
  const { accessToken } = useToken();
  return useMutation({ mutationFn: (id: string) => apiFetch<AssignmentPreview>(`/v1/management-analytics/segments/${id}/assignment-preview`, accessToken, { method: "POST" }) });
}
export function useApplyAssignments() {
  const { accessToken } = useToken(); const qc = useQueryClient();
  return useMutation({ mutationFn: (data: { segmentId?: string; assignmentRule: string; rationale: string; assignments: { ownerId: string; toSubject: string }[] }) => apiFetch<AssignmentResult>("/v1/management-analytics/assignments", accessToken, { method: "POST", body: JSON.stringify(data) }), onSuccess: () => { void qc.invalidateQueries({ queryKey: ["management"] }); void qc.invalidateQueries({ queryKey: ["owners"] }); } });
}
