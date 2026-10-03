"use client";

import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useAddInheritanceNote, useChangeInheritanceStatus, useCheckOfficialInheritanceNotices, useSaveInheritanceHeir, useStartInheritanceCase, useInheritanceWorkspace } from "@/lib/api";
import type { SaveInheritanceHeir } from "@/lib/types";

const CASE_STATUS_LABELS: Record<string, string> = {
  NOTICE_RECEIVED: "Teade saabus", HEIR_DISCOVERY: "Pärijaid selgitatakse", HEIRS_CONTACTED: "Pärijaid kontaktitud",
  NEGOTIATION: "Läbirääkimised", SETTLED: "Kokkulepe", CLOSED: "Lõpetatud", NOT_RELEVANT: "Ei ole asjakohane",
};
const HEIR_STATUS_LABELS: Record<string, string> = {
  UNVERIFIED: "Kontrollimata", READY_TO_CONTACT: "Valmis kontaktiks", CONTACTED: "Kontakteeritud",
  INTERESTED: "Huvitatud", NOT_INTERESTED: "Ei huvita", NOT_REACHABLE: "Ei saa kätte",
};
const emptyHeir: SaveInheritanceHeir = { displayName: "", contactStatus: "UNVERIFIED", source: "MANUAL" };

export function InheritanceCasePanel({ ownerId, ownerStatus }: { ownerId: string; ownerStatus: string }) {
  const workspace = useInheritanceWorkspace(ownerId);
  const inheritanceCase = workspace.data?.inheritanceCase;
  const checkOfficial = useCheckOfficialInheritanceNotices(ownerId);
  const start = useStartInheritanceCase(ownerId);
  const changeStatus = useChangeInheritanceStatus(ownerId, inheritanceCase?.id);
  const saveHeir = useSaveInheritanceHeir(ownerId, inheritanceCase?.id);
  const addNote = useAddInheritanceNote(ownerId, inheritanceCase?.id);
  const [showManualStart, setShowManualStart] = useState(false);
  const [showHeir, setShowHeir] = useState(false);
  const [heirData, setHeirData] = useState<SaveInheritanceHeir>(emptyHeir);
  const [note, setNote] = useState("");
  const [checkMessage, setCheckMessage] = useState<string | null>(null);
  const isDeceased = ownerStatus === "DECEASED";

  if (workspace.isPending) return <Card><CardContent className="p-4 text-sm text-muted-foreground">Laadin pärimisjuhtumit…</CardContent></Card>;
  if (workspace.isError) return <Card><CardContent className="p-4 text-sm text-destructive">Pärimisjuhtumi andmete laadimine ebaõnnestus.</CardContent></Card>;

  const startCase = () => start.mutate({}, { onSuccess: () => setShowManualStart(false) });
  const createHeir = () => saveHeir.mutate({ data: heirData }, { onSuccess: () => { setHeirData(emptyHeir); setShowHeir(false); } });
  const check = () => checkOfficial.mutate(undefined, {
    onSuccess: result => setCheckMessage(result.message ?? "Ametliku pärimisteate kontroll lõpetati."),
    onError: error => setCheckMessage(error.message),
  });

  return <Card>
    <CardHeader className="flex-row items-center justify-between gap-3">
      <div>
        <CardTitle>Pärimisjuhtum</CardTitle>
        <p className="mt-1 text-xs text-muted-foreground">Ametlik teade käivitab surnud-isiku staatuse ja pärimistöövoo. Pärimisõigus tuleb alati kontrollida pädevast allikast.</p>
      </div>
      {!inheritanceCase && <Button size="sm" onClick={check} disabled={checkOfficial.isPending}>{checkOfficial.isPending ? "Kontrollin…" : "Kontrolli ametlikke teateid"}</Button>}
    </CardHeader>
    <CardContent className="space-y-4">
      {checkMessage && <p className={`rounded-md border p-3 text-sm ${isDeceased || inheritanceCase ? "border-primary/30 bg-primary/5" : "text-muted-foreground"}`}>{checkMessage}</p>}
      {!inheritanceCase && !isDeceased && <div className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">Pärimise töövahendid avanevad pärast seda, kui ametlik pärimisteade on omaniku isikukoodi järgi kinnitatud. Kontroll ei leia teadet ega muuda kliendiandmeid, kui täpset vastet pole.</div>}
      {!inheritanceCase && isDeceased && <div className="space-y-3 rounded-md border p-4">
        <p className="text-sm">Omanik on märgitud surnuks. Ava pärimisjuhtum ainult kinnitatud ametliku info või töökorra alusel.</p>
        {!showManualStart ? <Button size="sm" variant="outline" onClick={() => setShowManualStart(true)}>Ava kinnitatud pärimisjuhtum</Button> : <div className="flex flex-wrap gap-2"><Button size="sm" onClick={startCase} disabled={start.isPending}>Alusta juhtum</Button><Button size="sm" variant="ghost" onClick={() => setShowManualStart(false)}>Tühista</Button></div>}
      </div>}

      {inheritanceCase && <>
        <div className="flex flex-wrap items-center gap-2">
          <Badge>{inheritanceCase.status ? CASE_STATUS_LABELS[inheritanceCase.status] ?? inheritanceCase.status : "—"}</Badge>
          <span className="text-xs text-muted-foreground">Algas {formatDate(inheritanceCase.startedAt)}{inheritanceCase.endedAt ? ` · lõppes ${formatDate(inheritanceCase.endedAt)}` : " · menetlus aktiivne"}</span>
        </div>
        <div className="grid gap-2 text-sm md:grid-cols-2">
          <p>Teade: {inheritanceCase.sourceNoticeNumber ?? "Käsitsi kinnitatud"}</p><p>Notar: {inheritanceCase.notaryName ?? "—"}</p>
          <p>Teate kuupäev: {inheritanceCase.announcementDate ?? "—"}</p><p>Tähtaeg: {inheritanceCase.certificationDeadline ?? "—"}</p>
          {inheritanceCase.sourceUrl && <a className="text-primary underline" href={inheritanceCase.sourceUrl} target="_blank" rel="noreferrer">Ava ametlik teade</a>}
        </div>
        <div className="flex flex-wrap gap-2 rounded-md bg-muted/50 p-3">
          {Object.entries(CASE_STATUS_LABELS).map(([status, label]) => <Button key={status} size="sm" variant={inheritanceCase.status === status ? "default" : "outline"} disabled={changeStatus.isPending || inheritanceCase.status === status} onClick={() => changeStatus.mutate({ status })}>{label}</Button>)}
        </div>

        <div className="border-t pt-4"><div className="mb-2 flex items-center justify-between"><h3 className="font-medium">Pärijad ({(inheritanceCase.heirs ?? []).length})</h3><Button size="sm" variant="outline" onClick={() => setShowHeir(!showHeir)}>Lisa pärija</Button></div>
          {showHeir && <div className="mb-3 grid gap-2 rounded-md border p-3 md:grid-cols-2">
            <LabeledInput label="Nimi *" value={heirData.displayName ?? ""} onChange={v => setHeirData({ ...heirData, displayName: v })} />
            <LabeledInput label="Isiku- või registrikood" value={heirData.personalCode ?? ""} onChange={v => setHeirData({ ...heirData, personalCode: v })} />
            <LabeledInput label="Pärandi osa" value={heirData.inheritanceShare ?? ""} onChange={v => setHeirData({ ...heirData, inheritanceShare: v })} />
            <LabeledInput label="Seos pärandajaga" value={heirData.relationToDeceased ?? ""} onChange={v => setHeirData({ ...heirData, relationToDeceased: v })} />
            <LabeledInput label="Telefon" value={heirData.phone ?? ""} onChange={v => setHeirData({ ...heirData, phone: v })} />
            <LabeledInput label="E-post" value={heirData.email ?? ""} onChange={v => setHeirData({ ...heirData, email: v })} />
            <label className="grid gap-1 text-sm"><span className="font-medium">Kontaktistaatus</span><select className="h-10 rounded-md border bg-background px-3" value={heirData.contactStatus} onChange={e => setHeirData({ ...heirData, contactStatus: e.target.value })}>{Object.entries(HEIR_STATUS_LABELS).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
            <div className="flex items-end gap-2"><Button size="sm" onClick={createHeir} disabled={!heirData.displayName?.trim() || saveHeir.isPending}>Salvesta pärija</Button><Button size="sm" variant="ghost" onClick={() => setShowHeir(false)}>Tühista</Button></div>
          </div>}
          <div className="space-y-2">{(inheritanceCase.heirs ?? []).length ? (inheritanceCase.heirs ?? []).map(heir => <div className="rounded-md bg-muted p-3 text-sm" key={heir.id}><div className="flex justify-between gap-2"><p className="font-medium">{heir.displayName}</p><Badge variant="outline">{heir.contactStatus ? HEIR_STATUS_LABELS[heir.contactStatus] ?? heir.contactStatus : "—"}</Badge></div><p className="text-muted-foreground">{[heir.inheritanceShare, heir.relationToDeceased, heir.phone, heir.email].filter(Boolean).join(" · ") || "Kontaktandmed puuduvad"}</p></div>) : <p className="text-sm text-muted-foreground">Pärijaid pole veel lisatud.</p>}</div>
        </div>

        <div className="border-t pt-4"><h3 className="mb-2 font-medium">Juhtumi tegevusajalugu</h3><div className="mb-3 flex gap-2"><Input placeholder="Lisa tegevus või kokkulepe…" value={note} onChange={e => setNote(e.target.value)} onKeyDown={e => e.key === "Enter" && note.trim() && addNote.mutate(note, { onSuccess: () => setNote("") })} /><Button size="sm" disabled={!note.trim() || addNote.isPending} onClick={() => addNote.mutate(note, { onSuccess: () => setNote("") })}>Logi</Button></div><div className="space-y-3">{(inheritanceCase.events ?? []).map(event => <div className="border-l-2 border-primary/30 pl-3" key={event.id}><p className="text-sm">{event.description}</p><p className="text-xs text-muted-foreground">{event.createdBy} · {formatDate(event.createdAt)}</p></div>)}</div></div>
      </>}
    </CardContent>
  </Card>;
}

function LabeledInput({ label, value, onChange, type = "text" }: { label: string; value: string; onChange: (value: string) => void; type?: string }) {
  return <label className="grid gap-1 text-sm"><span className="font-medium">{label}</span><Input type={type} value={value} onChange={event => onChange(event.target.value)} /></label>;
}
function formatDate(value?: string) { return value ? new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "—"; }
