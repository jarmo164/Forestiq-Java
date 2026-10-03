"use client";

import Link from "next/link";
import { useState } from "react";
import { ArrowRight, CalendarClock, CheckCircle2, CircleAlert, Phone, Search, Sparkles, UserRound } from "lucide-react";
import { useApplySalesOutcome, useMyWork, useSalesWorkspaceQueue } from "@/lib/api";
import type { SalesOutcome } from "@/lib/api/sales";
import { toTelHref } from "@/lib/utils";
import { DealQualificationDialog } from "@/components/deals/deal-qualification-dialog";
import { qualificationFeedback } from "@/lib/deal-feedback";
import { NotInterestedDialog } from "@/components/sales/not-interested-dialog";
import type { SalesTask } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

const PRIORITY_COPY: Record<SalesTask["priorityReason"], { title: string; detail: string; variant: "warning" | "default" | "outline" }> = {
  OVERDUE_FOLLOW_UP: { title: "Järeltegevus on hilinenud", detail: "Klient ootab lubatud kontakti. Tee see enne uut müügitööd.", variant: "warning" },
  DUE_TODAY: { title: "Kontakt on täna tähtajaline", detail: "Täida tänane kokkulepe, et usaldus püsiks.", variant: "warning" },
  FIRST_CONTACT: { title: "Uus klient — esimene kontakt", detail: "Sinu kiire kontakt määrab, kas vihje liigub päris võimaluseks.", variant: "default" },
  STALE_OPPORTUNITY: { title: "Võimalus vajab äratamist", detail: "Viimasest tegevusest on möödas vähemalt seitse päeva.", variant: "warning" },
  ACTIVE_FOLLOW_UP: { title: "Aktiivne järeltegevus", detail: "Hoia müügivõimalus liikumises järgmise selge sammuga.", variant: "outline" },
};

const STATUS_LABELS: Record<string, string> = { NEW: "Uus", ASSIGNED: "Määratud", CONTACTED: "Kontakteeritud", INTERESTED: "Huvitatud", EVALUATION: "Hindamisel", VALUED: "Hinnastatud", NEGOTIATION: "Läbirääkimised", WAITS_FOR_EVALUATION: "Ootab hindamist", CONTRACTED: "Leping" };
const timeFormatter = new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" });

type SalesTaskCardProps = { task: SalesTask; primary?: boolean; onOutcome: (outcome: SalesOutcome) => void; onNotInterested: () => void; onQualify: () => void; pending: boolean };

function SalesTaskCard({ task, primary = false, onOutcome, onNotInterested, onQualify, pending }: SalesTaskCardProps) {
  const priority = PRIORITY_COPY[task.priorityReason];
  const telHref = toTelHref(task.phone);
  return <Card className={primary ? "border-primary/30 bg-gradient-to-br from-primary/5 to-white shadow-sm" : ""}><CardHeader className="space-y-2 pb-3"><div className="flex flex-wrap items-start justify-between gap-3"><div><p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{primary ? "Sinu järgmine parim kontakt" : priority.title}</p><CardTitle className={primary ? "mt-1 text-2xl" : "mt-1 text-lg"}>{task.ownerName}</CardTitle><p className="mt-1 text-sm text-muted-foreground">{task.personalOrRegistryCode} · {STATUS_LABELS[task.status] ?? task.status}</p></div><Badge variant={priority.variant}>{priority.title}</Badge></div><p className="text-sm text-muted-foreground">{priority.detail}</p></CardHeader><CardContent className="space-y-3"><div className="flex flex-wrap gap-2">{telHref ? <Button asChild><a href={telHref} aria-label={`Helista omanikule ${task.ownerName}: ${task.phone}`}><Phone className="mr-2 h-4 w-4" />Helista {task.phone}</a></Button> : <Button disabled><Phone className="mr-2 h-4 w-4" />Telefon puudub</Button>}<Button asChild variant="outline"><Link href={`/owners/${task.ownerId}`}><UserRound className="mr-2 h-4 w-4" />Ava kliendikaart</Link></Button></div><div className="border-t pt-3"><p className="mb-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">Pärast kontakti vali tulemus — ForestIQ teeb halduse sinu eest</p><div className="flex flex-wrap gap-2"><Button size="sm" variant="outline" disabled={pending} onClick={() => onOutcome("NO_ANSWER")}>Ei vastanud</Button><Button size="sm" variant="outline" disabled={pending} onClick={() => onOutcome("CALL_BACK")}>Helista hiljem</Button><Button size="sm" variant="default" disabled={pending} onClick={onQualify}>Kvalifitseeri huvi</Button><Button size="sm" variant="destructive" disabled={pending} onClick={onNotInterested}>Ei huvita</Button></div></div>{task.nextReminderAt && <p className="flex items-center gap-2 text-xs text-muted-foreground"><CalendarClock className="h-3.5 w-3.5" />Järgmine meeldetuletus: {timeFormatter.format(new Date(task.nextReminderAt))}</p>}</CardContent></Card>;
}

