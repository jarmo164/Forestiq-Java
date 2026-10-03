"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";

export interface GeospatialRunStatus {
  runId: string;
  triggerType: string;
  status: "QUEUED" | "RUNNING" | "SUCCESS" | "PARTIAL" | "FAILED" | "CANCELLED" | string;
  rowsRead?: number;
  rowsStaged?: number;
  rowsInserted?: number;
  rowsRejected?: number;
  bytes?: number;
  durationMs?: number;
  errorSummary?: string;
  startedAt?: string;
  completedAt?: string;
  createdAt?: string;
}

export interface GeospatialGenerationStatus {
  generationId: string;
  sourceDate?: string;
  sourceVersion?: string;
  checksum?: string;
  bytes?: number;
  schemaFingerprint?: string;
  rowCount?: number;
  publishedAt?: string;
}

export interface GeospatialSourceStatus {
  sourceKey: string;
  displayName: string;
  provider: string;
  datasetKey: string;
  sourceKind: string;
  enabled: boolean;
  cadenceSeconds?: number;
  freshnessTargetSeconds?: number;
  expectedSourceSrid?: number;
  canonicalSrid?: number;
  currentGeneration?: GeospatialGenerationStatus;
  latestRun?: GeospatialRunStatus;
  latestSuccessAt?: string;
  latestSuccessAgeSeconds?: number;
  freshnessState: "FRESH" | "STALE" | "DEGRADED" | "NEVER_SUCCESSFUL" | string;
  running: boolean;
  manualSupported: boolean;
  businessPurpose?: string;
  caveat?: string;
  recentRuns: GeospatialRunStatus[];
}

export interface ParcelFactProvenance {
  sourceKey: string;
  generationId: string;
  sourceDate?: string;
  sourceVersion?: string;
  checksum?: string;
  freshnessState?: "FRESH" | "STALE" | "DEGRADED" | "UNKNOWN" | string;
}

export interface ParcelLayerFact {
  sourceKey: string;
  title: string;
  datasetFamily: string;
  geometryType: string;
  available: boolean;
  featureCount?: number | null;
  overlapAreaHa?: number | null;
  overlapPercent?: number | null;
  intersectionLengthMeters?: number | null;
  provenance?: ParcelFactProvenance | null;
  caveat?: string | null;
  mapVisible: boolean;
}

export interface ParcelGeospatialContext {
  cadastralCode: string;
  woodyVegetation?: {
    areaHa: number;
    percent: number;
    provenance: ParcelFactProvenance;
    caveat: string;
  };
  county?: { name?: string; code?: string; provenance: ParcelFactProvenance };
  municipality?: { name?: string; code?: string; provenance: ParcelFactProvenance };
  settlement?: { name?: string; code?: string; provenance: ParcelFactProvenance };
  layerFacts?: ParcelLayerFact[];
}

export interface GeospatialMapLayerDescriptor {
  sourceKey: string;
  title: string;
  datasetFamily: string;
  geometryType: string;
  minZoom: number;
  maxZoom: number;
  defaultOpacity: number;
  caveat: string;
  generationId?: string;
  sourceDate?: string;
  tileUrlTemplate?: string;
  available: boolean;
}

export function useGeospatialSources() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["admin", "geospatial", "sources"],
    enabled,
    refetchInterval: 5_000,
    queryFn: () => apiFetch<GeospatialSourceStatus[]>("/v1/admin/geospatial/sources", accessToken),
  });
}

export function useStartGeospatialRefresh() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ sourceKey, forceSchemaDrift = false, reason }: { sourceKey: string; forceSchemaDrift?: boolean; reason?: string }) =>
      apiFetch<{ accepted: boolean; sourceKey: string }>(
        `/v1/admin/geospatial/sources/${encodeURIComponent(sourceKey)}/refresh`,
        accessToken,
        { method: "POST", body: JSON.stringify({ forceSchemaDrift, reason }) },
      ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["admin", "geospatial", "sources"] }),
  });
}

export function useParcelGeospatialContext(cadastralCode?: string, requested = true) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["map", "parcel-context", cadastralCode],
    enabled: enabled && Boolean(cadastralCode) && requested,
    queryFn: () => apiFetch<ParcelGeospatialContext>(
      `/v1/map/land-parcels/${encodeURIComponent(cadastralCode || "")}/context`, accessToken,
    ),
  });
}
