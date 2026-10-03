"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiBaseUrl, apiMultipartFetch, useToken } from "@/lib/api/core";
import { apiTransport } from "@/lib/api/transport";
import { queryKeys } from "@/lib/query-keys";
import type { ApiSchema } from "@/lib/api-contract";
import type { LandParcel, MapParcelSummary, OwnerDetails, OwnerStatusDefinition } from "@/lib/types";
import { requireProperties } from "@/lib/api/contract-normalizers";

type CreateOwnerRequest = ApiSchema<"CreateOwnerRequest">;
type UpdateOwnerRequest = ApiSchema<"UpdateOwnerRequest">;
type ChangeStatusRequest = ApiSchema<"ChangeStatusRequest">;
type AddActivityRequest = ApiSchema<"AddActivityRequest">;
type MarkParcelsRequest = ApiSchema<"MarkParcelsRequest">;

export type OwnerImportField = "displayName" | "personalOrRegistryCode" | "ownerType" | "email" | "phone" | "address" | "info";
export type OwnerImportMapping = { columns: Partial<Record<OwnerImportField, string>> };
export type OwnerImportInspect = {
  filename: string;
  sha256: string;
  headers: string[];
  suggestedMapping: Partial<Record<OwnerImportField, string>>;
  totalRows: number;
  sampleRows: Record<string, string>[];
};
export type OwnerImportRow = {
  rowNumber: number;
  displayName: string;
  personalOrRegistryCode: string;
  status: "READY" | "DUPLICATE" | "INVALID";
  errors: string[];
  existingOwnerId?: string | null;
  values: Record<OwnerImportField, string>;
};
export type OwnerImportPreview = {
  filename: string;
  sha256: string;
  headers: string[];
  mapping: Record<string, string>;
  totalRows: number;
  readyRows: number;
  duplicateRows: number;
  invalidRows: number;
  rows: OwnerImportRow[];
};
export type OwnerImportCommitResult = {
  batchId: string;
  filename: string;
  sha256: string;
  createdCount: number;
  duplicateCount: number;
  invalidCount: number;
  createdOwnerIds: string[];
};
export type OwnerImportBatch = ReturnType<typeof normalizeOwnerImportBatch>;

function normalizeOwnerImportBatch(batch: ApiSchema<"ImportBatchSummary">) {
  return requireProperties(
    batch,
    ["batchId", "filename", "sha256", "userSubject", "committedAt", "totalRows", "createdCount", "duplicateCount", "invalidCount"] as const,
    "Owner import batch",
  );
}

function normalizeLandParcel(parcel: ApiSchema<"LandParcel">): LandParcel {
  const required = requireProperties(parcel, ["id", "cadastralCode", "areaHectares"] as const, "Land parcel");
  return required as LandParcel;
}

function normalizeOwnerDetails(owner: ApiSchema<"OwnerDetails">): OwnerDetails {
  const required = requireProperties(
    owner,
    ["id", "displayName", "personalOrRegistryCode", "status", "parcelCount", "updatedAt", "version", "parcels", "activities", "followings"] as const,
    "Owner details",
  );
  return {
    ...required,
    parcels: required.parcels.map(normalizeLandParcel),
    activities: required.activities.map((activity) =>
      requireProperties(activity, ["id", "type", "content", "createdAt", "createdBy"] as const, "Owner activity"),
    ),
  } as OwnerDetails;
}

function normalizeMapParcelSummary(summary: ApiSchema<"ParcelMapSummary">): MapParcelSummary {
  return requireProperties(
    summary,
    ["cadastralCode", "ownerId", "ownerName", "ownerStatus", "areaHectares", "marked"] as const,
    "Map parcel summary",
  ) as MapParcelSummary;
}

// ===================== Owners =====================
// Interactive owner lists must use useOwnersCursor from owner-cursor.ts. The
// retired OFFSET/count hook intentionally no longer exists in the frontend API.

export function useMapParcelSummary(cadastralCode: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["map", "parcel-summary", cadastralCode],
    enabled: enabled && Boolean(cadastralCode),
    staleTime: 60_000,
    queryFn: async () =>
      normalizeMapParcelSummary(await apiTransport.GET("/api/v1/map/land-parcels/{cadastralCode}/summary", {
        path: { cadastralCode },
      })),
  });
}

export function useOwner(ownerId: string, queryEnabled = true) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: queryKeys.owners.detail(ownerId),
    enabled: enabled && queryEnabled && Boolean(ownerId),
    queryFn: async () => normalizeOwnerDetails(await apiTransport.GET("/api/v1/owners/{ownerId}", { path: { ownerId } })),
  });
}

