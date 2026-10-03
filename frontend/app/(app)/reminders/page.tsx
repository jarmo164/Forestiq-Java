"use client";

import { useState } from "react";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { useReminders, useCreateReminder, useDeleteReminder, useUpdateReminder } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog";
import type { Reminder } from "@/lib/types";

function toLocalInputValue(iso: string) {
  const date = new Date(iso);
  const offsetDate = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
  return offsetDate.toISOString().slice(0, 16);
}

type EditingReminder = { id: number; text: string; dueTime: string };

export default function RemindersPage() {
  const { data: reminders, isLoading, isError, error, refetch, isFetching } = useReminders();
  const createReminder = useCreateReminder();
  const updateReminder = useUpdateReminder();
  const deleteReminder = useDeleteReminder();
  const [text, setText] = useState("");
  const [dueTime, setDueTime] = useState("");
  const [editing, setEditing] = useState<EditingReminder | null>(null);
  const [deleting, setDeleting] = useState<Reminder | null>(null);
  const [feedback, setFeedback] = useState("");

  const handleCreate = () => {
    if (!text.trim() || !dueTime) return;
    setFeedback("");
    createReminder.mutate({ text: text.trim(), dueTime: new Date(dueTime).toISOString() }, {
      onSuccess: () => { setText(""); setDueTime(""); setFeedback("Meeldetuletus lisati sinu tööjärjekorda."); },
    });
  };
  const saveEdit = () => {
    if (!editing?.text.trim() || !editing.dueTime) return;
    setFeedback("");
    updateReminder.mutate({ id: editing.id, text: editing.text.trim(), dueTime: new Date(editing.dueTime).toISOString() }, {
      onSuccess: () => { setEditing(null); setFeedback("Meeldetuletus uuendati. Muudatus salvestati auditijälge."); },
    });
  };
  const confirmDelete = () => {
    if (!deleting) return;
    deleteReminder.mutate(deleting.id, { onSuccess: () => { setDeleting(null); setFeedback("Meeldetuletus kustutati."); } });
  };

  return <div className="space-y-6"><div><h1 className="text-2xl font-bold">Meeldetuletused</h1><p className="mt-1 text-sm text-muted-foreground">Hoia kliendi- ja kinnistutegevused selgete tähtaegadega. Muudatused jäävad auditijälge.</p></div>
    {feedback && <p role="status" className="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">{feedback}</p>}
    <Card><CardHeader><CardTitle>Uus meeldetuletus</CardTitle></CardHeader><CardContent className="space-y-3"><div className="flex flex-wrap gap-3"><Input aria-label="Meeldetuletuse tekst" placeholder="Näiteks: helista kliendile tagasi" value={text} onChange={event => setText(event.target.value)} className="flex-1 min-w-[200px]" maxLength={500} /><Input aria-label="Meeldetuletuse tähtaeg" type="datetime-local" value={dueTime} onChange={event => setDueTime(event.target.value)} className="w-56" /><Button onClick={handleCreate} disabled={createReminder.isPending || !text.trim() || !dueTime} aria-busy={createReminder.isPending}><Plus className="mr-2 h-4 w-4" />{createReminder.isPending ? "Lisan…" : "Lisa"}</Button></div>{createReminder.isError && <p role="alert" className="text-sm text-destructive">{createReminder.error.message}</p>}</CardContent></Card>
    {isLoading && <p role="status" className="text-sm text-muted-foreground">Laadin meeldetuletusi…</p>}
    {isError && <Card><CardContent className="space-y-3 p-5"><p role="alert" className="text-sm text-destructive">Meeldetuletusi ei saanud laadida. {error.message}</p><Button variant="outline" onClick={() => void refetch()} disabled={isFetching}>{isFetching ? "Laadin…" : "Proovi uuesti"}</Button></CardContent></Card>}
    {!isError && <div className="space-y-2">{reminders?.map(reminder => {
      const isEditing = editing?.id === reminder.id;
      return <Card key={reminder.id}><CardContent className="py-3">{isEditing ? <div className="space-y-3"><div className="grid gap-3 md:grid-cols-[1fr_14rem]"><Input aria-label="Muudetud meeldetuletuse tekst" value={editing.text} onChange={event => setEditing({ ...editing, text: event.target.value })} maxLength={500} /><Input aria-label="Muudetud meeldetuletuse tähtaeg" type="datetime-local" value={editing.dueTime} onChange={event => setEditing({ ...editing, dueTime: event.target.value })} /></div><div className="flex gap-2"><Button size="sm" onClick={saveEdit} disabled={updateReminder.isPending || !editing.text.trim() || !editing.dueTime}>{updateReminder.isPending ? "Salvestan…" : "Salvesta"}</Button><Button size="sm" variant="ghost" onClick={() => { setEditing(null); updateReminder.reset(); }} disabled={updateReminder.isPending}>Tühista</Button></div>{updateReminder.isError && <p role="alert" className="text-sm text-destructive">{updateReminder.error.message}</p>}</div> : <div className="flex flex-wrap items-center justify-between gap-3"><div><p className="font-medium">{reminder.text}</p><p className="text-xs text-muted-foreground">Tähtaeg: {new Date(reminder.dueTime).toLocaleString("et-EE")}{reminder.ownerName && ` · Omanik: ${reminder.ownerName}`}{reminder.cadastralCode && ` · Kinnistu: ${reminder.cadastralCode}`}</p></div><div className="flex gap-1"><Button variant="ghost" size="sm" onClick={() => { setEditing({ id: reminder.id, text: reminder.text, dueTime: toLocalInputValue(reminder.dueTime) }); updateReminder.reset(); }}><Pencil className="mr-1 h-4 w-4" />Muuda</Button><Button variant="ghost" size="sm" className="text-destructive" onClick={() => { setDeleting(reminder); deleteReminder.reset(); }}><Trash2 className="mr-1 h-4 w-4" />Kustuta</Button></div></div>}</CardContent></Card>;
    })}{reminders && reminders.length === 0 && <Card><CardContent className="p-5 text-sm text-muted-foreground">Meeldetuletusi pole. Lisa esimene tegevus, et järgmine samm ei jääks juhuse hooleks.</CardContent></Card>}</div>}
    <ConfirmActionDialog open={Boolean(deleting)} title="Kas kustutada meeldetuletus?" description={deleting ? <>Kustutad tegevuse <strong>{deleting.text}</strong>. Seda toimingut ei saa tagasi võtta.</> : ""} confirmLabel="Kustuta" destructive pending={deleteReminder.isPending} error={deleteReminder.isError ? deleteReminder.error.message : undefined} onConfirm={confirmDelete} onCancel={() => { if (!deleteReminder.isPending) { setDeleting(null); deleteReminder.reset(); } }} />
  </div>;
}
