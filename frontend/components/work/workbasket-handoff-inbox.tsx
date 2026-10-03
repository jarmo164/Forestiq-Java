"use client";

import Link from "next/link";
import { useWorkbasketHandoffInbox } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export function WorkbasketHandoffInbox() {
  const inbox = useWorkbasketHandoffInbox();
  const pending = inbox.data?.filter(item => item.status === "PENDING") ?? [];
  return <Card>
    <CardHeader className="flex flex-row items-center justify-between gap-3"><CardTitle>Üle antud analüüsitööd</CardTitle><Button asChild size="sm" variant="outline"><Link href="/workbaskets/handoffs">Kõik üleandmised</Link></Button></CardHeader>
    <CardContent className="space-y-3">
      {inbox.isLoading && <p className="text-sm text-muted-foreground">Laadin üleandmisi…</p>}
      {pending.map(item => <Link key={item.id} href={`/workbaskets/handoffs/${item.id}`} className="block rounded-md border p-3 hover:bg-muted/40">
        <div className="flex flex-wrap items-center gap-2"><strong>{item.workbasketName}</strong><Badge variant="outline">{item.permission}</Badge>{new Date(item.dueAt) < new Date() && <Badge variant="destructive">Üle tähtaja</Badge>}</div>
        <p className="mt-1 text-sm">{item.purpose}</p>
        <p className="mt-1 text-xs text-muted-foreground">Tähtaeg {new Date(item.dueAt).toLocaleString("et-EE")} · saatja {item.createdBy}</p>
      </Link>)}
      {!inbox.isLoading && pending.length === 0 && <p className="text-sm text-muted-foreground">Uusi töökorvi üleandmisi ei ole.</p>}
    </CardContent>
  </Card>;
}
