"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";
import type { CadastreLabels, CadastreEvaluation, CadastreNotification, ForestRegistryFeature, CadastreSubPart } from "@/lib/types";

export type MapGenerationInfo = {
  generationId: string | null;
  representationVersion: number;
  available: boolean;
};

/**
 * Reads the server-owned published cadastre generation. refreshKey only asks React Query to
 * re-check the server after a local sync/import action; it never selects a source generation.
 */
export function useMapGeneration(refreshKey: number | string = 0) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["map", "generation", refreshKey],
    enabled,
    queryFn: () => apiFetch<MapGenerationInfo>("/v1/map/generation", accessToken, { cache: "no-store" }),
    staleTime: 0,
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
}

// ===================== Cadastre Details =====================

export function useCadastreLabels(cadastralCode: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["cadastre", cadastralCode, "labels"],
    enabled: enabled && Boolean(cadastralCode),
    queryFn: () => apiFetch<CadastreLabels>(`/v1/cadastres/${encodeURIComponent(cadastralCode)}/labels`, accessToken),
  });
}

export function useAddCadastreLabel(cadastralCode: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (label: string) =>
      apiFetch<void>(`/v1/cadastres/${encodeURIComponent(cadastralCode)}/labels/${encodeURIComponent(label)}`, accessToken, { method: "POST" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["cadastre", cadastralCode, "labels"] }),
  });
}

export function useRemoveCadastreLabel(cadastralCode: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (label: string) =>
      apiFetch<void>(`/v1/cadastres/${encodeURIComponent(cadastralCode)}/labels/${encodeURIComponent(label)}`, accessToken, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["cadastre", cadastralCode, "labels"] }),
  });
}

export function useCadastreEvaluation(cadastralCode: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["cadastre", cadastralCode, "evaluation"],
    enabled: enabled && Boolean(cadastralCode),
    queryFn: () => apiFetch<CadastreEvaluation>(`/v1/cadastres/${encodeURIComponent(cadastralCode)}/evaluation`, accessToken),
  });
}

export function useSaveCadastreEvaluation(cadastralCode: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: { ourPrice: string; ownerPrice: string }) =>
      apiFetch<void>(`/v1/cadastres/${encodeURIComponent(cadastralCode)}/evaluation`, accessToken, { method: "PUT", body: JSON.stringify(data) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["cadastre", cadastralCode, "evaluation"] }),
  });
}

export function useCadastreNotifications(cadastralCode: string, requested = true) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["cadastre", cadastralCode, "notifications"],
    enabled: enabled && Boolean(cadastralCode) && requested,
    queryFn: () => apiFetch<CadastreNotification[]>(`/v1/cadastres/${encodeURIComponent(cadastralCode)}/notifications?limit=100`, accessToken),
  });
}

export function useCadastreForestFeatures(cadastralCode: string, requested = true) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["cadastre", cadastralCode, "forest-features"],
    enabled: enabled && Boolean(cadastralCode) && requested,
    queryFn: () => apiFetch<ForestRegistryFeature[]>(`/v1/cadastres/${encodeURIComponent(cadastralCode)}/forest-features`, accessToken),
  });
}

export function useCadastreSubParts(cadastralCode: string, requested = true) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["cadastre", cadastralCode, "sub-parts"],
    enabled: enabled && Boolean(cadastralCode) && requested,
    queryFn: () => apiFetch<CadastreSubPart[]>(`/v1/cadastres/${encodeURIComponent(cadastralCode)}/sub-parts`, accessToken),
  });
}
