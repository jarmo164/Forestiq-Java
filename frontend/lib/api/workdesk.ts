"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { apiTransport } from "@/lib/api/transport";
import { useToken } from "@/lib/api/core";
import { queryKeys } from "@/lib/query-keys";
import { requireProperties } from "@/lib/api/contract-normalizers";
import type { ApiSchema } from "@/lib/api-contract";
import type { OwnerListItem, PagedResponse } from "@/lib/types";

function normalizeOwnerListItem(item: ApiSchema<"OwnerListItem">): OwnerListItem {
  return requireProperties(
    item,
    ["id", "displayName", "personalOrRegistryCode", "status", "parcelCount", "updatedAt"] as const,
    "Owner list item",
  ) as OwnerListItem;
}

function normalizeMyWorkPage(page: ApiSchema<"PagedResponseOwnerListItem">): PagedResponse<OwnerListItem> {
  const required = requireProperties(
    page,
    ["content", "page", "size", "totalElements", "totalPages"] as const,
    "My Work page",
  );
  return {
    ...required,
    content: required.content.map(normalizeOwnerListItem),
  };
}

// ===================== My Work (Caller Workdesk) =====================

export function useMyWork(search = "", page = 0, size = 20) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: queryKeys.myWork.page(search, page, size),
    enabled,
    queryFn: async () =>
      normalizeMyWorkPage(await apiTransport.GET("/api/v1/my-work", {
        query: { search, page, size },
      })),
  });
}

export function useNextAssignedOwner() {
  return useMutation({
    mutationFn: () => apiTransport.POST("/api/v1/my-work/next"),
  });
}
