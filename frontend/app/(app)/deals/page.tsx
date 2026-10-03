"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import {
  useActiveContractSigning,
  useActiveDeals,
  useLoseDeal,
  useLossReasons,
  useLossReport,
  useUpdateDealNextAction,
  useUpdateLossReason,
  type DealWorkFilters,
} from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const stages = ["QUALIFIED", "EVALUATION_REQUESTED", "EVALUATING", "VALUED", "OFFER_PREPARED", "OFFER_SENT", "NEGOTIATION"];
const signingLabels: Record<string, string> = { PREPARING: "Ettevalmistamisel", SENT_FOR_SIGNATURE: "Allkirjastamisel" };

function dateTime(value?: string | null) {
  return value ? new Date(value).toLocaleString("et-EE", { dateStyle: "short", timeStyle: "short" }) : "—";
}

function healthLabel(value: string) {
  if (value === "CRITICAL") return "Kriitiline";
  if (value === "ATTENTION") return "Tähelepanu";
  return "Korras";
}

export default function DealsPage() {
  const { hasRole } = useAuth();
  const manager = hasRole("ORG_ADMIN", "CRM_MANAGER");
  const [stage, setStage] = useState("");
  const [assignee, setAssignee] = useState("");
  const [health, setHealth] = useState("");
  const [due, setDue] = useState("");
  const [minValue, setMinValue] = useState("");
  const [maxValue, setMaxValue] = useState("");
  const [lossReason, setLossReason] = useState<Record<string, string>>({});
  const [reportSales, setReportSales] = useState("");
  const [reportStage, setReportStage] = useState("");
  const [reportFrom, setReportFrom] = useState("");
  const [reportTo, setReportTo] = useState("");
  const [newReasonCode, setNewReasonCode] = useState("");
  const [newReasonLabel, setNewReasonLabel] = useState("");

  const filters: DealWorkFilters = useMemo(() => ({
    stage: stage || undefined,
    assignee: assignee || undefined,
    health: health || undefined,
    due: due || undefined,
    minValue: minValue ? Number(minValue) : undefined,
    maxValue: maxValue ? Number(maxValue) : undefined,
  }), [stage, assignee, health, due, minValue, maxValue]);

  const deals = useActiveDeals(filters);
  const signing = useActiveContractSigning();
  const { data: reasons = [] } = useLossReasons();
  const managedReasons = useLossReasons(true);
  const updateLossReason = useUpdateLossReason();
  const updateNext = useUpdateDealNextAction();
  const lose = useLoseDeal();
  const reportFilters = manager ? {
    from: reportFrom ? new Date(reportFrom).toISOString() : undefined,
    to: reportTo ? new Date(reportTo).toISOString() : undefined,
    salesSubject: reportSales || undefined,
    lostFromStage: reportStage || undefined,
  } : {};
  const report = useLossReport(reportFilters, manager);

  const changeNext = (dealId: string, current: string) => {
    const nextAction = window.prompt("Järgmine tegevus", current);
    if (!nextAction?.trim()) return;
    const rawDue = window.prompt("Tähtaeg, nt 2026-09-07T14:30");
    if (!rawDue) return;
    const parsed = new Date(rawDue);
    if (Number.isNaN(parsed.getTime())) return window.alert("Vigane kuupäev.");
    updateNext.mutate({ dealId, nextAction: nextAction.trim(), dueAt: parsed.toISOString() });
  };

  const closeLost = (dealId: string) => {
    const reasonCode = lossReason[dealId];
    if (!reasonCode) return;
    const note = window.prompt("Kaotuse märkus (soovi korral)") || undefined;
    const rawFollowUp = window.prompt("Järelkontakti aeg (soovi korral), nt 2026-10-01T10:00") || undefined;
    let followUpAt: string | undefined;
    if (rawFollowUp) {
      const parsed = new Date(rawFollowUp);
      if (Number.isNaN(parsed.getTime())) return window.alert("Vigane järelkontakti kuupäev.");
      followUpAt = parsed.toISOString();
    }
    lose.mutate({ dealId, reasonCode, note, followUpAt });
  };

  const createReason = () => {
    const code = newReasonCode.trim().toUpperCase().replace(/[^A-Z0-9_]/g, "_");
    if (!code || !newReasonLabel.trim()) return;
    updateLossReason.mutate({ code, label: newReasonLabel.trim(), active: true, sortOrder: 100 }, {
      onSuccess: () => { setNewReasonCode(""); setNewReasonLabel(""); },
    });
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Aktiivsed tehingud</h1>
        <p className="text-sm text-muted-foreground">Üks töövaade tehingu etapile, väärtusele, järgmisele sammule ning võidetud tehingute allkirjastamise lõppfaasile.</p>
      </div>

      <Card>
        <CardHeader><CardTitle>Allkirjastamisel lepingud</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {signing.isLoading && <p className="text-sm text-muted-foreground">Laadin allkirjastamise tööjärjekorda…</p>}
          {signing.isError && <p className="text-sm text-destructive">{signing.error.message}</p>}
          {signing.data?.map(item => <div key={item.contractId} className={`rounded-lg border p-4 ${item.overdue ? "border-amber-300 bg-amber-50/40" : ""}`}>
            <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between"><div className="space-y-1"><div className="flex flex-wrap items-center gap-2"><Link className="font-semibold text-primary hover:underline" href={`/deals/${item.dealId}`}>{item.ownerName}</Link><Badge variant="outline">Leping v{item.versionNo}</Badge><Badge variant={item.overdue ? "warning" : "secondary"}>{signingLabels[item.status] ?? item.status}</Badge></div><p className="text-sm">Vastutaja: {item.responsibleSubject} · tähtaeg {dateTime(item.dueAt)}</p>{item.stallReason && <p className="text-sm font-medium text-amber-900">Takerdumine: {item.stallReason}</p>}</div><Button asChild size="sm"><Link href={`/contracts/signing/${item.contractId}`}>Halda allkirjastamist</Link></Button></div>
          </div>)}
          {signing.data?.length === 0 && <p className="text-sm text-muted-foreground">Allkirjastamist ootavaid lepinguid ei ole.</p>}
        </CardContent>
      </Card>

      <Card>
        <CardContent className="grid gap-3 pt-5 md:grid-cols-3 xl:grid-cols-6">
          <select className="h-10 rounded-md border bg-background px-3 text-sm" value={stage} onChange={e => setStage(e.target.value)} aria-label="Etapp">
            <option value="">Kõik etapid</option>{stages.map(value => <option key={value}>{value}</option>)}
          </select>
          <Input placeholder="Vastutaja subject" value={assignee} onChange={e => setAssignee(e.target.value)} />
          <select className="h-10 rounded-md border bg-background px-3 text-sm" value={health} onChange={e => setHealth(e.target.value)} aria-label="Tervis"><option value="">Kõik tervised</option><option value="HEALTHY">Korras</option><option value="ATTENTION">Tähelepanu</option><option value="CRITICAL">Kriitiline</option></select>
          <select className="h-10 rounded-md border bg-background px-3 text-sm" value={due} onChange={e => setDue(e.target.value)} aria-label="Tähtaeg"><option value="">Kõik tähtajad</option><option value="OVERDUE">Üle tähtaja</option><option value="NEXT7">Järgmised 7 päeva</option><option value="MISSING">Tähtaeg puudub</option></select>
          <Input type="number" placeholder="Min väärtus €" value={minValue} onChange={e => setMinValue(e.target.value)} />
          <Input type="number" placeholder="Max väärtus €" value={maxValue} onChange={e => setMaxValue(e.target.value)} />
        </CardContent>
      </Card>

      <Card><CardContent className="pt-4">{deals.isLoading && <p className="text-sm text-muted-foreground">Laadin tehinguid…</p>}{deals.isError && <p className="text-sm text-destructive">{deals.error.message}</p>}<div className="space-y-4">{deals.data?.items.map(item => <div key={item.dealId} className="rounded-lg border p-4"><div className="flex flex-col gap-4 xl:flex-row xl:justify-between"><div className="min-w-0 space-y-2"><div className="flex flex-wrap items-center gap-2"><Link className="font-semibold text-primary hover:underline" href={`/deals/${item.dealId}`}>{item.ownerName}</Link><Badge variant="outline">{item.stage}</Badge><Badge variant={item.health === "CRITICAL" ? "warning" : item.health === "ATTENTION" ? "secondary" : "outline"}>{healthLabel(item.health)}</Badge></div><p className="text-sm">Kinnistud: {item.cadastralCodes.join(", ") || "—"}</p><p className="text-sm">Väärtus: {item.value == null ? "—" : `${Number(item.value).toLocaleString("et-EE")} €`} · müük: {item.salesSubject} {item.evaluatorSubject ? `· hindaja: ${item.evaluatorSubject}` : ""}</p><p className="text-sm"><strong>Järgmine:</strong> {item.nextAction || "—"} · {dateTime(item.nextActionDueAt)}</p>{item.healthReasons.length > 0 && <div className="space-y-2 pt-1">{item.healthReasons.map(reason => <div key={reason.code} className="rounded-md bg-muted/50 p-2 text-sm"><p className="font-medium">{reason.title}</p><p className="text-xs text-muted-foreground">Reegel: {reason.rule}{reason.relevantAt ? ` · ${dateTime(reason.relevantAt)}` : ""}</p><p className="mt-1 text-xs">Soovitus: {reason.recommendedAction}</p></div>)}</div>}</div><div className="flex min-w-64 flex-col gap-2"><Button size="sm" variant="outline" onClick={() => changeNext(item.dealId, item.nextAction)}>Muuda järgmist sammu</Button><div className="flex gap-2"><select className="h-9 min-w-0 flex-1 rounded-md border bg-background px-2 text-sm" value={lossReason[item.dealId] ?? ""} onChange={e => setLossReason(current => ({ ...current, [item.dealId]: e.target.value }))} aria-label="Kaotuse põhjus"><option value="">Kaotuse põhjus…</option>{reasons.map(reason => <option key={reason.code} value={reason.code}>{reason.label}</option>)}</select><Button size="sm" variant="outline" disabled={!lossReason[item.dealId] || lose.isPending} onClick={() => closeLost(item.dealId)}>Kaotatud</Button></div></div></div></div>)}</div>{deals.data?.items.length === 0 && <p className="text-sm text-muted-foreground">Filtrile vastavaid aktiivseid tehinguid ei ole.</p>}</CardContent></Card>

      {manager && <><Card><CardHeader><CardTitle>Kaotuse põhjuste haldus</CardTitle></CardHeader><CardContent className="space-y-4"><div className="grid gap-2 md:grid-cols-[1fr_2fr_auto]"><Input placeholder="Kood, nt FINANCING" value={newReasonCode} onChange={e => setNewReasonCode(e.target.value)} /><Input placeholder="Põhjuse nimetus" value={newReasonLabel} onChange={e => setNewReasonLabel(e.target.value)} /><Button onClick={createReason} disabled={updateLossReason.isPending || !newReasonCode.trim() || !newReasonLabel.trim()}>Lisa põhjus</Button></div><div className="space-y-2">{managedReasons.data?.map(reason => <div key={reason.code} className="flex flex-wrap items-center gap-2 rounded-md border p-3"><code className="text-xs">{reason.code}</code><span className="min-w-48 flex-1">{reason.label}</span><Badge variant={reason.active ? "outline" : "secondary"}>{reason.active ? "Aktiivne" : "Väljas"}</Badge><Button size="sm" variant="outline" onClick={() => updateLossReason.mutate({ ...reason, active: !reason.active })}>{reason.active ? "Lülita välja" : "Aktiveeri"}</Button></div>)}</div></CardContent></Card><Card><CardHeader><CardTitle>Kaotatud võimaluste põhjused</CardTitle></CardHeader><CardContent className="space-y-4"><div className="grid gap-3 md:grid-cols-4"><Input type="datetime-local" value={reportFrom} onChange={e => setReportFrom(e.target.value)} aria-label="Raport alates" /><Input type="datetime-local" value={reportTo} onChange={e => setReportTo(e.target.value)} aria-label="Raport kuni" /><Input placeholder="Müügivastutaja subject" value={reportSales} onChange={e => setReportSales(e.target.value)} /><select className="h-10 rounded-md border bg-background px-3 text-sm" value={reportStage} onChange={e => setReportStage(e.target.value)} aria-label="Kaotuse eelne etapp"><option value="">Kõik eelnevad etapid</option>{stages.map(value => <option key={value}>{value}</option>)}</select></div><Table><TableHeader><TableRow><TableHead>Põhjus</TableHead><TableHead className="text-right">Tehinguid</TableHead></TableRow></TableHeader><TableBody>{report.data?.map(row => <TableRow key={row.reasonCode}><TableCell>{row.reasonLabel}</TableCell><TableCell className="text-right">{row.count}</TableCell></TableRow>)}</TableBody></Table></CardContent></Card></>}
    </div>
  );
}
