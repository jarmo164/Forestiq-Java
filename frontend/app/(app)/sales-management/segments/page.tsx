"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useApplyAssignments, useAssignmentPreview, useRunSegment, useSaveSegment, useSegments, type AssignmentPreview, type AssignmentResult, type SegmentRunResult } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog";

export default function SegmentsPage() {
  const segments = useSegments();
  const save = useSaveSegment();
  const run = useRunSegment();
  const previewMutation = useAssignmentPreview();
  const apply = useApplyAssignments();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [statuses, setStatuses] = useState("NEW,CONTACTED,INTERESTED");
  const [county, setCounty] = useState("");
  const [minForestArea, setMinForestArea] = useState("");
  const [contactState, setContactState] = useState("");
  const [signalCode, setSignalCode] = useState("");
  const [workflow, setWorkflow] = useState("Helista ja kvalifitseeri");
  const [lastRun, setLastRun] = useState<SegmentRunResult>();
  const [preview, setPreview] = useState<AssignmentPreview>();
  const [decisions, setDecisions] = useState<Record<string, string>>({});
  const [assignmentResult, setAssignmentResult] = useState<AssignmentResult>();
  const [confirmApply, setConfirmApply] = useState(false);

  const statusList = useMemo(() => statuses.split(",").map(v => v.trim()).filter(Boolean), [statuses]);
  const create = () => {
    if (!name.trim()) return;
    save.mutate({ data: { name: name.trim(), description: description || undefined, active: true, recommendedWorkflow: workflow || undefined, rules: { statuses: statusList, county: county || undefined, minForestAreaHectares: minForestArea ? Number(minForestArea) : undefined, contactState: contactState || undefined, signalCode: signalCode || undefined } } }, { onSuccess: () => { setName(""); setDescription(""); } });
  };
  const runSegment = (id: string) => run.mutate(id, { onSuccess: result => { setLastRun(result); setPreview(undefined); setAssignmentResult(undefined); } });
  const buildPreview = (id: string) => previewMutation.mutate(id, { onSuccess: result => { setPreview(result); setAssignmentResult(undefined); setDecisions(Object.fromEntries(result.suggestions.map(s => [s.ownerId, s.recommendedSubject]))); } });
  const applyPreview = () => {
    if (!preview) return;
    const assignments = preview.suggestions.map(s => ({ ownerId: s.ownerId, toSubject: decisions[s.ownerId] ?? s.recommendedSubject }));
    apply.mutate({ segmentId: preview.segmentId, assignmentRule: preview.rule, rationale: preview.rationale, assignments }, { onSuccess: result => { setAssignmentResult(result); setPreview(undefined); setLastRun(undefined); setConfirmApply(false); } });
  };
  const candidateName = (subject?: string | null) => preview?.candidates.find(candidate => candidate.subject === subject)?.name || preview?.candidates.find(candidate => candidate.subject === subject)?.username || subject || "Määramata";
  const assignmentSummary = useMemo(() => {
    const summary = { newAssignments: 0, reassignments: 0, unchanged: 0 };
    preview?.suggestions.forEach(suggestion => {
      const next = decisions[suggestion.ownerId] ?? suggestion.recommendedSubject;
      if (!suggestion.currentSubject) summary.newAssignments++;
      else if (suggestion.currentSubject === next) summary.unchanged++;
      else summary.reassignments++;
    });
    return summary;
  }, [decisions, preview]);

  return <div className="space-y-6">
    <div className="flex flex-wrap items-end justify-between gap-3"><div><h1 className="text-2xl font-bold">Sihtrühmad ja tööjaotus</h1><p className="text-sm text-muted-foreground">Salvesta reeglid, arvuta sihtrühm uuesti ning jaga töö tegeliku koormuse järgi.</p></div><Button asChild variant="outline"><Link href="/sales-management/analytics">Müügianalüütika</Link></Button></div>

    <Card><CardHeader><CardTitle>Uus püsiv sihtrühm</CardTitle></CardHeader><CardContent className="space-y-4">
      <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4"><Input placeholder="Nimi" value={name} onChange={e => setName(e.target.value)} /><Input placeholder="Kirjeldus" value={description} onChange={e => setDescription(e.target.value)} /><Input placeholder="Staatused komaga" value={statuses} onChange={e => setStatuses(e.target.value)} /><Input placeholder="Maakond" value={county} onChange={e => setCounty(e.target.value)} /><Input type="number" placeholder="Min metsamaa ha" value={minForestArea} onChange={e => setMinForestArea(e.target.value)} /><select className="h-10 rounded-md border bg-background px-3 text-sm" value={contactState} onChange={e => setContactState(e.target.value)}><option value="">Kontakt: kõik</option><option value="HAS_CONTACT">Kontakt olemas</option><option value="MISSING_CONTACT">Kontakt puudub</option></select><select className="h-10 rounded-md border bg-background px-3 text-sm" value={signalCode} onChange={e => setSignalCode(e.target.value)}><option value="">Signaal: kõik</option><option value="FRESH_FOREST_NOTIFICATION">Värske metsateatis</option><option value="STALE_REGISTRY_DATA">Vananenud registriandmed</option><option value="RESTRICTION_LABEL">Kitsendusmärgend</option><option value="MISSING_CONTACT">Kontakt puudub</option></select><Input placeholder="Soovituslik töövoog" value={workflow} onChange={e => setWorkflow(e.target.value)} /></div>
      <Button onClick={create} disabled={!name.trim() || save.isPending}>Salvesta sihtrühm</Button>
    </CardContent></Card>

    <Card><CardHeader><CardTitle>Salvestatud sihtrühmad</CardTitle></CardHeader><CardContent className="space-y-3">
      {segments.data?.map(segment => <div key={segment.id} className="rounded-md border p-4"><div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between"><div><div className="flex flex-wrap items-center gap-2"><p className="font-semibold">{segment.name}</p><Badge variant={segment.active ? "outline" : "secondary"}>{segment.active ? "Aktiivne" : "Peatatud"}</Badge></div><p className="text-sm text-muted-foreground">{segment.description || "Kirjeldus puudub"}</p><p className="mt-1 text-xs text-muted-foreground">Reeglid: {JSON.stringify(segment.rules)} · töövoog: {segment.recommendedWorkflow || "—"}</p><p className="mt-1 text-xs">Viimane arvutus: {segment.lastCalculatedAt ? new Date(segment.lastCalculatedAt).toLocaleString("et-EE") : "pole käivitatud"} · maht {segment.lastCalculatedCount ?? "—"}</p></div><div className="flex gap-2"><Button size="sm" variant="outline" onClick={() => runSegment(segment.id)} disabled={run.isPending}>Arvuta</Button><Button size="sm" onClick={() => buildPreview(segment.id)} disabled={previewMutation.isPending}>Jaotamissoovitus</Button></div></div></div>)}
    </CardContent></Card>

    {lastRun && <Card><CardHeader><CardTitle>Arvutatud sihtrühm: {lastRun.total}</CardTitle></CardHeader><CardContent><Table><TableHeader><TableRow><TableHead>Omanik</TableHead><TableHead>Staatus</TableHead><TableHead>Maakond</TableHead><TableHead>Metsamaa</TableHead><TableHead>Kontakt</TableHead><TableHead>Praegune vastutaja</TableHead></TableRow></TableHeader><TableBody>{lastRun.members.map(m => <TableRow key={m.ownerId}><TableCell><Link className="text-primary hover:underline" href={`/owners/${m.ownerId}`}>{m.ownerName}</Link></TableCell><TableCell>{m.status}</TableCell><TableCell>{m.county || "—"}</TableCell><TableCell>{Number(m.forestAreaHectares).toLocaleString("et-EE")} ha</TableCell><TableCell>{m.hasContact ? "Jah" : "Ei"}</TableCell><TableCell>{m.assignedToSubject || "Määramata"}</TableCell></TableRow>)}</TableBody></Table>{lastRun.total > lastRun.members.length && <p className="mt-3 text-xs text-muted-foreground">Eelvaates kuvatakse esimesed {lastRun.members.length} objekti; tegelik maht on {lastRun.total}.</p>}</CardContent></Card>}

    {preview && <Card><CardHeader><CardTitle>Koormuspõhine jaotamissoovitus</CardTitle></CardHeader><CardContent className="space-y-5"><p className="text-sm text-muted-foreground">{preview.rationale}</p><div className="grid gap-2 rounded-md border bg-muted/30 p-3 text-sm sm:grid-cols-3"><p><strong>{assignmentSummary.newAssignments}</strong> uut määrangut</p><p><strong>{assignmentSummary.reassignments}</strong> ümbermääramist</p><p><strong>{assignmentSummary.unchanged}</strong> muutmata rida</p></div><Table><TableHeader><TableRow><TableHead>Kasutaja</TableHead><TableHead>Koormusskoor</TableHead><TableHead>Avatud</TableHead><TableHead>Üle tähtaja</TableHead><TableHead>7 päeva sees</TableHead><TableHead>Hiljuti määratud</TableHead></TableRow></TableHeader><TableBody>{preview.candidates.map(c => <TableRow key={c.subject}><TableCell><p className="font-medium">{c.name || c.username}</p><p className="text-xs text-muted-foreground">{c.explanation}</p></TableCell><TableCell>{c.loadScore}</TableCell><TableCell>{c.openWork}</TableCell><TableCell>{c.overdueWork}</TableCell><TableCell>{c.dueNextSevenDays}</TableCell><TableCell>{c.recentlyAssigned}</TableCell></TableRow>)}</TableBody></Table><div className="space-y-2"><p className="font-medium">Juhi kinnitatav jaotus</p>{preview.suggestions.map(s => { const next = decisions[s.ownerId] ?? s.recommendedSubject; const reassignment = Boolean(s.currentSubject && s.currentSubject !== next); return <div key={s.ownerId} className="grid gap-2 rounded-md border p-3 md:grid-cols-[1fr_12rem_15rem]"><div><p className="text-sm font-medium">{s.ownerName}</p><p className="text-xs text-muted-foreground">{s.explanation}</p></div><p className="text-sm">Praegu: <strong>{candidateName(s.currentSubject)}</strong>{reassignment && <Badge className="ml-2" variant="secondary">Ümbermääramine</Badge>}</p><select aria-label={`${s.ownerName} uus vastutaja`} className="h-10 rounded-md border bg-background px-3 text-sm" value={next} onChange={e => setDecisions(current => ({ ...current, [s.ownerId]: e.target.value }))}>{preview.candidates.map(c => <option key={c.subject} value={c.subject}>{c.name || c.username} · score {c.loadScore}</option>)}</select></div>; })}</div><Button onClick={() => { apply.reset(); setConfirmApply(true); }} disabled={apply.isPending || preview.suggestions.length === 0}>Kinnita massmääramine ({preview.suggestions.length})</Button>{apply.isError && <p role="alert" className="text-sm text-destructive">{apply.error.message}</p>}</CardContent></Card>}
    {assignmentResult && <Card><CardHeader><CardTitle>Massmääramise tulemus</CardTitle></CardHeader><CardContent className="space-y-3"><p className="text-sm text-muted-foreground">Toiming {new Date(assignmentResult.createdAt).toLocaleString("et-EE")} · run {assignmentResult.runId}</p><div className="grid gap-2 text-sm sm:grid-cols-4"><p><strong>{assignmentResult.assignedCount}</strong> uut</p><p><strong>{assignmentResult.reassignedCount}</strong> ümbermääratud</p><p><strong>{assignmentResult.unchangedCount}</strong> muutmata</p><p><strong>{assignmentResult.skippedCount}</strong> vahele jäetud</p></div><Table><TableHeader><TableRow><TableHead>Omanik</TableHead><TableHead>Senine</TableHead><TableHead>Uus</TableHead><TableHead>Tulemus</TableHead></TableRow></TableHeader><TableBody>{assignmentResult.items.map(item => <TableRow key={item.ownerId}><TableCell>{item.ownerName}</TableCell><TableCell>{item.fromSubject || "Määramata"}</TableCell><TableCell>{item.toSubject}</TableCell><TableCell>{item.result === "REASSIGNED" ? "Ümbermääratud" : item.result === "ASSIGNED" ? "Määratud" : "Muutmata"}</TableCell></TableRow>)}</TableBody></Table><p className="text-xs text-muted-foreground">Massmääramine on transaktsiooniline: vigase sisendi korral ei muudeta ühtegi rida osaliselt.</p></CardContent></Card>}
    <ConfirmActionDialog open={confirmApply} title="Kas kinnitada massmääramine?" description={<>{assignmentSummary.newAssignments} uut määrangut, {assignmentSummary.reassignments} ümbermääramist ja {assignmentSummary.unchanged} muutmata rida. {assignmentSummary.reassignments > 0 && <strong>Ümbermääramine muudab olemasoleva vastutaja.</strong>}</>} confirmLabel="Kinnita ja salvesta" pending={apply.isPending} error={apply.isError ? apply.error.message : undefined} onConfirm={applyPreview} onCancel={() => { if (!apply.isPending) { setConfirmApply(false); apply.reset(); } }} />
  </div>;
}
