"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Copy, ExternalLink, MapPinned, Phone, Sparkles, X } from "lucide-react";
import {
  useCadastreEvaluation,
  useCadastreLabels,
  useParcelGeospatialContext,
  useRegistryFreshness,
} from "@/lib/api";
import {
  useCadastreAllNotificationPages,
  useCadastreForestFeaturePages,
  useCadastreForestFeatureRaw,
  useCadastreSubPartGeometry,
  useCadastreSubPartPages,
  useCompleteMapParcelSummary,
  type CompleteMapParcelSummary,
} from "@/lib/api/map-cadastre";
import { useCadastreSubPartGeometryByCode } from "@/lib/api/parcel-detail";
import { MapOwnerWorkPanel } from "@/components/map/map-owner-work-panel";
import { DealQualificationDialog } from "@/components/deals/deal-qualification-dialog";
import { ParcelRegistryActions } from "@/components/map/parcel-registry-actions";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataField, DataSection, QueryMessage, SourceMeta } from "@/components/map/map-parcel-panel-shared";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { parseDetailGeometry, type MapDetailGeometrySelection } from "@/lib/map/detail-geometry";
import {
  CADASTRE_LABELS,
  displayValue,
  formatArea,
  formatNumber,
  formatPercent,
  formatRegistryDate,
  freshnessLabel,
  statusLabel,
} from "@/lib/map/parcel-panel-format";
import { toTelHref } from "@/lib/utils";
import { formatCount, landUseSegments, percentOf, positiveArea } from "./map-parcel-panel-utils";

type ParcelOverviewProps = {
  parcel: CompleteMapParcelSummary;
  ownerCount: number;
  labels?: string[];
};

