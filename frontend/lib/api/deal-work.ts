"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiTransport } from "@/lib/api/transport";
import { useToken } from "@/lib/api/core";
import { requireProperties, type RequiredProperties } from "@/lib/api/contract-normalizers";
import type { ApiPath, ApiQueryParameters, ApiSchema } from "@/lib/api-contract";

type GeneratedHealthReason = RequiredProperties<
  ApiSchema<"HealthReason">,
  "code" | "severity" | "title" | "rule" | "recommendedAction"
>;
export type DealHealthReason = GeneratedHealthReason;

type GeneratedDealWorkItem = RequiredProperties<
  ApiSchema<"DealWorkItem">,
  "dealId" | "ownerId" | "ownerName" | "stage" | "saleSubject" | "cadastralCodes" |
  "salesSubject" | "nextAction" | "health" | "healthReasons"
>;
export type DealWorkItem = Omit<GeneratedDealWorkItem, "health" | "healthReasons"> & {
  health: "HEALTHY" | "ATTENTION" | "CRITICAL";
  healthReasons: DealHealthReason[];
};

export type DealWorkFilters = NonNullable<
  ApiQueryParameters<ApiPath<"/api/v1/deal-work/active">["get"]>
>;

export type LossReasonDefinition = RequiredProperties<
  ApiSchema<"LossReasonDefinition">,
  "code" | "label" | "active" | "sortOrder"
>;
export type LossReportRow = RequiredProperties<
  ApiSchema<"LossReportRow">,
  "reasonCode" | "reasonLabel" | "count"
>;

type LossReportFilters = NonNullable<
  ApiQueryParameters<ApiPath<"/api/v1/deal-work/loss-report">["get"]>
>;
type UpdateNextAction = ApiSchema<"UpdateNextActionRequest">;
type UpdateLossReason = ApiSchema<"UpdateLossReasonRequest">;
type LoseDeal = ApiSchema<"DealWorkLoseDealRequest">;

function normalizeHealthReason(reason: ApiSchema<"HealthReason">): DealHealthReason {
  return requireProperties(
    reason,
    ["code", "severity", "title", "rule", "recommendedAction"] as const,
    "Deal health reason",
  );
}

function normalizeDealWorkItem(item: ApiSchema<"DealWorkItem">): DealWorkItem {
  const required = requireProperties(
    item,
    ["dealId", "ownerId", "ownerName", "stage", "saleSubject", "cadastralCodes", "salesSubject", "nextAction", "health", "healthReasons"] as const,
    "Deal work item",
  );
  if (!["HEALTHY", "ATTENTION", "CRITICAL"].includes(required.health)) {
    throw new Error(`Deal work item has unsupported health "${required.health}".`);
  }
  return {
    ...required,
    health: required.health as DealWorkItem["health"],
    healthReasons: required.healthReasons.map(normalizeHealthReason),
  };
}

function normalizeLossReason(reason: ApiSchema<"LossReasonDefinition">): LossReasonDefinition {
  return requireProperties(reason, ["code", "label", "active", "sortOrder"] as const, "Loss reason");
}

function normalizeLossReportRow(row: ApiSchema<"LossReportRow">): LossReportRow {
  return requireProperties(row, ["reasonCode", "reasonLabel", "count"] as const, "Loss report row");
}

export function useActiveDeals(filters: DealWorkFilters = {}) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["deal-work", "active", filters],
    enabled,
    queryFn: async () => {
      const response = requireProperties(
        await apiTransport.GET("/api/v1/deal-work/active", { query: filters }),
        ["items", "total"] as const,
        "Active deals response",
      );
      return {
        ...response,
        items: response.items.map(normalizeDealWorkItem),
      };
    },
  });
}

export function useDealWorkItem(dealId: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["deal-work", "item", dealId],
    enabled: enabled && Boolean(dealId),
    queryFn: async () => normalizeDealWorkItem(
      await apiTransport.GET("/api/v1/deal-work/{dealId}", { path: { dealId } }),
    ),
  });
}

export function useUpdateDealNextAction() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ dealId, nextAction, dueAt }: { dealId: string } & UpdateNextAction) =>
      normalizeDealWorkItem(await apiTransport.PUT(
        "/api/v1/deal-work/{dealId}/next-action",
        { path: { dealId }, body: { nextAction, dueAt } },
      )),
    onSuccess: (_, variables) => {
      void qc.invalidateQueries({ queryKey: ["deal-work", "active"] });
      void qc.invalidateQueries({ queryKey: ["deal-work", "item", variables.dealId] });
    },
  });
}

export function useLossReasons(includeInactive = false) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["deal-work", "loss-reasons", includeInactive],
    enabled,
    queryFn: async () => (await apiTransport.GET(
      "/api/v1/deal-work/loss-reasons",
      { query: { includeInactive } },
    )).map(normalizeLossReason),
  });
}

export function useLoseDeal() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ dealId, ...body }: { dealId: string } & LoseDeal) =>
      apiTransport.POST("/api/v1/deal-work/{dealId}/lose", { path: { dealId }, body }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["deal-work"] }),
  });
}

export function useUpdateLossReason() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ code, ...body }: { code: string } & UpdateLossReason) =>
      normalizeLossReason(await apiTransport.PUT(
        "/api/v1/deal-work/loss-reasons/{code}",
        { path: { code }, body },
      )),
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["deal-work", "loss-reasons"] }),
  });
}

export function useLossReport(filters: LossReportFilters = {}, queryEnabled = true) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["deal-work", "loss-report", filters],
    enabled: enabled && queryEnabled,
    queryFn: async () => (await apiTransport.GET(
      "/api/v1/deal-work/loss-report",
      { query: filters },
    )).map(normalizeLossReportRow),
  });
}
