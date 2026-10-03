"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Filter, MapPinned, Save, Search, Trees, X } from "lucide-react";
import { ParcelMap } from "@/components/map/parcel-map";
import { MapParcelPanel } from "@/components/map/map-parcel-panel";
import { useMapSearch, useOwner, useOwnersCursor, type MapSearchResult } from "@/lib/api";
import { useCompleteMapParcelSummary } from "@/lib/api/map-cadastre";
import type { MapDetailGeometrySelection } from "@/lib/map/detail-geometry";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";

const WORKFLOW_FILTERS = [
  { value: "", label: "Kõik kinnistud" }, { value: "CONTRACTED", label: "Leping" }, { value: "NEGOTIATION", label: "Läbirääkimised" },
  { value: "VALUED", label: "Hinnastatud" }, { value: "EVALUATION", label: "Hindamisel" }, { value: "WAITS_FOR_EVALUATION", label: "Ootab hindamist" },
  { value: "INTERESTED", label: "Huvitatud" }, { value: "CONTACTED", label: "Kontakteeritud" }, { value: "ASSIGNED", label: "Määratud" },
  { value: "NEW", label: "Uus" }, { value: "NOT_INTERESTED", label: "Ei huvita" }, { value: "DECEASED", label: "Pärimismenetlus" },
  { value: "__NO_OWNER__", label: "CRM omanik puudub" },
];
const STATUS_LABELS: Record<string, string> = { NEW: "Uus", CONTACTED: "Kontakteeritud", INTERESTED: "Huvitatud", NOT_INTERESTED: "Ei huvita", EVALUATION: "Hindamisel", WAITS_FOR_EVALUATION: "Ootab hindamist", VALUED: "Hinnastatud", NEGOTIATION: "Läbirääkimised", ASSIGNED: "Määratud", CONTRACTED: "Leping", DECEASED: "Pärimismenetlus" };
const CADASTRAL_CODE_PATTERN = /^\d{5}:\d{3}:\d{4}$/;

function useDebouncedValue(value: string, delayMs: number): string { const [debounced, setDebounced] = useState(value); useEffect(() => { const timer = window.setTimeout(() => setDebounced(value), delayMs); return () => window.clearTimeout(timer); }, [value, delayMs]); return debounced; }

