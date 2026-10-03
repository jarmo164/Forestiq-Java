"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiTransport } from "@/lib/api/transport";
import { useToken } from "@/lib/api/core";
import { requireProperties, type RequiredProperties } from "@/lib/api/contract-normalizers";
import type { ApiSchema } from "@/lib/api-contract";
import { dealWorkflowInvalidationKeys } from "@/lib/query-keys";

type GeneratedOffer = RequiredProperties<
  ApiSchema<"OfferEntry">,
  "entryId" | "revision" | "kind" | "amount" | "createdBy" | "createdAt"
>;
export type CommercialOffer = Omit<GeneratedOffer, "kind"> & {
  kind: "SELLER_OFFER" | "CUSTOMER_COUNTEROFFER";
};

type GeneratedCommercialState = RequiredProperties<
  ApiSchema<"CommercialState">,
  "dealId" | "stage" | "nextAction" | "offers"
>;
export type CommercialDealState = Omit<GeneratedCommercialState, "offers"> & {
  offers: CommercialOffer[];
};

export type DealLossReason =
  | "PRICE_GAP"
  | "CUSTOMER_WITHDREW"
  | "COMPETITOR"
  | "TITLE_OR_LEGAL_RISK"
  | "PROPERTY_NOT_SUITABLE"
  | "NO_RESPONSE"
  | "OTHER";

type PrepareOffer = ApiSchema<"PrepareOfferRequest">;
type CounterOffer = ApiSchema<"CounterOfferRequest">;
type WinDeal = ApiSchema<"WinDealRequest">;
type LoseDeal = Omit<ApiSchema<"CommercialLoseDealRequest">, "lossReason"> & { lossReason: DealLossReason };
type CancelDeal = ApiSchema<"CancelDealRequest">;

function normalizeOffer(offer: ApiSchema<"OfferEntry">): CommercialOffer {
  const required = requireProperties(
    offer,
    ["entryId", "revision", "kind", "amount", "createdBy", "createdAt"] as const,
    "Commercial offer",
  );
  if (required.kind !== "SELLER_OFFER" && required.kind !== "CUSTOMER_COUNTEROFFER") {
    throw new Error(`Commercial offer has unsupported kind "${required.kind}".`);
  }
  return { ...required, kind: required.kind };
}

function normalizeCommercialState(state: ApiSchema<"CommercialState">): CommercialDealState {
  const required = requireProperties(state, ["dealId", "stage", "nextAction", "offers"] as const, "Commercial state");
  return {
    ...required,
    offers: required.offers.map(normalizeOffer),
  };
}

function invalidateCommercialWorkflow(qc: ReturnType<typeof useQueryClient>, dealId: string, ownerId: string) {
  void qc.invalidateQueries({ queryKey: ["deal", "commercial", dealId] });
  for (const queryKey of dealWorkflowInvalidationKeys(ownerId)) {
    void qc.invalidateQueries({ queryKey });
  }
}

export function useDealCommercialState(dealId: string) {
  const { enabled } = useToken();
  return useQuery({
    queryKey: ["deal", "commercial", dealId],
    enabled: enabled && Boolean(dealId),
    queryFn: async () => normalizeCommercialState(
      await apiTransport.GET("/api/v1/deals/{dealId}/commercial", { path: { dealId } }),
    ),
  });
}

export function usePrepareDealOffer(dealId: string, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: PrepareOffer) => normalizeCommercialState(
      await apiTransport.POST("/api/v1/deals/{dealId}/commercial/offers", { path: { dealId }, body }),
    ),
    onSuccess: () => invalidateCommercialWorkflow(qc, dealId, ownerId),
  });
}

export function useSendDealOffer(dealId: string, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async () => normalizeCommercialState(
      await apiTransport.POST("/api/v1/deals/{dealId}/commercial/offers/send", { path: { dealId } }),
    ),
    onSuccess: () => invalidateCommercialWorkflow(qc, dealId, ownerId),
  });
}

export function useRecordDealCounterOffer(dealId: string, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CounterOffer) => normalizeCommercialState(
      await apiTransport.POST("/api/v1/deals/{dealId}/commercial/counteroffers", { path: { dealId }, body }),
    ),
    onSuccess: () => invalidateCommercialWorkflow(qc, dealId, ownerId),
  });
}

export function useWinDeal(dealId: string, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: WinDeal) => normalizeCommercialState(
      await apiTransport.POST("/api/v1/deals/{dealId}/commercial/won", { path: { dealId }, body }),
    ),
    onSuccess: () => invalidateCommercialWorkflow(qc, dealId, ownerId),
  });
}

export function useLoseDeal(dealId: string, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: LoseDeal) => normalizeCommercialState(
      await apiTransport.POST("/api/v1/deals/{dealId}/commercial/lost", { path: { dealId }, body }),
    ),
    onSuccess: () => invalidateCommercialWorkflow(qc, dealId, ownerId),
  });
}

export function useCancelDeal(dealId: string, ownerId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CancelDeal) => normalizeCommercialState(
      await apiTransport.POST("/api/v1/deals/{dealId}/commercial/cancelled", { path: { dealId }, body }),
    ),
    onSuccess: () => invalidateCommercialWorkflow(qc, dealId, ownerId),
  });
}
