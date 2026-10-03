"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { ArrowLeft, Database, Edit2, Eye, EyeOff, MapPin, Phone, Plus, Save, ShieldAlert, UserRound, X } from "lucide-react";

import { ParcelMap } from "@/components/map/parcel-map";
import { DealCasePanel } from "@/components/owners/deal-case-panel";
import { InheritanceCasePanel } from "@/components/owners/inheritance-case-panel";
import { OwnershipTransitionPanel } from "@/components/owners/ownership-transition-panel";
import { OwnerStatusTransitionSelect, ownerStatusLabel } from "@/components/owners/owner-status-transition-select";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import {
  useAddOwnerNote,
  useAllowedOwnerStatusTransitions,
  useChangeStatus,
  useCreateOwnershipRelation,
  useEndOwnershipRelation,
  useFollowOwner,
  useOwner,
  useOwner360History,
  useOwner360Map,
  useOwner360Overview,
  useOwner360Relations,
  useOwner360Workflow,
  usePortfolioBrief,
  useUnfollowOwner,
  useUpdateOwner,
  type Owner360ParcelRelation,
} from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { LandParcel } from "@/lib/types";
import { toTelHref } from "@/lib/utils";

type Section = "overview" | "parcels" | "map" | "forest" | "history" | "workflow" | "sources";
type EditForm = { displayName: string; phone: string; email: string; address: string; info: string; ownerType: string };

const SECTIONS: { id: Section; label: string }[] = [
  { id: "overview", label: "Ülevaade" },
  { id: "parcels", label: "Katastrid" },
  { id: "map", label: "Kaart" },
  { id: "forest", label: "Mets" },
  { id: "history", label: "Ajalugu" },
  { id: "workflow", label: "ForestIQ" },
  { id: "sources", label: "Andmeallikad" },
];

