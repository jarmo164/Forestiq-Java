"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { ArrowLeft, Camera, CheckCircle2, Database } from "lucide-react";
import { useConfirmEvidence, useEvidencePreview, useEvidenceSnapshots } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

function stamp(value?: string | null) { return value ? new Date(value).toLocaleString("et-EE") : "—"; }
function number(value?: number | null) { return value == null ? "Teadmata" : new Intl.NumberFormat("et-EE", { maximumFractionDigits: 2 }).format(value); }

export default function DealEvidencePage() {
  const { id } = useParams<{ id: string }>();
  const preview = useEvidencePreview(id);
  const snapshots = useEvidenceSnapshots(id);
  const confirm = useConfirmEvidence(id);

  return (
    <div className="space-y-6">
      <Button asChild variant="ghost" className="-ml-3"><Link href={`/deals/${id}/history`}><ArrowLeft className="mr-2 h-4 w-4" />Tagasi tehingu ajalukku</Link></Button>
      <div><h1 className="text-2xl font-bold">Hindamise lähteandmete tõenduspilt</h1><p className="text-sm text-muted-foreground">Kontrolli praegust tõenduspilti ja külmuta see enne hindamisotsust. Kinnitatud versiooni hilisem registrivärskendus ei muuda.</p></div>

      {preview.isLoading && <p className="text-sm text-muted-foreground">Koostan serveris praegust tõenduspilti...</p>}
      {preview.isError && <p className="text-destructive">{preview.error.message}</p>}
      {preview.data && (
        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2"><Camera className="h-5 w-5" />Kinnitamise eelvaade</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-4">
              <Stat label="Valitud kinnistuid" value={String(preview.data.selectedParcelIds.length)} />
              <Stat label="Pindala" value={`${number(preview.data.portfolio.totalAreaHectares)} ha`} />
              <Stat label="Metsamaa" value={`${number(preview.data.portfolio.forestAreaHectares)} ha`} />
              <Stat label="Teadaolev maht" value={preview.data.portfolio.knownVolumeCubicMeters == null ? "Teadmata" : `${number(preview.data.portfolio.knownVolumeCubicMeters)} m³`} />
            </div>
            <div>
              <p className="mb-2 text-sm font-medium">Allikad ja vaatlusajad</p>
              <div className="grid gap-2 md:grid-cols-3">{preview.data.portfolio.sources.map(source => <div key={source.source} className="rounded-md border p-3"><p className="text-sm font-medium">{source.source}</p><p className="text-xs text-muted-foreground">{stamp(source.observedAt)}</p><p className="mt-1 text-xs">{source.note}</p></div>)}</div>
            </div>
            <div>
              <p className="mb-2 text-sm font-medium">Tähelepanusignaalid ({preview.data.signals.length})</p>
              <div className="space-y-2">{preview.data.signals.map((signal, index) => <div key={`${signal.code}-${index}`} className="rounded-md border p-3 text-sm"><div className="flex items-center gap-2"><Badge variant="outline">{signal.code}</Badge><strong>{signal.title}</strong></div><p>{signal.reason}</p><p className="text-xs text-muted-foreground">{signal.source} · {stamp(signal.observedAt)} · {signal.recommendedAction}</p></div>)}</div>
            </div>
            <p className="rounded-md bg-slate-50 p-3 text-xs text-muted-foreground">Snapshot sisaldab otsustoe lähteandmeid, mitte varjatud sisemist hinnapiiri ega õiguslikku otsust.</p>
            <Button onClick={() => confirm.mutate()} disabled={confirm.isPending}><CheckCircle2 className="mr-2 h-4 w-4" />Kinnita ja külmuta tõenduspilt</Button>
            {confirm.isSuccess && <p className="text-sm text-emerald-700">Tõenduspilt kinnitati ja on nüüd muutmatu.</p>}
            {confirm.isError && <p className="text-sm text-destructive">{confirm.error.message}</p>}
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><Database className="h-5 w-5" />Kinnitatud versioonid</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          {snapshots.isLoading && <p className="text-sm text-muted-foreground">Laadin ajalugu...</p>}
          {snapshots.data?.length === 0 && <p className="text-sm text-muted-foreground">Kinnitatud tõenduspilte veel pole.</p>}
          {snapshots.data?.map(snapshot => <details key={snapshot.id} className="rounded-md border p-3"><summary className="cursor-pointer text-sm font-medium">{stamp(snapshot.confirmedAt ?? snapshot.createdAt)} · skeem v{snapshot.schemaVersion}</summary><div className="mt-3 text-sm"><p>Kinnitas: {snapshot.confirmedBy ?? snapshot.createdBy}</p><p>Allika vaatlus: {stamp(snapshot.sourceObservedAt)}</p><p>Kinnistuid: {snapshot.snapshot.selectedParcelIds?.length ?? 0}; signaale: {snapshot.snapshot.signals?.length ?? 0}</p></div></details>)}
        </CardContent>
      </Card>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) { return <div className="rounded-md border p-3"><p className="text-xs text-muted-foreground">{label}</p><p className="font-semibold">{value}</p></div>; }
