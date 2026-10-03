"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { AlertTriangle, ArrowLeft, Database, MapPinned, Trees } from "lucide-react";
import { useDecisionSignals, usePortfolioBrief } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

function number(value?: number | null, digits = 2) {
  return value == null ? "—" : new Intl.NumberFormat("et-EE", { maximumFractionDigits: digits }).format(value);
}
function date(value?: string | null) {
  return value ? new Date(value).toLocaleString("et-EE") : "Andmed puuduvad";
}

export default function OwnerBriefPage() {
  const { id } = useParams<{ id: string }>();
  const brief = usePortfolioBrief(id);
  const signals = useDecisionSignals(id);

  if (brief.isLoading) return <p className="text-sm text-muted-foreground">Koostan portfellibriefi...</p>;
  if (brief.isError || !brief.data) return <p className="text-destructive">{brief.error?.message ?? "Portfellibriefi ei õnnestunud koostada."}</p>;
  const data = brief.data;

  return (
    <div className="space-y-6">
      <Button asChild variant="ghost" className="-ml-3"><Link href={`/owners/${id}`}><ArrowLeft className="mr-2 h-4 w-4" />Tagasi omaniku juurde</Link></Button>
      <div>
        <h1 className="text-2xl font-bold">{data.ownerName} · portfellibrief</h1>
        <p className="text-sm text-muted-foreground">Aktiivsed omandisuhted, teadaolevad registrinäitajad ja selgitatavad tähelepanusignaalid.</p>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-5">
        <Metric label="Kinnistuid" value={String(data.parcelCount)} />
        <Metric label="Pindala" value={`${number(data.totalAreaHectares)} ha`} />
        <Metric label="Metsamaa" value={`${number(data.forestAreaHectares)} ha`} />
        <Metric label="Teadaolev maht" value={data.knownVolumeCubicMeters == null ? "Puudub" : `${number(data.knownVolumeCubicMeters, 0)} m³`} detail={`${data.volumeKnownParcelCount}/${data.parcelCount} kinnistul maht teada`} />
        <Metric label="Aktiivseid teatisi" value={String(data.activeNotificationCount)} />
      </div>

      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><AlertTriangle className="h-5 w-5" />Tähelepanu vajavad asjaolud</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {signals.isLoading && <p className="text-sm text-muted-foreground">Kontrollin signaale...</p>}
          {signals.data?.length === 0 && <p className="text-sm text-muted-foreground">Praeguste reeglite järgi eraldi tähelepanusignaale ei ole.</p>}
          {signals.data?.map((signal, index) => (
            <div key={`${signal.code}-${signal.parcelId ?? signal.dealId ?? index}`} className="rounded-md border p-3">
              <div className="flex flex-wrap items-center gap-2"><strong>{signal.title}</strong><Badge variant="outline">{signal.code}</Badge><span className="text-xs text-muted-foreground">{signal.source} · {date(signal.observedAt)}</span></div>
              <p className="mt-1 text-sm">{signal.reason}</p>
              <p className="mt-1 text-sm text-muted-foreground"><strong>Soovitus:</strong> {signal.recommendedAction}</p>
              {signal.cadastralCode && <Button asChild variant="ghost" className="h-auto p-0 text-xs text-primary hover:bg-transparent hover:underline"><Link href={`/map?cadastralCode=${encodeURIComponent(signal.cadastralCode)}`}>Ava kinnistu kaardil</Link></Button>}
            </div>
          ))}
          <p className="rounded-md bg-amber-50 p-3 text-xs text-amber-900">Need on selgitatavad äri- ja kontrollsignaalid, mitte õiguslik otsus ega lõplik metsa või kinnistu väärtuse hinnang.</p>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><Trees className="h-5 w-5" />Portfelli kinnistud</CardTitle></CardHeader>
        <CardContent>
          <Table>
            <TableHeader><TableRow><TableHead>Katastritunnus</TableHead><TableHead>Pindala</TableHead><TableHead>Metsamaa</TableHead><TableHead>Maht</TableHead><TableHead>Teatised</TableHead><TableHead>Asukoht</TableHead></TableRow></TableHeader>
            <TableBody>{data.parcels.map(parcel => (
              <TableRow key={parcel.id}>
                <TableCell><Link className="font-medium text-primary hover:underline" href={`/map?cadastralCode=${encodeURIComponent(parcel.cadastralCode)}`}><MapPinned className="mr-1 inline h-3.5 w-3.5" />{parcel.cadastralCode}</Link></TableCell>
                <TableCell>{number(parcel.areaHectares)} ha</TableCell><TableCell>{number(parcel.forestAreaHectares)} ha</TableCell>
                <TableCell>{parcel.knownVolumeCubicMeters == null ? "Teadmata" : `${number(parcel.knownVolumeCubicMeters, 0)} m³`}</TableCell>
                <TableCell>{parcel.activeNotificationCount}</TableCell><TableCell>{parcel.county ?? "—"}</TableCell>
              </TableRow>
            ))}</TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><Database className="h-5 w-5" />Allikad ja värskus</CardTitle></CardHeader>
        <CardContent className="grid gap-3 md:grid-cols-3">{data.sources.map(source => (
          <div key={source.source} className="rounded-md border p-3"><p className="font-medium">{source.source}</p><p className="text-xs text-muted-foreground">{date(source.observedAt)}</p><p className="mt-2 text-sm">{source.note}</p></div>
        ))}</CardContent>
      </Card>
    </div>
  );
}

function Metric({ label, value, detail }: { label: string; value: string; detail?: string }) {
  return <Card><CardContent className="pt-4"><p className="text-xs text-muted-foreground">{label}</p><p className="text-xl font-semibold">{value}</p>{detail && <p className="text-xs text-muted-foreground">{detail}</p>}</CardContent></Card>;
}
