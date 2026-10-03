"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import {
  type OwnerImportField,
  type OwnerImportMapping,
  useCommitOwnerImport,
  useInspectOwnerImport,
  useOwnerImportBatches,
  useIntegrationJobs,
  useDownloadOwnerImportErrors,
  usePreviewOwnerImport,
  useStartIntegrationJob,
} from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const IMPORT_FIELDS: Array<{ key: OwnerImportField; label: string; required?: boolean }> = [
  { key: "displayName", label: "Nimi", required: true },
  { key: "personalOrRegistryCode", label: "Isiku- või registrikood", required: true },
  { key: "ownerType", label: "Omaniku tüüp" },
  { key: "email", label: "E-post" },
  { key: "phone", label: "Telefon" },
  { key: "address", label: "Aadress" },
  { key: "info", label: "Info / märkused" },
];

type BulkSyncResult = {
  started: string[];
  failed: Array<{ name: string; message: string }>;
};

export function IntegrationOperationsTools() {
  const jobs = useIntegrationJobs();
  const start = useStartIntegrationJob();
  const inspectImport = useInspectOwnerImport();
  const previewImport = usePreviewOwnerImport();
  const commitImport = useCommitOwnerImport();
  const importBatches = useOwnerImportBatches();
  const downloadErrors = useDownloadOwnerImportErrors();

  const [bulkPending, setBulkPending] = useState(false);
  const [bulkResult, setBulkResult] = useState<BulkSyncResult | null>(null);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [mapping, setMapping] = useState<OwnerImportMapping["columns"]>({});

  const runnableJobs = useMemo(() => (jobs.data ?? []).filter(job =>
    job.manualSupported && job.configurationState !== "MISCONFIGURED" && !job.currentRun
  ), [jobs.data]);
  const cadastreJob = useMemo(() => (jobs.data ?? []).find(job => job.key === "CADASTRE_PUBLIC_DATA"), [jobs.data]);
  const fullCadastreAvailable = Boolean(
    cadastreJob?.manualSupported && cadastreJob.configurationState !== "MISCONFIGURED" && !cadastreJob.currentRun
  );

  const mappingReady = Boolean(mapping.displayName && mapping.personalOrRegistryCode);

  const startAll = async () => {
    const candidates = runnableJobs;
    if (candidates.length === 0) return;
    const includesOwnerSnapshot = candidates.some(job => job.key === "OWNER_CADASTRE_RELATIONS");
    const confirmation = includesOwnerSnapshot
      ? `Käivitan ${candidates.length} andmeallika sünkroonimise korraga, sh täieliku omaniku–katastri snapshoti. Jätkan?`
      : `Käivitan ${candidates.length} andmeallika sünkroonimise korraga. Jätkan?`;
    if (!window.confirm(confirmation)) return;

    setBulkPending(true);
    setBulkResult(null);
    try {
      const results = await Promise.allSettled(candidates.map(async job => {
        await start.mutateAsync({ key: job.key });
        return job.displayName;
      }));
      const started: string[] = [];
      const failed: Array<{ name: string; message: string }> = [];
      results.forEach((result, index) => {
        const job = candidates[index];
        if (result.status === "fulfilled") started.push(result.value);
        else failed.push({
          name: job.displayName,
          message: result.reason instanceof Error ? result.reason.message : "Sünkroonimise käivitamine ebaõnnestus.",
        });
      });
      setBulkResult({ started, failed });
      await jobs.refetch();
    } finally {
      setBulkPending(false);
    }
  };

  const startFullCadastre = async () => {
    if (!cadastreJob || !fullCadastreAvailable) return;
    if (!window.confirm(
      "Laen Maa- ja Ruumiameti WFS-ist alla kogu Eesti kehtivate katastriüksuste snapshoti. " +
      "Töö võib olla mahukas, kuid katkestuse korral jätkub järgmine käivitus checkpointist. Jätkan?"
    )) return;
    await start.mutateAsync({ key: cadastreJob.key, parameters: { mode: "full" } });
    await jobs.refetch();
  };

  const resetImportFlow = () => {
    inspectImport.reset();
    previewImport.reset();
    commitImport.reset();
    setMapping({});
  };

  const selectImportFile = (file: File | null) => {
    setImportFile(file);
    resetImportFlow();
    if (!file) return;
    inspectImport.mutate(file, {
      onSuccess: result => setMapping({ ...result.suggestedMapping }),
    });
  };

  const setFieldMapping = (field: OwnerImportField, header: string) => {
    setMapping(previous => ({ ...previous, [field]: header || undefined }));
    previewImport.reset();
    commitImport.reset();
  };

  const runPreview = () => {
    if (!importFile || !mappingReady) return;
    previewImport.mutate({ file: importFile, mapping: { columns: mapping } });
  };

  const runCommit = () => {
    if (!importFile || !previewImport.data || previewImport.data.readyRows === 0) return;
    if (!window.confirm(`Impordin ${previewImport.data.readyRows} uut omanikku. Duplikaate ja vigaseid ridu ei kirjutata. Jätkan?`)) return;
    commitImport.mutate({
      file: importFile,
      mapping: { columns: mapping },
      confirmedSha256: previewImport.data.sha256,
    });
  };

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader><CardTitle>Sünkroonimise kiirtoimingud</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={startAll} disabled={bulkPending || start.isPending || runnableJobs.length === 0}>
              {bulkPending ? "Käivitan..." : "Sünkroniseeri kõik"}
            </Button>
            <span className="text-sm text-muted-foreground">
              Käivitab kõik käsitsi toetatud ja seadistatud andmeallikad olemasoleva tööjärjekorra kaudu.
            </span>
          </div>
          <div className="flex flex-wrap items-center gap-3 rounded-md border p-3">
            <Button variant="outline" onClick={startFullCadastre}
              disabled={!fullCadastreAvailable || start.isPending}>
              {cadastreJob?.currentRun ? "Katastri sünkroonimine käib..." : "Sünkrooni kogu Eesti kataster"}
            </Button>
            <Button asChild variant="outline">
              <Link href="/admin/cadastre-import">Impordi katastri CSV</Link>
            </Button>
            <Button asChild variant="outline">
              <Link href="/admin/metsaregister-wfs">Metsaregistri WFS kihid</Link>
            </Button>
            <span className="max-w-3xl text-sm text-muted-foreground">
              Laeb <strong>kataster:ky_kehtiv</strong> täisandmestiku lehekülgede kaupa ühisesse snapshoti.
              Katkestuse korral jätkub järgmine käivitus checkpointist; tenantide tabelites värskendatakse ainult ForestIQ-is juba kasutuses olevaid katastriüksusi.
            </span>
          </div>
          <div className="flex flex-wrap gap-3 rounded-md border bg-muted/30 p-3 text-sm">
            <span>Käsitsi käivitatavaid praegu: <strong>{runnableJobs.length}</strong></span>
            <span>Juba töös: <strong>{(jobs.data ?? []).filter(job => Boolean(job.currentRun)).length}</strong></span>
            <span>Seadistamata: <strong>{(jobs.data ?? []).filter(job => job.configurationState === "MISCONFIGURED").length}</strong></span>
          </div>
          {bulkResult && (
            <div className="rounded-md border p-3 text-sm">
              <div><strong>Käivitatud:</strong> {bulkResult.started.length}{bulkResult.started.length > 0 ? ` — ${bulkResult.started.join(", ")}` : ""}</div>
              {bulkResult.failed.length > 0 && (
                <div className="mt-2 text-destructive">
                  <strong>Käivitamata:</strong> {bulkResult.failed.map(item => `${item.name}: ${item.message}`).join(" · ")}</div>
              )}
            </div>
          )}
          {jobs.isError && <p className="text-sm text-destructive">Andmeallikate oleku laadimine ebaõnnestus.</p>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Käsitsi CSV import</CardTitle></CardHeader>
        <CardContent className="space-y-5">
          <div className="grid gap-4 lg:grid-cols-[220px_1fr]">
            <label className="text-sm">
              Andmetüüp
              <select className="mt-1 h-10 w-full rounded-md border bg-background px-3" value="owners" disabled>
                <option value="owners">Omanike andmed</option>
              </select>
            </label>
            <label className="text-sm">
              CSV või XLSX fail
              <Input className="mt-1" type="file" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                onChange={event => selectImportFile(event.target.files?.[0] ?? null)} />
            </label>
          </div>
          <p className="text-xs text-muted-foreground">
            Import on organisatsioonipõhine ja turvaline: fail võib olla kuni 10 MB / 10 000 andmerida. Enne kirjutamist tehakse päiste kontroll, veergude mapping ja eelvaade.
            Praegu on käsitsi failiimport toetatud omanike algandmetele; katastri täissnapshoti import on eraldi tööriistas ülal.
          </p>

          {inspectImport.isPending && <p className="text-sm text-muted-foreground">Analüüsin faili päiseid...</p>}
          {inspectImport.isError && <p className="text-sm text-destructive">{inspectImport.error.message}</p>}
          {inspectImport.data && (
            <div className="space-y-4 rounded-md border p-4">
              <div className="flex flex-wrap gap-4 text-sm">
                <span><strong>Fail:</strong> {inspectImport.data.filename}</span>
                <span><strong>Ridu:</strong> {inspectImport.data.totalRows}</span>
                <span><strong>Veerge:</strong> {inspectImport.data.headers.length}</span>
              </div>
              <div>
                <p className="mb-2 text-sm font-medium">Veergude vastendus</p>
                <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                  {IMPORT_FIELDS.map(field => (
                    <label key={field.key} className="text-sm">
                      {field.label}{field.required ? " *" : ""}
                      <select className="mt-1 h-10 w-full rounded-md border bg-background px-3"
                        value={mapping[field.key] ?? ""}
                        onChange={event => setFieldMapping(field.key, event.target.value)}>
                        <option value="">— ei impordita —</option>
                        {inspectImport.data.headers.map(header => <option key={header} value={header}>{header}</option>)}
                      </select>
                    </label>
                  ))}
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-3">
                <Button variant="outline" onClick={runPreview} disabled={!mappingReady || previewImport.isPending}>
                  {previewImport.isPending ? "Kontrollin..." : "Kontrolli ja näita eelvaadet"}
                </Button>
                {!mappingReady && <span className="text-xs text-destructive">Nimi ja isiku- või registrikood peavad olema seotud failiveergudega.</span>}
              </div>
            </div>
          )}

          {previewImport.isError && <p className="text-sm text-destructive">{previewImport.error.message}</p>}
          {previewImport.data && (
            <div className="space-y-4 rounded-md border p-4">
              <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                <div className="rounded-md bg-muted/40 p-3 text-sm"><div className="text-muted-foreground">Kokku</div><div className="text-xl font-semibold">{previewImport.data.totalRows}</div></div>
                <div className="rounded-md bg-muted/40 p-3 text-sm"><div className="text-muted-foreground">Valmis impordiks</div><div className="text-xl font-semibold">{previewImport.data.readyRows}</div></div>
                <div className="rounded-md bg-muted/40 p-3 text-sm"><div className="text-muted-foreground">Duplikaadid</div><div className="text-xl font-semibold">{previewImport.data.duplicateRows}</div></div>
                <div className="rounded-md bg-muted/40 p-3 text-sm"><div className="text-muted-foreground">Vigased</div><div className="text-xl font-semibold">{previewImport.data.invalidRows}</div></div>
              </div>
              <Table>
                <TableHeader><TableRow><TableHead>Rida</TableHead><TableHead>Nimi</TableHead><TableHead>Kood</TableHead><TableHead>Staatus</TableHead><TableHead>Probleem</TableHead></TableRow></TableHeader>
                <TableBody>
                  {previewImport.data.rows.slice(0, 10).map(row => <TableRow key={row.rowNumber}>
                    <TableCell>{row.rowNumber}</TableCell>
                    <TableCell>{row.displayName || "—"}</TableCell>
                    <TableCell>{row.personalOrRegistryCode || "—"}</TableCell>
                    <TableCell><Badge variant={row.status === "READY" ? "default" : row.status === "INVALID" ? "warning" : "outline"}>{row.status}</Badge></TableCell>
                    <TableCell className="max-w-sm text-xs">{row.errors.join(" · ") || (row.status === "DUPLICATE" ? "Omanik on juba olemas." : "—")}</TableCell>
                  </TableRow>)}
                </TableBody>
              </Table>
              {previewImport.data.rows.length > 10 && <p className="text-xs text-muted-foreground">Näitan esimest 10 rida {previewImport.data.rows.length} reast.</p>}
              <Button onClick={runCommit} disabled={previewImport.data.readyRows === 0 || commitImport.isPending}>
                {commitImport.isPending ? "Impordin..." : `Impordi ${previewImport.data.readyRows} valmis rida`}
              </Button>
            </div>
          )}

          {commitImport.isError && <p className="text-sm text-destructive">{commitImport.error.message}</p>}
          {commitImport.data && (
            <div className="space-y-2 rounded-md border bg-muted/30 p-4 text-sm">
              <div className="font-medium">Import lõpetatud.</div>
              <div className="mt-1">Lisatud {commitImport.data.createdCount} omanikku · duplikaate {commitImport.data.duplicateCount} · vigaseid {commitImport.data.invalidCount}.</div>
              <div className="text-xs text-muted-foreground">Batch: {commitImport.data.batchId} · fail: {commitImport.data.filename} · SHA-256: {commitImport.data.sha256}</div>
              {(commitImport.data.duplicateCount > 0 || commitImport.data.invalidCount > 0) && <Button size="sm" variant="outline" onClick={() => downloadErrors.mutate(commitImport.data!.batchId)} disabled={downloadErrors.isPending}>Laadi vigaste ja duplikaatsete ridade CSV</Button>}
              {downloadErrors.isError && <p role="alert" className="text-xs text-destructive">{downloadErrors.error.message}</p>}
            </div>
          )}
          <div className="border-t pt-4">
            <p className="mb-2 text-sm font-medium">Hiljutised impordid</p>
            {importBatches.isLoading && <p className="text-sm text-muted-foreground">Laadin auditi ajalugu…</p>}
            {importBatches.isError && <p role="alert" className="text-sm text-destructive">Impordiajalugu ei ole praegu saadaval.</p>}
            {importBatches.data && importBatches.data.length === 0 && <p className="text-sm text-muted-foreground">Selles organisatsioonis ei ole veel impordibatch&apos;e.</p>}
            {importBatches.data && importBatches.data.length > 0 && <Table><TableHeader><TableRow><TableHead>Aeg</TableHead><TableHead>Fail / SHA-256</TableHead><TableHead>Teostaja</TableHead><TableHead>Tulemus</TableHead><TableHead /></TableRow></TableHeader><TableBody>{importBatches.data.map(batch => <TableRow key={batch.batchId}><TableCell className="text-xs">{new Date(batch.committedAt).toLocaleString("et-EE")}</TableCell><TableCell><p className="text-sm">{batch.filename}</p><code className="text-xs text-muted-foreground">{batch.sha256.slice(0, 18)}…</code></TableCell><TableCell className="text-xs">{batch.userSubject}</TableCell><TableCell className="text-xs">{batch.createdCount} lisatud · {batch.duplicateCount} dupl. · {batch.invalidCount} viga</TableCell><TableCell>{(batch.duplicateCount > 0 || batch.invalidCount > 0) && <Button size="sm" variant="ghost" onClick={() => downloadErrors.mutate(batch.batchId)} disabled={downloadErrors.isPending}>Veaaruanne</Button>}</TableCell></TableRow>)}</TableBody></Table>}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
