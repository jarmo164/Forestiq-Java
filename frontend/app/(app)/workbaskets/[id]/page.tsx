"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { ArrowLeft, MapPinned, Share2, Trees } from "lucide-react";
import { useShareWorkbasket, useWorkbasket, useWorkflowAssignees } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

export default function WorkbasketPage() {
  const { id } = useParams<{ id: string }>();
  const basket = useWorkbasket(id);
  const { data: assignees = [] } = useWorkflowAssignees();
  const share = useShareWorkbasket();
  const [recipientMode, setRecipientMode] = useState<"USER" | "ROLE">("USER");
  const [recipientSubject, setRecipientSubject] = useState("");
  const [recipientRole, setRecipientRole] = useState("");
  const [permission, setPermission] = useState<"READ" | "EDIT">("READ");
  const [purpose, setPurpose] = useState("");
  const [dueAt, setDueAt] = useState("");

  if (basket.isLoading) return <p className="text-sm text-muted-foreground">Laadin töökorvi...</p>;
  if (basket.isError || !basket.data) return <p className="text-destructive">{basket.error?.message ?? "Töökorvi ei leitud."}</p>;
  const data = basket.data;
  const mapParams = new URLSearchParams();
  const firstOwner = data.owners[0];
  const firstParcel = data.parcels[0];
  if (firstOwner) mapParams.set("ownerId", firstOwner.id);
  else if (firstParcel) mapParams.set("cadastralCode", firstParcel.cadastralCode);
  if (data.filters.status) mapParams.set("status", data.filters.status);

  const submitShare = () => {
    if (!purpose.trim() || !dueAt) return;
    const due = new Date(dueAt);
    if (Number.isNaN(due.getTime())) return;
    share.mutate({
      workbasketId: id,
      recipientSubject: recipientMode === "USER" ? recipientSubject || undefined : undefined,
      recipientRole: recipientMode === "ROLE" ? recipientRole || undefined : undefined,
      permission,
      purpose: purpose.trim(),
      dueAt: due.toISOString(),
    }, { onSuccess: () => { setPurpose(""); setDueAt(""); } });
  };

  return (
    <div className="space-y-6">
      <Button asChild variant="ghost" className="-ml-3"><Link href="/workbaskets"><ArrowLeft className="mr-2 h-4 w-4" />Tagasi töökorvide juurde</Link></Button>
      <div><div className="flex flex-wrap items-center gap-2"><h1 className="text-2xl font-bold">{data.name}</h1><Badge variant="outline">{data.selectionMode === "DYNAMIC" ? "Dünaamiline" : "Fikseeritud"}</Badge></div><p className="text-sm text-muted-foreground">{data.purpose || "Eesmärki pole kirjeldatud."}</p></div>
      <div className="flex flex-wrap gap-2">
        {(firstOwner || firstParcel) && <Button asChild><Link href={`/map?${mapParams.toString()}`}><MapPinned className="mr-2 h-4 w-4" />Taasta kaardivaade</Link></Button>}
        {firstOwner && <Button asChild variant="outline"><Link href={`/owners/${firstOwner.id}/brief`}><Trees className="mr-2 h-4 w-4" />Ava portfellibrief</Link></Button>}
        <Button asChild variant="outline"><Link href="/workbaskets/handoffs">Minu üleandmised</Link></Button>
      </div>
      {data.hiddenItemCount > 0 && <p className="rounded-md bg-amber-50 p-3 text-sm text-amber-900">{data.hiddenItemCount} varem salvestatud objekti ei kuvata, sest sul puudub neile praegu juurdepääs.</p>}
      <div className="grid gap-4 lg:grid-cols-2">
        <Card><CardHeader><CardTitle>Omanikud ({data.owners.length})</CardTitle></CardHeader><CardContent className="space-y-2">{data.owners.map(owner => <div key={owner.id} className="flex items-center justify-between rounded-md border p-2 text-sm"><Link className="font-medium text-primary hover:underline" href={`/owners/${owner.id}`}>{owner.name}</Link><Button asChild variant="ghost" size="sm"><Link href={`/owners/${owner.id}/brief`}>Brief</Link></Button></div>)}{data.owners.length === 0 && <p className="text-sm text-muted-foreground">Omanikke pole fikseeritud.</p>}</CardContent></Card>
        <Card><CardHeader><CardTitle>Kinnistud ({data.parcels.length})</CardTitle></CardHeader><CardContent className="space-y-2">{data.parcels.map(parcel => <div key={parcel.id} className="flex items-center justify-between rounded-md border p-2 text-sm"><span>{parcel.name || "Kinnistu"} · {parcel.cadastralCode}</span><Button asChild variant="ghost" size="sm"><Link href={`/map?cadastralCode=${encodeURIComponent(parcel.cadastralCode)}${data.filters.status ? `&status=${encodeURIComponent(data.filters.status)}` : ""}`}>Kaart</Link></Button></div>)}{data.parcels.length === 0 && <p className="text-sm text-muted-foreground">Kinnistuid pole fikseeritud.</p>}</CardContent></Card>
      </div>

      <Card><CardHeader><CardTitle className="flex items-center gap-2"><Share2 className="h-5 w-5" />Anna analüüsitöö üle</CardTitle></CardHeader><CardContent className="space-y-4">
        <p className="text-sm text-muted-foreground">Üleandmine ei anna vastuvõtjale uusi kinnistu- ega omanikuõigusi. Ligipääs kontrollitakse uuesti avamisel.</p>
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <select className="h-10 rounded-md border bg-background px-3 text-sm" value={recipientMode} onChange={e => setRecipientMode(e.target.value as "USER" | "ROLE")} aria-label="Saaja tüüp"><option value="USER">Kasutaja</option><option value="ROLE">Roll</option></select>
          {recipientMode === "USER" ? <select className="h-10 rounded-md border bg-background px-3 text-sm" value={recipientSubject} onChange={e => setRecipientSubject(e.target.value)} aria-label="Saaja"><option value="">Vali kasutaja…</option>{assignees.map(user => <option key={user.subject} value={user.subject}>{user.name || user.username}</option>)}</select>
            : <select className="h-10 rounded-md border bg-background px-3 text-sm" value={recipientRole} onChange={e => setRecipientRole(e.target.value)} aria-label="Saajaroll"><option value="">Vali roll…</option>{["CRM_MANAGER", "EVALUATOR", "CALLER", "VIEWER"].map(role => <option key={role}>{role}</option>)}</select>}
          <select className="h-10 rounded-md border bg-background px-3 text-sm" value={permission} onChange={e => setPermission(e.target.value as "READ" | "EDIT")} aria-label="Õigus"><option value="READ">Lugemine</option><option value="EDIT">Muutmine</option></select>
          <Input type="datetime-local" value={dueAt} onChange={e => setDueAt(e.target.value)} aria-label="Üleandmise tähtaeg" />
        </div>
        <textarea className="min-h-24 w-full rounded-md border bg-background p-3 text-sm" value={purpose} onChange={e => setPurpose(e.target.value)} placeholder="Mida vastuvõtja peab kontrollima või tegema?" />
        <Button onClick={submitShare} disabled={share.isPending || !purpose.trim() || !dueAt || (recipientMode === "USER" ? !recipientSubject : !recipientRole)}>Anna üle</Button>
        {share.isError && <p className="text-sm text-destructive">{share.error.message}</p>}
        {share.data && <p className="text-sm text-emerald-700">Üleandmine loodud. Vastuvõtja näeb seda oma töölaual.</p>}
      </CardContent></Card>

      <Card><CardHeader><CardTitle>Salvestatud filtrid</CardTitle></CardHeader><CardContent><pre className="overflow-auto rounded-md bg-slate-50 p-3 text-xs">{JSON.stringify(data.filters, null, 2)}</pre><p className="mt-3 text-xs text-muted-foreground">Loodud {new Date(data.createdAt).toLocaleString("et-EE")} · viimati muudetud {new Date(data.updatedAt).toLocaleString("et-EE")}</p></CardContent></Card>
    </div>
  );
}
