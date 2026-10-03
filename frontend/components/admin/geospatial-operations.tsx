"use client";

import { useMemo } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { useGeospatialSources, useStartGeospatialRefresh, type GeospatialSourceStatus } from "@/lib/api";

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString("et-EE") : "—";
}

function duration(seconds?: number) {
  if (seconds === undefined || seconds === null || seconds < 0) return "—";
  if (seconds >= 86_400) return `${Math.floor(seconds / 86_400)} p ${Math.floor((seconds % 86_400) / 3_600)} h`;
  if (seconds >= 3_600) return `${Math.floor(seconds / 3_600)} h ${Math.floor((seconds % 3_600) / 60)} min`;
  if (seconds >= 60) return `${Math.floor(seconds / 60)} min`;
  return `${seconds} s`;
}

function bytes(value?: number) {
  if (!value) return "—";
  if (value >= 1024 * 1024 * 1024) return `${(value / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  if (value >= 1024 * 1024) return `${(value / (1024 * 1024)).toFixed(1)} MB`;
  if (value >= 1024) return `${(value / 1024).toFixed(1)} kB`;
  return `${value} B`;
}

function freshnessVariant(state: string): "default" | "warning" | "outline" | "destructive" {
  if (state === "FRESH") return "default";
  if (state === "STALE" || state === "DEGRADED") return "warning";
  if (state === "NEVER_SUCCESSFUL") return "outline";
  return "outline";
}

function SourceRows({ source }: { source: GeospatialSourceStatus }) {
  const refresh = useStartGeospatialRefresh();
  const latest = source.latestRun;
  const schemaDrift = Boolean(latest?.errorSummary?.toLowerCase().includes("schema drift"));

  const start = (forceSchemaDrift = false) => {
    let reason: string | undefined;
    if (forceSchemaDrift) {
      reason = window.prompt("Kirjelda, miks provider schema muutus on kontrollitud ja avaldamiseks sobiv:") || undefined;
      if (!reason || reason.trim().length < 10) return;
    }
    refresh.mutate({ sourceKey: source.sourceKey, forceSchemaDrift, reason });
  };

  return <>
    <TableRow>
      <TableCell className="align-top">
        <div className="font-medium">{source.displayName}</div>
        <div className="mt-1 text-xs text-muted-foreground">{source.sourceKey} · {source.sourceKind}</div>
        {source.businessPurpose && <div className="mt-1 max-w-md text-xs">{source.businessPurpose}</div>}
        {source.caveat && <div className="mt-1 max-w-md text-xs text-amber-700">{source.caveat}</div>}
      </TableCell>
      <TableCell className="align-top text-xs">
        <Badge variant={source.enabled ? "default" : "outline"}>{source.enabled ? "Aktiivne" : "Väljas"}</Badge>
        <div className="mt-1">Iga {duration(source.cadenceSeconds)}</div>
        <div className="text-muted-foreground">SLO {duration(source.freshnessTargetSeconds)}</div>
      </TableCell>
      <TableCell className="align-top text-xs">
        <Badge variant={freshnessVariant(source.freshnessState)}>{source.freshnessState}</Badge>
        <div className="mt-1">Viimane edu {formatDate(source.latestSuccessAt)}</div>
        <div className="text-muted-foreground">Vanus {duration(source.latestSuccessAgeSeconds)}</div>
      </TableCell>
      <TableCell className="align-top text-xs">
        {source.currentGeneration ? <>
          <div className="font-mono">{source.currentGeneration.generationId.slice(0, 8)}…</div>
          <div>Andmeseis {source.currentGeneration.sourceDate || "—"}</div>
          <div>{source.currentGeneration.rowCount?.toLocaleString("et-EE") || 0} rida · {bytes(source.currentGeneration.bytes)}</div>
          <div className="text-muted-foreground" title={source.currentGeneration.checksum || ""}>
            SHA {source.currentGeneration.checksum?.slice(0, 12) || "—"}…
          </div>
        </> : <span className="text-muted-foreground">Avaldatud generatsioon puudub</span>}
      </TableCell>
      <TableCell className="align-top text-xs">
        {latest ? <>
          <Badge variant={latest.status === "SUCCESS" ? "default" : latest.status === "RUNNING" ? "warning" : "outline"}>{latest.status}</Badge>
          <div className="mt-1">{latest.triggerType} · {formatDate(latest.startedAt || latest.createdAt)}</div>
          <div>{latest.rowsRead?.toLocaleString("et-EE") || 0} loetud · {latest.rowsInserted?.toLocaleString("et-EE") || 0} lisatud</div>
          {latest.errorSummary && <div className="mt-1 max-w-sm text-destructive">{latest.errorSummary}</div>}
        </> : "Pole veel käivitatud"}
      </TableCell>
      <TableCell className="align-top">
        <div className="flex flex-col gap-2">
          <Button size="sm" variant="outline" disabled={!source.manualSupported || source.running || refresh.isPending}
            onClick={() => start(false)}>
            {source.running ? "Sünkroonimine käib" : "Käivita sünkroonimine"}
          </Button>
          {schemaDrift && source.manualSupported && <Button size="sm" variant="destructive" disabled={source.running || refresh.isPending}
            onClick={() => start(true)}>Kontrollitud schema-force…</Button>}
        </div>
      </TableCell>
    </TableRow>
    {source.recentRuns.length > 1 && <TableRow className="bg-muted/20">
      <TableCell colSpan={6} className="py-2 text-xs text-muted-foreground">
        Ajalugu: {source.recentRuns.map(run => `${run.status} ${formatDate(run.completedAt || run.createdAt)}${run.durationMs ? ` (${Math.round(run.durationMs / 1000)} s)` : ""}`).join(" · ")}
      </TableCell>
    </TableRow>}
  </>;
}

export function GeospatialOperationsWorkspace() {
  const sources = useGeospatialSources();
  const grouped = useMemo(() => {
    const values = sources.data || [];
    return {
      cadastre: values.filter(source => source.sourceKey === "MARU_CADASTRE"),
      etak: values.filter(source => source.sourceKey.startsWith("MARU_ETAK_")),
      admin: values.filter(source => source.sourceKey.startsWith("MARU_ADMIN_")),
      basemap: values.filter(source => source.sourceKey === "MARU_BASEMAP_METADATA"),
    };
  }, [sources.data]);

  return <Card>
    <CardHeader>
      <CardTitle>Ruumiandmete teenused</CardTitle>
    </CardHeader>
    <CardContent className="space-y-4">
      <p className="text-sm text-muted-foreground">
        Kuvatakse ainult avaldatud current-generatsioon ja eraldi viimane run. FAILED või PARTIAL refresh ei asenda viimast head generatsiooni.
        Käsitsi käivitus kasutab sama PostgreSQL lukku ja sama run/generation rada nagu scheduler.
      </p>
      {sources.isLoading && <p className="text-sm text-muted-foreground">Laadin ruumiandmete olekut…</p>}
      {sources.isError && <p className="text-sm text-destructive">Ruumiandmete teenuste olekut ei õnnestunud laadida.</p>}
      {sources.data && <div className="space-y-5">
        {([
          ["Kadaster", grouped.cadastre],
          ["ETAK kontekstikihid", grouped.etak],
          ["Haldusjaotus", grouped.admin],
          ["Aluskaardi metaandmed", grouped.basemap],
        ] as const).map(([title, rows]) => rows.length > 0 && <section key={title}>
          <h3 className="mb-2 text-sm font-semibold">{title}</h3>
          <Table>
            <TableHeader><TableRow>
              <TableHead>Allikas</TableHead><TableHead>Ajakava</TableHead><TableHead>Värskus</TableHead>
              <TableHead>Current generatsioon</TableHead><TableHead>Viimane run</TableHead><TableHead></TableHead>
            </TableRow></TableHeader>
            <TableBody>{rows.map(source => <SourceRows key={source.sourceKey} source={source} />)}</TableBody>
          </Table>
        </section>)}
      </div>}
    </CardContent>
  </Card>;
}