function LandUseBar({ parcel }: { parcel: CompleteMapParcelSummary }) {
  const segments = landUseSegments(parcel);
  const total = positiveArea(parcel.areaHectares) || segments.reduce((sum, segment) => sum + segment.value, 0);
  if (segments.length === 0 || total <= 0) {
    return <p className="rounded-lg border bg-slate-50 px-3 py-2 text-xs text-muted-foreground">Maakasutuse jaotus puudub.</p>;
  }
  return (
    <div className="rounded-lg border bg-slate-50 p-3">
      <div className="mb-2 flex items-center justify-between gap-2">
        <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Maakasutus</p>
        <p className="text-xs text-muted-foreground">{formatArea(total)}</p>
      </div>
      <div className="flex h-2 overflow-hidden rounded-full bg-slate-200" aria-hidden="true">
        {segments.map(segment => (
          <span
            key={segment.label}
            className={segment.tone}
            style={{ width: `${percentOf(segment.value, total) ?? 0}%` }}
            title={`${segment.label}: ${formatArea(segment.value)}`}
          />
        ))}
      </div>
      <div className="mt-2 grid grid-cols-2 gap-x-3 gap-y-1 text-xs text-slate-600">
        {segments.slice(0, 6).map(segment => (
          <div key={segment.label} className="flex items-center justify-between gap-2">
            <span className="min-w-0 truncate">{segment.label}</span>
            <span className="shrink-0 font-medium text-slate-900">{formatArea(segment.value)}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

export function ParcelOverview({ parcel, ownerCount, labels = [] }: ParcelOverviewProps) {
  const totalOwners = parcel.ownerCount ?? ownerCount;
  const forestShare = percentOf(positiveArea(parcel.forestArea), positiveArea(parcel.areaHectares));
  const primaryOwner = parcel.ownerName ?? parcel.owners?.find(owner => owner.ownerName)?.ownerName;
  const primaryPhone = parcel.phone;
  const activeSignals = [
    parcel.activeNotificationCount ? `${formatCount(parcel.activeNotificationCount)} aktiivset teatist` : null,
    parcel.subPartCount ? `${formatCount(parcel.subPartCount)} eraldist` : null,
    parcel.forestFeatureCount ? `${formatCount(parcel.forestFeatureCount)} metsaregistri objekti` : null,
  ].filter(Boolean);

  return (
    <section className="space-y-3 rounded-xl border bg-white p-3 shadow-sm" aria-label="Katastri kiire ülevaade">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant={parcel.marked ? "default" : "outline"}>{parcel.marked ? "Prioriteetne" : "Jälgimisel"}</Badge>
          <Badge variant="outline">{statusLabel(parcel.ownerStatus)}</Badge>
          {labels.slice(0, 3).map(label => <Badge key={label} variant="outline">{CADASTRE_LABELS[label] ?? label}</Badge>)}
        </div>
        <span className="text-xs text-muted-foreground">{[parcel.municipality, parcel.county].filter(Boolean).join(", ") || "Asukoht teadmata"}</span>
      </div>
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <div className="rounded-lg border bg-emerald-50/60 p-3">
          <p className="text-[11px] font-medium uppercase tracking-wide text-emerald-900">Pindala</p>
          <p className="mt-1 text-lg font-semibold text-emerald-950">{formatArea(parcel.areaHectares)}</p>
        </div>
        <div className="rounded-lg border bg-slate-50 p-3">
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Metsamaa</p>
          <p className="mt-1 text-lg font-semibold">{formatArea(parcel.forestArea)}</p>
          <p className="text-xs text-muted-foreground">{formatPercent(forestShare)}</p>
        </div>
        <div className="rounded-lg border bg-slate-50 p-3">
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Omanikud</p>
          <p className="mt-1 text-lg font-semibold">{formatCount(totalOwners)}</p>
          <p className="truncate text-xs text-muted-foreground">{primaryOwner ?? "CRM omanik puudub"}</p>
        </div>
        <div className="rounded-lg border bg-slate-50 p-3">
          <p className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Metsa signaalid</p>
          <p className="mt-1 text-lg font-semibold">{formatCount((parcel.activeNotificationCount ?? 0) + (parcel.subPartCount ?? 0))}</p>
          <p className="truncate text-xs text-muted-foreground">{activeSignals[0] ?? "aktiivsed signaalid puuduvad"}</p>
        </div>
      </div>
      <div className="rounded-lg border bg-slate-950 p-3 text-white">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <p className="text-xs uppercase tracking-wide text-slate-300">Esmane kontakt</p>
            <p className="mt-1 text-sm font-semibold">{primaryOwner ?? "CRM omanik puudub"}</p>
          </div>
          <div className="text-right text-xs text-slate-300">
            <p>{primaryPhone ?? "telefon puudub"}</p>
            <p>{parcel.assignedTo ? `Vastutaja: ${parcel.assignedTo}` : "vastutaja määramata"}</p>
          </div>
        </div>
        {activeSignals.length > 0 && <p className="mt-2 text-xs text-slate-300">{activeSignals.join(" · ")}</p>}
      </div>
      <LandUseBar parcel={parcel} />
      <p className="text-xs text-muted-foreground">{[parcel.address, parcel.municipality, parcel.county].filter(Boolean).join(", ") || "Asukoht teadmata"}</p>
      <SourceMeta source="Maa- ja Ruumiamet · katastri avaldatud generatsioon" sourceDate={parcel.sourceDate} generationId={parcel.generationId} />
    </section>
  );
}

function RawData({ label, value }: { label: string; value?: string | null }) {
  if (!value) return null;
  return (
    <details className="mt-2 rounded border bg-slate-50 px-2.5 py-2 text-xs">
      <summary className="cursor-pointer font-medium">{label}</summary>
      <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-all text-[11px] text-muted-foreground">{value}</pre>
    </details>
  );
}

function ForestFeatureRawData({
  cadastralCode,
  featureId,
  label,
  onGeometry,
}: {
  cadastralCode: string;
  featureId: number;
  label: string;
  onGeometry?: (selection: MapDetailGeometrySelection | null) => void;
}) {
  const [requested, setRequested] = useState(false);
  const rawQuery = useCadastreForestFeatureRaw(cadastralCode, featureId, requested);
  const parsed = useMemo(() => parseDetailGeometry(rawQuery.data?.geometry), [rawQuery.data?.geometry]);

  if (!requested) {
    return <Button type="button" size="sm" variant="ghost" className="mt-2" onClick={() => setRequested(true)}>Allikaandmed</Button>;
  }
  if (rawQuery.isLoading) return <p className="mt-2 text-xs text-muted-foreground">Laadin allikaandmeid...</p>;
  if (rawQuery.isError) return <p className="mt-2 text-xs text-destructive">Allikaandmete laadimine ebaõnnestus.</p>;
  if (!rawQuery.data?.attributes && !rawQuery.data?.geometry) return <p className="mt-2 text-xs text-muted-foreground">Allikaandmed puuduvad.</p>;

  return (
    <>
      {parsed && onGeometry && <Button type="button" size="sm" variant="outline" className="mt-2" onClick={() => onGeometry({ id: `forest-feature:${featureId}`, label, geometry: parsed })}>Näita geomeetriat kaardil</Button>}
      <RawData label="Allika atribuudid" value={rawQuery.data.attributes} />
      <RawData label="Geomeetria" value={rawQuery.data.geometry} />
    </>
  );
}

type GeometryTarget =
  | { kind: "id"; id: number; code?: number | null; label: string }
  | { kind: "code"; code: number; label: string };

type MapParcelPanelProps = {
  cadastralCode: string;
  onClose: () => void;
  onMapDataChanged?: () => void;
  onDetailGeometryChange?: (selection: MapDetailGeometrySelection | null) => void;
  onShowContextLayer?: (sourceKey: string) => void;
};

export function MapParcelPanel({
  cadastralCode,
  onClose,
  onMapDataChanged,
  onDetailGeometryChange,
  onShowContextLayer,
}: MapParcelPanelProps) {
  const [notificationsRequested, setNotificationsRequested] = useState(false);
  const [forestFeaturesRequested, setForestFeaturesRequested] = useState(false);
  const [subPartsRequested, setSubPartsRequested] = useState(false);
  const [gisRequested, setGisRequested] = useState(false);
  const [geometryTarget, setGeometryTarget] = useState<GeometryTarget | null>(null);
  const [qualifyOpen, setQualifyOpen] = useState(false);
  const [qualificationFeedback, setQualificationFeedback] = useState("");

  const parcelQuery = useCompleteMapParcelSummary(cadastralCode);
  const labelsQuery = useCadastreLabels(cadastralCode);
  const evaluationQuery = useCadastreEvaluation(cadastralCode);
  const registryFreshnessQuery = useRegistryFreshness();
  const notificationsQuery = useCadastreAllNotificationPages(cadastralCode, notificationsRequested);
  const forestFeaturesQuery = useCadastreForestFeaturePages(cadastralCode, forestFeaturesRequested);
  const subPartsQuery = useCadastreSubPartPages(cadastralCode, subPartsRequested);
  const geospatialQuery = useParcelGeospatialContext(cadastralCode, gisRequested);
  const geometryByIdQuery = useCadastreSubPartGeometry(
    cadastralCode,
    geometryTarget?.kind === "id" ? geometryTarget.id : null,
    geometryTarget?.kind === "id",
  );
  const geometryByCodeQuery = useCadastreSubPartGeometryByCode(
    cadastralCode,
    geometryTarget?.kind === "code" ? geometryTarget.code : null,
    geometryTarget?.kind === "code",
  );

  const notifications = useMemo(
    () => notificationsQuery.data?.pages.flatMap(page => page.items) ?? [],
    [notificationsQuery.data?.pages],
  );
  const forestFeatures = useMemo(
    () => forestFeaturesQuery.data?.pages.flatMap(page => page.items) ?? [],
    [forestFeaturesQuery.data?.pages],
  );
  const subParts = useMemo(
    () => subPartsQuery.data?.pages.flatMap(page => page.items) ?? [],
    [subPartsQuery.data?.pages],
  );
  const parcel = parcelQuery.data;
  const owners = parcel?.owners ?? [];
  const telHref = toTelHref(parcel?.phone);
  const staleParcel = registryFreshnessQuery.data?.staleParcels?.find(item => item.cadastralCode === cadastralCode);
  const registryFreshnessState = registryFreshnessQuery.isError
    ? "UNKNOWN"
    : staleParcel
      ? "STALE"
      : registryFreshnessQuery.data
        ? "FRESH"
        : "UNKNOWN";

  const orderedNotifications = useMemo(
    () => [...notifications].sort((a, b) => Number(a.archived) - Number(b.archived) || b.id - a.id),
    [notifications],
  );

  useEffect(() => {
    setNotificationsRequested(false);
    setForestFeaturesRequested(false);
    setSubPartsRequested(false);
    setGisRequested(false);
    setGeometryTarget(null);
    onDetailGeometryChange?.(null);
  }, [cadastralCode, onDetailGeometryChange]);

  const selectedGeometryData = geometryTarget?.kind === "id"
    ? geometryByIdQuery.data
    : geometryTarget?.kind === "code"
      ? geometryByCodeQuery.data
      : undefined;

  useEffect(() => {
    if (!geometryTarget || !selectedGeometryData?.polygon) return;
    const geometry = parseDetailGeometry(selectedGeometryData.polygon);
    if (!geometry) return;
    onDetailGeometryChange?.({
      id: `subpart:${selectedGeometryData.id}`,
      label: geometryTarget.label,
      geometry,
    });
  }, [geometryTarget, selectedGeometryData, onDetailGeometryChange]);

  const selectGeometry = (target: GeometryTarget) => {
    setGeometryTarget(target);
    onDetailGeometryChange?.(null);
  };

  const clearGeometry = () => {
    setGeometryTarget(null);
    onDetailGeometryChange?.(null);
  };

  const geometryLoading = geometryTarget?.kind === "id" ? geometryByIdQuery.isLoading : geometryTarget?.kind === "code" ? geometryByCodeQuery.isLoading : false;
  const geometryError = geometryTarget?.kind === "id" ? geometryByIdQuery.isError : geometryTarget?.kind === "code" ? geometryByCodeQuery.isError : false;
  const geometryLoaded = selectedGeometryData;
  const geometryUnrenderable = Boolean(geometryTarget && geometryLoaded?.polygon && !parseDetailGeometry(geometryLoaded.polygon));

  return (
    <Card className="absolute inset-y-3 right-3 z-20 flex w-[min(34rem,calc(100%-1.5rem))] flex-col overflow-hidden border-slate-200 bg-white/95 shadow-2xl backdrop-blur md:inset-y-4 md:right-4 md:w-[32rem]">
      <CardHeader className="sticky top-0 z-10 flex-row items-start justify-between gap-3 space-y-0 border-b bg-white/95 pb-3 backdrop-blur">
        <div className="min-w-0">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Katastri terviklik vaade</p>
          <CardTitle className="mt-1 break-all text-lg">{cadastralCode}</CardTitle>
          {parcel?.name && <p className="mt-1 truncate text-sm text-muted-foreground">{parcel.name}</p>}
        </div>
        <Button variant="ghost" size="sm" aria-label="Sulge katastri detailvaade" onClick={onClose}><X className="h-4 w-4" /></Button>
      </CardHeader>

      <CardContent className="min-h-0 flex-1 space-y-3 overflow-y-auto p-4">
        {parcelQuery.isLoading && <p className="text-sm text-muted-foreground" role="status">Laadin katastri põhiandmeid...</p>}
        {parcelQuery.isError && <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-900" role="alert">{parcelQuery.error.message}<Button size="sm" variant="outline" className="ml-2" onClick={() => void parcelQuery.refetch()}>Proovi uuesti</Button></div>}

        {parcel && (
          <>
            {qualificationFeedback && <p role="status" className="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">{qualificationFeedback}</p>}

            <ParcelOverview parcel={parcel} ownerCount={owners.length} labels={labelsQuery.data?.setLabels} />

            <div className="grid grid-cols-2 gap-2 rounded-xl border bg-white p-2 shadow-sm">
              {parcel.ownerId ? <Button asChild size="sm"><Link href={`/owners/${parcel.ownerId}`}><ExternalLink className="mr-2 h-4 w-4" />Omaniku kaart</Link></Button> : <Button size="sm" disabled><ExternalLink className="mr-2 h-4 w-4" />Omanik puudub</Button>}
              {telHref ? <Button asChild size="sm" variant="outline"><a href={telHref}><Phone className="mr-2 h-4 w-4" />Helista</a></Button> : <Button size="sm" variant="outline" disabled><Phone className="mr-2 h-4 w-4" />Telefon puudub</Button>}
              {parcel.ownerId ? <Button size="sm" variant="outline" className="col-span-2" onClick={() => { setQualificationFeedback(""); setQualifyOpen(true); }}><Sparkles className="mr-2 h-4 w-4" />Saada hindamisse</Button> : <Button size="sm" variant="outline" className="col-span-2" disabled><Sparkles className="mr-2 h-4 w-4" />Hindamiseks on vaja CRM omanikku</Button>}
            </div>

            {geometryTarget && <div className="flex items-center justify-between gap-2 rounded-md border bg-slate-50 px-3 py-2 text-xs"><span>{geometryLoading ? `Laadin kaardile: ${geometryTarget.label}` : geometryError ? `Geomeetria laadimine ebaõnnestus: ${geometryTarget.label}` : geometryUnrenderable ? `Geomeetria vormingut ei saanud kaardil kuvada: ${geometryTarget.label}` : `Kaardil valitud: ${geometryTarget.label}`}</span><Button size="sm" variant="ghost" onClick={clearGeometry}>Eemalda</Button></div>}

            <DataSection title="Katastri põhiandmed" defaultOpen>
              <div className="grid grid-cols-2 gap-x-4 gap-y-3">
                <DataField label="Katastritunnus"><span className="font-medium">{parcel.cadastralCode}</span></DataField>
                <DataField label="Registrinumber">{displayValue(parcel.regNr)}</DataField>
                <DataField label="Nimi">{displayValue(parcel.name)}</DataField>
                <DataField label="Katastri liik">{displayValue(parcel.parcelType)}</DataField>
                <DataField label="Postiindeks">{displayValue(parcel.postal)}</DataField>
                <DataField label="Andmete kuupäev (MK)">{formatRegistryDate(parcel.mkDate)}</DataField>
                <DataField label="Vastutaja">{displayValue(parcel.assignedTo)}</DataField>
                <DataField label="Märgitud">{parcel.marked ? "Jah" : "Ei"}</DataField>
                <div className="col-span-2"><DataField label="Asukoht">{[parcel.address, parcel.municipality, parcel.county].filter(Boolean).join(", ") || "—"}</DataField></div>
              </div>
              <div className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3 border-t pt-3">
                <DataField label="Kokku"><span className="font-medium">{formatArea(parcel.areaHectares)}</span></DataField>
                <DataField label="Metsamaa"><span className="font-medium">{formatArea(parcel.forestArea)}</span></DataField>
                <DataField label="Õuemaa">{formatArea(parcel.yardArea)}</DataField>
                <DataField label="Haritav maa">{formatArea(parcel.arableArea)}</DataField>
                <DataField label="Rohumaa">{formatArea(parcel.meadowArea)}</DataField>
                <DataField label="Veealune maa">{formatArea(parcel.underwaterArea)}</DataField>
                <DataField label="Ehitiste alune maa">{formatArea(parcel.buildingsArea)}</DataField>
                <DataField label="Muu maa">{formatArea(parcel.otherArea)}</DataField>
              </div>
              <SourceMeta source="Maa- ja Ruumiamet" sourceDate={parcel.sourceDate} generationId={parcel.generationId} />
            </DataSection>

            <DataSection title="Omanikud" count={parcel.ownerCount ?? owners.length} defaultOpen={owners.length > 0}>
              {owners.length > 0 ? (
                <div className="space-y-2">
                  {owners.map((owner, index) => {
                    return owner.ownerId ? (
                      <div key={owner.ownerId} className="rounded-md border bg-white p-3 text-sm">
                        <div className="flex items-center justify-between gap-3">
                          <Link href={`/owners/${owner.ownerId}`} className="min-w-0 truncate font-medium hover:underline">{owner.ownerName ?? "Nimetu omanik"}</Link>
                          <Badge variant="outline">{statusLabel(owner.ownerStatus)}</Badge>
                        </div>
                        <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
                          <span>Seos: {owner.relationSource === "OWNER_PARCEL_RELATION" ? "kanoniline aktiivne seos" : displayValue(owner.relationSource)}</span>
                          {owner.assignedTo && <span>Vastutaja: {owner.assignedTo}</span>}
                        </div>
                      </div>
                    ) : (
                      <div key={`${owner.ownerName ?? "owner"}-${index}`} className="rounded-md border bg-white p-3 text-sm">
                        <span className="font-medium">{owner.ownerName ?? "Nimetu omanik"}</span>
                      </div>
                    );
                  })}
                </div>
              ) : <p className="text-sm text-muted-foreground">Katastrile ei ole CRM-is aktiivset omanikku seotud.</p>}
              <SourceMeta source="ForestIQ CRM · owner_parcel_relation" freshness="FRESH" />
            </DataSection>

            <DataSection title="Mets ja eraldised" count={parcel.subPartCount ?? undefined} onOpen={() => setSubPartsRequested(true)}>
              <QueryMessage loading={subPartsQuery.isLoading} error={subPartsQuery.isError} empty={Boolean(subPartsQuery.data && subParts.length === 0)} emptyText="Eraldisi ei leitud." retry={() => void subPartsQuery.refetch()} />
              {subParts.length > 0 && <div className="space-y-2">{subParts.map(subPart => {
                const selected = geometryTarget?.kind === "id" && geometryTarget.id === subPart.id;
                return <div key={subPart.id} className={`rounded-md border bg-white p-2.5 ${selected ? "border-slate-900 ring-1 ring-slate-900" : ""}`}>
                  <div className="grid grid-cols-3 gap-2"><DataField label="Eraldis">{subPart.subPartCode ?? "—"}</DataField><DataField label="Puuliik">{displayValue(subPart.treeTypeCode)}</DataField><DataField label="Pindala">{formatArea(subPart.area)}</DataField></div>
                  <Button type="button" size="sm" variant={selected ? "default" : "outline"} className="mt-2" aria-pressed={selected} onClick={() => selected ? clearGeometry() : selectGeometry({ kind: "id", id: subPart.id, code: subPart.subPartCode, label: `Eraldis ${subPart.subPartCode ?? subPart.id}` })}>{selected ? "Eemalda kaardilt" : "Näita polygon kaardil"}</Button>
                </div>;
              })}</div>}
              {subPartsQuery.hasNextPage && <Button type="button" size="sm" variant="outline" className="mt-2" disabled={subPartsQuery.isFetchingNextPage} onClick={() => void subPartsQuery.fetchNextPage()}>{subPartsQuery.isFetchingNextPage ? "Laadin..." : "Laadi järgmised eraldised"}</Button>}
              <SourceMeta source="Metsaregister / ForestIQ sünkroonitud eraldised" freshness={registryFreshnessState} />
            </DataSection>

            <DataSection title="Metsateatised" count={parcel.notificationCount ?? undefined} onOpen={() => setNotificationsRequested(true)}>
              <QueryMessage loading={notificationsQuery.isLoading} error={notificationsQuery.isError} empty={Boolean(notificationsQuery.data && notifications.length === 0)} emptyText="Metsateatisi ei leitud." retry={() => void notificationsQuery.refetch()} />
              {orderedNotifications.length > 0 && <div className="space-y-2">{orderedNotifications.map(notification => (
                <div key={notification.id} className="rounded-md border bg-white p-2.5 text-sm">
                  <div className="flex items-start justify-between gap-2"><span className="font-medium">{displayValue(notification.notificationNumber) !== "—" ? notification.notificationNumber : `Teatis #${notification.id}`}</span><Badge variant="outline">{notification.archived ? "Ajalooline" : "Aktiivne"}</Badge></div>
                  <div className="mt-2 grid grid-cols-2 gap-2">
                    <DataField label="Eraldis">{notification.cadastreSubpartCode ?? "—"}</DataField>
                    <DataField label="Töö kood">{displayValue(notification.workCode)}</DataField>
                    <DataField label="Kahjustuse kood">{displayValue(notification.damageCode)}</DataField>
                    <DataField label="Pindala">{formatArea(notification.area)}</DataField>
                    <DataField label="Raiemaht">{formatNumber(notification.amountToBeCut, " m³")}</DataField>
                    <DataField label="Staatus">{displayValue(notification.state)}</DataField>
                    <DataField label="Registreeritud">{formatRegistryDate(notification.registrationDate)}</DataField>
                    <DataField label="Kinnitatud">{formatRegistryDate(notification.confirmationDate)}</DataField>
                    {notification.archived && <DataField label="Arhiveeritud">{formatRegistryDate(notification.archiveDate)}</DataField>}
                    <div className="col-span-2"><DataField label="Otsus">{displayValue(notification.decision)}</DataField></div>
                  </div>
                  {notification.cadastreSubpartCode != null && <Button type="button" size="sm" variant="outline" className="mt-2" onClick={() => selectGeometry({ kind: "code", code: notification.cadastreSubpartCode!, label: `Teatise eraldis ${notification.cadastreSubpartCode}` })}>Näita seotud eraldist kaardil</Button>}
                </div>
              ))}</div>}
              {notificationsQuery.hasNextPage && <Button type="button" size="sm" variant="outline" className="mt-2" disabled={notificationsQuery.isFetchingNextPage} onClick={() => void notificationsQuery.fetchNextPage()}>{notificationsQuery.isFetchingNextPage ? "Laadin..." : "Laadi vanemad teatised"}</Button>}
              <SourceMeta source="Metsaregister / ForestIQ sünkroonitud teatised" freshness={registryFreshnessState} />
            </DataSection>

            <DataSection title="Metsaregistri muud andmed" count={parcel.forestFeatureCount ?? undefined} onOpen={() => setForestFeaturesRequested(true)}>
              <QueryMessage loading={forestFeaturesQuery.isLoading} error={forestFeaturesQuery.isError} empty={Boolean(forestFeaturesQuery.data && forestFeatures.length === 0)} emptyText="Metsaregistri objekte ei leitud." retry={() => void forestFeaturesQuery.refetch()} />
              {forestFeatures.length > 0 && <div className="space-y-2">{forestFeatures.map(feature => (
                <div key={feature.id} className="rounded-md border bg-white p-2.5 text-sm">
                  <p className="font-medium">{displayValue(feature.title) !== "—" ? feature.title : `${displayValue(feature.sourceLayer)} · ${displayValue(feature.sourceId)}`}</p>
                  <div className="mt-2 grid grid-cols-2 gap-2">
                    <DataField label="Kiht">{displayValue(feature.sourceLayer)}</DataField>
                    <DataField label="Allika ID">{displayValue(feature.sourceId)}</DataField>
                    <DataField label="Eraldis">{feature.subpartCode ?? "—"}</DataField>
                    <DataField label="Sündmuse kuupäev">{formatRegistryDate(feature.eventDate)}</DataField>
                    <DataField label="Töö kood">{displayValue(feature.workCode)}</DataField>
                    <DataField label="Otsus">{displayValue(feature.decision)}</DataField>
                    <DataField label="Pindala">{formatArea(feature.area)}</DataField>
                    <DataField label="Maht">{formatNumber(feature.volume, " m³")}</DataField>
                  </div>
                  {feature.subpartCode != null && <Button type="button" size="sm" variant="outline" className="mt-2" onClick={() => selectGeometry({ kind: "code", code: feature.subpartCode!, label: `Metsaregistri eraldis ${feature.subpartCode}` })}>Näita eraldist kaardil</Button>}
                  <ForestFeatureRawData cadastralCode={cadastralCode} featureId={feature.id} label={feature.title ?? `Metsaregistri objekt ${feature.id}`} onGeometry={onDetailGeometryChange} />
                </div>
              ))}</div>}
              {forestFeaturesQuery.hasNextPage && <Button type="button" size="sm" variant="outline" className="mt-2" disabled={forestFeaturesQuery.isFetchingNextPage} onClick={() => void forestFeaturesQuery.fetchNextPage()}>{forestFeaturesQuery.isFetchingNextPage ? "Laadin..." : "Laadi järgmised objektid"}</Button>}
              <SourceMeta source="Metsaregister / ForestIQ sünkroonitud projektsioon" freshness={registryFreshnessState} />
            </DataSection>

            <DataSection title="Maakasutus, ETAK ja ruumilised faktid" onOpen={() => setGisRequested(true)}>
              <QueryMessage loading={geospatialQuery.isLoading} error={geospatialQuery.isError} empty={Boolean(geospatialQuery.data && (geospatialQuery.data.layerFacts?.length ?? 0) === 0)} emptyText="Selle katastri kohta pole avaldatud GIS-fakte." retry={() => void geospatialQuery.refetch()} />
              {geospatialQuery.data && <>
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
                  <DataField label="Maakond">{geospatialQuery.data.county?.name ?? parcel.county ?? "—"}</DataField>
                  <DataField label="Omavalitsus">{geospatialQuery.data.municipality?.name ?? parcel.municipality ?? "—"}</DataField>
                  <DataField label="Asustusüksus">{geospatialQuery.data.settlement?.name ?? "—"}</DataField>
                </div>
                <div className="mt-3 space-y-2">{(geospatialQuery.data.layerFacts ?? []).map(fact => (
                  <div key={fact.sourceKey} className="rounded-md border bg-white p-3">
                    <div className="flex items-start justify-between gap-2"><div><p className="text-sm font-medium">{fact.title}</p><p className="text-xs text-muted-foreground">{fact.caveat}</p></div>{fact.provenance?.freshnessState && <Badge variant="outline">{freshnessLabel(fact.provenance.freshnessState)}</Badge>}</div>
                    {!fact.available ? <p className="mt-2 text-sm text-amber-800">Avaldatud generatsioon puudub.</p> : <div className="mt-2 grid grid-cols-3 gap-2"><DataField label="Objekte">{fact.featureCount ?? "—"}</DataField>{fact.geometryType === "POLYGON" ? <><DataField label="Kattuvus">{formatArea(fact.overlapAreaHa)}</DataField><DataField label="Osakaal">{formatPercent(fact.overlapPercent)}</DataField></> : <DataField label="Pikkus kinnistul">{formatNumber(fact.intersectionLengthMeters, " m")}</DataField>}</div>}
                    {fact.available && fact.mapVisible && onShowContextLayer && <Button type="button" size="sm" variant="outline" className="mt-2" onClick={() => onShowContextLayer(fact.sourceKey)}>Näita kihti kaardil</Button>}
                    {fact.provenance && <SourceMeta source={fact.sourceKey} sourceDate={fact.provenance.sourceDate} generationId={fact.provenance.generationId} freshness={fact.provenance.freshnessState} />}
                  </div>
                ))}</div>
              </>}
            </DataSection>

            <DataSection title="ForestIQ töövoog">
              {parcel.ownerId && <MapOwnerWorkPanel ownerId={parcel.ownerId} cadastralCode={cadastralCode} onMapDataChanged={onMapDataChanged} />}
              <div className="mt-3 rounded-md border bg-white p-3">
                <p className="mb-2 text-sm font-semibold">Hinnang</p>
                <QueryMessage loading={evaluationQuery.isLoading} error={evaluationQuery.isError} empty={!evaluationQuery.data} emptyText="Hinnang puudub." retry={() => void evaluationQuery.refetch()} />
                {evaluationQuery.data && <div className="grid grid-cols-2 gap-4"><DataField label="Meie hind">{displayValue(evaluationQuery.data.ourPrice)}</DataField><DataField label="Omaniku hind">{displayValue(evaluationQuery.data.ownerPrice)}</DataField></div>}
              </div>
              <div className="mt-3 rounded-md border bg-white p-3">
                <p className="mb-2 text-sm font-semibold">Märgendid</p>
                <QueryMessage loading={labelsQuery.isLoading} error={labelsQuery.isError} empty={Boolean(labelsQuery.data && labelsQuery.data.setLabels.length === 0)} emptyText="Märgendeid pole lisatud." retry={() => void labelsQuery.refetch()} />
                {labelsQuery.data && labelsQuery.data.setLabels.length > 0 && <div className="flex flex-wrap gap-1.5">{labelsQuery.data.setLabels.map(label => <Badge key={label} variant="outline">{CADASTRE_LABELS[label] ?? label}</Badge>)}</div>}
              </div>
              <SourceMeta source="ForestIQ CRM ja hindamisvoog" freshness="FRESH" />
            </DataSection>

            <DataSection title="Andmeallikad ja värskus">
              <div className="space-y-2 text-sm">
                <div className="rounded-md border bg-white p-3"><p className="font-medium">Katastri põhiandmed ja geomeetria</p><SourceMeta source="Maa- ja Ruumiamet" sourceDate={parcel.sourceDate} generationId={parcel.generationId} /></div>
                <div className="rounded-md border bg-white p-3"><p className="font-medium">Omaniku–katastri seosed ja CRM</p><SourceMeta source="ForestIQ · owner_parcel_relation" freshness="FRESH" /></div>
                <div className="rounded-md border bg-white p-3"><p className="font-medium">Metsaregistri andmed</p>{staleParcel?.staleSources?.length ? <p className="mt-1 text-xs text-amber-800">Värskendamist vajavad allikad: {staleParcel.staleSources.join(", ")}</p> : <p className="mt-1 text-xs text-muted-foreground">{registryFreshnessQuery.isError ? "Värskuse kontroll pole hetkel kättesaadav." : "Katastrile ei ole värskusvaates aegunud allika signaali."}</p>}<SourceMeta source="ForestIQ registrisünkroonimine" freshness={registryFreshnessState} /></div>
                {(geospatialQuery.data?.layerFacts ?? []).map(fact => fact.provenance ? <div key={fact.sourceKey} className="rounded-md border bg-white p-3"><p className="font-medium">{fact.title}</p><SourceMeta source={fact.sourceKey} sourceDate={fact.provenance.sourceDate} generationId={fact.provenance.generationId} freshness={fact.provenance.freshnessState} /></div> : null)}
              </div>
            </DataSection>

            <DataSection title="Ametlikud allikad ja käsitsi värskendamine">
              <p className="text-xs text-muted-foreground">Värskenda katastri, metsateatiste ja metsaplaani andmed otse kaardilt. Ühe allika viga ei tühjenda viimast töötavat detailvaadet.</p>
              <ParcelRegistryActions cadastralCode={cadastralCode} onMapDataChanged={onMapDataChanged} />
            </DataSection>

            <div className="grid grid-cols-2 gap-2 border-t pt-3">
              <Button size="sm" variant="outline" className="col-span-2" onClick={() => void navigator.clipboard?.writeText(parcel.cadastralCode)}><Copy className="mr-2 h-4 w-4" />Kopeeri katastritunnus</Button>
            </div>
            <div className="flex items-center gap-2 pb-1 text-xs text-muted-foreground"><MapPinned className="h-3.5 w-3.5" />Katastri, omanike, metsa, teatiste, GIS-faktide ja allikate info ühes töövaates.</div>
          </>
        )}
      </CardContent>
      {parcel?.ownerId && <div className="absolute inset-x-4 bottom-4 z-30 max-h-[calc(100%-2rem)] overflow-y-auto"><DealQualificationDialog ownerId={parcel.ownerId} open={qualifyOpen} onCancel={() => setQualifyOpen(false)} onCreated={() => { setQualifyOpen(false); setQualificationFeedback("Tehingujuhtum loodi ja hindamise töö suunati järjekorda."); }} /></div>}
    </Card>
  );
}
