"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";
import type { OwnerListItem, OwnerStatusDefinition, AdminPrepData, UserStatusChangeStats, MaintainableUser } from "@/lib/types";

export interface IntegrationRun {
  id: string;
  jobKey: string;
  trigger: "MANUAL" | "SCHEDULED";
  state: "QUEUED" | "RUNNING" | "SUCCEEDED" | "PARTIAL" | "FAILED" | "SKIPPED";
  requestedBy: string;
  parameters: string;
  metrics: string;
  message?: string;
  errorSummary?: string;
  queuedAt: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface IntegrationHealth {
  state: "HEALTHY" | "DEGRADED" | "STALE" | "DISABLED" | "MISCONFIGURED";
  freshnessSloSeconds: number;
  latestSuccessAgeSeconds?: number;
  currentRunAgeSeconds?: number;
  failureStreak: number;
  backlogRemaining: boolean;
  cursorLagSeconds?: number;
  reason: string;
}

export interface IntegrationJob {
  key: string;
  displayName: string;
  provider: string;
  purpose: string;
  schedule: string;
  configurationState: "ENABLED" | "DISABLED" | "MISCONFIGURED";
  manualSupported: boolean;
  latestRun?: IntegrationRun;
  latestSuccess?: IntegrationRun;
  currentRun?: IntegrationRun;
  health: IntegrationHealth;
}

// ===================== Admin =====================

export function useAdminPrepData() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["admin", "prep-data"],
    enabled,
    queryFn: () => apiFetch<AdminPrepData>("/v1/admin/workdesk/prep-data", accessToken),
  });
}

export function useAdminSearch() {
  const { accessToken } = useToken();
  return useMutation({
    mutationFn: (criteria: Record<string, unknown>) =>
      apiFetch<OwnerListItem[]>("/v1/admin/workdesk/search", accessToken, { method: "POST", body: JSON.stringify(criteria) }),
  });
}

export function useAssignWork() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: { assignee: string; owners: string[]; reassign: boolean }) =>
      apiFetch<void>("/v1/admin/workdesk/assign", accessToken, { method: "POST", body: JSON.stringify(data) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["owners"] }),
  });
}

export function useIntegrationJobs() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["admin", "integrations"],
    enabled,
    refetchInterval: 5_000,
    queryFn: () => apiFetch<IntegrationJob[]>("/v1/admin/integrations", accessToken),
  });
}

export function useStartIntegrationJob() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation<IntegrationRun, Error, { key: string; parameters?: Record<string, string> }>({
    mutationFn: ({ key, parameters }) => apiFetch<IntegrationRun>(
      `/v1/admin/integrations/${encodeURIComponent(key)}/runs`, accessToken,
      { method: "POST", body: JSON.stringify({ parameters: parameters || {} }) },
    ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin", "integrations"] }),
  });
}

export function useIntegrationRuns(key?: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["admin", "integrations", key, "runs"],
    enabled: enabled && Boolean(key),
    queryFn: () => apiFetch<IntegrationRun[]>(
      `/v1/admin/integrations/${encodeURIComponent(key || "")}/runs?limit=10`, accessToken,
    ),
  });
}

export function useOwnerStatusDefinitions() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["admin", "owner-statuses"],
    enabled,
    queryFn: () => apiFetch<OwnerStatusDefinition[]>("/v1/admin/owner-statuses", accessToken),
  });
}

export function useSaveOwnerStatusDefinition() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: { id: string; colorHex: string; durationDays: number }) =>
      apiFetch<void>("/v1/admin/owner-statuses", accessToken, { method: "POST", body: JSON.stringify(data) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin", "owner-statuses"] }),
  });
}

export function useDeleteOwnerStatusDefinition() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<void>(`/v1/admin/owner-statuses/${encodeURIComponent(id)}`, accessToken, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin", "owner-statuses"] }),
  });
}

// ===================== Admin - User Management (Keycloak-backed) =====================

export function useAdminUsers() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["admin", "users"],
    enabled,
    queryFn: () => apiFetch<MaintainableUser[]>("/v1/admin/users", accessToken),
  });
}

export function useCreateAdminUser() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: { username: string; name: string; password: string; roles: string[] }) =>
      apiFetch<MaintainableUser>("/v1/admin/users", accessToken, { method: "POST", body: JSON.stringify(data) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin", "users"] }),
  });
}

export function useUpdateAdminUserRoles() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, roles }: { userId: string; roles: string[] }) =>
      apiFetch<void>(`/v1/admin/users/${userId}/roles`, accessToken, { method: "PUT", body: JSON.stringify({ roles }) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin", "users"] }),
  });
}

export function useDeleteAdminUser() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) =>
      apiFetch<void>(`/v1/admin/users/${userId}`, accessToken, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin", "users"] }),
  });
}

// ===================== Admin - User Statistics =====================

export function useUserStatistics(params: { userIds: string[]; fromStatuses: string[]; toStatuses: string[]; granularity: string; from: string; to: string }) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["admin", "user-statistics", params],
    enabled: enabled && params.userIds.length > 0,
    queryFn: () => apiFetch<UserStatusChangeStats[]>("/v1/admin/user-statistics", accessToken, {
      method: "POST", body: JSON.stringify(params),
    }),
  });
}

// ===================== Dashboard =====================

export function useDashboardStats() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["dashboard"],
    enabled,
    queryFn: () => apiFetch<{
      activeOwners: number; newLeads: number; activeOpportunities: number; pendingEvaluation: number;
      assignedWork: number; contracted: number; totalParcels: number; overdueReminders: number;
    }>("/v1/dashboard/stats", accessToken),
  });
}
