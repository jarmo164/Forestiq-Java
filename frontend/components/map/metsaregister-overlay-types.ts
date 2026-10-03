import type { GeospatialMapLayerDescriptor } from "@/lib/api";

export type MapLayerDescriptor = {
  key: string;
  title: string;
  provider: string;
  category: "FORESTIQ_FORESTRY" | "METSAREGISTER_EXTERNAL" | string;
  businessMode: string;
  transport: "MVT" | "WMS" | "WMTS";
  sourceLayer?: string | null;
  crs?: string;
  sourceCrs?: string;
  sourceAxisOrder?: string;
  canonicalCrs?: string;
  presentationCrs?: string;
  minZoom: number;
  maxZoom: number;
  defaultOpacity: number;
  order: number;
  defaultVisible: boolean;
  queryable: boolean;
  mandatory: boolean;
  attribution: string;
  freshnessTarget: string;
  authorizationScope: string;
  styleKind: string;
  available: boolean;
  tileUrlTemplate?: string | null;
  localAvailability?: "AVAILABLE" | "UNAVAILABLE" | "UNKNOWN" | "NOT_APPLICABLE" | string;
  freshnessState?: "FRESH" | "STALE_USABLE" | string | null;
  sourceDate?: string | null;
  publishedAt?: string | null;
  externalAvailability?: "AVAILABLE" | "UNAVAILABLE" | "UNKNOWN" | "NOT_APPLICABLE" | string;
  externalLastCheckedAt?: string | null;
  externalErrorCode?: string | null;
};

export type LayerRegistry = {
  manifestVersion: string;
  tileCatalogVersion?: string;
  forestryGenerationId?: string | null;
  layers: MapLayerDescriptor[];
};

export type ContextLayerDescriptor = GeospatialMapLayerDescriptor;
