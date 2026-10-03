"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useAcceptWorkbasketHandoff, useCancelWorkbasketHandoff, useEditSharedWorkbasket, useWorkbasketHandoff } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export default function WorkbasketHandoffPage() {
  const { id } = useParams<{ id: string }>();
  const handoff = useWorkbasketHandoff(id);
  const accept = useAcceptWorkbasketHandoff();
  const cancel = useCancelWorkbasketHandoff();
  const edit = useEditSharedWorkbasket();
  if (handoff.isLoading) return <p className="text-sm text-muted-foreground">Laadin üleandmist…</p>;
  if (handoff.isError || !handoff.data) return <p className="text-sm text-destructive">{handoff.error?.message ?? "Üleandmist ei leitud."}</p>;
  const { handoff: meta, workbasket } = handoff.data;
  const map = new URLSearchParams();
  if (workbasket.owners[0]) map.set("ownerId", workbasket.owners[0].id);
  else if (workbasket.parcels[0]) map.set("cadastralCode", workbasket.parcels[0].cadastralCode);
  if (workbasket.filters.status) map.set("status", workbasket.filters.status);

  const editShared = () => {
    const name = window.prompt("Töökorvi nimi", workbasket.name);
    if (!name?.trim()) return;
    const purpose = window.prompt("Töökorvi eesmärk", workbasket.purpose || "") ?? undefined;
    edit.mutate({ handoffId: id, data: {
      name: name.trim(), purpose: purpose?.trim() || undefined,
      dynamic: workbasket.selectionMode === "DYNAMIC", filters: workbasket.filters,
      cadastralCodes: workbasket.parcels.map(parcel => parcel.cadastralCode),
      ownerIds: workbasket.owners.map(owner => owner.id),
    }});
  };

  return <div className="space-y-6">
    <div><div className="flex flex-wrap items-center gap-2"><h1 className="text-2xl font-bold">{workbasket.name}</h1><Badge variant="outline">{meta.permission}</Badge><Badge>{meta.status}</Badge></div><p className="mt-1 text-sm">{meta.purpose}</p><p className="text-xs text-muted-foreground">Tähtaeg {new Date(meta.dueAt).toLocaleString("et-EE")} · saatja {meta.createdBy}</p></div>
    <div className="flex flex-wrap gap-2">
      {meta.status === "PENDING" && <Button onClick={() => accept.mutate(id)} disabled={accept.isPending}>Võta töö vastu</Button>}
      {meta.status === "ACCEPTED" && meta.permission === "EDIT" && <Button onClick={editShared} disabled={edit.isPending}>Muuda töökorvi</Button>}
      {map.size > 0 && <Button asChild variant="outline"><Link href={`/map?${map}`}>Ava kaardikontekst</Link></Button>}
      <Button asChild variant="outline"><Link href="/workbaskets/handoffs">Kõik üleandmised</Link></Button>
      <Button variant="ghost" onClick={() => cancel.mutate(id)} disabled={cancel.isPending}>Tühista minu üleandmine</Button>
    </div>
    {edit.isError && <p className="text-sm text-destructive">{edit.error.message}</p>}
    {workbasket.hiddenItemCount > 0 && <p className="rounded-md bg-amber-50 p-3 text-sm text-amber-900">{workbasket.hiddenItemCount} objekti on peidetud, sest sul pole neile praegu juurdepääsu. Üleandmine ei laienda ressursiõigusi ning jagatud muutmine ei eemalda peidetud objekte.</p>}
    <div className="grid gap-4 lg:grid-cols-2">
      <Card><CardHeader><CardTitle>Omanikud</CardTitle></CardHeader><CardContent className="space-y-2">{workbasket.owners.map(owner => <Link key={owner.id} className="block rounded-md border p-2 text-sm text-primary hover:underline" href={`/owners/${owner.id}`}>{owner.name}</Link>)}{workbasket.owners.length === 0 && <p className="text-sm text-muted-foreground">Nähtavaid omanikke pole.</p>}</CardContent></Card>
      <Card><CardHeader><CardTitle>Kinnistud</CardTitle></CardHeader><CardContent className="space-y-2">{workbasket.parcels.map(parcel => <Link key={parcel.id} className="block rounded-md border p-2 text-sm text-primary hover:underline" href={`/map?cadastralCode=${encodeURIComponent(parcel.cadastralCode)}`}>{parcel.name || "Kinnistu"} · {parcel.cadastralCode}</Link>)}{workbasket.parcels.length === 0 && <p className="text-sm text-muted-foreground">Nähtavaid kinnistuid pole.</p>}</CardContent></Card>
    </div>
    <Card><CardHeader><CardTitle>Salvestatud kaardifiltrid</CardTitle></CardHeader><CardContent><pre className="overflow-auto rounded-md bg-muted/40 p-3 text-xs">{JSON.stringify(workbasket.filters, null, 2)}</pre></CardContent></Card>
  </div>;
}
