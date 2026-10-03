"use client";

import Link from "next/link";
import { useRef, useState } from "react";
import { ClipboardCheck, SendToBack, UserCheck, UserPlus, WalletCards } from "lucide-react";
import { useCallBrief, useClaimDealEvaluation, useDealEvaluationQueue, useSubmitDealEvaluation, useUnclaimDealEvaluation } from "@/lib/api";
import { ApiError } from "@/lib/api/core";
import { useAuth } from "@/lib/auth";
import type { EvaluationTask, SubmitDealEvaluation } from "@/lib/types";
import { AsyncStatus } from "@/components/ui/async-status";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { FormField, fieldDescriptionIds } from "@/components/ui/form-field";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const formatter = new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" });
const money = new Intl.NumberFormat("et-EE", { style: "currency", currency: "EUR", maximumFractionDigits: 0 });
type MoneyKey = "estimatedMinPrice" | "estimatedMaxPrice" | "recommendedPurchasePrice" | "internalMinPrice" | "proposedOfferPrice";
type MoneyErrors = Partial<Record<MoneyKey, string>>;
const moneyKeys: MoneyKey[] = ["estimatedMinPrice", "estimatedMaxPrice", "recommendedPurchasePrice", "internalMinPrice", "proposedOfferPrice"];

function moneyError(value: number | undefined, required = false) {
  if (value === undefined) return required ? "Väärtus on kohustuslik." : undefined;
  if (!Number.isFinite(value)) return "Summa peab olema lõplik arv.";
  if (value < 0) return "Summa ei tohi olla negatiivne.";
  if (Math.round(value * 100) !== value * 100) return "Kasuta euro summat kuni kahe komakohaga.";
  if (required && value === 0) return "Summa peab olema suurem kui 0.";
  return undefined;
}

export default function EvaluationWorkdeskPage() {
  const queue = useDealEvaluationQueue();
  const { subject, hasRole } = useAuth();
  const [selected, setSelected] = useState<EvaluationTask | null>(null);
  const canManageAll = hasRole("ORG_ADMIN", "CRM_MANAGER");
  return <div className="space-y-6">
    <div><h1 className="flex items-center gap-2 text-2xl font-bold"><ClipboardCheck className="h-6 w-6" />Evaluaatori töölaud</h1><p className="mt-1 text-sm text-muted-foreground">Määramata ülesande saab hindaja endale võtta. Tavaline hindaja näeb enda ja määramata töid; müügijuht ning administraator näevad kogu järjekorda.</p></div>
    {selected && <EvaluationDecisionPanel task={selected} onClose={() => setSelected(null)} />}
    <Card><CardHeader><CardTitle>Hindamisjärjekord</CardTitle></CardHeader><CardContent>
      {queue.isLoading && <p role="status" className="text-sm text-muted-foreground">Laadin hindamisjuhtumeid...</p>}
      {queue.isError && <p role="alert" className="text-sm text-destructive">{queue.error.message}</p>}
      {queue.data?.length === 0 && <p className="text-sm text-muted-foreground">Praegu ei oota ükski tehingujuhtum hindamist.</p>}
      {queue.data && queue.data.length > 0 && <Table><TableHeader><TableRow><TableHead>Klient</TableHead><TableHead>Objekt</TableHead><TableHead>Kinnistuid</TableHead><TableHead>Hindamise tähtaeg</TableHead><TableHead>Hindaja</TableHead><TableHead>Müügi vastutaja</TableHead><TableHead /></TableRow></TableHeader><TableBody>{queue.data.map(task => { const overdue = Boolean(task.evaluationDueAt && new Date(task.evaluationDueAt) < new Date()); const mine = Boolean(subject && task.evaluatorSubject === subject); return <TableRow key={task.dealId}><TableCell><Link className="font-medium text-primary hover:underline" href={`/owners/${task.ownerId}`}>{task.ownerName}</Link><p className="mt-1 text-xs text-muted-foreground">{task.qualificationNotes || "Kõne kokkuvõte puudub"}</p></TableCell><TableCell><Badge variant="outline">{task.saleSubject === "FOREST" ? "Mets" : task.saleSubject === "LAND" ? "Maa" : "Mets ja maa"}</Badge></TableCell><TableCell>{task.parcelCount}</TableCell><TableCell className={overdue ? "font-medium text-destructive" : ""}>{task.evaluationDueAt ? formatter.format(new Date(task.evaluationDueAt)) : "—"}{overdue && <Badge variant="warning" className="ml-2">Üle tähtaja</Badge>}</TableCell><TableCell>{task.evaluatorSubject ? <Badge variant={mine ? "default" : "outline"}>{mine ? "Minu töö" : task.evaluatorSubject}</Badge> : <Badge variant="warning">Määramata</Badge>}</TableCell><TableCell>{task.salesSubject}</TableCell><TableCell><EvaluationTaskActions task={task} subject={subject} canManageAll={canManageAll} onOpen={() => setSelected(task)} /></TableCell></TableRow>; })}</TableBody></Table>}
    </CardContent></Card>
  </div>;
}

