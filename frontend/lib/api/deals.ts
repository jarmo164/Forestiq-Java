"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useToken } from "@/lib/api/core";
import { apiTransport } from "@/lib/api/transport";
import { dealWorkflowInvalidationKeys, queryKeys } from "@/lib/query-keys";
import type { ApiSchema } from "@/lib/api-contract";
import { requireProperties } from "@/lib/api/contract-normalizers";
import type {
  BriefParcel,
  CallBrief,
  DealCase as UiDealCase,
  DealEvaluation,
  DealEvent,
  DealParcel,
  EvaluationTask,
} from "@/lib/types";

type DealCase = UiDealCase;
type CreateDeal = ApiSchema<"CreateDealRequest">;
type SubmitDealEvaluation = ApiSchema<"SubmitEvaluationRequest">;

function normalizeDealParcel(parcel: ApiSchema<"DealParcel">): DealParcel {
  return requireProperties(parcel, ["id", "cadastralCode", "areaHectares"] as const, "Deal parcel") as DealParcel;
}

function normalizeDealEvent(event: ApiSchema<"DealEvent">): DealEvent {
  return requireProperties(event, ["id", "type", "description", "createdAt", "createdBy"] as const, "Deal event") as DealEvent;
}

function normalizeDealEvaluation(evaluation: ApiSchema<"DealEvaluation">): DealEvaluation {
  return requireProperties(evaluation, ["id", "revision", "status", "createdBy"] as const, "Deal evaluation") as DealEvaluation;
}

function normalizeDealCase(deal: ApiSchema<"DealDetails">): DealCase {
  const required = requireProperties(
    deal,
    ["id", "ownerId", "saleSubject", "stage", "salesSubject", "nextAction", "createdAt", "parcels", "events"] as const,
    "Deal details",
  );
  return {
    ...required,
    parcels: required.parcels.map(normalizeDealParcel),
    events: required.events.map(normalizeDealEvent),
    latestEvaluation: required.latestEvaluation ? normalizeDealEvaluation(required.latestEvaluation) : null,
  } as DealCase;
}

function normalizeBriefParcel(parcel: ApiSchema<"BriefParcel">): BriefParcel {
  return requireProperties(parcel, ["id", "cadastralCode", "areaHectares", "updatedAt"] as const, "Call brief parcel") as BriefParcel;
}

function normalizeCallBrief(brief: ApiSchema<"CallBrief">): CallBrief {
  const required = requireProperties(
    brief,
    ["ownerId", "ownerName", "ownerStatus", "parcels", "deals"] as const,
    "Call brief",
  );
  return {
    ...required,
    parcels: required.parcels.map(normalizeBriefParcel),
    deals: required.deals.map(normalizeDealCase),
  } as CallBrief;
}

function normalizeEvaluationTask(task: ApiSchema<"EvaluationTask">): EvaluationTask {
  return requireProperties(
    task,
    ["dealId", "ownerId", "ownerName", "saleSubject", "salesSubject", "stage", "parcelCount"] as const,
    "Evaluation task",
  ) as EvaluationTask;
}

// ===================== Deal workflow =====================

export function useCallBrief(ownerId: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: queryKeys.deal.brief(ownerId), enabled: enabled && Boolean(ownerId),
    queryFn: async () => normalizeCallBrief(await apiTransport.GET("/api/v1/deals/owners/{ownerId}/brief", { path: { ownerId } })),
  });
}

export function useOwnerDeals(ownerId: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: queryKeys.deal.owner(ownerId), enabled: enabled && Boolean(ownerId),
    queryFn: async () => (await apiTransport.GET("/api/v1/deals/owners/{ownerId}", { path: { ownerId } })).map(normalizeDealCase),
  });
}

function invalidateDealWorkflow(qc: ReturnType<typeof useQueryClient>, ownerId?: string) {
  for (const queryKey of dealWorkflowInvalidationKeys(ownerId)) {
    void qc.invalidateQueries({ queryKey });
  }
}

export function useCreateDeal(ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (data: CreateDeal) => normalizeDealCase(await apiTransport.POST("/api/v1/deals/owners/{ownerId}", { path: { ownerId }, body: data })),
    onSuccess: () => invalidateDealWorkflow(qc, ownerId),
  });
}

export function useDealEvaluationQueue() {
  const { enabled } = useToken();
  return useQuery({
    queryKey: queryKeys.deal.evaluationQueue, enabled,
    queryFn: async () => (await apiTransport.GET("/api/v1/deals/evaluation-queue")).map(normalizeEvaluationTask),
  });
}

export function useClaimDealEvaluation(dealId: string, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => normalizeDealCase(await apiTransport.POST(
      "/api/v1/deals/{dealId}/evaluation-assignment/claim",
      { path: { dealId } },
    )),
    onSuccess: () => invalidateDealWorkflow(qc, ownerId),
  });
}

export function useUnclaimDealEvaluation(dealId: string, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => normalizeDealCase(await apiTransport.DELETE(
      "/api/v1/deals/{dealId}/evaluation-assignment/claim",
      { path: { dealId } },
    )),
    onSuccess: () => invalidateDealWorkflow(qc, ownerId),
  });
}

export function useAssignDealEvaluator(dealId: string, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (evaluatorSubject?: string) => normalizeDealCase(await apiTransport.PUT(
      "/api/v1/deals/{dealId}/evaluation-assignment",
      { path: { dealId }, body: { evaluatorSubject: evaluatorSubject || undefined } },
    )),
    onSuccess: () => invalidateDealWorkflow(qc, ownerId),
  });
}

export function useSubmitDealEvaluation(dealId?: string, ownerId?: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: SubmitDealEvaluation) => apiTransport.POST("/api/v1/deals/{dealId}/evaluations", { path: { dealId: dealId! }, body: data }),
    onSuccess: () => invalidateDealWorkflow(qc, ownerId),
  });
}
