import type { CompleteMapParcelSummary } from "@/lib/api/map-cadastre";

export type LandUseSegment = {
  label: string;
  value: number;
  tone: string;
};

export function positiveArea(value?: number | null) {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : 0;
}

export function percentOf(value: number, total: number) {
  if (!Number.isFinite(value) || !Number.isFinite(total) || total <= 0) return null;
  return Math.max(0, Math.min(100, (value / total) * 100));
}

export function formatCount(value?: number | null) {
  return value === undefined || value === null ? "—" : new Intl.NumberFormat("et-EE").format(value);
}

export function landUseSegments(parcel: CompleteMapParcelSummary): LandUseSegment[] {
  const segments: LandUseSegment[] = [
    { label: "Metsamaa", value: positiveArea(parcel.forestArea), tone: "bg-emerald-600" },
    { label: "Haritav maa", value: positiveArea(parcel.arableArea), tone: "bg-amber-500" },
    { label: "Rohumaa", value: positiveArea(parcel.meadowArea), tone: "bg-lime-500" },
    { label: "Õuemaa", value: positiveArea(parcel.yardArea), tone: "bg-sky-500" },
    { label: "Vesi", value: positiveArea(parcel.underwaterArea), tone: "bg-blue-500" },
    { label: "Ehitised", value: positiveArea(parcel.buildingsArea), tone: "bg-slate-500" },
    { label: "Muu maa", value: positiveArea(parcel.otherArea), tone: "bg-zinc-400" },
  ].filter(segment => segment.value > 0);
  const area = positiveArea(parcel.areaHectares);
  const known = segments.reduce((sum, segment) => sum + segment.value, 0);
  const unknown = area > known ? area - known : 0;
  if (unknown > 0.01) segments.push({ label: "Tuvastamata", value: unknown, tone: "bg-slate-200" });
  return segments;
}
