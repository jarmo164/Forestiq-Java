"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiBaseUrl, apiFetch, useToken } from "@/lib/api/core";
import type { ContractHistoryItem, CompanyProfile, ContractTemplate, SaveCompanyProfile, SaveContractTemplate, GeneratedContract } from "@/lib/types";

export type ContractHistoryWithSource = ContractHistoryItem & {
  dealId?: string;
  offerEntryId?: string;
};

export type DealContractDraft = {
  dealId: string;
  offerEntryId: string;
  acceptedPrice: number;
  acceptedTerms?: string;
  seller: { name: string; code?: string; address?: string; iban?: string };
  parcels: { cadastralCode: string; address?: string; areaHectares?: number }[];
};

export type ContractChecklist = {
  partiesConfirmed: boolean;
  parcelsConfirmed: boolean;
  priceConfirmed: boolean;
  ibanConfirmed: boolean;
  acceptedOfferConfirmed: boolean;
  templateConfirmed: boolean;
  exceptionReason?: string;
};

export type ContractVersionSummary = {
  contractId: string;
  dealId: string;
  versionNo: number;
  replacesContractId?: string | null;
  changeReason?: string | null;
  checklist: ContractChecklist;
  templateId: string;
  templateSha256: string;
  createdBy: string;
  createdAt: string;
};

// ===================== Contracts =====================

export function useContractHistory(filter: { cadastre?: string; buyer?: string; seller?: string; offset?: number } = {}) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["contracts", "history", filter],
    enabled,
    queryFn: () => apiFetch<ContractHistoryWithSource[]>("/v1/contracts/history/search", accessToken, {
      method: "POST", body: JSON.stringify({ ...filter, offset: filter.offset ?? 0 }),
    }),
  });
}

export function useDealContractDraft(dealId?: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["contracts", "deal-draft", dealId],
    enabled: enabled && Boolean(dealId),
    queryFn: () => apiFetch<DealContractDraft>(`/v1/contracts/deals/${dealId}/draft`, accessToken),
  });
}

export function useDealContractVersions(dealId?: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["contracts", "versions", dealId],
    enabled: enabled && Boolean(dealId),
    queryFn: () => apiFetch<ContractVersionSummary[]>(`/v1/contracts/deals/${dealId}/versions`, accessToken),
  });
}

// ===================== Contract configuration =====================

export function useCompanyProfiles() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["contract-configuration", "companies"],
    enabled,
    queryFn: () => apiFetch<CompanyProfile[]>("/v1/contract-configuration/companies", accessToken),
  });
}

export function useSaveCompanyProfile() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id?: string; data: SaveCompanyProfile }) => apiFetch<CompanyProfile>(
      id ? `/v1/contract-configuration/companies/${id}` : "/v1/contract-configuration/companies", accessToken,
      { method: id ? "PUT" : "POST", body: JSON.stringify(data) },
    ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["contract-configuration", "companies"] }),
  });
}

export function useContractTemplates() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["contract-configuration", "templates"],
    enabled,
    queryFn: () => apiFetch<ContractTemplate[]>("/v1/contract-configuration/templates", accessToken),
  });
}

export function useContractTemplatePlaceholders() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["contract-configuration", "placeholders"],
    enabled,
    queryFn: () => apiFetch<{ placeholders: string[] }>("/v1/contract-configuration/templates/placeholders", accessToken),
  });
}

export function useSaveContractTemplate() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, data }: { id?: string; data: SaveContractTemplate }) => apiFetch<ContractTemplate>(
      id ? `/v1/contract-configuration/templates/${id}` : "/v1/contract-configuration/templates", accessToken,
      { method: id ? "PUT" : "POST", body: JSON.stringify(data) },
    ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["contract-configuration", "templates"] }),
  });
}

export type GenerateContractPayload = {
  templateId?: string;
  companyProfileId?: string;
  contractNumber: string;
  contractDate?: string;
  place?: string;
  price?: number;
  terms?: string;
  seller: { name: string; code?: string; address?: string; iban?: string };
  parcels: { cadastralCode: string; address?: string; areaHectares?: number }[];
  dealId?: string;
  offerEntryId?: string;
  checklist: ContractChecklist;
  changeReason?: string;
  replacesContractId?: string;
};

export type GeneratedContractWithSource = GeneratedContract & {
  dealId?: string;
  offerEntryId?: string;
  versionNo: number;
};

export function useGenerateContract() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: GenerateContractPayload) => apiFetch<GeneratedContractWithSource>("/v1/contracts/generate", accessToken, { method: "POST", body: JSON.stringify(data) }),
    onSuccess: (_, data) => {
      void qc.invalidateQueries({ queryKey: ["contracts", "history"] });
      if (data.dealId) void qc.invalidateQueries({ queryKey: ["contracts", "versions", data.dealId] });
    },
  });
}

export function useDownloadContract() {
  const { accessToken } = useToken();
  return useMutation({
    mutationFn: async ({ contractId, fileName }: { contractId: string; fileName: string }) => {
      const response = await fetch(`${apiBaseUrl}/v1/contracts/${contractId}/download`, {
        headers: { ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}) },
      });
      if (!response.ok) throw new Error(`Lepingu allalaadimine ebaõnnestus (${response.status})`);
      const url = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = fileName;
      anchor.click();
      URL.revokeObjectURL(url);
    },
  });
}