function EvaluationTaskActions({ task, subject, canManageAll, onOpen }: { task: EvaluationTask; subject?: string; canManageAll: boolean; onOpen: () => void }) {
  const claim = useClaimDealEvaluation(task.dealId, task.ownerId); const unclaim = useUnclaimDealEvaluation(task.dealId, task.ownerId); const mine = Boolean(subject && task.evaluatorSubject === subject); const unassigned = !task.evaluatorSubject; const canOpen = canManageAll || mine;
  return <div className="flex flex-wrap justify-end gap-2">{unassigned && <Button size="sm" onClick={() => claim.mutate()} disabled={claim.isPending}><UserPlus className="mr-1 h-4 w-4" />Võta töö endale</Button>}{canOpen && <Button size="sm" variant="outline" onClick={onOpen}><UserCheck className="mr-1 h-4 w-4" />Ava hindamine</Button>}{!unassigned && (mine || canManageAll) && <Button size="sm" variant="ghost" onClick={() => unclaim.mutate()} disabled={unclaim.isPending}>Vabasta</Button>}{(claim.isError || unclaim.isError) && <span role="alert" className="basis-full text-xs text-destructive">{claim.error?.message ?? unclaim.error?.message}</span>}</div>;
}

function EvaluationDecisionPanel({ task, onClose }: { task: EvaluationTask; onClose: () => void }) {
  const brief = useCallBrief(task.ownerId);
  const queue = useDealEvaluationQueue();
  const submit = useSubmitDealEvaluation(task.dealId, task.ownerId);
  const [mode, setMode] = useState<"SUBMITTED" | "NEEDS_DATA">("SUBMITTED");
  const [form, setForm] = useState<SubmitDealEvaluation>({ status: "SUBMITTED" });
  const [errors, setErrors] = useState<MoneyErrors>({});
  const [conflict, setConflict] = useState(false);
  const [actualTask, setActualTask] = useState<EvaluationTask | null>(null);
  const recommendedRef = useRef<HTMLInputElement>(null);
  const minRef = useRef<HTMLInputElement>(null);
  const maxRef = useRef<HTMLInputElement>(null);
  const setNumber = (key: MoneyKey, value: string) => setForm(current => ({ ...current, [key]: value === "" ? undefined : Number(value) }));

  const validateMoney = () => {
    const next: MoneyErrors = {};
    for (const key of moneyKeys) { const error = moneyError(form[key], key === "recommendedPurchasePrice"); if (error) next[key] = error; }
    if (form.estimatedMinPrice !== undefined && form.estimatedMaxPrice !== undefined && form.estimatedMinPrice > form.estimatedMaxPrice) { next.estimatedMinPrice = "Väärtuse alampiir ei tohi olla ülempiirist suurem."; next.estimatedMaxPrice = "Väärtuse ülempiir peab olema vähemalt alampiiriga võrdne."; }
    if (form.internalMinPrice !== undefined && form.recommendedPurchasePrice !== undefined && form.internalMinPrice > form.recommendedPurchasePrice) next.internalMinPrice = "Sisemine minimaalhind ei tohi ületada soovituslikku ostuhinda.";
    setErrors(next);
    if (next.estimatedMinPrice) minRef.current?.focus(); else if (next.estimatedMaxPrice) maxRef.current?.focus(); else if (next.recommendedPurchasePrice) recommendedRef.current?.focus();
    return Object.keys(next).length === 0;
  };
  const save = () => {
    if (submit.isPending) return;
    if (mode === "SUBMITTED" && !validateMoney()) return;
    setConflict(false);
    submit.mutate({ ...form, status: mode }, { onSuccess: onClose, onError: error => { if (error instanceof ApiError && error.status === 409) setConflict(true); } });
  };
  const refreshConflict = async () => { const result = await queue.refetch(); setActualTask(result.data?.find(item => item.dealId === task.dealId) ?? null); await brief.refetch(); };
  const briefParcels = brief.data?.parcels ?? [];
  const selectedDeal = (brief.data?.deals ?? []).find(deal => deal.id === task.dealId);
  const selectedParcelIds = new Set((selectedDeal?.parcels ?? []).map(parcel => parcel.id));
  const selectedParcels = briefParcels.filter(parcel => selectedParcelIds.has(parcel.id));
  const selectedParcelsText = selectedParcels.map(parcel => `${parcel.cadastralCode} (${(parcel.areaHectares ?? 0).toFixed(2)} ha)`).join(", ") || "Kinnistute nimekiri pole saadaval";
  const latestParcelUpdate = briefParcels.length ? `Viimane kinnistuandmete uuendus: ${formatter.format(new Date(Math.max(...briefParcels.map(parcel => new Date(parcel.updatedAt ?? 0).getTime()))))}` : "Puudub";

  return <Card className="border-primary/40 shadow-sm"><CardHeader className="flex-row justify-between gap-4"><div><CardTitle className="flex items-center gap-2"><WalletCards className="h-5 w-5" />Hindamisotsus: {task.ownerName}</CardTitle><p className="mt-1 text-sm text-muted-foreground">Hindaja: {task.evaluatorSubject ?? "määramata"} · Müügi vastutaja: {task.salesSubject}. Klient näeb ainult hiljem kinnitatud pakkumist, mitte sisemist minimaalhinda.</p></div><Button variant="ghost" size="sm" onClick={onClose}>Sulge</Button></CardHeader><CardContent className="space-y-5">
    {brief.data && <div className="grid gap-3 rounded-md bg-muted/50 p-4 md:grid-cols-2"><div><p className="text-xs font-medium uppercase text-muted-foreground">Helistaja kokkuvõte</p><p className="text-sm">{task.qualificationNotes || "Puudub"}</p></div><div><p className="text-xs font-medium uppercase text-muted-foreground">Hinnatavad kinnistud</p><p className="text-sm">{selectedParcelsText}</p></div><div><p className="text-xs font-medium uppercase text-muted-foreground">Viimane kliendikontakt</p><p className="text-sm">{brief.data.latestActivity || "Puudub"}</p></div><div><p className="text-xs font-medium uppercase text-muted-foreground">Registriandmete värskus</p><p className="text-sm">{latestParcelUpdate}</p></div></div>}
    {conflict && <div role="alert" className="rounded-md border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950"><p className="font-medium">Juhtumit muudeti samal ajal. Värskenda ja kontrolli etappi.</p><p className="mt-1">Sinu hindamismustand on alles ja seda ei kirjutata värskendamisel üle.</p><Button type="button" size="sm" variant="outline" className="mt-3" onClick={() => void refreshConflict()} disabled={queue.isFetching}>Värskenda tegelik olek</Button>{actualTask && <dl className="mt-3 grid gap-2 sm:grid-cols-3"><div><dt className="text-xs text-amber-800">Etapp</dt><dd className="font-medium">{actualTask.stage}</dd></div><div><dt className="text-xs text-amber-800">Hindaja</dt><dd>{actualTask.evaluatorSubject ?? "määramata"}</dd></div><div><dt className="text-xs text-amber-800">Müügi vastutaja</dt><dd>{actualTask.salesSubject}</dd></div></dl>}</div>}
    <div className="flex flex-wrap gap-2"><Button size="sm" variant={mode === "SUBMITTED" ? "default" : "outline"} onClick={() => setMode("SUBMITTED")}>Esita hindamisotsus</Button><Button size="sm" variant={mode === "NEEDS_DATA" ? "default" : "outline"} onClick={() => setMode("NEEDS_DATA")}>Küsi müügilt täiendust</Button></div>
    {mode === "SUBMITTED" ? <><div className="grid gap-4 md:grid-cols-3"><MoneyInput id="evaluation-min" inputRef={minRef} label="Väärtuse alampiir" value={form.estimatedMinPrice} error={errors.estimatedMinPrice} onChange={value => setNumber("estimatedMinPrice", value)} /><MoneyInput id="evaluation-max" inputRef={maxRef} label="Väärtuse ülempiir" value={form.estimatedMaxPrice} error={errors.estimatedMaxPrice} onChange={value => setNumber("estimatedMaxPrice", value)} /><MoneyInput id="evaluation-recommended" inputRef={recommendedRef} label="Soovituslik ostuhind" required value={form.recommendedPurchasePrice} error={errors.recommendedPurchasePrice} onChange={value => setNumber("recommendedPurchasePrice", value)} /><MoneyInput id="evaluation-internal-min" label="Sisemine minimaalhind" value={form.internalMinPrice} error={errors.internalMinPrice} onChange={value => setNumber("internalMinPrice", value)} /><MoneyInput id="evaluation-offer" label="Kliendile pakutav hind" value={form.proposedOfferPrice} error={errors.proposedOfferPrice} onChange={value => setNumber("proposedOfferPrice", value)} /><label className="grid gap-1 text-sm"><span className="font-medium">Pakkumise kehtivus</span><Input type="date" value={form.offerValidUntil || ""} onChange={event => setForm(current => ({ ...current, offerValidUntil: event.target.value || undefined }))} /></label></div><TextArea label="Hindamise eeldused" value={form.assumptions || ""} onChange={value => setForm(current => ({ ...current, assumptions: value }))} placeholder="Millistel eeldustel hind põhineb?" /><TextArea label="Riskid ja tähelepanekud" value={form.risks || ""} onChange={value => setForm(current => ({ ...current, risks: value }))} placeholder="Kitsendused, andmeriskid, väljavedu, tururiskid…" /></> : <TextArea label="Milliseid andmeid on veel vaja? *" value={form.returnedReason || ""} onChange={value => setForm(current => ({ ...current, returnedReason: value }))} placeholder="Kirjelda konkreetselt, mida helistaja või juht peab täiendama." />}
    <AsyncStatus state={submit.isPending ? "loading" : submit.isError && !conflict ? "error" : "idle"} message={submit.isPending ? "Salvestan hindamisotsust…" : submit.isError && !conflict ? submit.error.message : null} />
    <div className="flex items-center gap-3"><Button onClick={save} disabled={submit.isPending || (mode === "NEEDS_DATA" && !form.returnedReason?.trim())}>{mode === "SUBMITTED" ? "Salvesta otsus ja saada müüki" : "Tagasta täiendamiseks"}</Button>{mode === "SUBMITTED" && form.recommendedPurchasePrice && Number.isFinite(form.recommendedPurchasePrice) && <span className="text-sm text-muted-foreground">Soovituslik ostuhind: {money.format(form.recommendedPurchasePrice)}</span>}{mode === "NEEDS_DATA" && <span className="flex items-center gap-1 text-sm text-muted-foreground"><SendToBack className="h-4 w-4" />Müüjale tekib automaatselt järeltegevus.</span>}</div>
  </CardContent></Card>;
}

function MoneyInput({ id, label, value, onChange, error, required, inputRef }: { id: string; label: string; value?: number; onChange: (value: string) => void; error?: string; required?: boolean; inputRef?: React.RefObject<HTMLInputElement | null> }) { const hint = "Mitte-negatiivne eurosumma, samm 0,01 €."; return <FormField id={id} label={`${label} (€)`} hint={hint} error={error} required={required}><Input ref={inputRef} id={id} type="number" min="0" step="0.01" inputMode="decimal" value={value?.toString() || ""} aria-invalid={Boolean(error)} aria-describedby={fieldDescriptionIds(id, hint, error)} onChange={event => onChange(event.target.value)} /></FormField>; }
function TextArea({ label, value, onChange, placeholder }: { label: string; value: string; onChange: (value: string) => void; placeholder: string }) { return <label className="grid gap-1 text-sm"><span className="font-medium">{label}</span><textarea className="min-h-20 rounded-md border bg-background p-3" value={value} placeholder={placeholder} onChange={event => onChange(event.target.value)} /></label>; }