export default function SalesWorkspacePage() {
  const [search, setSearch] = useState("");
  const [feedback, setFeedback] = useState("");
  const [notInterestedTask, setNotInterestedTask] = useState<SalesTask | null>(null);
  const [notInterestedReason, setNotInterestedReason] = useState("");
  const queue = useSalesWorkspaceQueue();
  const searchResults = useMyWork(search, 0, 6);
  const outcome = useApplySalesOutcome();
  const [qualificationTask, setQualificationTask] = useState<SalesTask | null>(null);
  const primaryTask = queue.data?.[0];

  const applyOutcome = (task: SalesTask, selectedOutcome: SalesOutcome, reason?: string) => {
    setFeedback("");
    outcome.mutate({ ownerId: task.ownerId, outcome: selectedOutcome, reason }, { onSuccess: (result) => {
      setFeedback(result.nextActionAt ? `Tulemus salvestatud. ${result.nextAction ?? "Järgmine tegevus"}: ${timeFormatter.format(new Date(result.nextActionAt))}.` : "Tulemus salvestatud. Sinu tööjärjekord on uuendatud.");
      if (selectedOutcome === "NOT_INTERESTED") { setNotInterestedTask(null); setNotInterestedReason(""); }
    } });
  };
  const openNotInterested = (task: SalesTask) => { setFeedback(""); setNotInterestedReason(""); setNotInterestedTask(task); outcome.reset(); };
  const openQualification = (task: SalesTask) => { setFeedback(""); setQualificationTask(task); };

  return <div className="space-y-6"><div className="flex flex-wrap items-end justify-between gap-4"><div><h1 className="flex items-center gap-2 text-3xl font-bold tracking-tight"><Sparkles className="h-7 w-7 text-primary" />Minu müügipult</h1><p className="mt-2 text-muted-foreground">Süsteem hoiab järge. Sina keskendu kliendile, kontaktile ja tehingu järgmisele sammule.</p></div><Button asChild variant="outline"><Link href="/map"><Search className="mr-2 h-4 w-4" />Otsi kaardilt</Link></Button></div>
    {feedback && <div role="status" className="flex items-start gap-2 rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900"><CheckCircle2 className="mt-0.5 h-4 w-4" />{feedback}</div>}
    {qualificationTask && <DealQualificationDialog ownerId={qualificationTask.ownerId} open onCancel={() => setQualificationTask(null)} onCreated={(deal) => { setQualificationTask(null); setFeedback(qualificationFeedback({ kind: "created", deal })); }} />}
    {outcome.isError && !notInterestedTask && <div role="alert" className="flex items-start gap-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900"><CircleAlert className="mt-0.5 h-4 w-4" />{outcome.error.message}</div>}
    {queue.isLoading && <Card><CardContent className="p-6 text-sm text-muted-foreground" role="status">Koostan sinu tänast kontaktide järjekorda…</CardContent></Card>}
    {queue.isError && <Card><CardContent className="space-y-3 p-6"><p role="alert" className="text-sm text-destructive">Müügijärjekorda ei saanud laadida. {queue.error.message}</p><Button variant="outline" onClick={() => void queue.refetch()} disabled={queue.isFetching} aria-busy={queue.isFetching}>{queue.isFetching ? "Laadin uuesti…" : "Proovi uuesti"}</Button></CardContent></Card>}
    {!queue.isError && primaryTask && <SalesTaskCard task={primaryTask} primary onOutcome={(selectedOutcome) => applyOutcome(primaryTask, selectedOutcome)} onNotInterested={() => openNotInterested(primaryTask)} onQualify={() => openQualification(primaryTask)} pending={outcome.isPending} />}
    {!queue.isError && queue.isSuccess && queue.data.length === 0 && <Card><CardContent className="p-6 text-sm text-muted-foreground">Suurepärane — aktiivseid määratud kontakte pole. Otsi uut võimalust kaardilt või küsi juhilt tööjaotust.</CardContent></Card>}
    <Card><CardContent className="space-y-3 p-4"><div className="relative max-w-xl"><Search className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" /><Input aria-label="Otsi minu klienti" className="pl-9" placeholder="Otsi oma klienti nime, telefoni või koodi järgi…" value={search} onChange={(event) => setSearch(event.target.value)} /></div>{search.trim().length > 0 && <div className="max-w-xl rounded-md border">{searchResults.isLoading && <p className="p-3 text-sm text-muted-foreground">Otsin sinu klientide hulgast…</p>}{searchResults.data?.content.map((owner) => <Link key={owner.id} href={`/owners/${owner.id}`} className="flex items-center justify-between border-b px-3 py-2 text-sm last:border-b-0 hover:bg-slate-50"><span><strong>{owner.displayName}</strong><span className="ml-2 text-muted-foreground">{owner.personalOrRegistryCode}</span></span><ArrowRight className="h-4 w-4 text-muted-foreground" /></Link>)}{searchResults.data?.content.length === 0 && <p className="p-3 text-sm text-muted-foreground">Sulle määratud klientide seas vastet ei leitud.</p>}</div>}</CardContent></Card>
    {!queue.isError && queue.data && queue.data.length > 1 && <div className="space-y-3"><div><h2 className="text-xl font-semibold">Pärast järgmist kontakti</h2><p className="text-sm text-muted-foreground">Süsteem järjestab sinu töö automaatselt tähtaja, seisma jäänud võimaluse ja esimese kontakti järgi.</p></div><div className="grid gap-4 lg:grid-cols-2">{queue.data.slice(1, 9).map((task) => <SalesTaskCard key={task.ownerId} task={task} onOutcome={(selectedOutcome) => applyOutcome(task, selectedOutcome)} onNotInterested={() => openNotInterested(task)} onQualify={() => openQualification(task)} pending={outcome.isPending} />)}</div></div>}
    <NotInterestedDialog task={notInterestedTask} reason={notInterestedReason} pending={outcome.isPending} error={outcome.isError ? outcome.error.message : undefined} onReasonChange={setNotInterestedReason} onCancel={() => { if (!outcome.isPending) { setNotInterestedTask(null); setNotInterestedReason(""); outcome.reset(); } }} onConfirm={() => { if (notInterestedTask) applyOutcome(notInterestedTask, "NOT_INTERESTED", notInterestedReason.trim()); }} />
  </div>;
}
