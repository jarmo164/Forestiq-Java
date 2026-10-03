"use client";

import { useQuery } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";
import type { CadastreSubPartGeometry } from "@/lib/api/map-cadastre";

export function useCadastreSubPartGeometryByCode(
  cadastralCode: string,
  subPartCode: number | null,
  requested = true,
) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["cadastre", cadastralCode, "sub-parts", "code", subPartCode, "geometry"],
    enabled: enabled && Boolean(cadastralCode) && subPartCode !== null && requested,
    queryFn: () => apiFetch<CadastreSubPartGeometry>(
      `/v1/cadastres/${encodeURIComponent(cadastralCode)}/sub-parts/by-code/${subPartCode}/geometry`,
      accessToken,
    ),
  });
}
