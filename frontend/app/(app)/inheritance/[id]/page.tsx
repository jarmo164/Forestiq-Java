"use client";

import { use, useState } from "react";
import Link from "next/link";
import { ArrowRight, CheckCircle2, ExternalLink } from "lucide-react";
import { useAssignInheritanceCase, useChangeInheritanceStatus, useInheritanceCaseWorkspace } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { ConfirmActionDialog } from "@/components/ui/confirm-action-dialog";
import { useAuth } from "@/lib/auth";

const statusLabels: Record<string, string> = { NOTICE_RECEIVED: "Teade vastu võetud", HEIR_DISCOVERY: "Pärijate tuvastamine", HEIRS_CONTACTED: "Pärijatega kontakt", NEGOTIATION: "Läbirääkimised", SETTLED: "Kokkulepe saavutatud", CLOSED: "Juhtum lõpetatud", NOT_RELEVANT: "Ei kuulu menetlusse" };
const nextSteps: Record<string, Array<{ status: string; label: string; detail: string }>> = {
  NOTICE_RECEIVED: [{ status: "HEIR_DISCOVERY", label: "Alusta pärijate tuvastamist", detail: "Kontrolli teate andmed ja leia pärijad." }, { status: "NOT_RELEVANT", label: "Märgi menetlusse mittekuuluvaks", detail: "Juhtum ei vaja edasist pärimistööd." }, { status: "CLOSED", label: "Sulge juhtum", detail: "Sulge ainult juhul, kui edasine töö pole vajalik." }],
  HEIR_DISCOVERY: [{ status: "HEIRS_CONTACTED", label: "Märgi pärijad kontaktiks valmis", detail: "Pärijate ring on tuvastatud ja kontaktitöö võib alata." }, { status: "NOT_RELEVANT", label: "Märgi menetlusse mittekuuluvaks", detail: "Juhtum ei vaja edasist pärimistööd." }, { status: "CLOSED", label: "Sulge juhtum", detail: "Sulge ainult juhul, kui edasine töö pole vajalik." }],
  HEIRS_CONTACTED: [{ status: "NEGOTIATION", label: "Alusta läbirääkimisi", detail: "Pärijatega on ühendust võetud ja võimalus on aktiivne." }, { status: "NOT_RELEVANT", label: "Märgi menetlusse mittekuuluvaks", detail: "Juhtum ei vaja edasist pärimistööd." }, { status: "CLOSED", label: "Sulge juhtum", detail: "Sulge ainult juhul, kui edasine töö pole vajalik." }],
  NEGOTIATION: [{ status: "SETTLED", label: "Märgi kokkulepe saavutatuks", detail: "Läbirääkimised on edukalt lõppenud." }, { status: "NOT_RELEVANT", label: "Märgi menetlusse mittekuuluvaks", detail: "Juhtum ei vaja edasist pärimistööd." }, { status: "CLOSED", label: "Sulge juhtum", detail: "Sulge ainult juhul, kui edasine töö pole vajalik." }],
  SETTLED: [], CLOSED: [], NOT_RELEVANT: [],
};

