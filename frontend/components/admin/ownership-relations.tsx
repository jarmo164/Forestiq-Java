"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { AlertTriangle, History, Link2, MapPinned, Plus, Search } from "lucide-react";

import {
  useCreateOwnershipRelation,
  useEndOwnershipRelation,
  useOwnersCursor,
  useOwnershipRelationAudit,
  useOwnershipRelationsCursor,
  useResolveOwnershipConflict,
  type OwnershipRelation,
} from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";

const SOURCE_LABELS: Record<string, string> = {
  METSIS_PORTFOLIO: "MetsIS / Forestek",
  MANUAL: "Käsitsi",
  FORESTIQ_LEGACY: "ForestIQ legacy",
};

function formatTime(value?: string | null) {
  return value ? new Date(value).toLocaleString("et-EE") : "—";
}

export function OwnershipRelationsWorkspace() {
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [active, setActive] = useState("");
  const [source, setSource] = useState("");
  const [pageIndex, setPageIndex] = useState(0);
  const [cursorHistory, setCursorHistory] = useState<Array<string | null>>([null]);
  const cursor = cursorHistory[pageIndex] ?? null;
  const relations = useOwnershipRelationsCursor(search, active, source, cursor, 50);
  const relationData = useMemo(() => relations.data?.content ?? [], [relations.data?.content]);
  const createRelation = useCreateOwnershipRelation();
  const endRelation = useEndOwnershipRelation();
  const resolveConflict = useResolveOwnershipConflict();

  useEffect(() => {
    const timeout = window.setTimeout(() => {
      setSearch(searchInput.trim());
      setPageIndex(0);
      setCursorHistory([null]);
    }, 300);
    return () => window.clearTimeout(timeout);
  }, [searchInput]);

  useEffect(() => {
    setPageIndex(0);
    setCursorHistory([null]);
  }, [active, source]);

  const nextPage = () => {
    const nextCursor = relations.data?.nextCursor;
    if (!nextCursor) return;
    setCursorHistory(current => [...current.slice(0, pageIndex + 1), nextCursor]);
    setPageIndex(current => current + 1);
  };

  const [ownerSearch, setOwnerSearch] = useState("");
  const owners = useOwnersCursor(ownerSearch, null, 10);
  const [selectedOwnerId, setSelectedOwnerId] = useState("");
  const [cadastralCode, setCadastralCode] = useState("");
  const [reason, setReason] = useState("");
  const [historyRelation, setHistoryRelation] = useState<OwnershipRelation | null>(null);
  const audit = useOwnershipRelationAudit(
    historyRelation?.ownerId ?? "",
    historyRelation?.parcelId ?? "",
    Boolean(historyRelation),
  );

  const activeOwnerCounts = useMemo(() => {
    const ownersByCadastre = new Map<string, Set<string>>();
    for (const relation of relationData) {
      if (!relation.active) continue;
      const ownersForCadastre = ownersByCadastre.get(relation.cadastralCode) ?? new Set<string>();
      ownersForCadastre.add(relation.ownerId);
      ownersByCadastre.set(relation.cadastralCode, ownersForCadastre);
    }
    return new Map(Array.from(ownersByCadastre.entries()).map(([code, ownerIds]) => [code, ownerIds.size]));
  }, [relationData]);

  const addRelation = () => {
    if (!selectedOwnerId || !cadastralCode.trim() || !reason.trim()) return;
    createRelation.mutate({ ownerId: selectedOwnerId, cadastralCode: cadastralCode.trim(), reason: reason.trim() }, {
      onSuccess: () => {
        setCadastralCode("");
        setReason("");
      },
    });
  };

  const end = (relation: OwnershipRelation) => {
    const value = window.prompt(`Põhjus, miks lõpetada seos ${relation.ownerName} ↔ ${relation.cadastralCode}:`);
    if (!value?.trim()) return;
    endRelation.mutate({ ownerId: relation.ownerId, parcelId: relation.parcelId, reason: value.trim() });
  };

  const reactivate = (relation: OwnershipRelation) => {
    const value = window.prompt(`Põhjus, miks taastada seos ${relation.ownerName} ↔ ${relation.cadastralCode}:`);
    if (!value?.trim()) return;
    createRelation.mutate({ ownerId: relation.ownerId, cadastralCode: relation.cadastralCode, reason: value.trim() });
  };

  const resolve = (relation: OwnershipRelation, resolution: "KEEP_MANUAL" | "ACCEPT_PROVIDER") => {
    if (!relation.conflict) return;
    const action = resolution === "KEEP_MANUAL" ? "jätta käsitsi otsus" : "võtta registri seis";
    const value = window.prompt(`Sisesta põhjus, miks ${action}:`);
    if (!value?.trim()) return;
    resolveConflict.mutate({ conflictId: relation.conflict.id, resolution, reason: value.trim() });
  };

  const busy = createRelation.isPending || endRelation.isPending || resolveConflict.isPending;
  const mutationError = createRelation.error ?? endRelation.error ?? resolveConflict.error;

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2"><Link2 className="h-5 w-5" />Omaniku–katastri seosed</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          <p className="text-sm text-muted-foreground">
            See vaade haldab canonical <code>owner_parcel_relation</code> seoseid. Käsitsi lõpetamine ei kustuta ajalugu ning
            registri vastuolu muutub konfliktiks, mitte vaikivaks ülekirjutuseks.
          </p>
          <div className="grid gap-3 rounded-md border bg-muted/20 p-4 lg:grid-cols-[1.2fr_1fr_1fr_auto]">
            <div className="relative">
              <Search className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
              <Input className="pl-9" value={searchInput} onChange={event => setSearchInput(event.target.value)}
                placeholder="Omanik, isiku-/registrikood või katastritunnus" />
            </div>
            <select className="h-10 rounded-md border bg-background px-3 text-sm" value={active} onChange={event => setActive(event.target.value)}>
              <option value="">Kõik seosed</option>
              <option value="true">Aktiivsed</option>
              <option value="false">Lõpetatud</option>
            </select>
            <select className="h-10 rounded-md border bg-background px-3 text-sm" value={source} onChange={event => setSource(event.target.value)}>
              <option value="">Kõik allikad</option>
              <option value="METSIS_PORTFOLIO">MetsIS / Forestek</option>
              <option value="MANUAL">Käsitsi</option>
              <option value="FORESTIQ_LEGACY">ForestIQ legacy</option>
            </select>
            <Button variant="outline" onClick={() => relations.refetch()} disabled={relations.isFetching}>Värskenda</Button>
          </div>

          {relations.isLoading && <p className="text-sm text-muted-foreground">Laadin omandisuhteid...</p>}
          {relations.isError && <p className="text-sm text-destructive">{relations.error.message}</p>}
          {mutationError && <p className="text-sm text-destructive">{mutationError.message}</p>}
          {relations.data && (
            <>
              <div className="overflow-x-auto">
                <Table>
                  <TableHeader><TableRow>
                    <TableHead>Omanik</TableHead><TableHead>Katastriüksus</TableHead><TableHead>Staatus</TableHead>
                    <TableHead>Allikas</TableHead><TableHead>Ajad</TableHead><TableHead>Andmekvaliteet</TableHead><TableHead></TableHead>
                  </TableRow></TableHeader>
                  <TableBody>
                    {relationData.map(relation => {
                      const ownerCount = activeOwnerCounts.get(relation.cadastralCode) ?? 0;
                      return (
                      <TableRow key={`${relation.ownerId}:${relation.parcelId}`}>
                        <TableCell>
                          <Link className="font-medium hover:underline" href={`/owners/${relation.ownerId}`}>{relation.ownerName}</Link>
                          <div className="text-xs text-muted-foreground">{relation.ownerCode}</div>
                        </TableCell>
                        <TableCell>
                          <div className="font-mono text-xs">{relation.cadastralCode}</div>
                          <div className="text-xs text-muted-foreground">
                            {relation.parcelName || "—"}{relation.areaHectares != null ? ` · ${Number(relation.areaHectares).toFixed(2)} ha` : ""}
                          </div>
                          {relation.active && ownerCount > 1 && <Badge variant="secondary" className="mt-1">{ownerCount} aktiivset omanikku sellel lehel</Badge>}
                        </TableCell>
                        <TableCell><Badge variant={relation.active ? "default" : "outline"}>{relation.active ? "Aktiivne" : "Lõpetatud"}</Badge>
                          {relation.manualOverride && <div className="mt-1"><Badge variant="secondary">Käsitsi kaitstud</Badge></div>}
                        </TableCell>
                        <TableCell className="text-xs">
                          {SOURCE_LABELS[relation.source] ?? relation.source}
                          {relation.manualActorSubject && <div className="mt-1 text-muted-foreground">Muutja: {relation.manualActorSubject}</div>}
                          {relation.manualReason && <div className="mt-1 max-w-56 text-muted-foreground">{relation.manualReason}</div>}
                        </TableCell>
                        <TableCell className="min-w-44 text-xs">
                          <div>Loodud: {formatTime(relation.createdAt)}</div>
                          <div>Muudetud: {formatTime(relation.updatedAt)}</div>
                          {!relation.active && <div>Lõpetatud: {formatTime(relation.deactivatedAt)}</div>}
                        </TableCell>
                        <TableCell className="max-w-sm text-xs">
                          {relation.conflict ? <div className="space-y-2 rounded-md border border-amber-300 bg-amber-50 p-2 text-amber-950">
                            <div className="flex items-center gap-1 font-medium"><AlertTriangle className="h-4 w-4" />Registri vastuolu</div>
                            <div>{SOURCE_LABELS[relation.conflict.source] ?? relation.conflict.source} näeb seost kui <strong>{relation.conflict.observedActive ? "aktiivset" : "lõpetatut"}</strong>.</div>
                            <div className="text-[11px]">Vaatlus: {formatTime(relation.conflict.observedAt)}</div>
                            <div className="flex flex-wrap gap-1">
                              <Button size="sm" variant="outline" disabled={busy} onClick={() => resolve(relation, "KEEP_MANUAL")}>Jäta käsitsi otsus</Button>
                              <Button size="sm" disabled={busy} onClick={() => resolve(relation, "ACCEPT_PROVIDER")}>Võta registri seis</Button>
                            </div>
                          </div> : <span className="text-muted-foreground">Konflikti pole</span>}
                        </TableCell>
                        <TableCell>
                          <div className="flex min-w-44 flex-col gap-1">
                            <Button asChild size="sm" variant="outline"><Link href={`/map?cadastralCode=${encodeURIComponent(relation.cadastralCode)}`}><MapPinned className="mr-1 h-3.5 w-3.5" />Kaardile</Link></Button>
                            <Button size="sm" variant="ghost" onClick={() => setHistoryRelation(relation)}><History className="mr-1 h-3.5 w-3.5" />Ajalugu</Button>
                            {relation.active
                              ? <Button size="sm" variant="ghost" disabled={busy} onClick={() => end(relation)}>Lõpeta seos</Button>
                              : <Button size="sm" variant="ghost" disabled={busy} onClick={() => reactivate(relation)}>Taasta seos</Button>}
                          </div>
                        </TableCell>
                      </TableRow>
                    );})}
                    {relationData.length === 0 && <TableRow><TableCell colSpan={7} className="py-8 text-center text-muted-foreground">Sobivaid seoseid ei leitud.</TableCell></TableRow>}
                  </TableBody>
                </Table>
              </div>
              <div className="flex items-center justify-between text-sm text-muted-foreground">
                <span>Leht {pageIndex + 1} · {relationData.length} seost</span>
                <div className="flex gap-2">
                  <Button variant="outline" size="sm" disabled={pageIndex === 0} onClick={() => setPageIndex(current => current - 1)}>Eelmine</Button>
                  <Button variant="outline" size="sm" disabled={!relations.data.hasNext} onClick={nextPage}>Järgmine</Button>
                </div>
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle className="flex items-center gap-2"><Plus className="h-5 w-5" />Lisa puuduv seos käsitsi</CardTitle></CardHeader>
        <CardContent className="space-y-3">
          <div className="grid gap-3 lg:grid-cols-2">
            <div className="space-y-2">
              <Input value={ownerSearch} onChange={event => { setOwnerSearch(event.target.value); setSelectedOwnerId(""); }} placeholder="Otsi omanikku nime või registrikoodi järgi" />
              {ownerSearch.trim().length >= 2 && owners.data && (
                <div className="max-h-44 overflow-y-auto rounded-md border">
                  {owners.data.content.map(owner => (
                    <button key={owner.id} type="button"
                      className={`flex w-full justify-between border-b px-3 py-2 text-left text-sm last:border-b-0 hover:bg-muted ${selectedOwnerId === owner.id ? "bg-primary/10" : ""}`}
                      onClick={() => { setSelectedOwnerId(owner.id ?? ""); setOwnerSearch(`${owner.displayName} · ${owner.personalOrRegistryCode}`); }}>
                      <span>{owner.displayName}</span><span className="text-muted-foreground">{owner.personalOrRegistryCode}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <Input value={cadastralCode} onChange={event => setCadastralCode(event.target.value)} placeholder="Katastritunnus 12345:678:9012" />
          </div>
          <Input value={reason} onChange={event => setReason(event.target.value)} placeholder="Muudatuse põhjus (kohustuslik)" />
          <Button onClick={addRelation} disabled={!selectedOwnerId || !cadastralCode.trim() || !reason.trim() || createRelation.isPending}>Lisa seos</Button>
        </CardContent>
      </Card>

      {historyRelation && (
        <Card>
          <CardHeader className="flex-row items-center justify-between gap-3">
            <CardTitle>Seose ajalugu · {historyRelation.ownerName} ↔ {historyRelation.cadastralCode}</CardTitle>
            <Button variant="ghost" onClick={() => setHistoryRelation(null)}>Sulge</Button>
          </CardHeader>
          <CardContent>
            {audit.isLoading && <p className="text-sm text-muted-foreground">Laadin ajalugu...</p>}
            {audit.isError && <p className="text-sm text-destructive">{audit.error.message}</p>}
            {audit.data?.length === 0 && <p className="text-sm text-muted-foreground">Käsitsi muudatusi pole veel tehtud.</p>}
            <div className="space-y-3">
              {audit.data?.map(event => <div key={event.id} className="rounded-md border p-3 text-sm">
                <div className="flex flex-wrap justify-between gap-2"><strong>{event.action}</strong><span className="text-xs text-muted-foreground">{formatTime(event.createdAt)}</span></div>
                <div className="mt-1">{event.reason}</div>
                <div className="mt-1 text-xs text-muted-foreground">{event.actorSubject} · {SOURCE_LABELS[event.relationSource] ?? event.relationSource}</div>
              </div>)}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  );
}