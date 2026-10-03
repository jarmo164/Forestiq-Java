"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useToken } from "@/lib/api/core";
import { apiTransport } from "@/lib/api/transport";
import { requireProperties, type RequiredProperties } from "@/lib/api/contract-normalizers";
import type { ApiSchema } from "@/lib/api-contract";

export type NotificationPreference = RequiredProperties<
  ApiSchema<"NotificationPreference">,
  "leadMinutes" | "inAppEnabled" | "emailEnabled"
>;

export type PortfolioSource = RequiredProperties<
  ApiSchema<"PortfolioSource">,
  "source" | "available" | "note"
>;
export type PortfolioParcel = RequiredProperties<
  ApiSchema<"PortfolioParcel">,
  "id" | "cadastralCode" | "activeNotificationCount"
>;
type GeographicGroup = RequiredProperties<
  ApiSchema<"GeographicGroup">,
  "county" | "parcelCount" | "areaHectares"
>;
type GeneratedPortfolioBrief = RequiredProperties<
  ApiSchema<"PortfolioBrief">,
  "ownerId" | "ownerName" | "parcelCount" | "totalAreaHectares" | "forestAreaHectares" |
  "volumeKnownParcelCount" | "activeNotificationCount" | "parcels" | "geography" | "sources" | "generatedAt"
>;
export type PortfolioBrief = Omit<GeneratedPortfolioBrief, "parcels" | "geography" | "sources"> & {
  parcels: PortfolioParcel[];
  geography: GeographicGroup[];
  sources: PortfolioSource[];
};

export type DecisionSignal = RequiredProperties<
  ApiSchema<"DecisionSignal">,
  "code" | "severity" | "title" | "reason" | "source" | "recommendedAction" | "legalDecision"
>;

type GeneratedEvidencePreview = RequiredProperties<
  ApiSchema<"EvidencePreview">,
  "dealId" | "ownerId" | "generatedAt" | "selectedParcelIds" | "portfolio" | "signals"
>;
export type EvidencePreview = Omit<GeneratedEvidencePreview, "portfolio" | "signals"> & {
  portfolio: PortfolioBrief;
  signals: DecisionSignal[];
};

type GeneratedEvidenceSnapshot = RequiredProperties<
  ApiSchema<"EvidenceSnapshot">,
  "id" | "dealId" | "ownerId" | "state" | "schemaVersion" | "snapshot" | "createdBy" | "createdAt"
>;
export type EvidenceSnapshot = Omit<GeneratedEvidenceSnapshot, "snapshot"> & {
  snapshot: EvidencePreview;
};

type GeneratedWorkbasketParcel = RequiredProperties<ApiSchema<"WorkbasketParcel">, "id" | "cadastralCode">;
type GeneratedWorkbasketOwner = RequiredProperties<ApiSchema<"WorkbasketOwner">, "id" | "name">;
type GeneratedWorkbasket = RequiredProperties<
  ApiSchema<"Workbasket">,
  "id" | "name" | "selectionMode" | "filters" | "parcels" | "owners" | "hiddenItemCount" | "createdAt" | "updatedAt"
>;

export type Workbasket = Omit<GeneratedWorkbasket, "selectionMode" | "parcels" | "owners"> & {
  selectionMode: "FIXED" | "DYNAMIC";
  parcels: GeneratedWorkbasketParcel[];
  owners: GeneratedWorkbasketOwner[];
};
export type SaveWorkbasket = ApiSchema<"SaveWorkbasketRequest">;

function normalizeNotificationPreference(value: ApiSchema<"NotificationPreference">): NotificationPreference {
  return requireProperties(
    value,
    ["leadMinutes", "inAppEnabled", "emailEnabled"] as const,
    "Notification preference",
  );
}

function normalizePortfolioSource(value: ApiSchema<"PortfolioSource">): PortfolioSource {
  return requireProperties(value, ["source", "available", "note"] as const, "Portfolio source");
}

function normalizePortfolioParcel(value: ApiSchema<"PortfolioParcel">): PortfolioParcel {
  return requireProperties(value, ["id", "cadastralCode", "activeNotificationCount"] as const, "Portfolio parcel");
}

function normalizeGeographicGroup(value: ApiSchema<"GeographicGroup">): GeographicGroup {
  return requireProperties(value, ["county", "parcelCount", "areaHectares"] as const, "Geographic group");
}

function normalizeDecisionSignal(value: ApiSchema<"DecisionSignal">): DecisionSignal {
  return requireProperties(
    value,
    ["code", "severity", "title", "reason", "source", "recommendedAction", "legalDecision"] as const,
    "Decision signal",
  );
}

function normalizePortfolioBrief(value: ApiSchema<"PortfolioBrief">): PortfolioBrief {
  const required = requireProperties(
    value,
    ["ownerId", "ownerName", "parcelCount", "totalAreaHectares", "forestAreaHectares", "volumeKnownParcelCount",
      "activeNotificationCount", "parcels", "geography", "sources", "generatedAt"] as const,
    "Portfolio brief",
  );
  return {
    ...required,
    parcels: required.parcels.map(normalizePortfolioParcel),
    geography: required.geography.map(normalizeGeographicGroup),
    sources: required.sources.map(normalizePortfolioSource),
  };
}

function normalizeEvidencePreview(value: ApiSchema<"EvidencePreview">): EvidencePreview {
  const required = requireProperties(
    value,
    ["dealId", "ownerId", "generatedAt", "selectedParcelIds", "portfolio", "signals"] as const,
    "Evidence preview",
  );
  return {
    ...required,
    portfolio: normalizePortfolioBrief(required.portfolio),
    signals: required.signals.map(normalizeDecisionSignal),
  };
}

