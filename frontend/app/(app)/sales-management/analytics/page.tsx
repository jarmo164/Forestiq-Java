"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useForecast, useFunnel, useFunnelCases, useStageProbabilities, useUpdateStageProbability } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

function iso(value: string, end = false) {
  if (!value) return undefined;
  const date = new Date(`${value}T${end ? "23:59:59" : "00:00:00"}`);
  return date.toISOString();
}
function money(value?: number | null) { return value == null ? "—" : `${Number(value).toLocaleString("et-EE", { maximumFractionDigits: 0 })} €`; }
function pct(value?: number | null) { return value == null ? "—" : `${(Number(value) * 100).toFixed(1)}%`; }

export default function SalesAnalyticsPage() {
  const [from, setFrom] = useState(() => { const d = new Date(); d.setDate(d.getDate() - 90); return d.toISOString().slice(0, 10); });
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 10));
  const [salesSubject, setSalesSubject] = useState("");
  const [probabilityDraft, setProbabilityDraft] = useState<Record<string, string>>({});
  const filters = useMemo(() => ({ from: iso(from), to: iso(to, true), salesSubject: salesSubject || undefined }), [from, to, salesSubject]);
  const funnel = useFunnel(filters);
  const cases = useFunnelCases(filters);
  const forecast = useForecast(filters);
  const probabilities = useStageProbabilities(false);
  const updateProbability = useUpdateStageProbability();

  return <div className="space-y-6">
    <div className="flex flex-wrap items-end justify-between gap-3">
      <div><h1 className="text-2xl font-bold">Müügianalüütika</h1><p className="text-sm text-muted-foreground">Ajalooliste sündmuste põhine funnel, tsükliajad ja väärtusprognoos.</p></div>
      <Button asChild variant="outline"><Link href="/sales-management/segments">Sihtrühmad ja tööjaotus</Link></Button>
    </div>

    <Card><CardContent className="grid gap-3 pt-5 md:grid-cols-3">
      <label className="grid gap-1 text-sm"><span>Alates</span><Input type="date" value={from} onChange={e => setFrom(e.target.value)} /></label>
      <label className="grid gap-1 text-sm"><span>Kuni</span><Input type="date" value={to} onChange={e => setTo(e.target.value)} /></label>
      <label className="grid gap-1 text-sm"><span>Müügivastutaja subject</span><Input value={salesSubject} onChange={e => setSalesSubject(e.target.value)} placeholder="Kõik" /></label>
    </CardContent></Card>

    <div className="grid gap-4 md:grid-cols-3">
      {funnel.data?.steps.map(step => <Card key={step.code}><CardHeader><CardTitle className="text-base">{step.label}</CardTitle></CardHeader><CardContent className="space-y-2"><div className="text-2xl font-bold">{pct(step.conversionRate)}</div><p className="text-sm">{step.advanced} / {step.entered} juhtumit liikus edasi</p><p className="text-sm text-muted-foreground">Mediaan {Number(step.medianCycleHours).toLocaleString("et-EE", { maximumFractionDigits: 1 })} h</p></CardContent></Card>)}
    </div>

    <Card><CardHeader><CardTitle>Funneli juhtumid</CardTitle></CardHeader><CardContent>
      <Table><TableHeader><TableRow><TableHead>Omanik</TableHead><TableHead>Vastutaja</TableHead><TableHead>Praegune etapp</TableHead><TableHead>Kvalifitseeritud</TableHead><TableHead>Hindamine</TableHead><TableHead>Pakkumine</TableHead><TableHead>Tulemus</TableHead></TableRow></TableHeader>
        <TableBody>{cases.data?.map(item => <TableRow key={item.dealId}><TableCell><Link className="text-primary hover:underline" href={`/deals/${item.dealId}`}>{item.ownerName}</Link></TableCell><TableCell>{item.salesSubject}</TableCell><TableCell><Badge variant="outline">{item.currentStage}</Badge></TableCell><TableCell>{item.qualifiedAt ? new Date(item.qualifiedAt).toLocaleDateString("et-EE") : "—"}</TableCell><TableCell>{item.evaluationAt ? new Date(item.evaluationAt).toLocaleDateString("et-EE") : "—"}</TableCell><TableCell>{item.offerAt ? new Date(item.offerAt).toLocaleDateString("et-EE") : "—"}</TableCell><TableCell>{item.outcome || "—"}</TableCell></TableRow>)}</TableBody>
      </Table>
    </CardContent></Card>

    <div className="grid gap-6 xl:grid-cols-[1.4fr_1fr]">
      <Card><CardHeader><CardTitle>Kaalutud prognoos</CardTitle></CardHeader><CardContent className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-4"><Metric label="Sisendväärtus" value={money(forecast.data?.totalInputValue)} /><Metric label="Kaalutud" value={money(forecast.data?.totalWeightedValue)} /><Metric label="Võidetud" value={money(forecast.data?.totalWonValue)} /><Metric label="Hälve" value={money(forecast.data?.wonVariance)} /></div>
        <Table><TableHeader><TableRow><TableHead>Omanik</TableHead><TableHead>Etapp</TableHead><TableHead>Väärtuse allikas</TableHead><TableHead>Sisend</TableHead><TableHead>Tõenäosus</TableHead><TableHead>Kaalutud</TableHead></TableRow></TableHeader><TableBody>{forecast.data?.items.map(item => <TableRow key={item.dealId}><TableCell><Link className="text-primary hover:underline" href={`/deals/${item.dealId}`}>{item.ownerName}</Link></TableCell><TableCell>{item.stage}</TableCell><TableCell>{item.valueKind}</TableCell><TableCell>{money(item.inputValue)}</TableCell><TableCell>{pct(item.probability)} <span className="text-xs text-muted-foreground">v{item.probabilityVersion}</span></TableCell><TableCell>{money(item.weightedValue)}</TableCell></TableRow>)}</TableBody></Table>
      </CardContent></Card>

      <Card><CardHeader><CardTitle>Etapitõenäosused</CardTitle></CardHeader><CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">Iga muudatus loob uue versiooni; vana tõenäosus jääb ajalukku.</p>
        {probabilities.data?.map(item => <div key={item.stage} className="rounded-md border p-3"><div className="flex items-center justify-between gap-2"><div><p className="font-medium">{item.stage}</p><p className="text-xs text-muted-foreground">Praegu {pct(item.probability)} · v{item.versionNo}</p></div><div className="flex gap-2"><Input className="w-24" type="number" min="0" max="100" step="1" value={probabilityDraft[item.stage] ?? String(Math.round(item.probability * 100))} onChange={e => setProbabilityDraft(current => ({ ...current, [item.stage]: e.target.value }))} /><Button size="sm" disabled={updateProbability.isPending} onClick={() => { const value = Number(probabilityDraft[item.stage] ?? item.probability * 100); if (value >= 0 && value <= 100) updateProbability.mutate({ stage: item.stage, probability: value / 100 }); }}>Salvesta %</Button></div></div></div>)}
      </CardContent></Card>
    </div>
  </div>;
}

function Metric({ label, value }: { label: string; value: string }) { return <div className="rounded-md border p-3"><p className="text-xs text-muted-foreground">{label}</p><p className="text-lg font-semibold">{value}</p></div>; }
