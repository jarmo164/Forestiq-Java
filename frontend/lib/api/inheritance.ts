"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";
import type { ApiSchema } from "@/lib/api-contract";

export interface InheritanceCaseListItem {
  id: string;
  ownerId: string;
  ownerName: string;
  ownerCodeMasked: string;
  noticeNumber?: string;
  announcementDate?: string;
  certificationDeadline?: string;
  status: string;
  assignedToSubject?: string;
  heirCount: number;
  contactedHeirCount: number;
  updatedAt: string;
}

export interface InheritanceCasePage {
  items: InheritanceCaseListItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface InheritanceCaseWorkspace {
  id: string;
  ownerId: string;
  ownerName: string;
  ownerCodeMasked: string;
  assignedToSubject?: string;
  inheritanceCase: InheritanceCase;
}

export interface InheritanceNoticeListItem {
  noticeNumber: string;
  feedDate: string;
  announcementDate?: string;
  sourceUrl?: string;
  deathDate?: string;
  certificationDeadline?: string;
  notaryName?: string;
  notaryPhone?: string;
  hasPersonalCode: boolean;
  ownerMatched: boolean;
  imported: boolean;
}

type InheritanceWorkspace = ApiSchema<"InheritanceWorkspace">;
type InheritanceCase = ApiSchema<"InheritanceCaseDetails">;
type SaveInheritanceHeir = ApiSchema<"SaveInheritanceHeirRequest">;
type StartInheritanceCase = ApiSchema<"StartInheritanceCaseRequest">;
type OfficialInheritanceNoticeCheck = ApiSchema<"OfficialNoticeCheckResult">;
type ChangeInheritanceStatus = ApiSchema<"ChangeInheritanceStatusRequest">;
type AddInheritanceNote = ApiSchema<"AddInheritanceNoteRequest">;

export function useInheritanceWorkspace(ownerId: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["inheritance", ownerId],
    enabled: enabled && Boolean(ownerId),
    queryFn: () => apiFetch<InheritanceWorkspace>(`/v1/inheritance/owners/${ownerId}`, accessToken),
  });
}

export function useInheritanceCases(params: {
  query?: string; status?: string; assignedTo?: string; overdue?: boolean; page?: number; size?: number;
}) {
  const { accessToken, enabled } = useToken();
  const search = new URLSearchParams();
  if (params.query) search.set("query", params.query);
  if (params.status) search.set("status", params.status);
  if (params.assignedTo) search.set("assignedTo", params.assignedTo);
  if (params.overdue) search.set("overdue", "true");
  search.set("page", String(params.page ?? 0));
  search.set("size", String(params.size ?? 25));
  return useQuery({
    queryKey: ["inheritance", "cases", params],
    enabled,
    queryFn: () => apiFetch<InheritanceCasePage>(`/v1/inheritance/cases?${search.toString()}`, accessToken),
  });
}

export function useInheritanceNotices(limit = 100) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["inheritance", "notices", limit],
    enabled,
    queryFn: () => apiFetch<InheritanceNoticeListItem[]>(`/v1/inheritance/notices?limit=${limit}`, accessToken),
  });
}

export function useInheritanceCaseWorkspace(caseId: string) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["inheritance", "case", caseId],
    enabled: enabled && Boolean(caseId),
    queryFn: () => apiFetch<InheritanceCaseWorkspace>(`/v1/inheritance/cases/${caseId}`, accessToken),
  });
}

export function useAssignInheritanceCase(caseId: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (assignedToSubject?: string) => apiFetch<InheritanceCaseWorkspace>(
      `/v1/inheritance/cases/${caseId}/assignment`, accessToken,
      { method: "PATCH", body: JSON.stringify({ assignedToSubject: assignedToSubject || null }) },
    ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["inheritance", "case", caseId] });
      qc.invalidateQueries({ queryKey: ["inheritance", "cases"] });
    },
  });
}

export function useCheckOfficialInheritanceNotices(ownerId: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch<OfficialInheritanceNoticeCheck>(`/v1/inheritance/owners/${ownerId}/official-notices/check`, accessToken, { method: "POST" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["inheritance", ownerId] });
      qc.invalidateQueries({ queryKey: ["owners", ownerId] });
      qc.invalidateQueries({ queryKey: ["owners"] });
    },
  });
}

export function useStartInheritanceCase(ownerId: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: StartInheritanceCase) => apiFetch<InheritanceCase>(`/v1/inheritance/owners/${ownerId}`, accessToken, { method: "POST", body: JSON.stringify(data) }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["inheritance", ownerId] });
      qc.invalidateQueries({ queryKey: ["owners", ownerId] });
    },
  });
}

export function useImportOfficialInheritanceNotice(ownerId: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (noticeNumber: string) => apiFetch<InheritanceCase>(`/v1/inheritance/owners/${ownerId}/official-notices/${encodeURIComponent(noticeNumber)}/import`, accessToken, { method: "POST" }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["inheritance", ownerId] });
      qc.invalidateQueries({ queryKey: ["owners", ownerId] });
    },
  });
}

export function useChangeInheritanceStatus(ownerId: string, caseId?: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: ChangeInheritanceStatus) => apiFetch<InheritanceCase>(`/v1/inheritance/cases/${caseId}/status`, accessToken, { method: "PATCH", body: JSON.stringify(data) }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["inheritance", ownerId] });
      qc.invalidateQueries({ queryKey: ["inheritance", "case", caseId] });
      qc.invalidateQueries({ queryKey: ["inheritance", "cases"] });
    },
  });
}

export function useSaveInheritanceHeir(ownerId: string, caseId?: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ heirId, data }: { heirId?: string; data: SaveInheritanceHeir }) => apiFetch<InheritanceCase>(
      heirId ? `/v1/inheritance/cases/${caseId}/heirs/${heirId}` : `/v1/inheritance/cases/${caseId}/heirs`, accessToken,
      { method: heirId ? "PUT" : "POST", body: JSON.stringify(data) },
    ),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["inheritance", ownerId] }),
  });
}

export function useAddInheritanceNote(ownerId: string, caseId?: string) {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (content: string) => {
      const data: AddInheritanceNote = { content };
      return apiFetch<InheritanceCase>(`/v1/inheritance/cases/${caseId}/events`, accessToken, { method: "POST", body: JSON.stringify(data) });
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["inheritance", ownerId] }),
  });
}
