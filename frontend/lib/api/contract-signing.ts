"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, apiMultipartFetch, useToken } from "@/lib/api/core";

export type ContractSigningStatus = "PREPARING" | "SENT_FOR_SIGNATURE" | "SIGNED" | "CANCELLED";

export type SigningState = {
  contractId: string; dealId: string; versionNo: number; status: ContractSigningStatus;
  responsibleSubject: string; dueAt: string; statusAt: string; stallReason?: string | null;
  hasSignedDocument: boolean; hasSignedExternalReference: boolean; overdue: boolean;
  updatedBy: string; updatedAt: string;
};
export type SigningWorkItem = {
  contractId: string; dealId: string; versionNo: number; ownerId: string; ownerName: string;
  status: ContractSigningStatus; responsibleSubject: string; dueAt: string; statusAt: string;
  stallReason?: string | null; overdue: boolean; salesSubject: string; updatedAt: string;
};
export type SigningEvent = {
  id: string; contractId: string; dealId: string; eventType: string; fromStatus?: string | null;
  toStatus: string; effectiveAt: string; responsibleSubject: string; dueAt: string;
  reason?: string | null; referenceKind?: string | null; createdBy: string; createdAt: string;
};
export type SigningTransition = {
  status: ContractSigningStatus; responsibleSubject: string; dueAt: string; effectiveAt?: string;
  reason?: string; signedDocumentId?: string; signedExternalUrl?: string;
};
export type UploadedSignedFile = { documentId: string; fileName: string; contentType: string; byteSize: number };

export function useActiveContractSigning() {
  const { accessToken, enabled } = useToken();
  return useQuery({ queryKey: ["contract-signing", "active"], enabled, queryFn: () => apiFetch<SigningWorkItem[]>("/v1/contract-signing/active", accessToken) });
}
export function useDealSigningStates(dealId?: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({ queryKey: ["contract-signing", "deal", dealId], enabled: enabled && Boolean(dealId), queryFn: () => apiFetch<SigningState[]>(`/v1/contract-signing/deals/${dealId}`, accessToken) });
}
export function useContractSigningState(contractId: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({ queryKey: ["contract-signing", "contract", contractId], enabled: enabled && Boolean(contractId), queryFn: () => apiFetch<SigningState>(`/v1/contract-signing/contracts/${contractId}`, accessToken) });
}
export function useContractSigningHistory(contractId: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({ queryKey: ["contract-signing", "history", contractId], enabled: enabled && Boolean(contractId), queryFn: () => apiFetch<SigningEvent[]>(`/v1/contract-signing/contracts/${contractId}/history`, accessToken) });
}
export function useUploadSignedContractFile() {
  const { accessToken } = useToken();
  return useMutation({ mutationFn: ({ contractId, file }: { contractId: string; file: File }) => { const body = new FormData(); body.append("file", file); return apiMultipartFetch<UploadedSignedFile>(`/v1/contract-signing/contracts/${contractId}/signed-file`, accessToken, body); } });
}
export function useUpdateContractSigning() {
  const { accessToken } = useToken(); const qc = useQueryClient();
  return useMutation({ mutationFn: ({ contractId, data }: { contractId: string; data: SigningTransition }) => apiFetch<SigningState>(`/v1/contract-signing/contracts/${contractId}`, accessToken, { method: "PUT", body: JSON.stringify(data) }), onSuccess: (state) => { void qc.invalidateQueries({ queryKey: ["contract-signing"] }); void qc.invalidateQueries({ queryKey: ["contract-signing", "contract", state.contractId] }); } });
}