export function useAllowedOwnerStatusTransitions(ownerId: string, queryEnabled = true) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["owners", ownerId, "allowed-status-transitions"],
    enabled: enabled && queryEnabled && Boolean(ownerId),
    queryFn: async () => (await apiTransport.GET(
      "/api/v1/owners/{ownerId}/allowed-status-transitions",
      { path: { ownerId } },
    )).map((status) =>
      requireProperties(status, ["id", "colorHex", "durationDays", "isProtected"] as const, "Owner status definition") as OwnerStatusDefinition
    ),
  });
}

export function useCreateOwner() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: CreateOwnerRequest) =>
      apiTransport.POST("/api/v1/owners", { body: data }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.owners.all }),
  });
}

export function useUpdateOwner(ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: UpdateOwnerRequest) =>
      apiTransport.PUT("/api/v1/owners/{ownerId}", { path: { ownerId }, body: data }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.owners.detail(ownerId) });
      void qc.invalidateQueries({ queryKey: queryKeys.owners.all });
      void qc.invalidateQueries({ queryKey: ["owners", ownerId, "allowed-status-transitions"] });
      void qc.invalidateQueries({ queryKey: ["owner-360", ownerId] });
    },
  });
}

export function useChangeStatus(ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: ChangeStatusRequest) =>
      apiTransport.PATCH("/api/v1/owners/{ownerId}/status", { path: { ownerId }, body: data }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.owners.detail(ownerId) });
      void qc.invalidateQueries({ queryKey: queryKeys.owners.all });
      void qc.invalidateQueries({ queryKey: ["owners", ownerId, "allowed-status-transitions"] });
      void qc.invalidateQueries({ queryKey: ["owner-360", ownerId] });
    },
  });
}

export function useFollowOwner(ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiTransport.POST("/api/v1/owners/{ownerId}/followings", { path: { ownerId } }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.owners.detail(ownerId) }),
  });
}

export function useUnfollowOwner(ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiTransport.DELETE("/api/v1/owners/{ownerId}/followings", { path: { ownerId } }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.owners.detail(ownerId) }),
  });
}

export function useAddOwnerNote(ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: AddActivityRequest) =>
      apiTransport.POST("/api/v1/owners/{ownerId}/activities", { path: { ownerId }, body: data }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.owners.detail(ownerId) });
      void qc.invalidateQueries({ queryKey: ["owner-360", ownerId, "history"] });
    },
  });
}

export function useMarkParcels(ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (cadastralCodes: string[]) => {
      const data: MarkParcelsRequest = { cadastralCodes };
      return apiTransport.POST("/api/v1/owners/{ownerId}/parcels/mark", { path: { ownerId }, body: data });
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: queryKeys.owners.detail(ownerId) }),
  });
}

// ===================== Owner onboarding imports =====================

function importForm(file: File, mapping?: OwnerImportMapping, confirmedSha256?: string) {
  const form = new FormData();
  form.append("file", file);
  if (mapping) form.append("mapping", new Blob([JSON.stringify(mapping)], { type: "application/json" }));
  if (confirmedSha256) form.append("confirmedSha256", confirmedSha256);
  return form;
}

export function useInspectOwnerImport() {
  const { accessToken } = useToken();
  return useMutation({
    mutationFn: (file: File) => apiMultipartFetch<OwnerImportInspect>("/v1/owners/imports/inspect", accessToken, importForm(file)),
  });
}

export function usePreviewOwnerImport() {
  const { accessToken } = useToken();
  return useMutation({
    mutationFn: ({ file, mapping }: { file: File; mapping: OwnerImportMapping }) =>
      apiMultipartFetch<OwnerImportPreview>("/v1/owners/imports/preview", accessToken, importForm(file, mapping)),
  });
}

export function useCommitOwnerImport() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ file, mapping, confirmedSha256 }: { file: File; mapping: OwnerImportMapping; confirmedSha256: string }) =>
      apiMultipartFetch<OwnerImportCommitResult>("/v1/owners/imports/commit", accessToken, importForm(file, mapping, confirmedSha256)),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: queryKeys.owners.all });
      void qc.invalidateQueries({ queryKey: ["owner-imports", "batches"] });
    },
  });
}

export function useOwnerImportBatches() {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["owner-imports", "batches"],
    enabled,
    queryFn: async () => (await apiTransport.GET("/api/v1/owners/imports/batches")).map(normalizeOwnerImportBatch),
  });
}

export function useDownloadOwnerImportErrors() {
  const { accessToken } = useToken();
  return useMutation({
    mutationFn: async (batchId: string) => {
      const response = await fetch(`${apiBaseUrl}/v1/owners/imports/batches/${batchId}/errors.csv`, {
        headers: { ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
      });
      if (!response.ok) throw new Error(`Veaaruande allalaadimine ebaõnnestus (${response.status})`);
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = `owner-import-${batchId}-errors.csv`;
      anchor.click();
      URL.revokeObjectURL(url);
    },
  });
}