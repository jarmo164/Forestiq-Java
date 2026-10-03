"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import { Download, FileSpreadsheet, Plus, Upload } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { useAuth } from "@/lib/auth";
import {
  type OwnerImportField,
  type OwnerImportMapping,
  type OwnerImportPreview,
  useCommitOwnerImport,
  useCreateOwner,
  useDownloadOwnerImportErrors,
  useInspectOwnerImport,
  useOwnerImportBatches,
  useOwnersCursor,
  usePreviewOwnerImport,
} from "@/lib/api";

const importFields: { key: OwnerImportField; label: string; required?: boolean }[] = [
  { key: "displayName", label: "Nimi", required: true },
  { key: "personalOrRegistryCode", label: "Isiku- või registrikood", required: true },
  { key: "ownerType", label: "Omaniku tüüp" },
  { key: "email", label: "E-post" },
  { key: "phone", label: "Telefon" },
  { key: "address", label: "Aadress" },
  { key: "info", label: "Info" },
];

export function OwnerOnboardingPanel() {
  const { hasRole } = useAuth();
  const [mode, setMode] = useState<"create" | "import" | null>(null);

  if (!hasRole("ORG_ADMIN", "CRM_MANAGER")) return null;

  return <Card className="mb-6">
    <CardHeader className="flex-row items-center justify-between gap-3">
      <div>
        <CardTitle>Omanike lisamine</CardTitle>
        <p className="mt-1 text-sm text-muted-foreground">Lisa üks omanik käsitsi või valideeri CSV/XLSX fail enne hulgiimporti.</p>
      </div>
      <div className="flex gap-2">
        <Button size="sm" variant={mode === "create" ? "default" : "outline"} onClick={() => setMode(mode === "create" ? null : "create")}>
          <Plus className="mr-1 h-4 w-4" />Lisa omanik
        </Button>
        <Button size="sm" variant={mode === "import" ? "default" : "outline"} onClick={() => setMode(mode === "import" ? null : "import")}>
          <FileSpreadsheet className="mr-1 h-4 w-4" />Impordi failist
        </Button>
      </div>
    </CardHeader>
    {mode && <CardContent className="border-t pt-4">
      {mode === "create" ? <CreateOwnerForm /> : <OwnerImportForm />}
    </CardContent>}
  </Card>;
}

function CreateOwnerForm() {
  const create = useCreateOwner();
  const [displayName, setDisplayName] = useState("");
  const [code, setCode] = useState("");
  const [ownerType, setOwnerType] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [duplicateCode, setDuplicateCode] = useState("");
  const duplicates = useOwnersCursor(duplicateCode, null, 10);
  const duplicateOwner = duplicateCode
    ? duplicates.data?.content?.find(owner => owner.personalOrRegistryCode === duplicateCode)
    : undefined;

  const submit = () => {
    if (!displayName.trim() || !code.trim()) return;
    setDuplicateCode("");
    create.mutate({
      displayName: displayName.trim(),
      personalOrRegistryCode: code.trim(),
      ownerType: ownerType.trim() || undefined,
      email: email.trim() || undefined,
      phone: phone.trim() || undefined,
    }, {
      onSuccess: () => {
        setDisplayName(""); setCode(""); setOwnerType(""); setEmail(""); setPhone("");
      },
      onError: () => setDuplicateCode(code.trim()),
    });
  };

  return <div className="space-y-3">
    <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
      <LabeledInput label="Nimi *" value={displayName} onChange={setDisplayName} />
      <LabeledInput label="Isiku- või registrikood *" value={code} onChange={setCode} />
      <LabeledInput label="Omaniku tüüp" value={ownerType} onChange={setOwnerType} />
      <LabeledInput label="E-post" value={email} onChange={setEmail} type="email" />
      <LabeledInput label="Telefon" value={phone} onChange={setPhone} />
    </div>
    <div className="flex flex-wrap items-center gap-3">
      <Button onClick={submit} disabled={!displayName.trim() || !code.trim() || create.isPending}>
        {create.isPending ? "Lisan…" : "Lisa omanik"}
      </Button>
      {create.data && <span className="text-sm">Omanik lisatud. <Link className="font-medium underline" href={`/owners/${create.data.id}`}>Ava profiil</Link></span>}
      {create.isError && !duplicateOwner && <span className="text-sm text-destructive">{create.error.message}</span>}
      {duplicateOwner && <span className="text-sm text-amber-700 dark:text-amber-300">
        Selle koodiga omanik on juba olemas: <Link className="font-medium underline" href={`/owners/${duplicateOwner.id}`}>{duplicateOwner.displayName}</Link>
      </span>}
    </div>
  </div>;
}