export function Owner360Workspace({ ownerId }: { ownerId: string }) {
  const { username, hasRole } = useAuth();
  const [section, setSection] = useState<Section>("overview");
  const [includeHistorical, setIncludeHistorical] = useState(false);
  const [selectedParcel, setSelectedParcel] = useState<string>();
  const [managementOpen, setManagementOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [showStatusChange, setShowStatusChange] = useState(false);
  const [editData, setEditData] = useState<EditForm>({ displayName: "", phone: "", email: "", address: "", info: "", ownerType: "" });
  const [newStatus, setNewStatus] = useState("");
  const [statusComment, setStatusComment] = useState("");
  const [noteText, setNoteText] = useState("");
  const [showAddParcel, setShowAddParcel] = useState(false);
  const [newCadastralCode, setNewCadastralCode] = useState("");
  const [relationReason, setRelationReason] = useState("");

  const overview = useOwner360Overview(ownerId);
  const relations = useOwner360Relations(ownerId, includeHistorical, 0, 200, section === "overview" || section === "parcels");
  const ownerMap = useOwner360Map(ownerId, section === "map");
  const history = useOwner360History(ownerId, 0, 100, section === "history");
  const workflow = useOwner360Workflow(ownerId, section === "workflow");
  const forestry = usePortfolioBrief(ownerId, section === "forest");
  const canManageOwner = hasRole("ORG_ADMIN") || hasRole("CRM_MANAGER") || hasRole("EVALUATOR") || hasRole("CALLER");
  const legacyOwner = useOwner(ownerId, canManageOwner && (managementOpen || editing || showStatusChange));
  const allowedStatuses = useAllowedOwnerStatusTransitions(ownerId, showStatusChange);

  const updateOwner = useUpdateOwner(ownerId);
  const changeStatus = useChangeStatus(ownerId);
  const followOwner = useFollowOwner(ownerId);
  const unfollowOwner = useUnfollowOwner(ownerId);
  const addNote = useAddOwnerNote(ownerId);
  const createRelation = useCreateOwnershipRelation();
  const endRelation = useEndOwnershipRelation();

  const canManageOwnership = hasRole("ORG_ADMIN") || hasRole("CRM_MANAGER");
  const isFollowing = legacyOwner.data?.followings?.includes(username ?? "") ?? false;

  const mapParcels = useMemo<LandParcel[]>(() => (ownerMap.data ?? []).map(parcel => ({
    id: parcel.parcelId,
    cadastralCode: parcel.cadastralCode,
    areaHectares: parcel.areaHectares ?? 0,
    name: parcel.name ?? undefined,
    marked: false,
    centroid: parcel.centroid ?? undefined,
  })), [ownerMap.data]);
  const selectedMapParcel = ownerMap.data?.find(parcel => parcel.cadastralCode === selectedParcel);
  const selectedLocation = selectedMapParcel?.centroid
    ? { longitude: selectedMapParcel.centroid.longitude, latitude: selectedMapParcel.centroid.latitude }
    : undefined;

  if (overview.isPending) return <p className="text-sm text-muted-foreground" role="status">Laadin omaniku ülevaadet...</p>;
  if (overview.isError || !overview.data) return <Card><CardContent className="space-y-3 pt-6"><p className="font-medium text-destructive">Omaniku ülevaate laadimine ebaõnnestus.</p><p className="text-sm text-muted-foreground">{overview.error?.message ?? "Omanikku ei leitud."}</p><Button variant="outline" onClick={() => void overview.refetch()}>Proovi uuesti</Button></CardContent></Card>;

  const { owner, portfolio, signals, sources, sections } = overview.data;
  const failedSections = Object.entries(sections ?? {}).filter(([, state]) => state.state === "ERROR").map(([name]) => name);
  const telHref = toTelHref(owner.phone);

  const beginEditing = () => {
    const value = legacyOwner.data;
    if (!value) return;
    setEditData({ displayName: value.displayName, phone: value.phone ?? "", email: value.email ?? "", address: value.address ?? "", info: value.info ?? "", ownerType: value.ownerType ?? "" });
    setEditing(true);
  };
  const saveEdits = () => {
    const value = legacyOwner.data;
    if (!value) return;
    updateOwner.mutate({ ...editData, version: value.version }, { onSuccess: () => setEditing(false) });
  };
  const saveStatus = () => {
    const value = legacyOwner.data;
    if (!value || !newStatus) return;
    changeStatus.mutate({ status: newStatus, comment: statusComment || undefined, version: value.version }, { onSuccess: () => { setNewStatus(""); setStatusComment(""); setShowStatusChange(false); } });
  };
  const saveNote = () => {
    if (!noteText.trim()) return;
    addNote.mutate({ content: noteText.trim() }, { onSuccess: () => setNoteText("") });
  };
  const addParcel = () => {
    if (!newCadastralCode.trim() || !relationReason.trim()) return;
    createRelation.mutate({ ownerId, cadastralCode: newCadastralCode.trim(), reason: relationReason.trim() }, { onSuccess: () => { setNewCadastralCode(""); setRelationReason(""); setShowAddParcel(false); void relations.refetch(); void overview.refetch(); } });
  };
  const endParcel = (relation: Owner360ParcelRelation) => {
    const reason = window.prompt(`Sisesta põhjus, miks lõpetada seos katastriüksusega ${relation.cadastralCode}:`);
    if (!reason?.trim()) return;
    endRelation.mutate({ ownerId, parcelId: relation.parcelId, reason: reason.trim() }, { onSuccess: () => { void relations.refetch(); void overview.refetch(); } });
  };

  return <div className="space-y-6">
    <Button asChild variant="ghost" className="-ml-3"><Link href="/owners"><ArrowLeft className="mr-2 h-4 w-4" />Tagasi omanike juurde</Link></Button>

    <header className="flex flex-col justify-between gap-4 xl:flex-row xl:items-start">
      <div className="min-w-0 space-y-2"><div className="flex flex-wrap items-center gap-2"><h1 className="truncate text-3xl font-bold tracking-tight">{owner.displayName}</h1><Badge variant="secondary">{ownerStatusLabel(owner.status)}</Badge>{owner.ownerType && <Badge variant="outline">{owner.ownerType}</Badge>}</div><div className="flex flex-wrap gap-x-4 gap-y-1 text-sm text-muted-foreground"><span>{owner.personalOrRegistryCode ?? (owner.sensitiveDetailsVisible ? "Identifikaator puudub" : "Identifikaator piiratud")}</span><span>Vastutaja: {owner.assignedTo ?? "määramata"}</span>{owner.updatedAt && <span>Uuendatud {formatDateTime(owner.updatedAt)}</span>}</div></div>
      <div className="flex flex-wrap gap-2"><Button asChild variant="outline" size="sm"><Link href={`/owners/${ownerId}/contact`}>Salvesta kontakt</Link></Button>{canManageOwner && <Button variant="outline" size="sm" onClick={() => setManagementOpen(value => !value)}><Edit2 className="mr-2 h-4 w-4" />{managementOpen ? "Peida haldus" : "Halda"}</Button>}</div>
    </header>

    {failedSections.length > 0 && <div className="rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-950" role="status"><p className="font-medium">Osa ülevaatest ei ole praegu saadaval.</p><p>Teised sektsioonid jäävad kasutatavaks. Mõjutatud: {failedSections.join(", ")}.</p></div>}
    <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-6"><Metric label="Aktiivsed katastrid" value={formatCount(portfolio.activeParcelCount)} /><Metric label="Kogupindala" value={formatHa(portfolio.totalAreaHectares)} /><Metric label="Metsamaa" value={formatHa(portfolio.forestAreaHectares)} /><Metric label="Teadaolev maht" value={portfolio.knownVolumeCubicMeters == null ? "—" : `${formatNumber(portfolio.knownVolumeCubicMeters)} m³`} hint={portfolio.knownVolumeCubicMeters == null ? "Puuduv väärtus ei ole 0" : `${formatCount(portfolio.volumeKnownParcelCount)} katastril`} /><Metric label="Aktiivsed teatised" value={formatCount(portfolio.activeNotificationCount)} /><Metric label="Ajaloolised seosed" value={formatCount(portfolio.historicalParcelCount)} /></div>

    {managementOpen && <Card><CardHeader><CardTitle className="text-base">Omaniku haldus</CardTitle></CardHeader><CardContent className="space-y-4">
      {legacyOwner.isPending && <p className="text-sm text-muted-foreground" role="status">Laadin muutmiseks vajalikke andmeid...</p>}
      {legacyOwner.isError && <SectionError message={legacyOwner.error.message} retry={() => void legacyOwner.refetch()} />}
      {legacyOwner.data && !editing && !showStatusChange && <div className="flex flex-wrap gap-2"><Button size="sm" variant="outline" onClick={beginEditing}><Edit2 className="mr-1 h-4 w-4" />Muuda andmeid</Button><Button size="sm" variant="outline" onClick={() => setShowStatusChange(true)}>Muuda staatust</Button><Button size="sm" variant="outline" onClick={() => isFollowing ? unfollowOwner.mutate() : followOwner.mutate()}>{isFollowing ? <><EyeOff className="mr-1 h-4 w-4" />Lõpeta jälgimine</> : <><Eye className="mr-1 h-4 w-4" />Jälgi</>}</Button></div>}
      {editing && legacyOwner.data && <div className="space-y-4"><div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3"><LabeledInput label="Nimi" value={editData.displayName} onChange={displayName => setEditData({ ...editData, displayName })} /><LabeledInput label="Telefon" value={editData.phone} onChange={phone => setEditData({ ...editData, phone })} /><LabeledInput label="E-post" value={editData.email} onChange={email => setEditData({ ...editData, email })} /><LabeledInput label="Aadress" value={editData.address} onChange={address => setEditData({ ...editData, address })} /><LabeledInput label="Tüüp" value={editData.ownerType} onChange={ownerType => setEditData({ ...editData, ownerType })} /><LabeledInput label="Info" value={editData.info} onChange={info => setEditData({ ...editData, info })} /></div><div className="flex gap-2"><Button size="sm" onClick={saveEdits} disabled={updateOwner.isPending}><Save className="mr-1 h-4 w-4" />Salvesta</Button><Button size="sm" variant="ghost" onClick={() => setEditing(false)}>Tühista</Button></div>{updateOwner.isError && <p className="text-sm text-destructive">{updateOwner.error.message}</p>}</div>}
      {showStatusChange && legacyOwner.data && <div className="flex flex-wrap items-end gap-3"><label className="grid gap-1 text-sm"><span className="font-medium">Uus staatus</span><OwnerStatusTransitionSelect definitions={allowedStatuses.data} value={newStatus} onChange={setNewStatus} loading={allowedStatuses.isPending} disabled={allowedStatuses.isError} /></label><LabeledInput label="Kommentaar" value={statusComment} onChange={setStatusComment} /><Button size="sm" onClick={saveStatus} disabled={!newStatus || changeStatus.isPending}>Muuda</Button><Button size="sm" variant="ghost" aria-label="Tühista staatuse muutmine" onClick={() => setShowStatusChange(false)}><X className="h-4 w-4" /></Button>{changeStatus.isError && <p className="basis-full text-sm text-destructive">{changeStatus.error.message}</p>}</div>}
    </CardContent></Card>}

    <nav className="flex gap-1 overflow-x-auto border-b pb-2" aria-label="Omaniku detailvaate sektsioonid" role="tablist">{SECTIONS.map(item => <Button key={item.id} size="sm" variant={section === item.id ? "default" : "ghost"} role="tab" aria-selected={section === item.id} tabIndex={section === item.id ? 0 : -1} onClick={() => setSection(item.id)}>{item.label}</Button>)}</nav>

    {section === "overview" && <div className="grid gap-6 xl:grid-cols-[minmax(0,1.5fr)_minmax(320px,0.7fr)]"><div className="space-y-6"><Card><CardHeader><CardTitle>Otsustugi</CardTitle></CardHeader><CardContent className="space-y-3">{signals.length === 0 && <p className="text-sm text-muted-foreground">Praegu ei ole tähelepanusignaale.</p>}{signals.slice(0, 8).map(signal => <div key={`${signal.code}:${signal.cadastralCode ?? signal.dealId ?? signal.title}`} className="rounded-lg border p-3"><div className="flex flex-wrap items-center gap-2"><Badge variant={signal.severity === "WARNING" ? "destructive" : "secondary"}>{signal.severity}</Badge><p className="font-medium">{signal.title}</p></div><p className="mt-1 text-sm text-muted-foreground">{signal.reason}</p><p className="mt-1 text-xs text-muted-foreground">{signal.source}{signal.observedAt ? ` · ${formatDateTime(signal.observedAt)}` : " · aeg teadmata"}</p>{signal.recommendedAction && <p className="mt-2 text-sm"><strong>Soovitus:</strong> {signal.recommendedAction}</p>}{signal.cadastralCode && <Button asChild variant="ghost" size="sm" className="h-auto px-0"><Link href={`/map?cadastralCode=${encodeURIComponent(signal.cadastralCode)}`}>Ava põhjustanud kataster</Link></Button>}</div>)}</CardContent></Card><Card><CardHeader className="flex-row items-center justify-between"><CardTitle>Aktiivne portfell</CardTitle><Button variant="ghost" size="sm" onClick={() => setSection("parcels")}>Kõik katastrid</Button></CardHeader><CardContent className="space-y-2">{relations.isPending && <p className="text-sm text-muted-foreground">Laadin katastreid...</p>}{relations.isError && <SectionError message={relations.error.message} retry={() => void relations.refetch()} />}{relations.data?.content.filter(item => item.active).slice(0, 6).map(item => <ParcelRow key={item.parcelId} relation={item} />)}</CardContent></Card></div><div className="space-y-6"><Card><CardHeader><CardTitle className="flex items-center gap-2"><UserRound className="h-5 w-5" />Kontakt ja kontekst</CardTitle></CardHeader><CardContent className="space-y-3 text-sm">{telHref ? <Button asChild variant="outline" size="sm"><a href={telHref}><Phone className="mr-2 h-4 w-4" />{owner.phone}</a></Button> : <p>Telefon: {owner.phone ?? "—"}</p>}<p>E-post: {owner.email ?? "—"}</p><p>Aadress: {owner.address ?? "—"}</p>{owner.info && <p className="rounded-md bg-muted p-3 text-muted-foreground">{owner.info}</p>}{!owner.phone && !owner.email && <p className="text-xs text-muted-foreground">{owner.sensitiveDetailsVisible ? "Kontaktandmed puuduvad." : "Kontaktandmed on sinu rollile piiratud."}</p>}</CardContent></Card><Card><CardHeader><CardTitle className="flex items-center gap-2"><Database className="h-5 w-5" />Andmete seis</CardTitle></CardHeader><CardContent className="space-y-2">{sources.map(source => <SourceRow key={source.source} source={source} />)}<Button variant="ghost" size="sm" className="px-0" onClick={() => setSection("sources")}>Vaata allikaid</Button></CardContent></Card></div></div>}

    {section === "parcels" && <Card><CardHeader className="gap-3 md:flex-row md:items-center md:justify-between"><div><CardTitle>Katastriseosed</CardTitle><p className="mt-1 text-sm text-muted-foreground">Aktiivsed ja ajaloolised seosed ning kaasomandi kontekst.</p></div><div className="flex flex-wrap gap-2"><Button size="sm" variant={includeHistorical ? "default" : "outline"} onClick={() => setIncludeHistorical(value => !value)}>{includeHistorical ? "Peida ajaloolised" : "Näita ajaloolisi"}</Button>{canManageOwnership && <Button size="sm" variant="outline" onClick={() => setShowAddParcel(value => !value)}><Plus className="mr-1 h-4 w-4" />Lisa seos</Button>}</div></CardHeader><CardContent className="space-y-3">{showAddParcel && canManageOwnership && <div className="grid gap-2 rounded-md border p-3 md:grid-cols-[1fr_1.5fr_auto]"><Input value={newCadastralCode} onChange={event => setNewCadastralCode(event.target.value)} placeholder="12345:678:9012" /><Input value={relationReason} onChange={event => setRelationReason(event.target.value)} placeholder="Lisamise põhjus" /><Button onClick={addParcel} disabled={!newCadastralCode.trim() || !relationReason.trim() || createRelation.isPending}>Lisa</Button>{createRelation.isError && <p className="text-sm text-destructive md:col-span-3">{createRelation.error.message}</p>}</div>}{relations.isPending && <p className="text-sm text-muted-foreground">Laadin seoseid...</p>}{relations.isError && <SectionError message={relations.error.message} retry={() => void relations.refetch()} />}{relations.data?.content.map(item => <div key={`${item.parcelId}:${item.active}`} className="flex flex-col justify-between gap-3 rounded-lg border p-3 md:flex-row md:items-start"><div><div className="flex flex-wrap items-center gap-2"><span className="font-medium">{item.cadastralCode}{item.name ? ` — ${item.name}` : ""}</span><Badge variant={item.active ? "secondary" : "outline"}>{item.active ? "Aktiivne" : "Ajalooline"}</Badge>{item.activeOwnerCount > 1 && <Badge variant="outline">Kaasomand · {item.activeOwnerCount}</Badge>}</div><p className="mt-1 text-sm text-muted-foreground"><MapPin className="mr-1 inline h-3 w-3" />{formatHa(item.areaHectares)}{item.forestAreaHectares != null ? ` · mets ${formatHa(item.forestAreaHectares)}` : ""}{item.county ? ` · ${item.county}` : ""}</p><p className="mt-1 text-xs text-muted-foreground">Allikas {item.source} · {item.sourceSeenAt ? formatDateTime(item.sourceSeenAt) : "aeg teadmata"}{item.deactivatedAt ? ` · lõppenud ${formatDateTime(item.deactivatedAt)}` : ""}</p></div><div className="flex gap-2"><Button asChild size="sm" variant="outline"><Link href={`/map?cadastralCode=${encodeURIComponent(item.cadastralCode)}`}>Ava detail</Link></Button>{canManageOwnership && item.active && <Button size="sm" variant="ghost" onClick={() => endParcel(item)} disabled={endRelation.isPending}>Lõpeta seos</Button>}</div></div>)}{relations.data?.hasMore && <p className="rounded-md bg-muted p-3 text-sm text-muted-foreground">Kuvatud on esimesed {relations.data.limit} seost. Detaililoend on teadlikult pagineeritud.</p>}</CardContent></Card>}

    {section === "map" && <Card><CardHeader><CardTitle>Omaniku portfell kaardil</CardTitle><p className="text-sm text-muted-foreground">Kaart laadib kogu aktiivse portfelli eraldi kergest projektsioonist.</p></CardHeader><CardContent className="space-y-4">{ownerMap.isPending && <p className="text-sm text-muted-foreground">Laadin kaardiobjekte...</p>}{ownerMap.isError && <SectionError message={ownerMap.error.message} retry={() => void ownerMap.refetch()} />}{ownerMap.data && <ParcelMap parcels={mapParcels} cadastralCodes={ownerMap.data.map(item => item.cadastralCode)} selectedCadastralCode={selectedParcel} selectedParcelLocation={selectedLocation} onParcelClick={setSelectedParcel} className="h-[min(68vh,680px)] min-h-[420px]" />}{ownerMap.data && <p className="text-sm text-muted-foreground">{ownerMap.data.length} aktiivset katastrit · {ownerMap.data.filter(item => !item.centroid).length} ilma kaardifookuseta</p>}{selectedMapParcel && <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border p-3"><p className="font-medium">{selectedMapParcel.cadastralCode}{selectedMapParcel.name ? ` — ${selectedMapParcel.name}` : ""}</p><Button asChild size="sm" variant="outline"><Link href={`/map?cadastralCode=${encodeURIComponent(selectedMapParcel.cadastralCode)}`}>Täielik detail</Link></Button></div>}</CardContent></Card>}

    {section === "forest" && <div className="space-y-6">{forestry.isPending && <p className="text-sm text-muted-foreground">Laadin metsanduslikku portfelli...</p>}{forestry.isError && <SectionError message={forestry.error.message} retry={() => void forestry.refetch()} />}{forestry.data && <><div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4"><Metric label="Metsamaa" value={formatHa(forestry.data.forestAreaHectares)} /><Metric label="Teadaolev maht" value={forestry.data.knownVolumeCubicMeters == null ? "—" : `${formatNumber(forestry.data.knownVolumeCubicMeters)} m³`} hint={`${forestry.data.volumeKnownParcelCount}/${forestry.data.parcelCount} katastril`} /><Metric label="Aktiivsed teatised" value={String(forestry.data.activeNotificationCount)} /><Metric label="Katastreid" value={String(forestry.data.parcelCount)} /></div><Card><CardHeader><CardTitle>Metsanduslik sisu</CardTitle></CardHeader><CardContent className="space-y-2">{forestry.data.parcels.map(parcel => <div key={parcel.id} className="flex flex-col justify-between gap-2 rounded-md border p-3 md:flex-row md:items-center"><div><p className="font-medium">{parcel.cadastralCode}{parcel.name ? ` — ${parcel.name}` : ""}</p><p className="text-sm text-muted-foreground">mets {formatHa(parcel.forestAreaHectares)} · maht {parcel.knownVolumeCubicMeters == null ? "—" : `${formatNumber(parcel.knownVolumeCubicMeters)} m³`} · teatisi {parcel.activeNotificationCount}</p></div><Button asChild size="sm" variant="outline"><Link href={`/map?cadastralCode=${encodeURIComponent(parcel.cadastralCode)}`}>Ava detail</Link></Button></div>)}</CardContent></Card></>}</div>}

    {section === "history" && <Card><CardHeader><CardTitle>Kronoloogiline ajalugu</CardTitle><p className="text-sm text-muted-foreground">Registri-/omandisündmused ja ForestIQ töövoog on semantiliselt eristatud.</p></CardHeader><CardContent className="space-y-4">{canManageOwner && <div className="flex gap-2"><Input placeholder="Lisa märkus..." value={noteText} onChange={event => setNoteText(event.target.value)} onKeyDown={event => { if (event.key === "Enter") saveNote(); }} /><Button aria-label="Lisa märkus" onClick={saveNote} disabled={!noteText.trim() || addNote.isPending}><Plus className="h-4 w-4" /></Button></div>}{history.isPending && <p className="text-sm text-muted-foreground">Laadin ajalugu...</p>}{history.isError && <SectionError message={history.error.message} retry={() => void history.refetch()} />}{history.data?.content.map(event => <div key={event.id} className="border-l-2 border-primary/30 pl-4"><div className="flex flex-wrap items-center gap-2"><Badge variant="outline">{event.category}</Badge><p className="font-medium">{event.title}</p></div>{event.description && <p className="mt-1 text-sm text-muted-foreground">{event.description}</p>}<p className="mt-1 text-xs text-muted-foreground">{event.source ?? "ForestIQ"}{event.createdAt ? ` · ${formatDateTime(event.createdAt)}` : " · aeg teadmata"}{event.createdBy ? ` · ${event.createdBy}` : ""}</p>{event.cadastralCode && <Button asChild variant="ghost" size="sm" className="h-auto px-0"><Link href={`/map?cadastralCode=${encodeURIComponent(event.cadastralCode)}`}>{event.cadastralCode}</Link></Button>}</div>)}{history.data?.content.length === 0 && <p className="text-sm text-muted-foreground">Ajalugu puudub.</p>}{history.data?.hasMore && <p className="text-sm text-muted-foreground">Vanemad sündmused asuvad järgmisel leheküljel.</p>}</CardContent></Card>}

    {section === "workflow" && <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_minmax(320px,0.8fr)]"><div className="space-y-6"><Card><CardHeader><CardTitle>ForestIQ töövoog</CardTitle></CardHeader><CardContent className="space-y-3">{workflow.isPending && <p className="text-sm text-muted-foreground">Laadin töövoogu...</p>}{workflow.isError && <SectionError message={workflow.error.message} retry={() => void workflow.refetch()} />}{workflow.data && <>{workflow.data.businessDetailsVisible ? <div className="grid gap-3 sm:grid-cols-2"><Metric label="Staatus" value={ownerStatusLabel(workflow.data.ownerStatus)} /><Metric label="Vastutaja" value={workflow.data.assignedTo ?? "Määramata"} /><Metric label="Aktiivseid tehinguid" value={workflow.data.activeDealCount == null ? "—" : String(workflow.data.activeDealCount)} /><Metric label="Meeldetuletusi" value={workflow.data.openReminderCount == null ? "—" : String(workflow.data.openReminderCount)} />{workflow.data.nextAction && <div className="rounded-md border p-3 sm:col-span-2"><p className="text-xs uppercase tracking-wide text-muted-foreground">Järgmine tegevus</p><p className="font-medium">{workflow.data.nextAction}</p>{workflow.data.nextActionDueAt && <p className="text-sm text-muted-foreground">Tähtaeg {formatDateTime(workflow.data.nextActionDueAt)}</p>}</div>}</div> : <p className="text-sm text-muted-foreground">Äriline töövoo detail on sinu rollile piiratud. Piiratud väärtusi ei esitata nullidena.</p>}</>}</CardContent></Card><DealCasePanel ownerId={ownerId} ownerStatus={owner.status} /><OwnershipTransitionPanel ownerId={ownerId} /></div><div className="space-y-6"><InheritanceCasePanel ownerId={ownerId} ownerStatus={owner.status} /><Card><CardHeader><CardTitle>Kontakt</CardTitle></CardHeader><CardContent><Button asChild className="w-full"><Link href={`/owners/${ownerId}/contact`}>Salvesta struktureeritud kontakt</Link></Button></CardContent></Card></div></div>}

    {section === "sources" && <Card><CardHeader><CardTitle>Andmeallikad ja värskus</CardTitle><p className="text-sm text-muted-foreground">Puuduv väärtus, null ja aegunud/teadmata seisund on eri tähendusega.</p></CardHeader><CardContent className="space-y-3">{sources.map(source => <div key={source.source} className="rounded-lg border p-3"><SourceRow source={source} /><p className="mt-2 text-sm text-muted-foreground">{source.note ?? "Lisamärkus puudub."}</p></div>)}</CardContent></Card>}
  </div>;
}

function Metric({ label, value, hint }: { label: string; value: string; hint?: string }) { return <Card><CardContent className="p-4"><p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</p><p className="mt-1 text-2xl font-semibold tracking-tight">{value}</p>{hint && <p className="mt-1 text-xs text-muted-foreground">{hint}</p>}</CardContent></Card>; }
function ParcelRow({ relation }: { relation: Owner360ParcelRelation }) { return <div className="rounded-md border p-3"><div className="flex flex-wrap items-center gap-2"><span className="font-medium">{relation.cadastralCode}{relation.name ? ` — ${relation.name}` : ""}</span>{relation.activeOwnerCount > 1 && <Badge variant="outline">{relation.activeOwnerCount} omanikku</Badge>}</div><p className="mt-1 text-sm text-muted-foreground">{formatHa(relation.areaHectares)}{relation.forestAreaHectares != null ? ` · mets ${formatHa(relation.forestAreaHectares)}` : ""}{relation.county ? ` · ${relation.county}` : ""}</p></div>; }
function SourceRow({ source }: { source: { source: string; freshness: string; observedAt?: string | null; available: boolean } }) { return <div className="flex flex-wrap items-center justify-between gap-2"><div><p className="font-medium">{source.source}</p><p className="text-xs text-muted-foreground">{source.observedAt ? formatDateTime(source.observedAt) : "Vaatlusaeg teadmata"}</p></div><Badge variant={source.freshness === "FRESH" ? "secondary" : source.freshness === "STALE" || source.freshness === "FAILED_REFRESH" ? "destructive" : "outline"}>{source.available ? source.freshness : "UNKNOWN"}</Badge></div>; }
function SectionError({ message, retry }: { message: string; retry: () => void }) { return <div className="flex flex-col gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 sm:flex-row sm:items-center sm:justify-between" role="alert"><div className="flex items-start gap-2"><ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-destructive" /><div><p className="text-sm font-medium">Selle sektsiooni laadimine ebaõnnestus.</p><p className="text-xs text-muted-foreground">{message}</p></div></div><Button size="sm" variant="outline" onClick={retry}>Proovi uuesti</Button></div>; }
function LabeledInput({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) { return <label className="grid gap-1 text-sm"><span className="font-medium">{label}</span><Input value={value} onChange={event => onChange(event.target.value)} /></label>; }
function formatHa(value?: number | null) { return value == null ? "—" : `${new Intl.NumberFormat("et-EE", { maximumFractionDigits: 2 }).format(value)} ha`; }
function formatNumber(value: number) { return new Intl.NumberFormat("et-EE", { maximumFractionDigits: 1 }).format(value); }
function formatCount(value?: number | null) { return value == null ? "—" : new Intl.NumberFormat("et-EE").format(value); }
function formatDateTime(value: string) { const date = new Date(value); return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat("et-EE", { dateStyle: "medium", timeStyle: "short" }).format(date); }