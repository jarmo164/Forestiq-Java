"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";
import type { ApiPath, ApiResponse, ApiSchema } from "@/lib/api-contract";

type RegistrySyncResult = ApiSchema<"SyncResult">;
type RegistryFullSync = ApiResponse<ApiPath<"/api/v1/registry/cadastres/{cadastralCode}/refresh-all">["post"]>;
type RegistryHealth = ApiSchema<"RegistryHealth">;
type RegistryFreshnessDashboard = ApiSchema<"RegistryFreshnessDashboard">;
type RegistryRecoveryRun = ApiSchema<"RecoveryRun">;
type RegistryRecoveryRequest = ApiSchema<"RecoveryRequest">;
type OwnershipTransitionEvent = ApiSchema<"OwnershipTransitionEvent">;
type OwnershipTransitionSyncRun = ApiResponse<ApiPath<"/api/v1/ownership-transitions/sync">["post"]>;
type MetsisPortfolioSyncStatus = ApiSchema<"SyncStatus">;
type MetsisPortfolioSyncResult = {
  trigger?: string;
  from?: string;
  to?: string;
  pages?: number;
  rows?: number;
  relationsAdded?: number;
  relationsClosed?: number;
  failed?: number;
  message?: string;
};

export function useRegistryHealth() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["registry", "health"], enabled, staleTime: 60_000,
    queryFn: () => apiFetch<RegistryHealth>("/v1/registry/health", accessToken),
  });
}

export function useRegistryFreshness() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["registry", "freshness"], enabled, refetchInterval: 60_000,
    queryFn: () => apiFetch<RegistryFreshnessDashboard>("/v1/registry/freshness", accessToken),
  });
}

export function useMetsisPortfolioStatus() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["metsis-portfolio", "status"], enabled,
    queryFn: () => apiFetch<MetsisPortfolioSyncStatus>("/v1/metsis-portfolio/status", accessToken),
  });
}

export function useMetsisPortfolioSync() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch<MetsisPortfolioSyncResult>("/v1/metsis-portfolio/sync", accessToken, { method: "POST" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["metsis-portfolio"] });
      qc.invalidateQueries({ queryKey: ["owners"] });
    },
  });
}

export function useRegistryFreshnessRecovery() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation<RegistryRecoveryRun, Error, number>({
    mutationFn: (batchSize) => {
      const data: RegistryRecoveryRequest = { batchSize };
      return apiFetch<RegistryRecoveryRun>("/v1/registry/freshness/recover", accessToken, {
        method: "POST", body: JSON.stringify(data),
      });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["registry", "freshness"] });
      qc.invalidateQueries({ queryKey: ["map"] });
      qc.invalidateQueries({ queryKey: ["owner"] });
    },
  });
}

export function useCadastreRegistryRefresh() {
  const { accessToken } = useToken();
  return useMutation({
    mutationFn: ({ cadastralCode, registry }: { cadastralCode: string; registry: "maaamet" | "notifications" | "plan" }) => {
      const path = registry === "maaamet"
        ? `/v1/registry/cadastres/${encodeURIComponent(cadastralCode)}/maaamet/refresh`
        : `/v1/registry/cadastres/${encodeURIComponent(cadastralCode)}/metsaregister/${registry}/refresh`;
      return apiFetch<RegistrySyncResult>(path, accessToken, { method: "POST" });
    },
  });
}

export function useCadastreFullRegistryRefresh() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ cadastralCode }: { cadastralCode: string }) => apiFetch<RegistryFullSync>(`/v1/registry/cadastres/${encodeURIComponent(cadastralCode)}/refresh-all`, accessToken, { method: "POST" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["owner"] });
      qc.invalidateQueries({ queryKey: ["map"] });
    },
  });
}

export function useOwnershipTransitionEvents(ownerId: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["ownership-transitions", ownerId], enabled: enabled && Boolean(ownerId),
    queryFn: () => apiFetch<OwnershipTransitionEvent[]>(`/v1/ownership-transitions/owners/${ownerId}`, accessToken),
  });
}

export function useOwnershipTransitionSync() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch<OwnershipTransitionSyncRun>("/v1/ownership-transitions/sync", accessToken, { method: "POST" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["ownership-transitions"] });
      qc.invalidateQueries({ queryKey: ["owners"] });
      qc.invalidateQueries({ queryKey: ["admin"] });
    },
  });
}