export default function MapPage() {
  const [search, setSearch] = useState("");
  const [searchPanelOpen, setSearchPanelOpen] = useState(false);
  const debouncedSearch = useDebouncedValue(search.trim(), 300);
  const [statusFilter, setStatusFilter] = useState("");
  const [selectedOwnerId, setSelectedOwnerId] = useState("");
  const [selectedParcelCode, setSelectedParcelCode] = useState("");
  const [parcelFocusOriginId, setParcelFocusOriginId] = useState("");
  const [searchFocusLocation, setSearchFocusLocation] = useState<{ longitude: number; latitude: number }>();
  const [detailGeometry, setDetailGeometry] = useState<MapDetailGeometrySelection | null>(null);
  const [contextLayerRequest, setContextLayerRequest] = useState<{ sourceKey: string; requestId: number } | null>(null);
  const [mapDataVersion, setMapDataVersion] = useState(0);
  const panelFocusRef = useRef<HTMLDivElement>(null);
  const normalizedSearch = search.trim();
  const isCadastralCode = CADASTRAL_CODE_PATTERN.test(normalizedSearch);
  const isDebouncedCadastralCode = CADASTRAL_CODE_PATTERN.test(debouncedSearch);
  const mapSearch = useMapSearch(debouncedSearch, debouncedSearch.length >= 2);
  const ownerSearch = useOwnersCursor(!isDebouncedCadastralCode && debouncedSearch.length >= 2 ? debouncedSearch : "", null, 8);
  const selectedOwner = useOwner(selectedOwnerId);
  const selectedParcelQuery = useCompleteMapParcelSummary(selectedParcelCode);
  const ownerResults = ownerSearch.data?.content ?? [];
  const mapResults = mapSearch.data?.results ?? [];

  useEffect(() => { const params = new URLSearchParams(window.location.search); const requestedOwnerId = params.get("ownerId")?.trim() ?? ""; const requestedCode = params.get("cadastralCode")?.trim() ?? ""; const requestedStatus = params.get("status")?.trim() ?? ""; if (WORKFLOW_FILTERS.some(filter => filter.value === requestedStatus)) setStatusFilter(requestedStatus); if (requestedOwnerId) { setSelectedParcelCode(""); setSelectedOwnerId(requestedOwnerId); } else if (CADASTRAL_CODE_PATTERN.test(requestedCode)) { setSelectedOwnerId(""); setSelectedParcelCode(requestedCode); } }, []);
  useEffect(() => { if (selectedParcelCode) window.requestAnimationFrame(() => panelFocusRef.current?.focus()); }, [selectedParcelCode]);

  const selectedParcelLocation = useMemo(() => { const parcel = selectedParcelQuery.data; if (!parcel) return undefined; return { west: parcel.bboxWest, south: parcel.bboxSouth, east: parcel.bboxEast, north: parcel.bboxNorth, longitude: parcel.centroidLongitude, latitude: parcel.centroidLatitude }; }, [selectedParcelQuery.data]);
  const chooseOwner = (ownerId: string) => { setSelectedOwnerId(ownerId); setSelectedParcelCode(""); setDetailGeometry(null); setSearchFocusLocation(undefined); setSearch(""); };
  const openParcel = (code: string, focusOriginId = "") => { setParcelFocusOriginId(focusOriginId); setSelectedParcelCode(code); setDetailGeometry(null); setSearchFocusLocation(undefined); };
  const closeParcel = () => { const focusId = parcelFocusOriginId; setSelectedParcelCode(""); setDetailGeometry(null); window.requestAnimationFrame(() => { if (focusId) document.getElementById(focusId)?.focus(); }); };
  const chooseMapResult = (result: MapSearchResult) => { if (result.type === "CADASTRE" && result.local && result.cadastralCode) { setSelectedOwnerId(""); openParcel(result.cadastralCode); setSearch(""); return; } if (typeof result.longitude === "number" && typeof result.latitude === "number") { setSearchFocusLocation({ longitude: result.longitude, latitude: result.latitude }); setSearch(""); } };
  const openExactCadastralCode = async () => { if (!isCadastralCode) return; const response = await mapSearch.refetch(); const local = response.data?.results.find(result => result.local && result.cadastralCode === normalizedSearch); if (local) chooseMapResult(local); };
  const saveWorkbasketHref = useMemo(() => { const params = new URLSearchParams(); if (selectedOwnerId) params.set("ownerId", selectedOwnerId); else if (selectedParcelCode) params.set("cadastralCode", selectedParcelCode); if (statusFilter) params.set("status", statusFilter); return `/workbaskets/new?${params.toString()}`; }, [selectedOwnerId, selectedParcelCode, statusFilter]);
  const showContextLayer = (sourceKey: string) => setContextLayerRequest(previous => ({ sourceKey, requestId: (previous?.requestId ?? 0) + 1 }));

  return <div className="relative h-[calc(100vh-5rem)] min-h-[560px] lg:h-[calc(100vh-1.5rem)]">
    <h1 className="sr-only">ForestIQ kaardipõhine katastritööruum</h1>
    <ParcelMap parcels={selectedOwner.data?.parcels} statusFilter={statusFilter || undefined} selectedCadastralCode={selectedParcelCode || undefined} selectedParcelLocation={selectedParcelLocation} searchFocusLocation={searchFocusLocation} detailGeometry={detailGeometry} contextLayerRequest={contextLayerRequest} dataVersion={mapDataVersion} onParcelClick={(code) => openParcel(code)} className="h-full w-full rounded-xl [&_.maplibregl-ctrl-top-left]:left-14 [&_.maplibregl-ctrl-top-left]:top-0 md:[&_.maplibregl-ctrl-top-left]:left-[3.75rem]" />

    <div className="absolute left-3 top-3 z-20 flex items-center gap-2 md:left-4 md:top-4">
      <Button type="button" size="icon" variant="outline" className="h-10 w-10 bg-white shadow hover:bg-slate-50" aria-label={searchPanelOpen ? "Sulge otsing ja filtrid" : "Ava otsing ja filtrid"} aria-expanded={searchPanelOpen} onClick={() => setSearchPanelOpen(open => !open)}>
        <Search className="h-5 w-5" />
      </Button>
    </div>

    {searchPanelOpen && <Card className={`absolute left-3 top-16 z-20 w-[min(27rem,calc(100%-1.5rem))] border-slate-200 bg-white/95 shadow-xl backdrop-blur md:left-4 md:top-[4.25rem] ${selectedParcelCode ? "lg:hidden xl:block" : ""}`}><CardContent className="space-y-3 p-3">
      <div className="flex items-center gap-2"><MapPinned className="h-5 w-5 shrink-0 text-primary" /><div className="min-w-0 flex-1"><p className="text-sm font-semibold">Kaart on põhitööruum</p><p className="truncate text-xs text-muted-foreground">Otsi aadressi, katastritunnust või CRM omanikku.</p></div></div>
      <div className="relative"><Search className="absolute left-3 top-2.5 h-4 w-4 text-muted-foreground" /><Input aria-label="Otsi aadressi, katastritunnust või CRM omanikku" className="h-9 bg-white pl-9" value={search} onChange={event => setSearch(event.target.value)} onKeyDown={event => { if (event.key === "Enter" && isCadastralCode) void openExactCadastralCode(); }} placeholder="Aadress, nimi või 12345:678:9012" /></div>
      {normalizedSearch.length > 0 && normalizedSearch.length < 2 && <p className="text-xs text-muted-foreground">Sisesta otsinguks vähemalt kaks märki.</p>}
      {debouncedSearch.length >= 2 && <div className="max-h-72 space-y-2 overflow-y-auto rounded-md border bg-white p-1.5"><div><p className="px-2 py-1 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">Kaardiotsing</p>{mapSearch.isLoading && <p className="px-2 py-2 text-sm text-muted-foreground" role="status">Otsin Maa- ja Ruumiametist...</p>}{mapSearch.isError && <div className="px-2 py-2 text-sm text-amber-800" role="alert">Aadressiotsing ei ole praegu saadaval. Täpne lokaalne katastriotsing töötab jätkuvalt.<Button variant="ghost" size="sm" className="ml-1 h-7" onClick={() => void mapSearch.refetch()}>Proovi uuesti</Button></div>}{!mapSearch.isLoading && !mapSearch.isError && mapResults.length === 0 && <p className="px-2 py-2 text-sm text-muted-foreground">Kaardiotsingust vasteid ei leitud.</p>}{mapResults.map((result, index) => <button key={`${result.provider}:${result.providerIdentity ?? result.displayLabel}:${index}`} type="button" className="flex w-full items-center justify-between gap-2 rounded px-2 py-2 text-left text-sm hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => chooseMapResult(result)}><span className="min-w-0 truncate">{result.displayLabel}</span><Badge variant="outline">{result.local ? "Katastriüksus" : result.type === "CADASTRE" ? "Katastri vaste" : "Aadress"}</Badge></button>)}</div>{!isDebouncedCadastralCode && <div className="border-t pt-1"><p className="px-2 py-1 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">CRM omanikud</p>{ownerSearch.isLoading && <p className="px-2 py-2 text-sm text-muted-foreground" role="status">Otsin CRM-ist...</p>}{ownerSearch.data && ownerResults.length === 0 && <p className="px-2 py-2 text-sm text-muted-foreground">Sobivaid omanikke ei leitud.</p>}{ownerResults.map(owner => <button key={owner.id} type="button" className="flex w-full items-center justify-between gap-3 rounded px-2 py-2 text-left text-sm hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" onClick={() => chooseOwner(owner.id ?? "")}><span className="min-w-0 truncate"><strong>{owner.displayName}</strong><span className="ml-2 text-muted-foreground">{owner.personalOrRegistryCode}</span></span><Badge variant="outline">{owner.status ? STATUS_LABELS[owner.status] ?? owner.status : "—"}</Badge></button>)}</div>}</div>}
      <div className="flex items-center gap-2"><Filter className="h-4 w-4 shrink-0 text-muted-foreground" /><select aria-label="Filtreeri kinnistuid töövoo staatuse järgi" className="h-9 min-w-0 flex-1 rounded-md border border-input bg-white px-3 text-sm shadow-sm outline-none focus:ring-2 focus:ring-ring" value={statusFilter} onChange={event => setStatusFilter(event.target.value)}>{WORKFLOW_FILTERS.map(filter => <option key={filter.value || "all"} value={filter.value}>{filter.label}</option>)}</select></div>
      {selectedOwner.data && <div className="space-y-2 rounded-md bg-slate-50 px-2.5 py-2 text-xs"><div className="flex items-center justify-between gap-2"><span className="min-w-0 truncate"><strong>{selectedOwner.data.displayName}</strong> · {selectedOwner.data.parcels.length} kinnistut</span><Button variant="ghost" size="sm" className="h-7 shrink-0 px-2" onClick={() => { setSelectedOwnerId(""); setSelectedParcelCode(""); setDetailGeometry(null); }}><X className="mr-1 h-3.5 w-3.5" />Tühjenda</Button></div><Button asChild variant="outline" size="sm" className="h-7"><Link href={`/owners/${selectedOwnerId}/brief`}><Trees className="mr-1 h-3.5 w-3.5" />Portfellibrief</Link></Button>
        <div aria-label="Omaniku kinnistud klaviatuurivalikuks" className="max-h-36 space-y-1 overflow-y-auto border-t pt-2">{selectedOwner.data.parcels.map(parcel => { const id = `map-parcel-choice-${parcel.cadastralCode.replace(/:/g, "-")}`; const selected = selectedParcelCode === parcel.cadastralCode; return <button id={id} key={parcel.id} type="button" aria-pressed={selected} className={`w-full rounded border bg-white px-2 py-1.5 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${selected ? "border-primary" : ""}`} onClick={() => openParcel(parcel.cadastralCode, id)}><span className="block font-medium">{parcel.cadastralCode}</span><span className="text-muted-foreground">{parcel.areaHectares.toFixed(2)} ha · {STATUS_LABELS[statusFilter] ?? (statusFilter || "kõik töövood")}</span></button>; })}</div>
      </div>}
      {(selectedOwnerId || selectedParcelCode) && <Button asChild variant="outline" size="sm" className="w-full"><Link href={saveWorkbasketHref}><Save className="mr-2 h-4 w-4" />Salvesta töökorv</Link></Button>}
    </CardContent></Card>}

    {selectedParcelCode && <div ref={panelFocusRef} tabIndex={-1} role="region" aria-label={`Kinnistu ${selectedParcelCode} detailpaneel`} onKeyDown={event => { if (event.key === "Escape") { event.preventDefault(); closeParcel(); } }}><span className="sr-only" role="status" aria-live="polite">Valitud kinnistu {selectedParcelCode}. Detailpaneel avatud.</span><MapParcelPanel cadastralCode={selectedParcelCode} onClose={closeParcel} onMapDataChanged={() => setMapDataVersion(version => version + 1)} onDetailGeometryChange={setDetailGeometry} onShowContextLayer={showContextLayer} /></div>}
    {selectedOwner.isLoading && <div className="absolute bottom-4 left-4 z-10 rounded-md border bg-white/95 px-3 py-2 text-sm shadow" role="status">Fokuseerin omaniku kinnistuid...</div>}
    {!selectedParcelCode && !selectedOwner.isLoading && <div className="pointer-events-none absolute bottom-3 left-3 z-10 max-w-[calc(100%-1.5rem)] rounded-md border bg-white/90 px-3 py-2 text-xs text-muted-foreground shadow-sm backdrop-blur md:bottom-4 md:left-4">Madalal suumil on katastrid koondatud ülevaateks. Täpsed kinnistupiirid kuvatakse alates suumist 11; klõpsa ülevaatepunkti või kasuta omaniku kinnistute klaviatuuriloendit.</div>}
  </div>;
}
