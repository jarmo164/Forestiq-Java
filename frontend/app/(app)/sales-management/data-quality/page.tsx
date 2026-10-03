"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import {
  useAssignDataQualityIssue,
  useDataQualityHistory,
  useDataQualityQueue,
  useRefreshDataQuality,
  useReleaseDataQualityIssue,
  useResolveDataQualityIssue,
  useWorkflowAssignees,
  type DataQualityItem,
} from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

const typeLabels: Record<string, string> = {
  MISSING_CONTACT: "Kontakt puudub",
  STALE_OWNER_DATA: "Andmed vananenud",
  DUPLICATE_CONTACT: "Duplikaadikahtlus",
  MISSING_DEAL_INFO: "Tehingu info puudub",
};
const impactLabels: Record<string, string> = { CRITICAL: "Kriitiline", HIGH: "Kõrge", MEDIUM: "Keskmine", LOW: "Madal" };
const statusLabels: Record<string, string> = { OPEN: "Avatud", ASSIGNED: "Määratud", RESOLVED: "Lahendatud" };

function dateTime(value?: string | null) {
  return value ? new Date(value).toLocaleString("et-EE", { dateStyle: "short", timeStyle: "short" }) : "—";
}

function IssueHistory({ issueId }: { issueId: string }) {
  const [open, setOpen] = useState(false);
  const history = useDataQualityHistory(issueId, open);
  return <div className="space-y-2">
    <Button size="sm" variant="ghost" onClick={() => setOpen(value => !value)}>{open ? "Peida ajalugu" : "Näita ajalugu"}</Button>
    {open && <div className="space-y-2 rounded-md bg-muted/40 p-3">
      {history.isLoading && <p className="text-xs text-muted-foreground">Laadin auditit…</p>}
      {history.data?.map(event => <div key={event.id} className="border-l-2 pl-3 text-xs">
        <p className="font-medium">{event.eventType} · {statusLabels[event.toStatus] ?? event.toStatus}</p>
        <p className="text-muted-foreground">{dateTime(event.createdAt)} · {event.actorSubject}</p>
        {event.reason && <p className="mt-1">{event.reason}</p>}
      </div>)}
    </div>}
  </div>;
}

