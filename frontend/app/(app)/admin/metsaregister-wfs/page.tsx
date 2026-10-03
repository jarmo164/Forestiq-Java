"use client";

import Link from "next/link";
import { useMemo } from "react";
import {
  useIntegrationJobs,
  useMetsaregisterWfsLayers,
  useRefreshAllMetsaregisterWfsLayers,
  useRefreshMetsaregisterWfsLayer,
} from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

export default function MetsaregisterWfsAdminPage() {
  const layers = useMetsaregisterWfsLayers();
  const jobs = useIntegrationJobs();
  const refreshLayer = useRefreshMetsaregisterWfsLayer();
  const refreshAll = useRefreshAllMetsaregisterWfsLayers();

  const mirrorJob = useMemo(
    () => (jobs.data ?? []).find(job => job.key === "METSAREGISTER_WFS_MIRROR"),
    [jobs.data],
  );
  const running = Boolean(mirrorJob?.currentRun);
  const rows = layers.data ?? [];
  const healthy = rows.filter(layer => layer.freshnessState === "HEALTHY").length;
  const syncing = rows.filter(layer => layer.freshnessState === "SYNCING").length;
  const due = rows.filter(layer => layer.due && layer.enabled && layer.approved).length;
  const failed = rows.filter(layer => ["FAILED", "SCHEMA_DRIFT", "UNSUPPORTED", "MISSING"].includes(layer.freshnessState)).length;

  const startAll = async () => {
    if (running || refreshAll.isPending) return;
    if (!window.confirm(
      "Käivitan kõigi kinnitatud Metsaregistri WFS kihtide sunnitud refreshi. " +
      "Mahukad kihid jätkavad vajadusel järgmise käivitusega checkpointist. Jätkan?",
    )) return;
    await refreshAll.mutateAsync();
    await layers.refetch();
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Metsaregistri WFS kihid</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Provider-global PostGIS peegel. Lähteread salvestatakse ühe korra ja tenantide vaated kasutavad sama current-generation andmestikku.
          </p>
        </div>
        <div className="flex gap-2">
          <Button asChild variant="outline"><Link href="/admin">Tagasi haldusesse</Link></Button>
          <Button onClick={startAll} disabled={running || refreshAll.isPending || rows.length === 0}>
            {running || refreshAll.isPending ? "Sünkroonimine käib..." : "Refreshi kõik kihid"}
          </Button>
        </div>
      </div>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Metric title="Kihte kataloogis" value={rows.length} />
        <Metric title="Värsked" value={healthy} />
        <Metric title="Due / aegunud" value={due} />
        <Metric title="Viga / drift" value={failed} detail={syncing > 0 ? `${syncing} sünkroonimisel` : undefined} />
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Kihipõhine olek ja refresh-poliitika</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {layers.isLoading && <p className="text-sm text-muted-foreground">Laadin Metsaregistri kihtide olekut...</p>}
          {layers.isError && <p className="text-sm text-destructive">Kihtide oleku laadimine ebaõnnestus.</p>}
          {rows.length > 0 && (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Kiht</TableHead>
                  <TableHead>Olek</TableHead>
                  <TableHead>Read</TableHead>
                  <TableHead>Refresh</TableHead>
                  <TableHead>Checkpoint</TableHead>
                  <TableHead>Viimane edu</TableHead>
                  <TableHead>Viga</TableHead>
                  <TableHead></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map(layer => {
                  const canStart = layer.approved && layer.enabled && layer.providerPresent && !running && !refreshLayer.isPending;
                  const rowMismatch = layer.providerNumberMatched !== undefined
                    && layer.currentRowCount !== undefined
                    && layer.providerNumberMatched !== layer.currentRowCount;
                  return (
                    <TableRow key={layer.layerName}>
                      <TableCell className="max-w-sm">
                        <div className="font-medium">{layer.title}</div>
                        <div className="font-mono text-xs text-muted-foreground">{layer.layerName}</div>
                        <div className="mt-1 text-xs text-muted-foreground">
                          võti {layer.identityProperty || "—"}{layer.geometryType ? ` · ${layer.geometryType}` : " · mitte-ruumiline"}
                        </div>
                      </TableCell>
                      <TableCell>
                        <Badge variant={badgeVariant(layer.freshnessState)}>{layer.freshnessState}</Badge>
                        {!layer.approved && <div className="mt-1 text-xs text-muted-foreground">pole kinnitatud</div>}
                        {!layer.providerPresent && <div className="mt-1 text-xs text-destructive">providerist puudu</div>}
                      </TableCell>
                      <TableCell className="text-sm">
                        <div>{formatNumber(layer.currentRowCount)} / {formatNumber(layer.providerNumberMatched)}</div>
                        <div className={rowMismatch ? "text-xs text-destructive" : "text-xs text-muted-foreground"}>
                          DB / provider{rowMismatch ? " · erinevus" : ""}
                        </div>
                      </TableCell>
                      <TableCell className="text-sm">
                        <div>{formatInterval(layer.refreshIntervalMinutes)}</div>
                        <div className="text-xs text-muted-foreground">prioriteet {layer.refreshPriority ?? "—"}</div>
                        {layer.due && layer.enabled && <Badge className="mt-1" variant="warning">due</Badge>}
                      </TableCell>
                      <TableCell className="text-sm">
                        {layer.stagingGenerationId ? (
                          <>
                            <div>{formatNumber(layer.stagingRowsStaged)} rida · {layer.stagingPagesProcessed ?? 0} lk</div>
                            <div className="text-xs text-muted-foreground">startIndex {formatNumber(layer.stagingNextStartIndex)}</div>
                            {layer.completionPercent !== undefined && (
                              <div className="text-xs text-muted-foreground">{layer.completionPercent.toFixed(1)}%</div>
                            )}
                          </>
                        ) : "—"}
                      </TableCell>
                      <TableCell className="text-xs">
                        <div>{formatDate(layer.lastSuccessAt)}</div>
                        <div className="mt-1 text-muted-foreground">vanus {formatAge(layer.freshnessAgeSeconds)}</div>
                      </TableCell>
                      <TableCell className="max-w-xs text-xs">
                        {layer.lastError || "—"}
                      </TableCell>
                      <TableCell>
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={!canStart}
                          onClick={async () => {
                            await refreshLayer.mutateAsync(layer.layerName);
                            await layers.refetch();
                          }}
                        >
                          Refresh
                        </Button>
                      </TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function Metric({ title, value, detail }: { title: string; value: number; detail?: string }) {
  return (
    <Card>
      <CardContent className="p-4">
        <div className="text-sm text-muted-foreground">{title}</div>
        <div className="mt-1 text-2xl font-semibold">{value}</div>
        {detail && <div className="mt-1 text-xs text-muted-foreground">{detail}</div>}
      </CardContent>
    </Card>
  );
}

function badgeVariant(state: string): "default" | "warning" | "outline" {
  if (state === "HEALTHY") return "default";
  if (state === "STALE" || state === "SYNCING") return "warning";
  return "outline";
}

function formatNumber(value?: number) {
  return value === undefined || value === null ? "—" : new Intl.NumberFormat("et-EE").format(value);
}

function formatDate(value?: string) {
  return value ? new Date(value).toLocaleString("et-EE") : "—";
}

function formatInterval(minutes: number) {
  if (minutes >= 10_080) return `${Math.round(minutes / 10_080)} nädal`;
  if (minutes >= 1_440) return `${Math.round(minutes / 1_440)} päev`;
  if (minutes >= 60) return `${Math.round(minutes / 60)} h`;
  return `${minutes} min`;
}

function formatAge(seconds?: number) {
  if (seconds === undefined || seconds === null) return "—";
  if (seconds >= 86_400) return `${Math.floor(seconds / 86_400)} p ${Math.floor((seconds % 86_400) / 3_600)} h`;
  if (seconds >= 3_600) return `${Math.floor(seconds / 3_600)} h ${Math.floor((seconds % 3_600) / 60)} min`;
  if (seconds >= 60) return `${Math.floor(seconds / 60)} min`;
  return `${seconds} s`;
}
