"use client";

import { useQuery } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";

export type MapSearchResult = {
  type: "ADDRESS" | "CADASTRE";
  displayLabel: string;
  longitude?: number | null;
  latitude?: number | null;
  west?: number | null;
  south?: number | null;
  east?: number | null;
  north?: number | null;
  provider: string;
  providerIdentity?: string | null;
  cadastralCode?: string | null;
  local: boolean;
};

type MapSearchResponse = {
  results: MapSearchResult[];
};

export function useMapSearch(query: string, enabledByQuery = true) {
  const { accessToken, enabled } = useToken();
  const normalized = query.trim();
  return useQuery({
    queryKey: ["map", "search", normalized],
    enabled: enabled && enabledByQuery && normalized.length >= 2,
    staleTime: 30_000,
    retry: false,
    queryFn: () => apiFetch<MapSearchResponse>(
      `/v1/map/search?q=${encodeURIComponent(normalized)}&limit=8`,
      accessToken,
      { cache: "no-store" },
    ),
  });
}
