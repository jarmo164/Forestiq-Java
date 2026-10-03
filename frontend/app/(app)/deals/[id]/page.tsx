"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useDealWorkItem } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

function fmt(value?: string | null) {
  return value ? new Date(value).toLocaleString("et-EE", { dateStyle: "medium", timeStyle: "short" }) : "—";
}

export default function DealDetailPage() {
  const params = useParams<{ id: string }>();
  const deal = useDealWorkItem(params.id);

  if (deal.isLoading) return <p className="text-sm text-muted-foreground">Laadin tehingut…</p>;
  if (deal.isError) return <p className="text-sm text-destructive">{deal.error.message}</p>;
  if (!deal.data) return null;
  const item = deal.data;

  return <div className="space-y-6">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-2xl font-bold">{item.ownerName}</h1>
        <p className="text-sm text-muted-foreground">Tehing {item.dealId}</p>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button asChild variant="outline"><Link href={`/deals/${item.dealId}/history`}>Otsustusajalugu</Link></Button>
        <Button asChild variant="outline"><Link href={`/deals/${item.dealId}/evidence`}>Tõenduspilt</Link></Button>
        {item.stage === "WON" && <Button asChild><Link href={`/contracts?dealId=${item.dealId}`}>Koosta leping</Link></Button>}
      </div>
    </div>

    <Card><CardContent className="grid gap-4 pt-5 md:grid-cols-2 xl:grid-cols-4">
      <div><p className="text-xs text-muted-foreground">Etapp</p><Badge variant="outline">{item.stage}</Badge></div>
      <div><p className="text-xs text-muted-foreground">Tervis</p><Badge variant={item.health === "CRITICAL" ? "warning" : "secondary"}>{item.health}</Badge></div>
      <div><p className="text-xs text-muted-foreground">Väärtus</p><p className="font-medium">{item.value == null ? "—" : `${Number(item.value).toLocaleString("et-EE")} €`}</p></div>
      <div><p className="text-xs text-muted-foreground">Vastutaja</p><p className="font-medium">{item.salesSubject}</p></div>
      <div className="md:col-span-2"><p className="text-xs text-muted-foreground">Kinnistud</p><p>{item.cadastralCodes.join(", ") || "—"}</p></div>
      <div><p className="text-xs text-muted-foreground">Järgmine tegevus</p><p>{item.nextAction || "—"}</p></div>
      <div><p className="text-xs text-muted-foreground">Tähtaeg</p><p>{fmt(item.nextActionDueAt)}</p></div>
    </CardContent></Card>

    <Card><CardHeader><CardTitle>Tehingu tervise põhjendus</CardTitle></CardHeader><CardContent className="space-y-3">
      {item.healthReasons.length === 0 && <p className="text-sm text-muted-foreground">Aktiivseid riskisignaale ei ole.</p>}
      {item.healthReasons.map(reason => <div key={reason.code} className="rounded-md border p-3">
        <div className="flex flex-wrap items-center gap-2"><Badge variant={reason.severity === "CRITICAL" ? "warning" : "secondary"}>{reason.severity}</Badge><strong>{reason.title}</strong></div>
        <p className="mt-2 text-sm">{reason.rule}</p>
        {reason.relevantAt && <p className="text-xs text-muted-foreground">Asjakohane aeg: {fmt(reason.relevantAt)}</p>}
        <p className="mt-1 text-sm">Soovitatud tegevus: {reason.recommendedAction}</p>
      </div>)}
    </CardContent></Card>
  </div>;
}