function OwnerImportForm() {
  const inspect = useInspectOwnerImport();
  const previewMutation = usePreviewOwnerImport();
  const commit = useCommitOwnerImport();
  const batches = useOwnerImportBatches();
  const downloadErrors = useDownloadOwnerImportErrors();
  const [file, setFile] = useState<File | null>(null);
  const [mapping, setMapping] = useState<OwnerImportMapping>({ columns: {} });

  const preview = previewMutation.data;
  const requiredMapped = Boolean(mapping.columns.displayName && mapping.columns.personalOrRegistryCode);
  const rejectedRows = useMemo(() => preview?.rows.filter(row => row.status !== "READY") ?? [], [preview]);

  const chooseFile = (selected: File | null) => {
    setFile(selected);
    setMapping({ columns: {} });
    previewMutation.reset();
    commit.reset();
    if (!selected) return;
    inspect.mutate(selected, {
      onSuccess: result => setMapping({ columns: { ...result.suggestedMapping } }),
    });
  };

  const setField = (field: OwnerImportField, header: string) => {
    setMapping(current => ({ columns: { ...current.columns, [field]: header || undefined } }));
    previewMutation.reset();
    commit.reset();
  };

  return <div className="space-y-5">
    <div className="space-y-2">
      <label className="grid max-w-xl gap-1 text-sm">
        <span className="font-medium">CSV või XLSX fail</span>
        <Input type="file" accept=".csv,.xlsx" onChange={event => chooseFile(event.target.files?.[0] ?? null)} />
      </label>
      <p className="text-xs text-muted-foreground">Kuni 10 MB ja 10 000 andmerida. XLSX valemeid ei impordita. Organisatsiooni ID-d failist ei loeta ega usaldata.</p>
      {inspect.isError && <p className="text-sm text-destructive">{inspect.error.message}</p>}
    </div>

    {inspect.data && file && <>
      <div className="rounded-md bg-muted/50 p-3 text-sm">
        <p><strong>{inspect.data.filename}</strong> · {inspect.data.totalRows} andmerida</p>
        <p className="mt-1 break-all text-xs text-muted-foreground">SHA-256: {inspect.data.sha256}</p>
      </div>

      <div>
        <p className="mb-2 font-medium">Veergude mapping</p>
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          {importFields.map(field => <label className="grid gap-1 text-sm" key={field.key}>
            <span>{field.label}{field.required ? " *" : ""}</span>
            <select className="h-10 rounded-md border bg-background px-3" value={mapping.columns[field.key] ?? ""} onChange={event => setField(field.key, event.target.value)}>
              <option value="">Ära impordi</option>
              {inspect.data.headers.map(header => <option key={header} value={header}>{header}</option>)}
            </select>
          </label>)}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <Button variant="outline" disabled={!requiredMapped || previewMutation.isPending} onClick={() => previewMutation.mutate({ file, mapping })}>
          {previewMutation.isPending ? "Valideerin…" : "Tee eelvaade / dry-run"}
        </Button>
        {previewMutation.isError && <span className="text-sm text-destructive">{previewMutation.error.message}</span>}
      </div>
    </>}

    {preview && file && <PreviewResult preview={preview} rejectedRows={rejectedRows} onDownload={() => downloadPreviewErrors(preview)}>
      <Button
        disabled={preview.readyRows === 0 || commit.isPending}
        onClick={() => commit.mutate({ file, mapping, confirmedSha256: preview.sha256 })}
      >
        <Upload className="mr-2 h-4 w-4" />{commit.isPending ? "Impordin…" : `Impordi ${preview.readyRows} valmis rida`}
      </Button>
    </PreviewResult>}

    {commit.data && <div className="rounded-md border p-3 text-sm">
      <p className="font-medium">Import lõpetatud</p>
      <p>Loodi {commit.data.createdCount}, duplikaate {commit.data.duplicateCount}, vigaseid {commit.data.invalidCount}.</p>
      <p className="mt-1 text-xs text-muted-foreground">Batch: {commit.data.batchId}</p>
    </div>}
    {commit.isError && <p className="text-sm text-destructive">{commit.error.message}</p>}

    <div className="border-t pt-4">
      <p className="mb-2 font-medium">Viimased impordibatchid</p>
      {!batches.data?.length ? <p className="text-sm text-muted-foreground">Impordiajalugu puudub.</p> : <div className="space-y-2">
        {batches.data.slice(0, 10).map(batch => <div key={batch.batchId} className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-2 text-sm">
          <div>
            <p className="font-medium">{batch.filename}</p>
            <p className="text-xs text-muted-foreground">{new Date(batch.committedAt).toLocaleString("et-EE")} · loodud {batch.createdCount} · duplikaate {batch.duplicateCount} · vigaseid {batch.invalidCount}</p>
          </div>
          {(batch.duplicateCount > 0 || batch.invalidCount > 0) && <Button size="sm" variant="ghost" onClick={() => downloadErrors.mutate(batch.batchId)}>
            <Download className="mr-1 h-4 w-4" />Veaaruanne
          </Button>}
        </div>)}
      </div>}
    </div>
  </div>;
}

