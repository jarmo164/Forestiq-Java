"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";
import { apiTransport } from "@/lib/api/transport";
import { requireProperties } from "@/lib/api/contract-normalizers";
import type { CadastreNotification, CadastreSubPart, ForestRegistryFeature, MapParcelSummary, OwnerStatusDefinition } from "@/lib/types";

export type CompleteMapParcelOwnerSummary = {
  ownerId?: string | null;
  ownerName?: string | null;
  ownerStatus?: string | null;
  assignedTo?: string | null;
  relationSource?: string | null;
};

export type CompleteMapParcelSummary = Omit<MapParcelSummary, "ownerId" | "ownerName" | "ownerStatus" | "assignedTo" | "phone" | "name" | "postal" | "regNr" | "parcelType" | "mkDate" | "yardArea" | "arableArea" | "meadowArea" | "underwaterArea" | "buildingsArea" | "otherArea" | "centroidLongitude" | "centroidLatitude" | "bboxWest" | "bboxSouth" | "bboxEast" | "bboxNorth" | "owners" | "generationId" | "sourceDate" | "ownerCount" | "subPartCount" | "activeNotificationCount" | "notificationCount" | "forestFeatureCount"> & {
  ownerId?: string | null;
  ownerName?: string | null;
  ownerStatus?: string | null;
  assignedTo?: string | null;
  phone?: string | null;
  name?: string | null;
  postal?: string | null;
  regNr?: string | null;
  parcelType?: string | null;
  mkDate?: number | null;
  yardArea?: number | null;
  arableArea?: number | null;
  meadowArea?: number | null;
  underwaterArea?: number | null;
  buildingsArea?: number | null;
  otherArea?: number | null;
  centroidLongitude?: number | null;
  centroidLatitude?: number | null;
  bboxWest?: number | null;
  bboxSouth?: number | null;
  bboxEast?: number | null;
  bboxNorth?: number | null;
  owners?: CompleteMapParcelOwnerSummary[] | null;
  generationId?: string | null;
  sourceDate?: string | null;
  ownerCount?: number | null;
  subPartCount?: number | null;
  activeNotificationCount?: number | null;
  notificationCount?: number | null;
  forestFeatureCount?: number | null;
};

export type CompleteCadastreNotification = CadastreNotification & {
  amountToBeCut?: number | null;
  cadastreSubpartCode?: number | null;
  damageCode?: string | null;
  confirmationDate?: number | null;
  registrationDate?: number | null;
  archiveDate?: number | null;
};

export type CadastreNotificationPage = {
  items: CompleteCadastreNotification[];
  nextCursor?: number | null;
  hasMore: boolean;
};

export type CadastreNotificationArchivePage = {
  items: CompleteCadastreNotification[];
  nextArchiveDate?: number | null;
  nextId?: number | null;
  hasMore: boolean;
};

type NotificationArchiveCursor = {
  archiveDate: number;
  id: number;
};

export type CompleteForestRegistryFeature = ForestRegistryFeature & {
  subpartCode?: number | null;
  eventDate?: number | null;
  attributes?: string | null;
  geometry?: string | null;
};

export type ForestRegistryFeaturePage = {
  items: CompleteForestRegistryFeature[];
  nextCursor?: number | null;
  hasMore: boolean;
};

export type ForestRegistryFeatureRaw = {
  id: number;
  attributes?: string | null;
  geometry?: string | null;
};

export type CompleteCadastreSubPart = CadastreSubPart & {
  treeTypeCode?: string | null;
  area?: number | null;
};

export type CadastreSubPartPage = {
  items: CompleteCadastreSubPart[];
  nextCursor?: number | null;
  hasMore: boolean;
};

export type CadastreSubPartGeometry = {
  id: number;
  cadastralCode: string;
  subPartCode?: number | null;
  polygon?: string | null;
};

export function useCompleteMapParcelSummary(cadastralCode: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["map", "parcel-summary", cadastralCode],
    enabled: enabled && Boolean(cadastralCode),
    staleTime: 60_000,
    queryFn: () => apiFetch<CompleteMapParcelSummary>(`/v1/map/land-parcels/${encodeURIComponent(cadastralCode)}/summary`, accessToken),
  });
}

export function useMapOwnerStatusDefinitions() {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["map", "owner-status-definitions"],
    enabled,
    queryFn: async () => (await apiTransport.GET("/api/v1/owners/status-definitions")).map((status) =>
      requireProperties(status, ["id", "colorHex", "durationDays", "isProtected"] as const, "Owner status definition") as OwnerStatusDefinition
    ),
  });
}

export function useCompleteCadastreNotifications(cadastralCode: string, requested = true) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["cadastre", cadastralCode, "notifications", "bounded"],
    enabled: enabled && Boolean(cadastralCode) && requested,
    queryFn: () => apiFetch<CompleteCadastreNotification[]>(`/v1/cadastres/${encodeURIComponent(cadastralCode)}/notifications?limit=100`, accessToken),
  });
}

/** All known notifications, current and archived, through bounded keyset pages. */
export function useCadastreAllNotificationPages(cadastralCode: string, requested = true) {
  const { accessToken, enabled } = useToken();
  return useInfiniteQuery({
    queryKey: ["cadastre", cadastralCode, "notifications", "all-pages"],
    enabled: enabled && Boolean(cadastralCode) && requested,
    initialPageParam: null as number | null,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: "20" });
      if (pageParam !== null) params.set("cursor", String(pageParam));
      return apiFetch<CadastreNotificationPage>(
        `/v1/cadastres/${encodeURIComponent(cadastralCode)}/notifications/page?${params.toString()}`,
        accessToken,
      );
    },
    getNextPageParam: (lastPage) => lastPage.hasMore && lastPage.nextCursor != null ? lastPage.nextCursor : undefined,
  });
}

