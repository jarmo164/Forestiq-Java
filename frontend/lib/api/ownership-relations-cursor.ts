"use client";

import { useQuery } from "@tanstack/react-query";
import { apiTransport } from "@/lib/api/transport";
import { useToken } from "@/lib/api/core";
import { requireProperties, type RequiredProperties } from "@/lib/api/contract-normalizers";
import type { ApiSchema } from "@/lib/api-contract";
import { normalizeOwnershipRelation, type OwnershipRelation } from "@/lib/api/ownership-relations";

type GeneratedOwnershipRelationCursorPage = RequiredProperties<
  ApiSchema<"RelationCursorPage">,
  "content" | "hasNext"
>;
export type OwnershipRelationCursorPage = Omit<GeneratedOwnershipRelationCursorPage, "content"> & {
  content: OwnershipRelation[];
};

function normalizePage(value: ApiSchema<"RelationCursorPage">): OwnershipRelationCursorPage {
  const required = requireProperties(value, ["content", "hasNext"] as const, "Ownership relation cursor page");
  return {
    ...required,
    content: required.content.map(normalizeOwnershipRelation),
  };
}

export function useOwnershipRelationsCursor(
  search = "",
  active = "",
  source = "",
  cursor: string | null = null,
  size = 50,
) {
  const { enabled } = useToken();
  const activeFilter = active === "" ? undefined : active === "true";

  return useQuery({
    queryKey: ["ownership-relations", "cursor", search, active, source, cursor, size],
    enabled,
    staleTime: 30_000,
    queryFn: async () => normalizePage(
      await apiTransport.GET("/api/v1/ownership-relations/cursor", {
        query: {
          search,
          active: activeFilter,
          source: source || undefined,
          cursor: cursor ?? undefined,
          size,
        },
      }),
    ),
  });
}