function PreviewResult({ preview, rejectedRows, onDownload, children }: {
  preview: OwnerImportPreview;
  rejectedRows: OwnerImportPreview["rows"];
  onDownload: () => void;
  children: React.ReactNode;
}) {
  return <div className="space-y-3 rounded-md border p-3">
    <div className="flex flex-wrap gap-2">
      <Badge>Valmis {preview.readyRows}</Badge>
      <Badge variant="secondary">Duplikaadid {preview.duplicateRows}</Badge>
      <Badge variant={preview.invalidRows ? "warning" : "secondary"}>Vigased {preview.invalidRows}</Badge>
    </div>
    {rejectedRows.length > 0 && <>
      <div className="max-h-72 overflow-auto rounded-md border">
        <table className="w-full text-left text-xs">
          <thead className="sticky top-0 bg-background"><tr><th className="p-2">Rida</th><th className="p-2">Staatus</th><th className="p-2">Nimi / kood</th><th className="p-2">Põhjus</th></tr></thead>
          <tbody>{rejectedRows.slice(0, 300).map(row => <tr className="border-t" key={`${row.rowNumber}-${row.personalOrRegistryCode}`}>
            <td className="p-2">{row.rowNumber}</td>
            <td className="p-2">{row.status}</td>
            <td className="p-2">{row.existingOwnerId ? <Link className="underline" href={`/owners/${row.existingOwnerId}`}>{row.displayName || row.personalOrRegistryCode}</Link> : `${row.displayName} · ${row.personalOrRegistryCode}`}</td>
            <td className="p-2">{row.errors.join("; ") || (row.status === "DUPLICATE" ? "Omanik on juba organisatsioonis olemas." : "—")}</td>
          </tr>)}</tbody>
        </table>
      </div>
      {rejectedRows.length > 300 && <p className="text-xs text-muted-foreground">Kuvatakse esimesed 300 probleemset rida; täielik raport on allalaaditav.</p>}
    </>}
    <div className="flex flex-wrap gap-2">
      {children}
      {rejectedRows.length > 0 && <Button variant="outline" onClick={onDownload}><Download className="mr-2 h-4 w-4" />Laadi preview veaaruanne</Button>}
    </div>
  </div>;
}

function downloadPreviewErrors(preview: OwnerImportPreview) {
  const rows = preview.rows.filter(row => row.status !== "READY");
  const quote = (value: string) => `"${value.replaceAll('"', '""')}"`;
  const csv = [
    "row,status,displayName,personalOrRegistryCode,existingOwnerId,errors",
    ...rows.map(row => [
      String(row.rowNumber), row.status, quote(row.displayName ?? ""), quote(row.personalOrRegistryCode ?? ""),
      row.existingOwnerId ?? "", quote(row.errors.join("; ")),
    ].join(",")),
  ].join("\n");
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = `${preview.filename}-preview-errors.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
}

function LabeledInput({ label, value, onChange, type = "text" }: { label: string; value: string; onChange: (value: string) => void; type?: string }) {
  return <label className="grid gap-1 text-sm"><span className="font-medium">{label}</span><Input type={type} value={value} onChange={event => onChange(event.target.value)} /></label>;
}
