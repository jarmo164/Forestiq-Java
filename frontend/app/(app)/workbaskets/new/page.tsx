"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowLeft, Save } from "lucide-react";
import { useCreateWorkbasket, useOwner } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

export default function NewWorkbasketPage() {
  const params = useSearchParams();
  const router = useRouter();
  const ownerId = params.get("ownerId") ?? "";
  const cadastralCode = params.get("cadastralCode") ?? "";
  const status = params.get("status") ?? "";
  const owner = useOwner(ownerId);
  const create = useCreateWorkbasket();
  const [name, setName] = useState("");
  const [purpose, setPurpose] = useState("");
  const [dynamic, setDynamic] = useState(false);

  const codes = useMemo(() => ownerId && owner.data ? owner.data.parcels.map(parcel => parcel.cadastralCode) : cadastralCode ? [cadastralCode] : [], [ownerId, owner.data, cadastralCode]);
  const save = () => create.mutate({
    name: name.trim(), purpose: purpose.trim() || undefined, dynamic,
    filters: status ? { status } : {},
    cadastralCodes: codes,
    ownerIds: ownerId ? [ownerId] : [],
  }, { onSuccess: basket => router.push(`/workbaskets/${basket.id}`) });

  return (
    <div className="max-w-2xl space-y-6">
      <Button asChild variant="ghost" className="-ml-3"><Link href="/map"><ArrowLeft className="mr-2 h-4 w-4" />Tagasi kaardile</Link></Button>
      <div><h1 className="text-2xl font-bold">Salvesta kaardi töökorv</h1><p className="text-sm text-muted-foreground">ForestIQ salvestab eesmärgi, töövoofistri ja praeguse omaniku või kinnistu valiku. Õigused kontrollitakse serveris.</p></div>
      <Card>
        <CardHeader><CardTitle>Uus töökorv</CardTitle></CardHeader>
        <CardContent className="space-y-4">
          <label className="grid gap-1 text-sm"><span className="font-medium">Nimi</span><Input value={name} onChange={event => setName(event.target.value)} placeholder="Näiteks: Võrumaa nädal 36" maxLength={180} /></label>
          <label className="grid gap-1 text-sm"><span className="font-medium">Eesmärk</span><Input value={purpose} onChange={event => setPurpose(event.target.value)} placeholder="Mida selle valikuga otsustad või kontrollid?" maxLength={1000} /></label>
          <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={dynamic} onChange={event => setDynamic(event.target.checked)} />Dünaamiline töökorv (filtrite tähendus on oluline ka hiljem)</label>
          <div className="rounded-md bg-slate-50 p-3 text-sm">
            <p><strong>Omanik:</strong> {ownerId ? owner.data?.displayName ?? "Laadin..." : "—"}</p>
            <p><strong>Kinnistuid:</strong> {codes.length}</p>
            <p><strong>Töövoofilter:</strong> {status || "Kõik"}</p>
          </div>
          <Button onClick={save} disabled={!name.trim() || create.isPending || Boolean(ownerId && owner.isLoading)}><Save className="mr-2 h-4 w-4" />Salvesta töökorv</Button>
          {create.isError && <p className="text-sm text-destructive">{create.error.message}</p>}
        </CardContent>
      </Card>
    </div>
  );
}
