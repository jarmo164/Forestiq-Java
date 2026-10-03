"use client";

import Link from "next/link";
import { useWorkbasketHandoffInbox, useWorkbasketHandoffSent } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

function fmt(value: string) { return new Date(value).toLocaleString("et-EE", { dateStyle: "short", timeStyle: "short" }); }

export default function WorkbasketHandoffsPage() {
  const inbox = useWorkbasketHandoffInbox();
  const sent = useWorkbasketHandoffSent();
  return <div className="space-y-6">
    <div><h1 className="text-2xl font-bold">Töökorvide üleandmised</h1><p className="text-sm text-muted-foreground">Sulle antud ja sinu algatatud analüüsitöö üleandmised.</p></div>
    <div className="grid gap-4 xl:grid-cols-2">
      <Card><CardHeader><CardTitle>Sulle antud</CardTitle></CardHeader><CardContent className="space-y-3">
        {inbox.data?.map(item => <Link key={item.id} href={`/workbaskets/handoffs/${item.id}`} className="block rounded-md border p-3 hover:bg-muted/40">
          <div className="flex flex-wrap items-center gap-2"><strong>{item.workbasketName}</strong><Badge variant="outline">{item.permission}</Badge><Badge variant={item.status === "PENDING" ? "default" : "secondary"}>{item.status}</Badge></div>
          <p className="mt-1 text-sm">{item.purpose}</p><p className="mt-1 text-xs text-muted-foreground">Tähtaeg {fmt(item.dueAt)} · saatja {item.createdBy}</p>
        </Link>)}
        {inbox.data?.length === 0 && <p className="text-sm text-muted-foreground">Aktiivseid üleandmisi ei ole.</p>}
      </CardContent></Card>
      <Card><CardHeader><CardTitle>Sinu algatatud</CardTitle></CardHeader><CardContent className="space-y-3">
        {sent.data?.map(item => <Link key={item.id} href={`/workbaskets/handoffs/${item.id}`} className="block rounded-md border p-3 hover:bg-muted/40">
          <div className="flex flex-wrap items-center gap-2"><strong>{item.workbasketName}</strong><Badge variant="outline">{item.permission}</Badge><Badge variant="secondary">{item.status}</Badge></div>
          <p className="mt-1 text-sm">{item.purpose}</p><p className="mt-1 text-xs text-muted-foreground">Saaja {item.recipientSubject || item.recipientRole} · tähtaeg {fmt(item.dueAt)}</p>
        </Link>)}
        {sent.data?.length === 0 && <p className="text-sm text-muted-foreground">Sa pole töökorve üle andnud.</p>}
      </CardContent></Card>
    </div>
  </div>;
}
