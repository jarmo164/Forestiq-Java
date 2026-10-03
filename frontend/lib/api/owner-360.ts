"use client";

import { useQuery } from "@tanstack/react-query";
import { apiTransport } from "@/lib/api/transport";
import { useToken } from "@/lib/api/core";
import { requireProperties, type RequiredProperties } from "@/lib/api/contract-normalizers";
import type { ApiSchema } from "@/lib/api-contract";
import type { DecisionSignal } from "@/lib/api/decision-support";

export type Owner360Identity = RequiredProperties<
  ApiSchema<"OwnerIdentity">,
  "id" | "displayName" | "sensitiveDetailsVisible" | "status"
>;
export type Owner360PortfolioKpis = ApiSchema<"PortfolioKpis">;
export type Owner360Source = RequiredProperties<
  ApiSchema<"SourceInfo">,
  "source" | "freshness" | "available"
>;
type Owner360SectionState = RequiredProperties<ApiSchema<"SectionState">, "state">;

type GeneratedOwner360Overview = RequiredProperties<
  ApiSchema<"Owner360Overview">,
  "owner" | "portfolio" | "signals" | "sources" | "sections" | "generatedAt"
>;
export type Owner360Overview = Omit<
  GeneratedOwner360Overview,
  "owner" | "signals" | "sources" | "sections"
> & {
  owner: Owner360Identity;
  signals: DecisionSignal[];
  sources: Owner360Source[];
  sections: Record<string, Owner360SectionState>;
};

type Coordinates = RequiredProperties<ApiSchema<"Coordinates">, "longitude" | "latitude">;
type GeneratedOwner360MapParcel = RequiredProperties<
  ApiSchema<"PortfolioMapParcel">,
  "parcelId" | "cadastralCode"
>;
export type Owner360MapParcel = Omit<GeneratedOwner360MapParcel, "centroid"> & {
  centroid?: Coordinates | null;
};

type GeneratedOwner360ParcelRelation = RequiredProperties<
  ApiSchema<"ParcelRelation">,
  "parcelId" | "cadastralCode" | "active" | "source" | "activeOwnerCount"
>;
export type Owner360ParcelRelation = Omit<GeneratedOwner360ParcelRelation, "centroid"> & {
  centroid?: Coordinates | null;
};

type GeneratedOwner360RelationsPage = RequiredProperties<
  ApiSchema<"ParcelRelationsPage">,
  "content" | "offset" | "limit" | "hasMore"
>;
export type Owner360RelationsPage = Omit<GeneratedOwner360RelationsPage, "content"> & {
  content: Owner360ParcelRelation[];
};

export type Owner360TimelineEvent = RequiredProperties<
  ApiSchema<"TimelineEvent">,
  "id" | "category" | "type" | "title"
>;
type GeneratedOwner360TimelinePage = RequiredProperties<
  ApiSchema<"TimelinePage">,
  "content" | "offset" | "limit" | "hasMore"
>;
export type Owner360TimelinePage = Omit<GeneratedOwner360TimelinePage, "content"> & {
  content: Owner360TimelineEvent[];
};

export type Owner360Workflow = RequiredProperties<
  ApiSchema<"WorkflowSummary">,
  "ownerStatus" | "businessDetailsVisible"
>;

function normalizeCoordinates(value: ApiSchema<"Coordinates"> | undefined): Coordinates | undefined {
  return value ? requireProperties(value, ["longitude", "latitude"] as const, "Coordinates") : undefined;
}

function normalizeDecisionSignal(value: ApiSchema<"DecisionSignal">): DecisionSignal {
  return requireProperties(
    value,
    ["code", "severity", "title", "reason", "source", "recommendedAction", "legalDecision"] as const,
    "Decision signal",
  );
}

function normalizeOwnerIdentity(value: ApiSchema<"OwnerIdentity">): Owner360Identity {
  return requireProperties(
    value,
    ["id", "displayName", "sensitiveDetailsVisible", "status"] as const,
    "Owner 360 identity",
  );
}

function normalizeOwnerSource(value: ApiSchema<"SourceInfo">): Owner360Source {
  return requireProperties(value, ["source", "freshness", "available"] as const, "Owner 360 source");
}

function normalizeOverview(value: ApiSchema<"Owner360Overview">): Owner360Overview {
  const required = requireProperties(
    value,
    ["owner", "portfolio", "signals", "sources", "sections", "generatedAt"] as const,
    "Owner 360 overview",
  );
  return {
    ...required,
    owner: normalizeOwnerIdentity(required.owner),
    signals: required.signals.map(normalizeDecisionSignal),
    sources: required.sources.map(normalizeOwnerSource),
    sections: Object.fromEntries(
      Object.entries(required.sections).map(([name, state]) => [
        name,
        requireProperties(state, ["state"] as const, `Owner 360 section "${name}"`),
      ]),
    ),
  };
}