export default function DataQualityPage() {
  const [issueType, setIssueType] = useState("");
  const [status, setStatus] = useState("OPEN");
  const [impact, setImpact] = useState("");
  const [assignedSubject, setAssignedSubject] = useState("");
  const [selection, setSelection] = useState<Record<string, string>>({});

  const filters = useMemo(() => ({
    issueType: issueType || undefined,
    status: status || undefined,
    impact: impact || undefined,
    assignedSubject: assignedSubject || undefined,
  }), [issueType, status, impact, assignedSubject]);

  const queue = useDataQualityQueue(filters);
  const refresh = useRefreshDataQuality();
  const assign = useAssignDataQualityIssue();
  const release = useReleaseDataQualityIssue();
  const resolve = useResolveDataQualityIssue();
  const { data: assignees = [] } = useWorkflowAssignees();
  const eligibleAssignees = assignees.filter(user => user.roles.some(role => ["CALLER", "CRM_MANAGER", "ORG_ADMIN"].includes(role)));

  const doAssign = (item: DataQualityItem) => {
    const assignee = selection[item.id] || item.recommendedSubject || "";
    if (!assignee) return;
    const reason = window.prompt("Määramise põhjendus (soovi korral)") || undefined;
    assign.mutate({ issueId: item.id, assigneeSubject: assignee, reason });
  };
  const doResolve = (item: DataQualityItem) => {
    const reason = window.prompt("Kirjelda, kuidas probleem lahendati");
    if (!reason?.trim()) return;
    resolve.mutate({ issueId: item.id, reason: reason.trim() });
  };
  const doRelease = (item: DataQualityItem) => {
    const reason = window.prompt("Miks vastutus vabastatakse?");
    if (!reason?.trim()) return;
    release.mutate({ issueId: item.id, reason: reason.trim() });
  };

  return <div className="space-y-6">
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div><h1 className="text-2xl font-bold">Andmekvaliteedi järjekord</h1><p className="text-sm text-muted-foreground">Süsteemsed CRM- ja tehinguandmete probleemid koos vastutuse, mõju ja auditeeritava lahendusega.</p></div>
      <Button disabled={refresh.isPending} onClick={() => refresh.mutate()}>{refresh.isPending ? "Kontrollin…" : "Kontrolli andmekvaliteeti"}</Button>
    </div>

    {refresh.data && <div className="rounded-md border bg-muted/30 p-3 text-sm">Viimane kontroll: {refresh.data.detectedCount} tuvastust, {refresh.data.autoResolvedCount} automaatselt lahendatud · {dateTime(refresh.data.finishedAt)}</div>}
    {refresh.isError && <p className="text-sm text-destructive">{refresh.error.message}</p>}

    <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Card><CardHeader className="pb-2"><CardTitle className="text-sm">Avatud</CardTitle></CardHeader><CardContent><div className="text-2xl font-bold">{queue.data?.openCount ?? "—"}</div></CardContent></Card>
      <Card><CardHeader className="pb-2"><CardTitle className="text-sm">Määratud</CardTitle></CardHeader><CardContent><div className="text-2xl font-bold">{queue.data?.assignedCount ?? "—"}</div></CardContent></Card>
      <Card><CardHeader className="pb-2"><CardTitle className="text-sm">Kriitilised aktiivsed</CardTitle></CardHeader><CardContent><div className="text-2xl font-bold">{queue.data?.criticalCount ?? "—"}</div></CardContent></Card>
      <Card><CardHeader className="pb-2"><CardTitle className="text-sm">Lahendatud</CardTitle></CardHeader><CardContent><div className="text-2xl font-bold">{queue.data?.resolvedCount ?? "—"}</div></CardContent></Card>
    </div>

    <Card><CardContent className="grid gap-3 pt-5 md:grid-cols-4">
      <select className="h-10 rounded-md border bg-background px-3 text-sm" value={issueType} onChange={e => setIssueType(e.target.value)} aria-label="Probleemi tüüp"><option value="">Kõik tüübid</option>{Object.entries(typeLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
      <select className="h-10 rounded-md border bg-background px-3 text-sm" value={status} onChange={e => setStatus(e.target.value)} aria-label="Olek"><option value="">Kõik olekud</option>{Object.entries(statusLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
      <select className="h-10 rounded-md border bg-background px-3 text-sm" value={impact} onChange={e => setImpact(e.target.value)} aria-label="Mõju"><option value="">Kõik mõjud</option>{Object.entries(impactLabels).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select>
      <Input placeholder="Vastutaja subject" value={assignedSubject} onChange={e => setAssignedSubject(e.target.value)} />
    </CardContent></Card>

    <Card><CardContent className="pt-4">
      {queue.isLoading && <p className="text-sm text-muted-foreground">Laadin järjekorda…</p>}
      {queue.isError && <p className="text-sm text-destructive">{queue.error.message}</p>}
      <div className="space-y-4">{queue.data?.items.map(item => <div key={item.id} className="rounded-lg border p-4">
        <div className="flex flex-col gap-4 xl:flex-row xl:justify-between">
          <div className="min-w-0 space-y-2">
            <div className="flex flex-wrap items-center gap-2"><Badge variant={item.impact === "CRITICAL" ? "destructive" : "outline"}>{impactLabels[item.impact]}</Badge><Badge variant="secondary">{typeLabels[item.issueType]}</Badge><Badge variant="outline">{statusLabels[item.status]}</Badge></div>
            <p className="font-semibold"><Link className="text-primary hover:underline" href={`/owners/${item.ownerId}`}>{item.ownerName}</Link>{item.dealId && <> · <Link className="text-primary hover:underline" href={`/deals/${item.dealId}`}>tehing</Link></>}</p>
            <p className="text-sm">{item.summary}</p>
            <p className="text-xs text-muted-foreground">Allikas: {item.sourceName} · esmane tuvastus {dateTime(item.firstDetectedAt)} · viimati {dateTime(item.lastDetectedAt)}</p>
            <p className="text-xs">Soovitatud vastutaja: <strong>{item.recommendedSubject || "—"}</strong> · praegune vastutaja: <strong>{item.assignedSubject || "määramata"}</strong></p>
            {item.issueType === "DUPLICATE_CONTACT" && <p className="rounded-md bg-amber-50 p-2 text-xs text-amber-900">Duplikaadikahtlus on ainult kontrollisignaal. ForestIQ ei ühenda ega kustuta omanikke automaatselt.</p>}
            {item.actionReason && <p className="text-xs text-muted-foreground">Viimane põhjendus: {item.actionReason}</p>}
            <IssueHistory issueId={item.id} />
          </div>
          {item.status !== "RESOLVED" && <div className="flex min-w-72 flex-col gap-2">
            <select className="h-9 rounded-md border bg-background px-2 text-sm" value={selection[item.id] ?? item.assignedSubject ?? item.recommendedSubject ?? ""} onChange={e => setSelection(current => ({ ...current, [item.id]: e.target.value }))} aria-label="Vastutaja"><option value="">Vali vastutaja…</option>{eligibleAssignees.map(user => <option key={user.subject} value={user.subject}>{user.name || user.username}</option>)}</select>
            <Button size="sm" variant="outline" onClick={() => doAssign(item)} disabled={assign.isPending}>Määra vastutaja</Button>
            {item.assignedSubject && <Button size="sm" variant="outline" onClick={() => doRelease(item)} disabled={release.isPending}>Vabasta vastutus</Button>}
            <Button size="sm" onClick={() => doResolve(item)} disabled={resolve.isPending}>Märgi lahendatuks</Button>
          </div>}
        </div>
      </div>)}</div>
      {queue.data?.items.length === 0 && <p className="text-sm text-muted-foreground">Valitud filtriga probleeme ei ole.</p>}
      {queue.data && <p className="mt-4 text-xs text-muted-foreground">Kuvatud {queue.data.items.length} / {queue.data.total}. Ühe päringu piir on 200 kirjet.</p>}
    </CardContent></Card>
  </div>;
}
