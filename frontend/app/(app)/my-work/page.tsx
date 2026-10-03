"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowRight, Phone } from "lucide-react";
import {
  useCompleteActivity,
  useDelegateActivity,
  useMyWork,
  useNextAssignedOwner,
  useOwnerStatusDefinitions,
  usePostponeActivity,
  useWorkflowAssignees,
  useWorkQueue,
  type WorkQueueItem,
  type WorkQueueView,
} from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card, CardContent } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog";

function dateTime(value: string) { return new Date(value).toLocaleString("et-EE", { dateStyle: "short", timeStyle: "short" }); }
function toLocalInputValue(value: Date) { const local = new Date(value.getTime() - value.getTimezoneOffset() * 60_000); return local.toISOString().slice(0, 16); }
type ActivityAction = "complete" | "postpone" | "delegate";

export default function MyWorkPage() {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [activityView, setActivityView] = useState<WorkQueueView>("TODAY");
  const [pendingAction, setPendingAction] = useState<{ item: WorkQueueItem; type: ActivityAction } | null>(null);
  const [completionNote, setCompletionNote] = useState("");
  const [postponeDueAt, setPostponeDueAt] = useState("");
  const [actionReason, setActionReason] = useState("");
  const [delegateSubject, setDelegateSubject] = useState("");
  const router = useRouter();
  const { data, isLoading } = useMyWork(search, page, 20);
  const nextOwner = useNextAssignedOwner();
  const { data: statusDefs } = useOwnerStatusDefinitions();
  const { data: queue, isLoading: queueLoading, isError: queueError, error: queueErrorMessage, refetch: refetchQueue } = useWorkQueue(activityView);
  const { data: assignees = [] } = useWorkflowAssignees();
  const completeActivity = useCompleteActivity();
  const postponeActivity = usePostponeActivity();
  const delegateActivity = useDelegateActivity();

  const statusColor = (status: string) => statusDefs?.find(item => item.id === status) ? `#${statusDefs.find(item => item.id === status)?.colorHex}` : undefined;
  const busy = completeActivity.isPending || postponeActivity.isPending || delegateActivity.isPending;
  const queueCounts = queue?.counts ?? { today: 0, nextSevenDays: 0, overdue: 0 };
  const currentError = completeActivity.error ?? postponeActivity.error ?? delegateActivity.error;

  const handleNext = () => nextOwner.mutate(undefined, { onSuccess: (result) => { if (result?.ownerId) router.push(`/owners/${result.ownerId}`); } });
  const openAction = (item: WorkQueueItem, type: ActivityAction) => {
    completeActivity.reset(); postponeActivity.reset(); delegateActivity.reset();
    setCompletionNote(""); setActionReason(""); setDelegateSubject("");
    setPostponeDueAt(toLocalInputValue(new Date(Math.max(Date.now() + 86_400_000, new Date(item.dueTime).getTime() + 86_400_000))));
    setPendingAction({ item, type });
  };
  const closeAction = () => { if (!busy) setPendingAction(null); };
  const confirmAction = () => {
    if (!pendingAction) return;
    const { item, type } = pendingAction;
    if (type === "complete") completeActivity.mutate({ id: item.id, note: completionNote.trim() || undefined }, { onSuccess: () => setPendingAction(null) });
    if (type === "postpone") postponeActivity.mutate({ id: item.id, dueTime: new Date(postponeDueAt).toISOString(), reason: actionReason.trim() }, { onSuccess: () => setPendingAction(null) });
    if (type === "delegate") delegateActivity.mutate({ id: item.id, assigneeSubject: delegateSubject, reason: actionReason.trim() }, { onSuccess: () => setPendingAction(null) });
  };
  const invalidAction = Boolean(pendingAction && ((pendingAction.type === "postpone" && (!postponeDueAt || !actionReason.trim() || new Date(postponeDueAt).getTime() <= Date.now())) || (pendingAction.type === "delegate" && (!delegateSubject || !actionReason.trim()))));
  const actionCopy = pendingAction?.type === "complete" ? { title: "Kas lõpetada tegevus?", label: "Lõpeta tegevus", description: "Lõpetatud tegevus jääb auditi ajalukku ning kaob aktiivsest tööjärjekorrast." } : pendingAction?.type === "postpone" ? { title: "Kas lükata tegevus edasi?", label: "Salvesta uus tähtaeg", description: "Põhjus, varasem tähtaeg ja uus tähtaeg jäävad töövoo auditijälge." } : { title: "Kas delegeerida tegevus?", label: "Delegeeri tegevus", description: "Valitud vastutaja, senine vastutaja ja põhjendus jäävad töövoo auditijälge." };

  return <div className="space-y-6"><div className="flex flex-wrap items-center justify-between gap-3"><div><h1 className="text-2xl font-bold">Minu töölaud</h1><p className="text-sm text-muted-foreground">Sinule määratud tegevused, omanikud ja helistamise töövoog.</p></div><Button onClick={handleNext} disabled={nextOwner.isPending}><ArrowRight className="mr-2 h-4 w-4" />Järgmine omanik</Button></div>
    <Card><CardContent className="space-y-4 pt-4"><div className="flex flex-wrap gap-2"><Button variant={activityView === "TODAY" ? "default" : "outline"} size="sm" onClick={() => setActivityView("TODAY")}>Täna ({queueCounts.today})</Button><Button variant={activityView === "WEEK" ? "default" : "outline"} size="sm" onClick={() => setActivityView("WEEK")}>Järgmised 7 päeva ({queueCounts.nextSevenDays})</Button><Button variant={activityView === "OVERDUE" ? "default" : "outline"} size="sm" onClick={() => setActivityView("OVERDUE")}>Üle tähtaja ({queueCounts.overdue})</Button></div>
      {queueLoading && <p role="status" className="text-sm text-muted-foreground">Tegevuste laadimine…</p>}{queueError && <div className="space-y-2"><p role="alert" className="text-sm text-destructive">Tegevuste laadimine ebaõnnestus. {queueErrorMessage.message}</p><Button variant="outline" size="sm" onClick={() => void refetchQueue()}>Proovi uuesti</Button></div>}{queue && queue.items.length === 0 && <p className="text-sm text-muted-foreground">Selles vaates aktiivseid tegevusi ei ole.</p>}
      <div className="grid gap-3">{queue?.items.map(item => <div key={item.id} className="rounded-lg border p-3"><div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between"><div className="min-w-0 space-y-1"><div className="flex flex-wrap items-center gap-2">{item.ownerId ? <Link href={`/owners/${item.ownerId}`} className="font-semibold text-primary hover:underline">{item.ownerName || "Omanik"}</Link> : <span className="font-semibold">{item.ownerName || "Tegevus"}</span>}<Badge variant="outline">{item.activityType}</Badge>{item.delegated && <Badge variant="secondary">Delegeeritud</Badge>}</div><p className="text-sm"><span className="text-muted-foreground">Tähtaeg:</span> {dateTime(item.dueTime)}</p><p className="text-sm"><span className="text-muted-foreground">Prioriteet:</span> {item.priorityReason}</p>{(item.relatedDeal || item.dealId) && <p className="text-sm"><span className="text-muted-foreground">Tehing:</span> {item.relatedDeal || item.dealId}</p>}{(item.propertyName || item.cadastralCode) && <p className="text-sm"><span className="text-muted-foreground">Kinnistu:</span> {[item.propertyName, item.cadastralCode].filter(Boolean).join(" · ")}</p>}{item.ownerId && <Link href={`/owners/${item.ownerId}/contact`} className="inline-block text-sm text-primary hover:underline">Salvesta kontakt</Link>}</div><div className="flex w-full flex-wrap gap-2 sm:w-auto"><Button size="sm" onClick={() => openAction(item, "complete")} disabled={busy}>Lõpeta</Button><Button size="sm" variant="outline" onClick={() => openAction(item, "postpone")} disabled={busy}>Lükka edasi</Button><Button size="sm" variant="outline" onClick={() => openAction(item, "delegate")} disabled={busy}>Delegeeri</Button></div></div></div>)}</div>
    </CardContent></Card>
    <div className="flex gap-3"><Input placeholder="Otsi nime, telefoni, koodi järgi…" value={search} onChange={event => { setSearch(event.target.value); setPage(0); }} className="max-w-sm" /></div>
    <Card><CardContent className="pt-4">{isLoading && <p className="text-sm text-muted-foreground">Laadimine…</p>}{data && <><Table><TableHeader><TableRow><TableHead>Nimi</TableHead><TableHead>Kood</TableHead><TableHead>Staatus</TableHead><TableHead>Telefon</TableHead><TableHead>Kinnistuid</TableHead><TableHead>Viimati muudetud</TableHead></TableRow></TableHeader><TableBody>{data.content.map(owner => <TableRow key={owner.id}><TableCell><Link href={`/owners/${owner.id}`} className="font-medium text-primary hover:underline">{owner.displayName}</Link></TableCell><TableCell>{owner.personalOrRegistryCode}</TableCell><TableCell><Badge variant="outline" style={statusColor(owner.status) ? { borderColor: statusColor(owner.status), color: statusColor(owner.status) } : undefined}>{owner.status}</Badge></TableCell><TableCell>{owner.phone ? <><Phone className="mr-1 inline h-3 w-3" />{owner.phone}</> : "—"}</TableCell><TableCell>{owner.parcelCount}</TableCell><TableCell>{new Date(owner.updatedAt).toLocaleDateString("et-EE")}</TableCell></TableRow>)}</TableBody></Table>{data.content.length === 0 && <p className="mt-4 text-sm text-muted-foreground">Sinule pole omanikke määratud.</p>}{data.totalPages > 1 && <div className="mt-4 flex items-center gap-2"><Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>Eelmine</Button><span className="text-sm text-muted-foreground">{page + 1} / {data.totalPages}</span><Button variant="outline" size="sm" disabled={page >= data.totalPages - 1} onClick={() => setPage(page + 1)}>Järgmine</Button></div>}</>}</CardContent></Card>
    <ConfirmActionDialog open={Boolean(pendingAction)} title={actionCopy.title} description={pendingAction ? <>{actionCopy.description} Tegevus: <strong>{pendingAction.item.priorityReason || `#${pendingAction.item.id}`}</strong>.</> : ""} confirmLabel={actionCopy.label} pending={busy} confirmDisabled={invalidAction} error={currentError?.message} onConfirm={confirmAction} onCancel={closeAction}>{pendingAction?.type === "complete" && <label className="grid gap-1 text-sm font-medium">Lõpetamise märkus (soovituslik)<textarea className="min-h-20 rounded-md border bg-background p-3 text-sm" value={completionNote} onChange={event => setCompletionNote(event.target.value)} maxLength={1000} /></label>}{pendingAction?.type === "postpone" && <div className="grid gap-3"><label className="grid gap-1 text-sm font-medium">Uus tähtaeg *<Input type="datetime-local" value={postponeDueAt} onChange={event => setPostponeDueAt(event.target.value)} /></label><label className="grid gap-1 text-sm font-medium">Edasilükkamise põhjus *<textarea className="min-h-20 rounded-md border bg-background p-3 text-sm" value={actionReason} onChange={event => setActionReason(event.target.value)} maxLength={1000} /></label>{postponeDueAt && new Date(postponeDueAt).getTime() <= Date.now() && <p className="text-xs text-destructive">Uus tähtaeg peab olema tulevikus.</p>}</div>}{pendingAction?.type === "delegate" && <div className="grid gap-3"><label className="grid gap-1 text-sm font-medium">Uus vastutaja *<select className="h-10 rounded-md border bg-background px-3 text-sm" value={delegateSubject} onChange={event => setDelegateSubject(event.target.value)}><option value="">Vali vastutaja</option>{assignees.filter(user => user.subject !== pendingAction.item.assigneeSubject).map(user => <option key={user.subject} value={user.subject}>{user.name || user.username}</option>)}</select></label><label className="grid gap-1 text-sm font-medium">Delegeerimise põhjus *<textarea className="min-h-20 rounded-md border bg-background p-3 text-sm" value={actionReason} onChange={event => setActionReason(event.target.value)} maxLength={1000} /></label></div>}</ConfirmActionDialog>
  </div>;
}
