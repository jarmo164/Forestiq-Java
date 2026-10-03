"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, useToken } from "@/lib/api/core";
import type { Reminder, Message, PhonebookEntry } from "@/lib/types";

// ===================== Reminders =====================

export function useReminders() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["reminders"],
    enabled,
    queryFn: () => apiFetch<Reminder[]>("/v1/reminders", accessToken),
  });
}

export function useReminderDashboard() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["reminders", "dashboard"],
    enabled,
    refetchInterval: 60_000,
    queryFn: () => apiFetch<Reminder[]>("/v1/reminders/dashboard", accessToken),
  });
}

export function useCreateReminder() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: { text: string; dueTime: string; ownerId?: string; cadastralCode?: string }) =>
      apiFetch<void>("/v1/reminders", accessToken, { method: "POST", body: JSON.stringify(data) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["reminders"] }),
  });
}

export function useDeleteReminder() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => apiFetch<void>(`/v1/reminders/${id}`, accessToken, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["reminders"] }),
  });
}

export function useUpdateReminder() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, text, dueTime }: { id: number; text: string; dueTime: string }) =>
      apiFetch<void>(`/v1/reminders/${id}`, accessToken, { method: "PATCH", body: JSON.stringify({ text, dueTime }) }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ["reminders"] });
      void qc.invalidateQueries({ queryKey: ["workflow"] });
    },
  });
}

// ===================== Messages =====================

export function useReceivedMessages(page = 0, size = 20) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["messages", "received", page, size],
    enabled,
    queryFn: () => apiFetch<Message[]>(`/v1/messages/received?page=${page}&size=${size}`, accessToken),
  });
}

export function useSentMessages(page = 0, size = 20) {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["messages", "sent", page, size],
    enabled,
    queryFn: () => apiFetch<Message[]>(`/v1/messages/sent?page=${page}&size=${size}`, accessToken),
  });
}

export function useUnnoticedCount() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["messages", "unnoticed"],
    enabled,
    refetchInterval: 30_000,
    queryFn: () => apiFetch<{ count: number }>("/v1/messages/unnoticed-count", accessToken),
  });
}

export function useSendMessage() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: { recipient: string; message: string }) =>
      apiFetch<void>("/v1/messages", accessToken, { method: "POST", body: JSON.stringify(data) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["messages"] }),
  });
}

export function useMarkMessagesNoticed() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation<void, Error, string | undefined>({
    mutationFn: (upTo) =>
      apiFetch<void>("/v1/messages/mark-noticed", accessToken, { method: "POST", body: JSON.stringify({ upTo: upTo ?? new Date().toISOString() }) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["messages"] }),
  });
}

export function useMessageRecipients() {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["messages", "recipients"],
    enabled,
    queryFn: () => apiFetch<{ id: string; name: string }[]>("/v1/messages/recipients", accessToken),
  });
}

// ===================== Phonebook =====================

export function usePhonebook(name = "", phone = "", code = "") {
  const { accessToken, enabled } = useToken();
  return useQuery({
    queryKey: ["phonebook", name, phone, code],
    enabled,
    queryFn: () => apiFetch<PhonebookEntry[]>(`/v1/phonebook?name=${encodeURIComponent(name)}&phone=${encodeURIComponent(phone)}&code=${encodeURIComponent(code)}`, accessToken),
  });
}

export function useCreatePhonebookEntry() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (data: { code?: string; name?: string; phone?: string; address?: string }) =>
      apiFetch<void>("/v1/phonebook", accessToken, { method: "POST", body: JSON.stringify(data) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["phonebook"] }),
  });
}

export function useDeletePhonebookEntry() {
  const { accessToken } = useToken();
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => apiFetch<void>(`/v1/phonebook/${id}`, accessToken, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["phonebook"] }),
  });
}