export default function InheritanceCasePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { hasRole } = useAuth();
  const workspace = useInheritanceCaseWorkspace(id);
  const [assignee, setAssignee] = useState("");
  const [pendingStatus, setPendingStatus] = useState<{ status: string; label: string } | null>(null);
  const [statusComment, setStatusComment] = useState("");
  const assignment = useAssignInheritanceCase(id);
  const ownerId = workspace.data?.ownerId || "";
  const statusMutation = useChangeInheritanceStatus(ownerId, id);

  if (workspace.isLoading) return <p role="status" className="text-sm text-muted-foreground">Laadin pärimisjuhtumit…</p>;
  if (workspace.isError || !workspace.data) return <Card><CardContent className="space-y-3 p-5"><p role="alert" className="text-sm text-destructive">{workspace.error?.message ?? "Pärimisjuhtumit ei leitud või puudub ligipääs."}</p><Button variant="outline" onClick={() => void workspace.refetch()}>Proovi uuesti</Button></CardContent></Card>;
  const data = workspace.data;
  const inheritanceCase = data.inheritanceCase;
  const heirs = inheritanceCase.heirs ?? [];
  const events = inheritanceCase.events ?? [];
  const currentStatus = inheritanceCase.status ?? "NOTICE_RECEIVED";
  const availableSteps = nextSteps[currentStatus] ?? [];
  const terminal = availableSteps.length === 0;

  return <div className="space-y-6"><div className="flex flex-wrap items-center justify-between gap-3"><div><h1 className="text-2xl font-bold">{data.ownerName}</h1><p className="text-sm text-muted-foreground">{data.ownerCodeMasked} · teade {inheritanceCase.sourceNoticeNumber || "—"}</p></div><div className="flex gap-2"><Button asChild variant="outline"><Link href={`/owners/${data.ownerId}`}>Ava omaniku kaart</Link></Button>{inheritanceCase.sourceUrl && <Button asChild variant="outline"><a href={inheritanceCase.sourceUrl} target="_blank" rel="noreferrer">Ava teate allikas <ExternalLink className="ml-2 h-4 w-4" /></a></Button>}</div></div>
    <div className="grid gap-6 lg:grid-cols-2"><Card><CardHeader><CardTitle>Juhtumi seis</CardTitle></CardHeader><CardContent className="space-y-3 text-sm"><div className="flex justify-between gap-4"><span>Staatus</span><Badge>{statusLabels[currentStatus] ?? currentStatus}</Badge></div><div className="flex justify-between gap-4"><span>Teate kuupäev</span><span>{inheritanceCase.announcementDate || "—"}</span></div><div className="flex justify-between gap-4"><span>Tõestamise tähtaeg</span><span>{inheritanceCase.certificationDeadline || "—"}</span></div><div className="flex justify-between gap-4"><span>Notar</span><span>{inheritanceCase.notaryName || "—"}{inheritanceCase.notaryPhone ? ` · ${inheritanceCase.notaryPhone}` : ""}</span></div></CardContent></Card>
      {hasRole("ORG_ADMIN", "CRM_MANAGER") && <Card><CardHeader><CardTitle>Töö määramine</CardTitle></CardHeader><CardContent className="space-y-3"><p className="text-sm">Praegu: <strong>{data.assignedToSubject || "määramata"}</strong></p><Input aria-label="Kasutaja subject" placeholder="Kasutaja subject" value={assignee} onChange={event => setAssignee(event.target.value)} /><div className="flex gap-2"><Button onClick={() => assignment.mutate(assignee.trim())} disabled={!assignee.trim() || assignment.isPending}>Määra</Button><Button variant="outline" onClick={() => assignment.mutate(undefined)} disabled={assignment.isPending}>Eemalda määrang</Button></div>{assignment.isError && <p role="alert" className="text-sm text-destructive">{assignment.error.message}</p>}</CardContent></Card>}</div>
    <Card><CardHeader><CardTitle>Järgmine samm</CardTitle></CardHeader><CardContent className="space-y-3">{terminal ? <p className="flex items-center gap-2 text-sm text-muted-foreground"><CheckCircle2 className="h-4 w-4 text-primary" />See pärimisjuhtum on lõpetatud. Uusi olekuüleminekuid enam ei pakuta.</p> : <>{availableSteps.map(step => <div key={step.status} className="flex flex-col gap-3 rounded-md border p-3 sm:flex-row sm:items-center sm:justify-between"><div><p className="font-medium">{step.label}</p><p className="text-sm text-muted-foreground">{step.detail}</p></div><Button onClick={() => { setStatusComment(""); statusMutation.reset(); setPendingStatus({ status: step.status, label: step.label }); }}>{step.label}<ArrowRight className="ml-2 h-4 w-4" /></Button></div>)}{statusMutation.isError && <p role="alert" className="text-sm text-destructive">{statusMutation.error.message}</p>}</>}</CardContent></Card>
    <Card><CardHeader><CardTitle>Pärijad</CardTitle></CardHeader><CardContent className="space-y-2">{heirs.length === 0 && <p className="text-sm text-muted-foreground">Pärijaid ei ole veel lisatud.</p>}{heirs.map(heir => <div key={heir.id} className="rounded-md border p-3 text-sm"><div className="font-medium">{heir.displayName}</div><div className="text-muted-foreground">{heir.contactStatus} · {heir.relationToDeceased || "seos täpsustamata"}</div></div>)}</CardContent></Card>
    <Card><CardHeader><CardTitle>Ajajoon</CardTitle></CardHeader><CardContent className="space-y-2">{events.map(event => <div key={event.id} className="border-l-2 pl-3 text-sm"><div className="font-medium">{event.type}</div><div>{event.description}</div><div className="text-xs text-muted-foreground">{event.createdAt ? new Date(event.createdAt).toLocaleString("et-EE") : "—"}</div></div>)}</CardContent></Card>
    <ConfirmActionDialog open={Boolean(pendingStatus)} title="Kas kinnitada järgmine samm?" description={pendingStatus ? <>Muudad pärimisjuhtumi staatuse väärtuseks <strong>{statusLabels[pendingStatus.status] ?? pendingStatus.status}</strong>. Muudatus lisatakse juhtumi ajajoonele.</> : ""} confirmLabel={pendingStatus?.label ?? "Kinnita"} pending={statusMutation.isPending} error={statusMutation.isError ? statusMutation.error.message : undefined} onConfirm={() => { if (pendingStatus) statusMutation.mutate({ status: pendingStatus.status, comment: statusComment.trim() || undefined }, { onSuccess: () => setPendingStatus(null) }); }} onCancel={() => { if (!statusMutation.isPending) { setPendingStatus(null); statusMutation.reset(); } }}><label className="grid gap-1 text-sm font-medium">Märkus (soovituslik)<textarea className="min-h-20 rounded-md border bg-background p-3 text-sm" value={statusComment} onChange={event => setStatusComment(event.target.value)} maxLength={1000} placeholder="Selgita otsust või järgmise sammu konteksti." /></label></ConfirmActionDialog>
  </div>;
}