function normalizeRelation(value: ApiSchema<"ParcelRelation">): Owner360ParcelRelation {
  const required = requireProperties(
    value,
    ["parcelId", "cadastralCode", "active", "source", "activeOwnerCount"] as const,
    "Owner 360 parcel relation",
  );
  return {
    ...required,
    centroid: normalizeCoordinates(required.centroid),
  };
}

function normalizeRelationsPage(value: ApiSchema<"ParcelRelationsPage">): Owner360RelationsPage {
  const required = requireProperties(value, ["content", "offset", "limit", "hasMore"] as const, "Owner 360 relations page");
  return { ...required, content: required.content.map(normalizeRelation) };
}

function normalizeMapParcel(value: ApiSchema<"PortfolioMapParcel">): Owner360MapParcel {
  const required = requireProperties(value, ["parcelId", "cadastralCode"] as const, "Owner 360 map parcel");
  return {
    ...required,
    centroid: normalizeCoordinates(required.centroid),
  };
}

function normalizeTimelineEvent(value: ApiSchema<"TimelineEvent">): Owner360TimelineEvent {
  return requireProperties(value, ["id", "category", "type", "title"] as const, "Owner 360 timeline event");
}

function normalizeTimelinePage(value: ApiSchema<"TimelinePage">): Owner360TimelinePage {
  const required = requireProperties(value, ["content", "offset", "limit", "hasMore"] as const, "Owner 360 timeline page");
  return { ...required, content: required.content.map(normalizeTimelineEvent) };
}

function normalizeWorkflow(value: ApiSchema<"WorkflowSummary">): Owner360Workflow {
  return requireProperties(value, ["ownerStatus", "businessDetailsVisible"] as const, "Owner 360 workflow");
}

const keys = {
  overview: (ownerId: string) => ["owner-360", ownerId, "overview"] as const,
  relations: (ownerId: string, includeHistorical: boolean, offset: number, limit: number) =>
    ["owner-360", ownerId, "relations", includeHistorical, offset, limit] as const,
  map: (ownerId: string) => ["owner-360", ownerId, "map"] as const,
  history: (ownerId: string, offset: number, limit: number) => ["owner-360", ownerId, "history", offset, limit] as const,
  workflow: (ownerId: string) => ["owner-360", ownerId, "workflow"] as const,
};

export function useOwner360Overview(ownerId: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.overview(ownerId),
    enabled: enabled && Boolean(ownerId),
    staleTime: 30_000,
    queryFn: async () => normalizeOverview(
      await apiTransport.GET("/api/v1/owners/{ownerId}/360", { path: { ownerId } }),
    ),
  });
}

export function useOwner360Relations(ownerId: string, includeHistorical = false, offset = 0, limit = 100, queryEnabled = true) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.relations(ownerId, includeHistorical, offset, limit),
    enabled: enabled && queryEnabled && Boolean(ownerId),
    staleTime: 30_000,
    queryFn: async () => normalizeRelationsPage(
      await apiTransport.GET("/api/v1/owners/{ownerId}/360/relations", {
        path: { ownerId },
        query: { includeHistorical, offset, limit },
      }),
    ),
  });
}

export function useOwner360Map(ownerId: string, queryEnabled = true) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.map(ownerId),
    enabled: enabled && queryEnabled && Boolean(ownerId),
    staleTime: 30_000,
    queryFn: async () => (await apiTransport.GET(
      "/api/v1/owners/{ownerId}/360/map",
      { path: { ownerId } },
    )).map(normalizeMapParcel),
  });
}

export function useOwner360History(ownerId: string, offset = 0, limit = 100, queryEnabled = true) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.history(ownerId, offset, limit),
    enabled: enabled && queryEnabled && Boolean(ownerId),
    staleTime: 15_000,
    queryFn: async () => normalizeTimelinePage(
      await apiTransport.GET("/api/v1/owners/{ownerId}/360/history", {
        path: { ownerId },
        query: { offset, limit },
      }),
    ),
  });
}

export function useOwner360Workflow(ownerId: string, queryEnabled = true) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: keys.workflow(ownerId),
    enabled: enabled && queryEnabled && Boolean(ownerId),
    staleTime: 15_000,
    queryFn: async () => normalizeWorkflow(
      await apiTransport.GET("/api/v1/owners/{ownerId}/360/workflow", { path: { ownerId } }),
    ),
  });
}
