"use client";

import { useEffect, useState } from "react";
import { Search } from "lucide-react";
import { OwnerOnboardingPanel } from "@/components/owners/owner-onboarding-panel";
import { OwnerTable } from "@/components/owners/owner-table";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { useOwnersCursor } from "@/lib/api";

export default function OwnersPage() {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [pageIndex, setPageIndex] = useState(0);
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([null]);
  const cursor = cursorHistory[pageIndex] ?? null;
  const owners = useOwnersCursor(search, cursor);

  useEffect(() => {
    const timeout = window.setTimeout(() => { setPageIndex(0); setCursorHistory([null]); setSearch(searchInput); }, 250);
    return () => window.clearTimeout(timeout);
  }, [searchInput]);

  const nextPage = () => {
    const nextCursor = owners.data?.nextCursor;
    if (!nextCursor) return;
    setCursorHistory(current => [...current.slice(0, pageIndex + 1), nextCursor]);
    setPageIndex(current => current + 1);
  };
  const emptyText = search ? "Tulemusi ei leitud. Kontrolli nime, isiku- või registrikoodi, e-posti või telefoninumbrit." : "Omanikke ei leitud.";

  return <><div className="mb-8 flex flex-col justify-between gap-4 sm:flex-row sm:items-end"><div><h1 className="text-3xl font-bold tracking-tight">Omanikud</h1><p className="mt-2 text-muted-foreground">Otsi ja halda oma organisatsiooni metsaomanikke.</p></div></div>
    <OwnerOnboardingPanel />
    <Card><CardContent className="p-4"><div className="relative mb-2 max-w-lg"><Search className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" /><Input aria-label="Otsi omaniku nime, isiku- või registrikoodi, e-posti või telefoninumbri järgi" value={searchInput} onChange={event => setSearchInput(event.target.value)} placeholder="Nimi, kood, e-post või telefon" className="pl-9" /></div><p className="mb-4 text-xs text-muted-foreground">Telefoninumbri puhul võid kasutada tühikuid, sidekriipse või sulge; otsing võrdleb numbreid normaliseeritult. Tulemused on serveris stabiilselt järjestatud viimase uuenduse järgi.</p>
      {owners.isPending ? <p role="status" className="p-8 text-center text-sm text-muted-foreground">Laadin omanikke…</p> : owners.isError ? <div className="space-y-3 p-8 text-center"><p role="alert" className="text-sm text-destructive">{owners.error.message}</p><Button variant="outline" size="sm" onClick={() => void owners.refetch()}>Proovi uuesti</Button></div> : <><OwnerTable data={owners.data?.content ?? []} emptyText={emptyText} /><div className="mt-4 flex items-center justify-between text-sm text-muted-foreground"><span>Leht {pageIndex + 1} · {owners.data?.content.length ?? 0} omanikku · järjestus: viimati uuendatud</span><div className="flex gap-2"><Button variant="outline" size="sm" disabled={pageIndex === 0} onClick={() => setPageIndex(current => current - 1)}>Eelmine</Button><Button variant="outline" size="sm" disabled={!owners.data?.hasNext} onClick={nextPage}>Järgmine</Button></div></div></>}</CardContent></Card>
  </>;
}