/** Common parcel-panel path: current/non-archived data only. */
export function useCadastreNotificationPages(cadastralCode: string, requested = true) {
  const { accessToken, enabled } = useToken();
  return useInfiniteQuery({
    queryKey: ["cadastre", cadastralCode, "notifications", "current-pages"],
    enabled: enabled && Boolean(cadastralCode) && requested,
    initialPageParam: null as number | null,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: "20" });
      if (pageParam !== null) params.set("cursor", String(pageParam));
      return apiFetch<CadastreNotificationPage>(
        `/v1/cadastres/${encodeURIComponent(cadastralCode)}/notifications/current/page?${params.toString()}`,
        accessToken,
      );
    },
    getNextPageParam: (lastPage) => lastPage.hasMore && lastPage.nextCursor != null ? lastPage.nextCursor : undefined,
  });
}

/** Archive history never runs without an explicit finite provider-date range. */
export function useCadastreNotificationArchivePages(
  cadastralCode: string,
  from: number | null,
  to: number | null,
  requested = true,
) {
  const { accessToken, enabled } = useToken();
  return useInfiniteQuery({
    queryKey: ["cadastre", cadastralCode, "notifications", "archive-pages", from, to],
    enabled: enabled && Boolean(cadastralCode) && requested && from !== null && to !== null,
    initialPageParam: null as NotificationArchiveCursor | null,
    queryFn: ({ pageParam }) => {
      if (from === null || to === null) throw new Error("Archive date range is required");
      const params = new URLSearchParams({ from: String(from), to: String(to), limit: "50" });
      if (pageParam !== null) {
        params.set("cursorArchiveDate", String(pageParam.archiveDate));
        params.set("cursorId", String(pageParam.id));
      }
      return apiFetch<CadastreNotificationArchivePage>(
        `/v1/cadastres/${encodeURIComponent(cadastralCode)}/notifications/archive/page?${params.toString()}`,
        accessToken,
      );
    },
    getNextPageParam: (lastPage): NotificationArchiveCursor | undefined =>
      lastPage.hasMore && lastPage.nextArchiveDate != null && lastPage.nextId != null
        ? { archiveDate: lastPage.nextArchiveDate, id: lastPage.nextId }
        : undefined,
  });
}

export function useCompleteCadastreForestFeatures(cadastralCode: string, requested = true) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["cadastre", cadastralCode, "forest-features"],
    enabled: enabled && Boolean(cadastralCode) && requested,
    queryFn: () => apiFetch<CompleteForestRegistryFeature[]>(`/v1/cadastres/${encodeURIComponent(cadastralCode)}/forest-features`, accessToken),
  });
}

export function useCadastreForestFeaturePages(cadastralCode: string, requested = true) {
  const { accessToken, enabled } = useToken();
  return useInfiniteQuery({
    queryKey: ["cadastre", cadastralCode, "forest-features", "pages"],
    enabled: enabled && Boolean(cadastralCode) && requested,
    initialPageParam: null as number | null,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: "20" });
      if (pageParam !== null) params.set("cursor", String(pageParam));
      return apiFetch<ForestRegistryFeaturePage>(
        `/v1/cadastres/${encodeURIComponent(cadastralCode)}/forest-features/page?${params.toString()}`,
        accessToken,
      );
    },
    getNextPageParam: (lastPage) => lastPage.hasMore && lastPage.nextCursor != null ? lastPage.nextCursor : undefined,
  });
}

export function useCadastreForestFeatureRaw(cadastralCode: string, featureId: number | null, requested = true) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["cadastre", cadastralCode, "forest-features", featureId, "raw"],
    enabled: enabled && Boolean(cadastralCode) && featureId !== null && requested,
    queryFn: () => apiFetch<ForestRegistryFeatureRaw>(
      `/v1/cadastres/${encodeURIComponent(cadastralCode)}/forest-features/${featureId}/raw`,
      accessToken,
    ),
  });
}

export function useCadastreSubPartPages(cadastralCode: string, requested = true) {
  const { accessToken, enabled } = useToken();
  return useInfiniteQuery({
    queryKey: ["cadastre", cadastralCode, "sub-parts", "pages"],
    enabled: enabled && Boolean(cadastralCode) && requested,
    initialPageParam: null as number | null,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: "20" });
      if (pageParam !== null) params.set("cursor", String(pageParam));
      return apiFetch<CadastreSubPartPage>(
        `/v1/cadastres/${encodeURIComponent(cadastralCode)}/sub-parts/page?${params.toString()}`,
        accessToken,
      );
    },
    getNextPageParam: (lastPage) => lastPage.hasMore && lastPage.nextCursor != null ? lastPage.nextCursor : undefined,
  });
}

export function useCadastreSubPartGeometry(cadastralCode: string, subPartId: number | null, requested = true) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["cadastre", cadastralCode, "sub-parts", subPartId, "geometry"],
    enabled: enabled && Boolean(cadastralCode) && subPartId !== null && requested,
    queryFn: () => apiFetch<CadastreSubPartGeometry>(
      `/v1/cadastres/${encodeURIComponent(cadastralCode)}/sub-parts/${subPartId}/geometry`,
      accessToken,
    ),
  });
}
