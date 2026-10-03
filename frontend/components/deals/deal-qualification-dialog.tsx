"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { CalendarClock, LoaderCircle, MapPin, Phone } from "lucide-react";
import { useCallBrief, useCreateDeal } from "@/lib/api";
import { useCompleteMapParcelSummary } from "@/lib/api/map-cadastre";
import type { CreateDeal, DealCase } from "@/lib/types";
import { AsyncStatus } from "@/components/ui/async-status";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { EmptyErrorState } from "@/components/ui/empty-error-state";
import { Input } from "@/components/ui/input";
import { UndoToast } from "@/components/ui/undo-toast";

type DealQualificationDialogProps = { ownerId: string; open: boolean; onCancel: () => void; onCreated: (deal: DealCase) => void; initialParcelIds?: string[] };
type LandUse = { area: number; forest: number; arable: number; other: number; refreshedAt?: string };
const area = (value?: number | null) => Number.isFinite(value) ? Number(value) : 0;
const areaText = (value: number) => `${new Intl.NumberFormat("et-EE", { maximumFractionDigits: 2 }).format(value)} ha`;

function QualificationParcelRow({ parcel, selected, onToggle, onSummary }: { parcel: { id: string; cadastralCode: string; name?: string | null; areaHectares: number; county?: string | null; updatedAt: string }; selected: boolean; onToggle: () => void; onSummary: (id: string, summary: LandUse) => void }) {
  const details = useCompleteMapParcelSummary(parcel.cadastralCode);
  useEffect(() => {
    const data = details.data;
    if (!data) return;
    onSummary(parcel.id, { area: area(data.areaHectares), forest: area(data.forestArea), arable: area(data.arableArea), other: area(data.otherArea), refreshedAt: data.mkDate ? String(data.mkDate) : parcel.updatedAt });
  }, [details.data, onSummary, parcel.id, parcel.updatedAt]);
  const summary = details.data;
  return <div className={`rounded-md border p-3 text-sm ${selected ? "border-primary bg-primary/5" : ""}`}>
    <label className="flex cursor-pointer items-start gap-3"><input type="checkbox" checked={selected} onChange={onToggle} className="mt-1" /><span className="min-w-0 flex-1"><strong>{parcel.cadastralCode}</strong>{parcel.name ? ` — ${parcel.name}` : ""}<br /><span className="text-muted-foreground"><MapPin className="mr-1 inline h-3.5 w-3.5" />Kokku {areaText(summary?.areaHectares ?? parcel.areaHectares)} · metsamaa {summary ? areaText(area(summary.forestArea)) : "laadin…"}{parcel.county ? ` · ${parcel.county}` : ""}</span></span></label>
    <div className="mt-2 flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground"><span>Allikas: Maa- ja Ruumiameti registriandmed · värskus {new Intl.DateTimeFormat("et-EE", { dateStyle: "medium" }).format(new Date(parcel.updatedAt))}</span><Link className="font-medium text-primary underline" href={`/map?cadastralCode=${encodeURIComponent(parcel.cadastralCode)}`}>Ava kaardil</Link></div>
  </div>;
}

