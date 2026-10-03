"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useToken } from "@/lib/api/core";
import { apiTransport } from "@/lib/api/transport";
import { requireProperties, type RequiredProperties } from "@/lib/api/contract-normalizers";
import type { ApiSchema } from "@/lib/api-contract";
import type { SaveWorkbasket, Workbasket } from "@/lib/api/decision-support";

type GeneratedHandoff = RequiredProperties<
  ApiSchema<"WorkbasketHandoff">,
  "id" | "workbasketId" | "workbasketName" | "permission" | "purpose" | "dueAt" | "status" |
  "createdBy" | "createdAt" | "hiddenItemCount"
>;

export type WorkbasketHandoff = Omit<GeneratedHandoff, "permission" | "status"> & {
  permission: "READ" | "EDIT";
  status: "PENDING" | "ACCEPTED" | "CANCELLED";
};

type GeneratedHandoffView = RequiredProperties<ApiSchema<"WorkbasketHandoffView">, "handoff" | "workbasket">;
export type WorkbasketHandoffView = Omit<GeneratedHandoffView, "handoff" | "workbasket"> & {
  handoff: WorkbasketHandoff;
  workbasket: Workbasket;
};

function normalizeHandoff(value: ApiSchema<"WorkbasketHandoff">): WorkbasketHandoff {
  const required = requireProperties(
    value,
    ["id", "workbasketId", "workbasketName", "permission", "purpose", "dueAt", "status", "createdBy", "createdAt", "hiddenItemCount"] as const,
    "Workbasket handoff",
  );
  if (required.permission !== "READ" && required.permission !== "EDIT") {
    throw new Error(`Workbasket handoff has unsupported permission "${required.permission}".`);
  }
  if (!["PENDING", "ACCEPTED", "CANCELLED"].includes(required.status)) {
    throw new Error(`Workbasket handoff has unsupported status "${required.status}".`);
  }
  return required as WorkbasketHandoff;
}

function normalizeWorkbasket(value: ApiSchema<"Workbasket">): Workbasket {
  const required = requireProperties(
    value,
    ["id", "name", "selectionMode", "filters", "parcels", "owners", "hiddenItemCount", "createdAt", "updatedAt"] as const,
    "Shared workbasket",
  );
  if (required.selectionMode !== "FIXED" && required.selectionMode !== "DYNAMIC") {
    throw new Error(`Shared workbasket has unsupported selectionMode "${required.selectionMode}".`);
  }
  return {
    ...required,
    parcels: required.parcels.map((parcel) =>
      requireProperties(parcel, ["id", "cadastralCode"] as const, "Shared workbasket parcel"),
    ),
    owners: required.owners.map((owner) =>
      requireProperties(owner, ["id", "name"] as const, "Shared workbasket owner"),
    ),
  } as Workbasket;
}

function normalizeHandoffView(value: ApiSchema<"WorkbasketHandoffView">): WorkbasketHandoffView {
  const required = requireProperties(value, ["handoff", "workbasket"] as const, "Workbasket handoff view");
  return {
    handoff: normalizeHandoff(required.handoff),
    workbasket: normalizeWorkbasket(required.workbasket),
  };
}

export function useWorkbasketHandoffInbox() {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["workbasket-handoffs", "inbox"],
    enabled,
    queryFn: async () => (await apiTransport.GET("/api/v1/decision-support/workbasket-handoffs/inbox")).map(normalizeHandoff),
  });
}

export function useWorkbasketHandoffSent() {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["workbasket-handoffs", "sent"],
    enabled,
    queryFn: async () => (await apiTransport.GET("/api/v1/decision-support/workbasket-handoffs/sent")).map(normalizeHandoff),
  });
}

export function useWorkbasketHandoff(id: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["workbasket-handoffs", id],
    enabled: enabled && Boolean(id),
    queryFn: async () => normalizeHandoffView(
      await apiTransport.GET("/api/v1/decision-support/workbasket-handoffs/{id}", { path: { id } }),
    ),
  });
}

export function useShareWorkbasket() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ workbasketId, recipientSubject, recipientRole, permission, purpose, dueAt }: {
      workbasketId: string; recipientSubject?: string; recipientRole?: string;
      permission: "READ" | "EDIT"; purpose: string; dueAt: string;
    }) => normalizeHandoff(await apiTransport.POST(
      "/api/v1/decision-support/workbaskets/{id}/handoffs",
      { path: { id: workbasketId }, body: { recipientSubject, recipientRole, permission, purpose, dueAt } },
    )),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["workbasket-handoffs"] }),
  });
}

export function useAcceptWorkbasketHandoff() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => normalizeHandoff(await apiTransport.POST(
      "/api/v1/decision-support/workbasket-handoffs/{id}/accept",
      { path: { id } },
    )),
    onSuccess: (_, id) => {
      void qc.invalidateQueries({ queryKey: ["workbasket-handoffs"] });
      void qc.invalidateQueries({ queryKey: ["workbasket-handoffs", id] });
    },
  });
}

export function useCancelWorkbasketHandoff() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => normalizeHandoff(await apiTransport.POST(
      "/api/v1/decision-support/workbasket-handoffs/{id}/cancel",
      { path: { id } },
    )),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["workbasket-handoffs"] }),
  });
}

export function useEditSharedWorkbasket() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ handoffId, data }: { handoffId: string; data: SaveWorkbasket }) =>
      apiTransport.PUT(
        "/api/v1/decision-support/workbasket-handoffs/{id}/workbasket",
        { path: { id: handoffId }, body: data },
      ),
    onSuccess: (_, variables) => {
      void qc.invalidateQueries({ queryKey: ["workbasket-handoffs", variables.handoffId] });
      void qc.invalidateQueries({ queryKey: ["workbasket-handoffs"] });
    },
  });
}
