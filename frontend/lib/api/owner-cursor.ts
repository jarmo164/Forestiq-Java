"use client";

import { useQuery } from "@tanstack/react-query";
import { apiTransport } from "@/lib/api/transport";
import { useToken } from "@/lib/api/core";
import { requireProperties, type RequiredProperties } from "@/lib/api/contract-normalizers";
import type { ApiSchema } from "@/lib/api-contract";
import type { OwnerListItem } from "@/lib/types";

type GeneratedOwnerCursorResponse = RequiredProperties<ApiSchema<"OwnerCursorPage">, "content" | "size" | "hasNext">;
export type OwnerCursorResponse = Omit<GeneratedOwnerCursorResponse, "content"> & {
  content: OwnerListItem[];
};

function normalizeOwnerListItem(item: ApiSchema<"OwnerListItem">): OwnerListItem {
  return requireProperties(
    item,
    ["id", "displayName", "personalOrRegistryCode", "status", "parcelCount", "updatedAt"] as const,
    "Owner cursor item",
  ) as OwnerListItem;
}

function normalizeOwnerCursorPage(page: ApiSchema<"OwnerCursorPage">): OwnerCursorResponse {
  const required = requireProperties(page, ["content", "size", "hasNext"] as const, "Owner cursor page");
  return {
    ...required,
    content: required.content.map(normalizeOwnerListItem),
  };
}

export function useOwnersCursor(search = "", cursor: string | null = null, size = 20) {
  const { enabled } = useToken();

  return useQuery({
    queryKey: ["owners", "cursor", search, cursor, size],
    enabled,
    staleTime: 30_000,
    queryFn: async () =>
      normalizeOwnerCursorPage(await apiTransport.GET("/api/v1/owners/cursor", {
        query: { search, size, cursor: cursor ?? undefined },
      })),
  });
}
