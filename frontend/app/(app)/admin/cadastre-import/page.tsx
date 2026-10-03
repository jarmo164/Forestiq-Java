"use client";

import { useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

type ImportResult = {
  snapshotId: string;
  rows: number;
  geometryRows: number;
  projectedCadastres: number;
  bytesRead: number;
  filename: string;
};

type UploadState = "IDLE" | "UPLOADING" | "FINALIZING" | "DONE" | "FAILED";

function megabytes(bytes: number) {
  return (bytes / (1024 * 1024)).toFixed(1);
}

function errorMessage(xhr: XMLHttpRequest) {
  try {
    const body = JSON.parse(xhr.responseText) as { detail?: string; message?: string };
    return body.detail || body.message || `Import ebaõnnestus (${xhr.status}).`;
  } catch {
    return xhr.responseText || `Import ebaõnnestus (${xhr.status}).`;
  }
}

export default function CadastreCsvImportPage() {
  const { hasRole } = useAuth();
  const [file, setFile] = useState<File | null>(null);
  const [state, setState] = useState<UploadState>("IDLE");
  const [uploadedBytes, setUploadedBytes] = useState(0);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const progress = file && file.size > 0 ? Math.min(100, Math.round(uploadedBytes * 100 / file.size)) : 0;
  const busy = state === "UPLOADING" || state === "FINALIZING";

  if (!hasRole("ORG_ADMIN")) {
    return (
      <Card>
        <CardHeader><CardTitle>Katastri CSV import</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm text-destructive">Selle toimingu jaoks on vajalik ORG_ADMIN roll.</p>
          <Button asChild variant="outline"><Link href="/admin">Tagasi haldusesse</Link></Button>
        </CardContent>
      </Card>
    );
  }

  const startImport = () => {
    if (!file || busy) return;
    if (!file.name.toLowerCase().endsWith(".csv")) {
      setError("Vali .csv fail.");
      return;
    }
    if (!window.confirm(
      `Impordin kogu katastri snapshoti failist ${file.name} (${megabytes(file.size)} MB). ` +
      "Uus snapshot avaldatakse alles siis, kui terve fail on edukalt kontrollitud. Jätkan?"
    )) return;

    setState("UPLOADING");
    setUploadedBytes(0);
    setResult(null);
    setError(null);

    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/v1/admin/imports/cadastre");
    xhr.withCredentials = true;
    xhr.timeout = 5_400_000;
    xhr.setRequestHeader("Accept", "application/json, application/problem+json");
    xhr.setRequestHeader("Content-Type", "text/csv; charset=utf-8");
    xhr.setRequestHeader("X-ForestIQ-Filename", encodeURIComponent(file.name));

    xhr.upload.onprogress = (event) => {
      if (!event.lengthComputable) return;
      setUploadedBytes(event.loaded);
      if (event.loaded >= event.total) setState("FINALIZING");
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          setResult(JSON.parse(xhr.responseText) as ImportResult);
          setUploadedBytes(file.size);
          setState("DONE");
        } catch {
          setError("Import lõpetati, kuid serveri vastust ei õnnestunud lugeda.");
          setState("FAILED");
        }
        return;
      }
      setError(errorMessage(xhr));
      setState("FAILED");
    };
    xhr.onerror = () => {
      setError("Võrguühendus katkes katastri CSV impordi ajal. Eelmine aktiivne snapshot jäi alles.");
      setState("FAILED");
    };
    xhr.ontimeout = () => {
      setError("Katastri CSV import ületas lubatud päringu kestuse. Eelmine aktiivne snapshot jäi alles.");
      setState("FAILED");
    };
    xhr.send(file);
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Katastri CSV import</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Maa- ja Ruumiameti kogu Eesti katastri snapshoti kontrollitud import.
          </p>
        </div>
        <Button asChild variant="outline"><Link href="/admin">Tagasi haldusesse</Link></Button>
      </div>

      <Card>
        <CardHeader><CardTitle>Vali lokaalselt genereeritud CSV</CardTitle></CardHeader>
        <CardContent className="space-y-5">
          <div className="rounded-md border bg-muted/30 p-4 text-sm">
            <p>
              Toetatud on <strong>download_maaamet_cadastre.py</strong> loodud CSV. Fail loetakse serveris voona ja
              kirjutatakse uude ühisesse katastri snapshoti; kogu faili ei laadita korraga rakenduse mällu.
            </p>
            <p className="mt-2 text-muted-foreground">
              Geomeetriaga CSV on toetatud. Maksimaalne failisuurus on 1 GB. Aktiivset snapshoti ei vahetata enne,
              kui kogu fail on edukalt sisse loetud ja valideeritud.
            </p>
          </div>

          <label className="block text-sm">
            CSV fail
            <Input
              className="mt-1"
              type="file"
              accept=".csv,text/csv"
              disabled={busy}
              onChange={(event) => {
                const selected = event.target.files?.[0] ?? null;
                setFile(selected);
                setState("IDLE");
                setUploadedBytes(0);
                setResult(null);
                setError(null);
              }}
            />
          </label>

          {file && (
            <div className="flex flex-wrap gap-4 rounded-md border p-3 text-sm">
              <span><strong>Fail:</strong> {file.name}</span>
              <span><strong>Suurus:</strong> {megabytes(file.size)} MB</span>
            </div>
          )}

          {busy && file && (
            <div className="space-y-2 rounded-md border p-4">
              <div className="flex items-center justify-between text-sm">
                <span>{state === "FINALIZING" ? "Lõpetan snapshoti ja uuendan ForestIQ andmeid..." : "Laen ja impordin..."}</span>
                <Badge variant="warning">{progress}%</Badge>
              </div>
              <div className="h-3 overflow-hidden rounded-full bg-muted">
                <div className="h-full bg-primary transition-all" style={{ width: `${progress}%` }} />
              </div>
              <div className="text-xs text-muted-foreground">
                {megabytes(uploadedBytes)} / {megabytes(file.size)} MB. Ära sulge seda lehte enne lõpptulemuse kuvamist.
              </div>
            </div>
          )}

          {error && <div className="rounded-md border border-destructive/50 p-4 text-sm text-destructive">{error}</div>}

          {result && (
            <div className="space-y-2 rounded-md border bg-muted/30 p-4 text-sm">
              <div className="flex items-center gap-2"><Badge>Valmis</Badge><strong>Katastri snapshot avaldati.</strong></div>
              <div>CSV ridu: <strong>{result.rows.toLocaleString("et-EE")}</strong></div>
              <div>Geomeetriaga ridu: <strong>{result.geometryRows.toLocaleString("et-EE")}</strong></div>
              <div>ForestIQ-is kasutuses olevaid katastriüksusi uuendati: <strong>{result.projectedCadastres.toLocaleString("et-EE")}</strong></div>
              <div>Loetud: <strong>{megabytes(result.bytesRead)} MB</strong></div>
              <div className="text-xs text-muted-foreground">Snapshot: {result.snapshotId}</div>
            </div>
          )}

          <div className="flex flex-wrap items-center gap-3">
            <Button onClick={startImport} disabled={!file || busy}>
              {busy ? "Import käib..." : "Impordi katastri CSV"}
            </Button>
            {state === "DONE" && <span className="text-sm text-muted-foreground">Faili võib nüüd lokaalselt arhiveerida või kustutada.</span>}
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
