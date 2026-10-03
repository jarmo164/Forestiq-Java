"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useContractSigningHistory, useContractSigningState, useUpdateContractSigning, useUploadSignedContractFile, useWorkflowAssignees, type ContractSigningStatus } from "@/lib/api";
import { signedExternalUrlError } from "@/lib/contracts/form-utils";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { FormField, fieldDescriptionIds } from "@/components/ui/form-field";
import { Input } from "@/components/ui/input";

const labels: Record<string, string> = { PREPARING: "Ettevalmistamisel", SENT_FOR_SIGNATURE: "Saadetud allkirjastusse", SIGNED: "Allkirjastatud", CANCELLED: "Katkestatud" };
const externalUrlHint = "Välisviide peab olema täielik HTTPS-aadress. Fail ja viide on alternatiivid.";

export default function ContractSigningPage() {
  const { contractId } = useParams<{ contractId: string }>();
  const state = useContractSigningState(contractId);
  const history = useContractSigningHistory(contractId);
  const update = useUpdateContractSigning();
  const upload = useUploadSignedContractFile();
  const { data: assignees = [] } = useWorkflowAssignees();
  const [responsible, setResponsible] = useState("");
  const [dueAt, setDueAt] = useState("");
  const [reason, setReason] = useState("");
  const [externalUrl, setExternalUrl] = useState("");
  const [externalUrlError, setExternalUrlError] = useState<string>();
  const [documentId, setDocumentId] = useState("");
  const [uploadedName, setUploadedName] = useState("");

  const currentResponsible = responsible || state.data?.responsibleSubject || "";
  const currentDue = dueAt || (state.data?.dueAt ? new Date(state.data.dueAt).toISOString().slice(0, 16) : "");
  const nextOptions = useMemo<ContractSigningStatus[]>(() => {
    if (!state.data) return [];
    if (state.data.status === "PREPARING") return ["SENT_FOR_SIGNATURE", "CANCELLED"];
    if (state.data.status === "SENT_FOR_SIGNATURE") return ["SIGNED", "CANCELLED"];
    return [];
  }, [state.data]);

  const uploadFile = (file?: File) => {
    if (!file) return;
    upload.mutate({ contractId, file }, { onSuccess: result => { setDocumentId(result.documentId); setUploadedName(result.fileName); setExternalUrl(""); setExternalUrlError(undefined); } });
  };

  const transition = (status: ContractSigningStatus) => {
    if (!currentResponsible || !currentDue) return;
    if (status === "SIGNED" && !documentId) {
      const error = signedExternalUrlError(externalUrl);
      setExternalUrlError(error);
      if (error) return;
    }
    update.mutate({ contractId, data: { status, responsibleSubject: currentResponsible, dueAt: new Date(currentDue).toISOString(), effectiveAt: new Date().toISOString(), reason: reason || undefined, signedExternalUrl: status === "SIGNED" && externalUrl ? externalUrl.trim() : undefined, signedDocumentId: status === "SIGNED" && documentId ? documentId : undefined } });
  };

  if (state.isLoading) return <p role="status" className="text-sm text-muted-foreground">Laadin allkirjastusolekut…</p>;
  if (state.isError || !state.data) return <p role="alert" className="text-sm text-destructive">{state.error?.message ?? "Lepingut ei leitud."}</p>;
  const data = state.data;

  return <div className="space-y-6">
    <div className="flex flex-wrap items-end justify-between gap-3"><div><div className="flex items-center gap-2"><h1 className="text-2xl font-bold">Lepingu allkirjastamine</h1><Badge>{labels[data.status] ?? data.status}</Badge><Badge variant="outline">v{data.versionNo}</Badge></div><p className="text-sm text-muted-foreground">Leping {data.contractId} · tehing {data.dealId}</p></div><Button asChild variant="outline"><Link href={`/deals/${data.dealId}`}>Ava tehing</Link></Button></div>

    {data.stallReason && <div className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900"><strong>Takerdumise põhjus:</strong> {data.stallReason}</div>}

    <Card><CardHeader><CardTitle>Olek ja vastutus</CardTitle></CardHeader><CardContent className="space-y-4">
      <div className="grid gap-3 md:grid-cols-2"><label className="grid gap-1 text-sm"><span>Vastutaja</span><select className="h-10 rounded-md border bg-background px-3" value={currentResponsible} onChange={e => setResponsible(e.target.value)} disabled={nextOptions.length === 0}><option value={data.responsibleSubject}>{data.responsibleSubject}</option>{assignees.filter(a => a.subject !== data.responsibleSubject).map(a => <option key={a.subject} value={a.subject}>{a.name || a.username}</option>)}</select></label><label className="grid gap-1 text-sm"><span>Tähtaeg</span><Input type="datetime-local" value={currentDue} onChange={e => setDueAt(e.target.value)} disabled={nextOptions.length === 0} /></label></div>
      <label className="grid gap-1 text-sm"><span>Põhjus / takistus</span><textarea className="min-h-20 rounded-md border bg-background p-2 text-sm" value={reason} onChange={e => setReason(e.target.value)} placeholder="Katkestamisel kohustuslik; muidu vajadusel" disabled={nextOptions.length === 0} /></label>
      {data.status === "SENT_FOR_SIGNATURE" && <div className="grid gap-4 md:grid-cols-2"><div className="rounded-md border p-3"><p className="mb-2 text-sm font-medium">Allkirjastatud fail</p><Input type="file" accept="application/pdf,.asice,.asics" onChange={e => uploadFile(e.target.files?.[0])} disabled={upload.isPending} />{uploadedName && <p className="mt-2 text-xs text-muted-foreground">Laaditud: {uploadedName}</p>}{upload.isError && <p role="alert" className="mt-2 text-xs text-destructive">{upload.error.message}</p>}</div><div className="rounded-md border p-3"><FormField id="signed-external-url" label="Või turvaline välisviide" hint={externalUrlHint} error={externalUrlError}><Input id="signed-external-url" type="url" inputMode="url" value={externalUrl} aria-invalid={Boolean(externalUrlError)} aria-describedby={fieldDescriptionIds("signed-external-url", externalUrlHint, externalUrlError)} onChange={e => { setExternalUrl(e.target.value); setExternalUrlError(undefined); if (e.target.value) { setDocumentId(""); setUploadedName(""); } }} placeholder="https://…" /></FormField></div></div>}
      <div className="flex flex-wrap gap-2">{nextOptions.map(status => <Button key={status} variant={status === "CANCELLED" ? "outline" : "default"} disabled={update.isPending || upload.isPending || (status === "SIGNED" && !documentId && !externalUrl) || (status === "CANCELLED" && !reason.trim())} onClick={() => transition(status)}>{status === "SENT_FOR_SIGNATURE" ? "Märgi saadetuks" : status === "SIGNED" ? "Märgi allkirjastatuks" : "Katkesta"}</Button>)}</div>{update.isError && <p role="alert" className="text-sm text-destructive">{update.error.message}</p>}
    </CardContent></Card>

    <Card><CardHeader><CardTitle>Auditiajalugu</CardTitle></CardHeader><CardContent className="space-y-3">{history.data?.map(event => <div key={event.id} className="rounded-md border p-3"><div className="flex flex-wrap items-center gap-2"><Badge variant="outline">{labels[event.toStatus] ?? event.toStatus}</Badge><span className="text-sm">{new Date(event.effectiveAt).toLocaleString("et-EE")}</span></div><p className="mt-1 text-sm">Vastutaja {event.responsibleSubject} · tähtaeg {new Date(event.dueAt).toLocaleString("et-EE")}</p>{event.reason && <p className="text-sm text-muted-foreground">{event.reason}</p>}<p className="mt-1 text-xs text-muted-foreground">Salvestas {event.createdBy} · {new Date(event.createdAt).toLocaleString("et-EE")}</p></div>)}</CardContent></Card>
  </div>;
}
