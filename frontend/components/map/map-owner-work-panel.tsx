"use client";

import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { CalendarClock, MessageSquarePlus, RefreshCw } from "lucide-react";
import {
  useAddOwnerNote,
  useChangeStatus,
  useCreateReminder,
  useOwner,
} from "@/lib/api";
import { useMapOwnerStatusDefinitions } from "@/lib/api/map-cadastre";
import { useAuth } from "@/lib/auth";
import { DealCasePanel } from "@/components/owners/deal-case-panel";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

const STATUS_LABELS: Record<string, string> = {
  NEW: "Uus",
  CONTACTED: "Kontakteeritud",
  INTERESTED: "Huvitatud",
  NOT_INTERESTED: "Ei huvita",
  EVALUATION: "Hindamisel",
  WAITS_FOR_EVALUATION: "Ootab hindamist",
  VALUED: "Hinnastatud",
  NEGOTIATION: "Läbirääkimised",
  ASSIGNED: "Määratud",
  CONTRACTED: "Leping",
  DECEASED: "Surnud – pärimismenetlus",
};

const WORKFLOW_MANAGED_STATUSES = new Set(["EVALUATION", "WAITS_FOR_EVALUATION"]);

type Props = {
  ownerId: string;
  cadastralCode: string;
  onMapDataChanged?: () => void;
};

