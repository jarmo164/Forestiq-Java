"use client";

export * from "@/lib/api/core";
export * from "@/lib/api/owners";
export * from "@/lib/api/owner-360";
export * from "@/lib/api/owner-cursor";
export * from "@/lib/api/ownership-relations";
export * from "@/lib/api/ownership-relations-cursor";
export * from "@/lib/api/sales";
export * from "@/lib/api/workdesk";
export * from "@/lib/api/cadastre";
export * from "@/lib/api/map-search";
export * from "@/lib/api/geospatial";
export * from "@/lib/api/communications";
export * from "@/lib/api/contracts";
export * from "@/lib/api/admin";
export * from "@/lib/api/metsaregister-wfs-admin";
export * from "@/lib/api/registry";
export * from "@/lib/api/inheritance";
export * from "@/lib/api/deals";
export type { CommercialOffer, CommercialDealState, DealLossReason } from "@/lib/api/commercial-deals";
export {
  useDealCommercialState,
  usePrepareDealOffer,
  useSendDealOffer,
  useRecordDealCounterOffer,
  useWinDeal,
  useLoseDeal as useLoseCommercialDeal,
  useCancelDeal,
} from "@/lib/api/commercial-deals";
export * from "@/lib/api/workflow";
export * from "@/lib/api/decision-support";
export * from "@/lib/api/workbasket-handoffs";
export * from "@/lib/api/deal-work";
export * from "@/lib/api/contract-signing";
export * from "@/lib/api/management-analytics";
export * from "@/lib/api/data-quality";