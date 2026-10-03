"use client";

import Link from "next/link";
import { BriefcaseBusiness, MapPinned } from "lucide-react";
import { useWorkbaskets } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";

export default function WorkbasketsPage() {
  const baskets = useWorkbaskets();
  return (
    <div className="space-y-6">
      <div><h1 className="flex items-center gap-2 text-2xl font-bold"><BriefcaseBusiness className="h-6 w-6" />Salvestatud töökorvid</h1><p className="text-sm text-muted-foreground">Taaskasuta kaardil uuritud valikut ja filtreid. Avamisel kontrollitakse õigused uuesti.</p></div>
      {baskets.isLoading && <p className="text-sm text-muted-foreground">Laadin töökorve...</p>}
      {baskets.isError && <p className="text-destructive">{baskets.error.message}</p>}
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {baskets.data?.map(basket => (
          <Card key={basket.id}>
            <CardHeader><div className="flex items-start justify-between gap-2"><CardTitle>{basket.name}</CardTitle><Badge variant="outline">{basket.selectionMode === "DYNAMIC" ? "Dünaamiline" : "Fikseeritud"}</Badge></div></CardHeader>
            <CardContent className="space-y-3">
              <p className="text-sm">{basket.purpose || "Eesmärki pole kirjeldatud."}</p>
              <p className="text-xs text-muted-foreground">Loodud {new Date(basket.createdAt).toLocaleString("et-EE")} · muudetud {new Date(basket.updatedAt).toLocaleString("et-EE")}</p>
              <Button asChild><Link href={`/workbaskets/${basket.id}`}><MapPinned className="mr-2 h-4 w-4" />Ava töökorv</Link></Button>
            </CardContent>
          </Card>
        ))}
      </div>
      {baskets.data?.length === 0 && <Card><CardContent className="pt-6 text-sm text-muted-foreground">Töökorve veel pole. Ava kaart ja salvesta uuritud omanik või kinnistu.</CardContent></Card>}
    </div>
  );
}
