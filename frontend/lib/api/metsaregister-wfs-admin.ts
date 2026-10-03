"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";
import type { IntegrationRun } from "@/lib/api/admin";

export interface MetsaregisterWfsLayerStatus {
  layerName: string;
  title: string;
  approved: boolean;
  enabled: boolean;
  providerPresent: boolean;
  state: string;
  defaultCrs?: string;
  geometryProperty?: string;
  geometryType?: string;
  identityProperty?: string;
  sortProperty?: string;
  refreshIntervalMinutes: number;
  refreshPriority?: number;
  providerNumberMatched?: number;
  currentRowCount?: number;
  currentGenerationId?: string;
  lastSeenAt?: string;
  lastSuccessAt?: string;
  lastFailureAt?: string;
  lastError?: string;
  freshnessState: "HEALTHY" | "STALE" | "SYNCING" | "FAILED" | "DISABLED" | "MISSING" | "SCHEMA_DRIFT" | "UNSUPPORTED";
  freshnessAgeSeconds?: number;
  due: boolean;
  backlog: boolean;
  stagingGenerationId?: string;
  stagingNextStartIndex?: number;
  stagingPagesProcessed?: number;
  stagingRowsStaged?: number;
  stagingProviderNumberMatched?: number;
  stagingStartedAt?: string;
  completionPercent?: number;
}

export function useMetsaregisterWfsLayers() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["admin", "integrations", "metsaregister-wfs", "layers"],
    enabled,
    refetchInterval: 5_000,
    queryFn: () => apiFetch<MetsaregisterWfsLayerStatus[]>(
      "/v1/admin/integrations/metsaregister-wfs/layers", accessToken,
    ),
  });
}

export function useRefreshMetsaregisterWfsLayer() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation<IntegrationRun, Error, string>({
    mutationFn: layerName => apiFetch<IntegrationRun>(
      `/v1/admin/integrations/metsaregister-wfs/layers/${encodeURIComponent(layerName)}/runs`,
      accessToken,
      { method: "POST" },
    ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin", "integrations"] });
      qc.invalidateQueries({ queryKey: ["admin", "integrations", "metsaregister-wfs", "layers"] });
    },
  });
}

export function useRefreshAllMetsaregisterWfsLayers() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation<IntegrationRun, Error, void>({
    mutationFn: () => apiFetch<IntegrationRun>(
      "/v1/admin/integrations/metsaregister-wfs/full-runs",
      accessToken,
      { method: "POST" },
    ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["admin", "integrations"] });
      qc.invalidateQueries({ queryKey: ["admin", "integrations", "metsaregister-wfs", "layers"] });
    },
  });
}