export function MapOwnerWorkPanel({ ownerId, cadastralCode, onMapDataChanged }: Props) {
  const { hasRole } = useAuth();
  const canWork = hasRole("ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "CALLER");
  const owner = useOwner(ownerId);
  const statusDefinitions = useMapOwnerStatusDefinitions();
  const changeStatus = useChangeStatus(ownerId);
  const addNote = useAddOwnerNote(ownerId);
  const createReminder = useCreateReminder();
  const queryClient = useQueryClient();

  const [newStatus, setNewStatus] = useState("");
  const [statusComment, setStatusComment] = useState("");
  const [note, setNote] = useState("");
  const [reminderText, setReminderText] = useState("");
  const [reminderDue, setReminderDue] = useState("");
  const [message, setMessage] = useState<string>();

  if (!canWork) return null;
  if (owner.isLoading) {
    return <section className="rounded-lg border bg-slate-50/70 p-3 text-sm text-muted-foreground">Laadin CRM tööriistu...</section>;
  }
  if (owner.isError || !owner.data) {
    return <section className="rounded-lg border bg-slate-50/70 p-3 text-sm text-destructive">CRM tööriistade laadimine ebaõnnestus.</section>;
  }

  const data = owner.data;
  const statusOptions = statusDefinitions.data?.filter(status => !WORKFLOW_MANAGED_STATUSES.has(status.id)) ?? [];
  const refreshMapOwnerState = () => {
    void queryClient.invalidateQueries({ queryKey: ["map", "parcel-summary", cadastralCode] });
    void queryClient.invalidateQueries({ queryKey: ["owner", ownerId] });
    onMapDataChanged?.();
  };

  const saveStatus = () => {
    if (!newStatus) return;
    setMessage(undefined);
    changeStatus.mutate(
      { status: newStatus, comment: statusComment.trim() || undefined, version: data.version },
      {
        onSuccess: () => {
          setNewStatus("");
          setStatusComment("");
          setMessage("Staatus uuendatud.");
          refreshMapOwnerState();
        },
        onError: error => setMessage(error.message),
      },
    );
  };

  const saveNote = () => {
    if (!note.trim()) return;
    setMessage(undefined);
    addNote.mutate(
      { content: note.trim() },
      {
        onSuccess: () => {
          setNote("");
          setMessage("Märkus lisatud.");
        },
        onError: error => setMessage(error.message),
      },
    );
  };

  const saveReminder = () => {
    if (!reminderText.trim() || !reminderDue) return;
    const due = new Date(reminderDue);
    if (Number.isNaN(due.getTime())) {
      setMessage("Meeldetuletuse aeg ei ole korrektne.");
      return;
    }
    setMessage(undefined);
    createReminder.mutate(
      { text: reminderText.trim(), dueTime: due.toISOString(), ownerId, cadastralCode },
      {
        onSuccess: () => {
          setReminderText("");
          setReminderDue("");
          setMessage("Meeldetuletus loodud.");
        },
        onError: error => setMessage(error.message),
      },
    );
  };

  return (
    <div className="space-y-3">
      <section className="rounded-lg border bg-slate-50/70 p-3">
        <div className="mb-3 flex items-center justify-between gap-3">
          <div>
            <h3 className="text-sm font-semibold">CRM töö</h3>
            <p className="text-xs text-muted-foreground">{data.displayName} · {STATUS_LABELS[data.status] ?? data.status}</p>
          </div>
          {(changeStatus.isPending || addNote.isPending || createReminder.isPending) && <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />}
        </div>

        <details className="rounded-md border bg-white p-2.5" open>
          <summary className="cursor-pointer text-sm font-medium">Muuda staatust</summary>
          <div className="mt-3 space-y-2">
            <select
              aria-label="Uus omaniku staatus"
              className="h-9 w-full rounded-md border border-input bg-white px-3 text-sm"
              value={newStatus}
              onChange={event => setNewStatus(event.target.value)}
            >
              <option value="">Vali staatus...</option>
              {statusOptions.map(status => (
                <option key={status.id} value={status.id}>{STATUS_LABELS[status.id] ?? status.id}</option>
              ))}
            </select>
            {statusDefinitions.isError && <p className="text-xs text-destructive">Staatuste laadimine ebaõnnestus.</p>}
            <Input aria-label="Staatuse kommentaar" value={statusComment} onChange={event => setStatusComment(event.target.value)} placeholder="Valikuline kommentaar" />
            <Button size="sm" onClick={saveStatus} disabled={!newStatus || changeStatus.isPending}>Salvesta staatus</Button>
            <p className="text-[11px] text-muted-foreground">Hindamise olekud tekivad tehingujuhtumi kvalifitseerimise kaudu.</p>
          </div>
        </details>

        <details className="mt-2 rounded-md border bg-white p-2.5">
          <summary className="cursor-pointer text-sm font-medium">Lisa märkus</summary>
          <div className="mt-3 flex gap-2">
            <Input aria-label="CRM märkus" value={note} onChange={event => setNote(event.target.value)} placeholder="Kõne või kokkuleppe märkus" onKeyDown={event => { if (event.key === "Enter") saveNote(); }} />
            <Button size="sm" aria-label="Salvesta CRM märkus" onClick={saveNote} disabled={!note.trim() || addNote.isPending}><MessageSquarePlus className="h-4 w-4" /></Button>
          </div>
        </details>

        <details className="mt-2 rounded-md border bg-white p-2.5">
          <summary className="cursor-pointer text-sm font-medium">Lisa järeltegevus</summary>
          <div className="mt-3 space-y-2">
            <Input aria-label="Meeldetuletuse tekst" value={reminderText} onChange={event => setReminderText(event.target.value)} placeholder="Näiteks: helista hinnapakkumise kohta" />
            <Input aria-label="Meeldetuletuse aeg" type="datetime-local" value={reminderDue} onChange={event => setReminderDue(event.target.value)} />
            <Button size="sm" onClick={saveReminder} disabled={!reminderText.trim() || !reminderDue || createReminder.isPending}><CalendarClock className="mr-2 h-4 w-4" />Loo meeldetuletus</Button>
          </div>
        </details>

        {message && <p className="mt-2 text-xs text-muted-foreground" role="status">{message}</p>}
      </section>

      <DealCasePanel ownerId={ownerId} ownerStatus={data.status} onOwnerWorkflowChanged={refreshMapOwnerState} />
    </div>
  );
}