export function DealQualificationDialog({ ownerId, open, onCancel, onCreated, initialParcelIds }: DealQualificationDialogProps) {
  const brief = useCallBrief(ownerId);
  const createDeal = useCreateDeal(ownerId);
  const [parcelIds, setParcelIds] = useState<string[]>([]);
  const [landUse, setLandUse] = useState<Record<string, LandUse>>({});
  const [removedParcelId, setRemovedParcelId] = useState<string | null>(null);
  const [form, setForm] = useState<Omit<CreateDeal, "parcelIds">>({ saleSubject: "FOREST", requestEvaluation: true, qualificationNotes: "" });
  useEffect(() => { if (!brief.data || parcelIds.length > 0) return; const available = new Set(brief.data.parcels.map(parcel => parcel.id)); const requested = initialParcelIds?.filter(parcelId => available.has(parcelId)) ?? []; setParcelIds(requested.length > 0 ? requested : brief.data.parcels.map(parcel => parcel.id)); }, [brief.data, initialParcelIds, parcelIds.length]);
  useEffect(() => { if (!open) { setParcelIds([]); setLandUse({}); setRemovedParcelId(null); setForm({ saleSubject: "FOREST", requestEvaluation: true, qualificationNotes: "" }); } }, [open]);
  const rememberSummary = useMemo(() => (id: string, summary: LandUse) => setLandUse(current => { const previous = current[id]; if (previous && JSON.stringify(previous) === JSON.stringify(summary)) return current; return { ...current, [id]: summary }; }), []);
  const total = useMemo(() => parcelIds.reduce((sum, id) => { const value = landUse[id]; return { area: sum.area + (value?.area ?? 0), forest: sum.forest + (value?.forest ?? 0), arable: sum.arable + (value?.arable ?? 0), other: sum.other + (value?.other ?? 0) }; }, { area: 0, forest: 0, arable: 0, other: 0 }), [parcelIds, landUse]);
  if (!open) return null;
  const toggleParcel = (id: string) => setParcelIds(current => { if (current.includes(id)) { setRemovedParcelId(id); return current.filter(value => value !== id); } setRemovedParcelId(null); return [...current, id]; });
  const submit = () => createDeal.mutate({ ...form, parcelIds, priceExpectation: form.priceExpectation || undefined }, { onSuccess: onCreated });

  return <Card className="border-primary/40 shadow-md"><CardHeader className="flex-row items-start justify-between gap-4"><div><CardTitle>Kvalifitseeri huvi ja saada hindamisse</CardTitle><p className="mt-1 text-sm text-muted-foreground">Müügiobjekti valik põhineb alloleval maakasutuse koondil; valiku muutmine ei kustuta muid sisestusi.</p></div><Button variant="ghost" size="sm" onClick={onCancel}>Sulge</Button></CardHeader><CardContent className="space-y-5">
    {brief.isLoading && <p role="status" className="flex items-center gap-2 text-sm text-muted-foreground"><LoaderCircle className="h-4 w-4 animate-spin" />Koostan kliendi ja kinnistute briefi…</p>}
    {brief.isError && <EmptyErrorState kind="error" title="Kõnebriefi laadimine ebaõnnestus" description={brief.error.message} onRetry={() => void brief.refetch()} />}
    {brief.data && <>
      <div className="grid gap-3 rounded-md bg-muted/50 p-4 md:grid-cols-3"><div><p className="text-xs font-medium uppercase text-muted-foreground">Klient</p><p className="font-medium">{brief.data.ownerName}</p><p className="text-sm text-muted-foreground">{brief.data.ownerStatus}</p></div><div><p className="text-xs font-medium uppercase text-muted-foreground">Viimane kontakt</p><p className="text-sm">{brief.data.latestActivity ?? "Tegevus puudub"}</p></div><div><p className="text-xs font-medium uppercase text-muted-foreground">Järgmine meeldetuletus</p><p className="text-sm">{brief.data.nextReminderAt ? new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" }).format(new Date(brief.data.nextReminderAt)) : "Pole määratud"}</p></div>{brief.data.phone && <a className="flex items-center gap-2 text-sm text-primary underline" href={`tel:${brief.data.phone.replace(/\s/g, "")}`}><Phone className="h-4 w-4" />{brief.data.phone}</a>}</div>
      <section><p className="mb-2 text-sm font-medium">Millised kinnistud on kliendi huvi objekt? *</p><div className="grid gap-2 md:grid-cols-2">{brief.data.parcels.map(parcel => <QualificationParcelRow key={parcel.id} parcel={parcel} selected={parcelIds.includes(parcel.id)} onToggle={() => toggleParcel(parcel.id)} onSummary={rememberSummary} />)}</div></section>
      {removedParcelId && <UndoToast message="Kinnistu eemaldati valikust." onUndo={() => { setParcelIds(current => current.includes(removedParcelId) ? current : [...current, removedParcelId]); setRemovedParcelId(null); }} onDismiss={() => setRemovedParcelId(null)} />}
      <section aria-label="Valitud kinnistute maakasutuse koond" className="rounded-md border bg-slate-50 p-3"><div className="flex flex-wrap items-center justify-between gap-2"><p className="text-sm font-semibold">Maakasutuse koond · {parcelIds.length} kinnistut</p><Badge variant="outline">Müügiobjekt: {form.saleSubject === "FOREST" ? "Mets" : form.saleSubject === "LAND" ? "Maa" : "Mets ja maa"}</Badge></div><dl className="mt-3 grid grid-cols-2 gap-3 text-sm sm:grid-cols-4"><div><dt className="text-xs text-muted-foreground">Kokku</dt><dd className="font-medium">{areaText(total.area)}</dd></div><div><dt className="text-xs text-muted-foreground">Metsamaa</dt><dd className="font-medium">{areaText(total.forest)}</dd></div><div><dt className="text-xs text-muted-foreground">Haritav maa</dt><dd>{areaText(total.arable)}</dd></div><div><dt className="text-xs text-muted-foreground">Muu maa</dt><dd>{areaText(total.other)}</dd></div></dl><p className="mt-2 text-xs text-muted-foreground">Allikas: Maa- ja Ruumiameti katastriandmed. Puuduv väli kuvatakse 0-na ainult koondis; kinnistu real on laadimisolek nähtav.</p></section>
      <div className="grid gap-4 md:grid-cols-3"><label className="grid gap-1 text-sm"><span className="font-medium">Müügiobjekt *</span><select className="h-10 rounded-md border bg-background px-3" value={form.saleSubject} onChange={event => setForm(current => ({ ...current, saleSubject: event.target.value as CreateDeal["saleSubject"] }))}><option value="FOREST">Mets</option><option value="LAND">Maa</option><option value="BOTH">Mets ja maa</option></select></label><LabeledInput label="Otsustaja / kontaktisik" value={form.decisionMaker ?? ""} onChange={value => setForm(current => ({ ...current, decisionMaker: value }))} /><LabeledInput label="Soovitud müügiaeg" placeholder="nt 1–3 kuu jooksul" value={form.saleTimeframe ?? ""} onChange={value => setForm(current => ({ ...current, saleTimeframe: value }))} /><LabeledInput label="Kliendi hinnasoov (€)" type="number" value={form.priceExpectation?.toString() ?? ""} onChange={value => setForm(current => ({ ...current, priceExpectation: value ? Number(value) : undefined }))} /><label className="flex items-end gap-2 pb-2 text-sm"><input type="checkbox" checked={form.requestEvaluation ?? true} onChange={event => setForm(current => ({ ...current, requestEvaluation: event.target.checked }))} /><span>Saada kohe hindaja tööjärjekorda <Badge variant="outline" className="ml-1">3 tööpäeva</Badge></span></label></div>
      <label className="grid gap-1 text-sm"><span className="font-medium">Kõne kokkuvõte ja kliendi eesmärk *</span><textarea className="min-h-24 rounded-md border bg-background p-3" value={form.qualificationNotes ?? ""} onChange={event => setForm(current => ({ ...current, qualificationNotes: event.target.value }))} /></label>
      <AsyncStatus state={createDeal.isPending ? "loading" : createDeal.isError ? "error" : "idle"} message={createDeal.isPending ? "Salvestan tehingujuhtumit…" : createDeal.error?.message} />
      <div className="flex flex-wrap items-center gap-3"><Button onClick={submit} disabled={createDeal.isPending || parcelIds.length === 0 || !form.qualificationNotes?.trim()}>{createDeal.isPending ? "Salvestan…" : form.requestEvaluation ? "Loo juhtum ja saada hindamisse" : "Loo tehingujuhtum"}</Button><p className="flex items-center gap-1 text-xs text-muted-foreground"><CalendarClock className="h-3.5 w-3.5" />Süsteem jätab ajajoone, vastutaja ja järgmise sammu automaatselt meelde.</p></div>
    </>}
  </CardContent></Card>;
}

function LabeledInput({ label, value, onChange, type = "text", placeholder }: { label: string; value: string; onChange: (value: string) => void; type?: string; placeholder?: string }) { return <label className="grid gap-1 text-sm"><span className="font-medium">{label}</span><Input type={type} min={type === "number" ? "0" : undefined} step={type === "number" ? "0.01" : undefined} value={value} placeholder={placeholder} onChange={event => onChange(event.target.value)} /></label>; }
