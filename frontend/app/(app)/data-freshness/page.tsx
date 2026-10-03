"use client";

import Link from "next/link";
import { AlertTriangle, CheckCircle2, RefreshCw, ShieldAlert } from "lucide-react";
import { useMetsisPortfolioStatus, useMetsisPortfolioSync, useRegistryFreshness, useRegistryFreshnessRecovery } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

function formatDate(value?: string) {
  return value ? new Intl.DateTimeFormat("et-EE", { dateStyle: "short", timeStyle: "short" }).format(new Date(value)) : "—";
}

export default function DataFreshnessPage() {
  const freshness = useRegistryFreshness();
  const recovery = useRegistryFreshnessRecovery();
  const portfolioStatus = useMetsisPortfolioStatus();
  const portfolioSync = useMetsisPortfolioSync();
  const data = freshness.data;
  const sources = data?.sources ?? [];
  const staleParcels = data?.staleParcels ?? [];
  const recoveryFailed = recovery.data?.failed ?? 0;
  const portfolioSyncFailed = portfolioSync.data?.failed ?? 0;

  const runRecovery = () => recovery.mutate(25);
  const runPortfolioSync = () => portfolioSync.mutate();

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight">Andmete värskus</h1>
          <p className="mt-1 text-muted-foreground">Ametlike Maa-ameti ja metsaregistri andmete seis enne tööjaotust.</p>
        </div>
        <Button onClick={runRecovery} disabled={recovery.isPending}>
          <RefreshCw className={"mr-2 h-4 w-4 " + (recovery.isPending ? "animate-spin" : "")} />
          Taasta aegunud andmed
        </Button>
      </div>

      {freshness.isLoading && <p className="text-muted-foreground">Laen andmete värskuse seisu…</p>}
      {freshness.error && <div className="rounded-md border border-destructive/30 bg-destructive/10 p-4 text-sm text-destructive">Värskuse ülevaadet ei saanud laadida: {freshness.error.message}</div>}
      {recovery.data && <div className={"rounded-md border p-4 text-sm " + (recoveryFailed > 0 ? "border-amber-500/40 bg-amber-50" : "border-emerald-500/30 bg-emerald-50")}>{recovery.data.message} Kontrollitud: {recovery.data.considered ?? 0}, õnnestus: {recovery.data.successful ?? 0}, ebaõnnestus: {recoveryFailed}.</div>}

      <Card>
        <CardHeader className="flex flex-row items-start justify-between gap-4 space-y-0">
          <div><CardTitle>MetsIS omaniku–katastri portfell</CardTitle><p className="mt-1 text-sm text-muted-foreground">Kanoniline välisportfell, millest luuakse enne tööjaotust ForestIQ omaniku–kinnistu seosed ja töösignaalid.</p></div>
          <Button onClick={runPortfolioSync} disabled={portfolioSync.isPending || !portfolioStatus.data?.configured} variant="outline"><RefreshCw className={"mr-2 h-4 w-4 " + (portfolioSync.isPending ? "animate-spin" : "")} />Sünkroniseeri portfell</Button>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          {portfolioStatus.isLoading && <p className="text-muted-foreground">Laen MetsIS portfelli seisu…</p>}
          {portfolioStatus.error && <p className="text-destructive">MetsIS portfelli seis pole saadaval: {portfolioStatus.error.message}</p>}
          {portfolioStatus.data && <>
            {!portfolioStatus.data.configured && <div className="rounded-md border border-amber-500/40 bg-amber-50 p-3 text-amber-900"><strong>Portfellisünkroniseerimine pole seadistatud.</strong> Lisa serverikeskkonda MetsIS API URL, autorisatsioon ja seotud ForestIQ organisatsiooni UUID.</div>}
            <div className="grid gap-3 md:grid-cols-4"><div><p className="text-muted-foreground">Viimane õnnestumine</p><strong>{formatDate(portfolioStatus.data.lastSuccessAt)}</strong></div><div><p className="text-muted-foreground">Viimane portfell</p><strong>{portfolioStatus.data.lastRowsSeen ?? 0} omanikurida</strong></div><div><p className="text-muted-foreground">Uusi seoseid</p><strong>{portfolioStatus.data.lastRelationsAdded ?? 0}</strong></div><div><p className="text-muted-foreground">Suletud seoseid</p><strong>{portfolioStatus.data.lastRelationsClosed ?? 0}</strong></div></div>
            {portfolioStatus.data.lastError && <p className="rounded-md border border-destructive/30 bg-destructive/10 p-3 text-destructive">Viimane tõrge: {portfolioStatus.data.lastError}</p>}
          </>}
          {portfolioSync.data && <p className={"rounded-md border p-3 " + (portfolioSyncFailed > 0 ? "border-amber-500/40 bg-amber-50" : "border-emerald-500/30 bg-emerald-50")}>{portfolioSync.data.message} Töödeldud: {portfolioSync.data.rows ?? 0}, uusi seoseid: {portfolioSync.data.relationsAdded ?? 0}, suletud seoseid: {portfolioSync.data.relationsClosed ?? 0}.</p>}
        </CardContent>
      </Card>

      {data && <>
        {!data.automaticRefreshEnabled && (
          <div className="flex gap-3 rounded-md border border-amber-500/40 bg-amber-50 p-4 text-sm text-amber-900">
            <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0" />
            <div><strong>Automaatne värskendus on välja lülitatud.</strong> Käsitsi taastamine töötab, kuid püsiva konkurentsieelise jaoks tuleb tootmiskeskkonnas aktiveerida <code>REGISTRY_AUTO_REFRESH_ENABLED=true</code>.</div>
          </div>
        )}

        <div className="grid gap-4 md:grid-cols-3">
          {sources.map(source => {
            const staleCount = source.staleParcels ?? 0;
            const failureCount = source.failuresLast24Hours ?? 0;
            const healthy = staleCount === 0 && failureCount === 0;
            return <Card key={source.registry}>
              <CardHeader className="pb-3">
                <CardTitle className="flex items-center justify-between text-base">{source.label}{healthy ? <CheckCircle2 className="h-5 w-5 text-emerald-600" /> : <AlertTriangle className="h-5 w-5 text-amber-600" />}</CardTitle>
                <p className="text-sm text-muted-foreground">Värskuse SLA: kuni {source.maxAgeHours ?? 0} tundi</p>
              </CardHeader>
              <CardContent className="space-y-2 text-sm">
                <div className="flex justify-between"><span>Värskeid kinnistuid</span><strong>{source.freshParcels ?? 0} / {source.totalParcels ?? 0}</strong></div>
                <div className="flex justify-between"><span>Aegunud</span><Badge variant={staleCount > 0 ? "warning" : "secondary"}>{staleCount}</Badge></div>
                <div className="flex justify-between"><span>Viimane õnnestumine</span><span>{formatDate(source.lastSuccessfulSyncAt)}</span></div>
                <div className="flex justify-between"><span>Tõrkeid 24 h</span><span>{failureCount}</span></div>
              </CardContent>
            </Card>;
          })}
        </div>

        <Card>
          <CardHeader>
            <CardTitle>Prioriteetsed aegunud kinnistud</CardTitle>
            <p className="text-sm text-muted-foreground">Taastamispartii eelistab kinnistuid, millel on avatud andmesignaal ja mis võivad jõuda järgmisesse helistajate töövalikusse.</p>
          </CardHeader>
          <CardContent>
            {staleParcels.length === 0 ? <p className="text-sm text-muted-foreground">Kõik jälgitavad kinnistuandmed vastavad praegusele SLA-le.</p> : (
              <Table><TableHeader><TableRow><TableHead>Katastritunnus</TableHead><TableHead>Kinnistu</TableHead><TableHead>Aegunud allikad</TableHead><TableHead></TableHead></TableRow></TableHeader>
                <TableBody>{staleParcels.map(parcel => <TableRow key={parcel.cadastralCode}><TableCell className="font-mono text-xs">{parcel.cadastralCode}</TableCell><TableCell>{parcel.name || "—"}</TableCell><TableCell className="space-x-1">{(parcel.staleSources ?? []).map(source => <Badge key={source} variant="outline">{source}</Badge>)}</TableCell><TableCell>{parcel.ownerId && <Link className="text-sm text-primary hover:underline" href={`/owners/${parcel.ownerId}`}>Ava töökaart</Link>}</TableCell></TableRow>)}</TableBody>
              </Table>
            )}
            <p className="mt-4 text-xs text-muted-foreground">Aktiivseid andmesignaale enne tööjaotust: {data.openDataSignals ?? 0}. Ülevaade arvutati: {formatDate(data.generatedAt)}.</p>
          </CardContent>
        </Card>
      </>}
    </div>
  );
}
