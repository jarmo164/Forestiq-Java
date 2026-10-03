"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiTransport } from "@/lib/api/transport";
import { useToken } from "@/lib/api/core";
import { requireProperties, type RequiredProperties } from "@/lib/api/contract-normalizers";
import type { ApiSchema } from "@/lib/api-contract";
import { queryKeys } from "@/lib/query-keys";

export type OwnershipConflict = RequiredProperties<
  ApiSchema<"ConflictView">,
  "id" | "source" | "observedActive" | "observedAt" | "createdAt"
>;

type GeneratedOwnershipRelation = RequiredProperties<
  ApiSchema<"RelationView">,
  "ownerId" | "ownerName" | "ownerCode" | "parcelId" | "cadastralCode" | "active" |
  "source" | "sourceSeenAt" | "createdAt" | "updatedAt" | "manualOverride" | "manualRevision"
>;
export type OwnershipRelation = Omit<GeneratedOwnershipRelation, "conflict"> & {
  conflict?: OwnershipConflict | null;
};

export type OwnershipAuditEvent = RequiredProperties<
  ApiSchema<"AuditView">,
  "id" | "action" | "relationSource" | "actorSubject" | "reason" | "payload" | "createdAt"
>;

function normalizeConflict(value: ApiSchema<"ConflictView">): OwnershipConflict {
  return requireProperties(
    value,
    ["id", "source", "observedActive", "observedAt", "createdAt"] as const,
    "Ownership conflict",
  );
}

export function normalizeOwnershipRelation(value: ApiSchema<"RelationView">): OwnershipRelation {
  const required = requireProperties(
    value,
    ["ownerId", "ownerName", "ownerCode", "parcelId", "cadastralCode", "active", "source",
      "sourceSeenAt", "createdAt", "updatedAt", "manualOverride", "manualRevision"] as const,
    "Ownership relation",
  );
  return {
    ...required,
    conflict: required.conflict ? normalizeConflict(required.conflict) : null,
  };
}

function normalizeAuditEvent(value: ApiSchema<"AuditView">): OwnershipAuditEvent {
  return requireProperties(
    value,
    ["id", "action", "relationSource", "actorSubject", "reason", "payload", "createdAt"] as const,
    "Ownership audit event",
  );
}

const relationKeys = {
  all: ["ownership-relations"] as const,
  audit: (ownerId: string, parcelId: string) => ["ownership-relations", "audit", ownerId, parcelId] as const,
};

function invalidateRelationViews(qc: ReturnType<typeof useQueryClient>, ownerId?: string, cadastralCode?: string) {
  void qc.invalidateQueries({ queryKey: relationKeys.all });
  void qc.invalidateQueries({ queryKey: queryKeys.owners.all });
  if (ownerId) {
    void qc.invalidateQueries({ queryKey: queryKeys.owners.detail(ownerId) });
    void qc.invalidateQueries({ queryKey: ["owner-360", ownerId] });
    void qc.invalidateQueries({ queryKey: ["decision-support", "portfolio", ownerId] });
    void qc.invalidateQueries({ queryKey: ["decision-support", "signals", ownerId] });
  }
  if (cadastralCode) void qc.invalidateQueries({ queryKey: ["map", "parcel-summary", cadastralCode] });
}

export function useCreateOwnershipRelation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: ApiSchema<"CreateRelationRequest">) =>
      normalizeOwnershipRelation(await apiTransport.POST("/api/v1/ownership-relations", { body })),
    onSuccess: relation => invalidateRelationViews(qc, relation.ownerId, relation.cadastralCode),
  });
}

export function useEndOwnershipRelation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ ownerId, parcelId, reason }: { ownerId: string; parcelId: string } & ApiSchema<"ReasonRequest">) =>
      normalizeOwnershipRelation(await apiTransport.POST(
        "/api/v1/ownership-relations/{ownerId}/{parcelId}/end",
        { path: { ownerId, parcelId }, body: { reason } },
      )),
    onSuccess: relation => invalidateRelationViews(qc, relation.ownerId, relation.cadastralCode),
  });
}

export function useResolveOwnershipConflict() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ conflictId, ...body }: { conflictId: string } & ApiSchema<"ResolveConflictRequest">) =>
      normalizeOwnershipRelation(await apiTransport.POST(
        "/api/v1/ownership-relations/conflicts/{conflictId}/resolve",
        { path: { conflictId }, body },
      )),
    onSuccess: relation => invalidateRelationViews(qc, relation.ownerId, relation.cadastralCode),
  });
}

export function useOwnershipRelationAudit(ownerId: string, parcelId: string, enabled = true) {
  const { enabled: authenticated } = useToken();
  return useQuery({
    queryKey: relationKeys.audit(ownerId, parcelId),
    enabled: authenticated && enabled && Boolean(ownerId) && Boolean(parcelId),
    queryFn: async () => (await apiTransport.GET(
      "/api/v1/ownership-relations/{ownerId}/{parcelId}/audit",
      { path: { ownerId, parcelId } },
    )).map(normalizeAuditEvent),
  });
}