function normalizeEvidenceSnapshot(value: ApiSchema<"EvidenceSnapshot">): EvidenceSnapshot {
  const required = requireProperties(
    value,
    ["id", "dealId", "ownerId", "state", "schemaVersion", "snapshot", "createdBy", "createdAt"] as const,
    "Evidence snapshot",
  );
  if (typeof required.snapshot !== "object" || required.snapshot === null || Array.isArray(required.snapshot)) {
    throw new Error("Evidence snapshot contains an invalid snapshot payload.");
  }
  return {
    ...required,
    snapshot: normalizeEvidencePreview(required.snapshot as ApiSchema<"EvidencePreview">),
  };
}

function normalizeWorkbasket(value: ApiSchema<"Workbasket">): Workbasket {
  const required = requireProperties(
    value,
    ["id", "name", "selectionMode", "filters", "parcels", "owners", "hiddenItemCount", "createdAt", "updatedAt"] as const,
    "Workbasket",
  );
  if (required.selectionMode !== "FIXED" && required.selectionMode !== "DYNAMIC") {
    throw new Error(`Workbasket has unsupported selectionMode "${required.selectionMode}".`);
  }
  return {
    ...required,
    selectionMode: required.selectionMode,
    parcels: required.parcels.map((parcel) =>
      requireProperties(parcel, ["id", "cadastralCode"] as const, "Workbasket parcel"),
    ),
    owners: required.owners.map((owner) =>
      requireProperties(owner, ["id", "name"] as const, "Workbasket owner"),
    ),
  };
}

const keys = {
  notification: ["notification-preference"] as const,
  portfolio: (ownerId: string) => ["decision-support", "portfolio", ownerId] as const,
  signals: (ownerId: string) => ["decision-support", "signals", ownerId] as const,
  evidencePreview: (dealId: string) => ["decision-support", "evidence-preview", dealId] as const,
  evidence: (dealId: string) => ["decision-support", "evidence", dealId] as const,
  workbaskets: ["decision-support", "workbaskets"] as const,
  workbasket: (id: string) => ["decision-support", "workbasket", id] as const,
};

export function useNotificationPreference() {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.notification,
    enabled,
    queryFn: async () => normalizeNotificationPreference(
      await apiTransport.GET("/api/v1/notifications/preferences"),
    ),
  });
}

export function useUpdateNotificationPreference() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (data: ApiSchema<"UpdateNotificationPreferenceRequest">) =>
      normalizeNotificationPreference(
        await apiTransport.PUT("/api/v1/notifications/preferences", { body: data }),
      ),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.notification }),
  });
}

export function usePortfolioBrief(ownerId: string, queryEnabled = true) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.portfolio(ownerId),
    enabled: enabled && queryEnabled && Boolean(ownerId),
    queryFn: async () => normalizePortfolioBrief(
      await apiTransport.GET("/api/v1/decision-support/owners/{ownerId}/portfolio", { path: { ownerId } }),
    ),
  });
}

export function useDecisionSignals(ownerId: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.signals(ownerId),
    enabled: enabled && Boolean(ownerId),
    queryFn: async () => (await apiTransport.GET(
      "/api/v1/decision-support/owners/{ownerId}/signals",
      { path: { ownerId } },
    )).map(normalizeDecisionSignal),
  });
}

export function useEvidencePreview(dealId: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.evidencePreview(dealId),
    enabled: enabled && Boolean(dealId),
    queryFn: async () => normalizeEvidencePreview(
      await apiTransport.GET("/api/v1/decision-support/deals/{dealId}/evidence/preview", { path: { dealId } }),
    ),
  });
}

export function useEvidenceSnapshots(dealId: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.evidence(dealId),
    enabled: enabled && Boolean(dealId),
    queryFn: async () => (await apiTransport.GET(
      "/api/v1/decision-support/deals/{dealId}/evidence",
      { path: { dealId } },
    )).map(normalizeEvidenceSnapshot),
  });
}

export function useConfirmEvidence(dealId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => normalizeEvidenceSnapshot(
      await apiTransport.POST("/api/v1/decision-support/deals/{dealId}/evidence", { path: { dealId } }),
    ),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: keys.evidence(dealId) });
      void qc.invalidateQueries({ queryKey: keys.evidencePreview(dealId) });
    },
  });
}

export function useWorkbaskets() {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.workbaskets,
    enabled,
    queryFn: async () => (await apiTransport.GET("/api/v1/decision-support/workbaskets")).map(normalizeWorkbasket),
  });
}

export function useWorkbasket(id: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.workbasket(id),
    enabled: enabled && Boolean(id),
    queryFn: async () => normalizeWorkbasket(
      await apiTransport.GET("/api/v1/decision-support/workbaskets/{id}", { path: { id } }),
    ),
  });
}

export function useCreateWorkbasket() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (data: SaveWorkbasket) =>
      normalizeWorkbasket(await apiTransport.POST("/api/v1/decision-support/workbaskets", { body: data })),
    onSuccess: () => void qc.invalidateQueries({ queryKey: keys.workbaskets }),
  });
}

export function useUpdateWorkbasket(id: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (data: SaveWorkbasket) =>
      normalizeWorkbasket(await apiTransport.PUT("/api/v1/decision-support/workbaskets/{id}", { path: { id }, body: data })),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: keys.workbaskets });
      void qc.invalidateQueries({ queryKey: keys.workbasket(id) });
    },
  });
}
