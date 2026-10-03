import type { Map } from "maplibre-gl";
import type { ContextLayerDescriptor, MapLayerDescriptor } from "./metsaregister-overlay-types";

export const SOURCE_PREFIX = "metsaregister-overlay-source:";
export const LAYER_PREFIX = "metsaregister-overlay:";
export const CONTEXT_SOURCE_PREFIX = "forestiq-context-source:";
export const CONTEXT_LAYER_PREFIX = "forestiq-context-layer:";

const VECTOR_LAYER_COLOR = "#166534";
const RASTER_LAYER_COLOR = "#64748b";
const ETAK_LAYER_COLOR = "#0f766e";
const ADMIN_LAYER_COLOR = "#475569";
const LAYER_COLORS: Record<string, string> = {
  "metsaregister:teatis": "#dc2626",
  "metsaregister:teatis_arhiiv": "#f97316",
  "metsaregister:eraldis": "#166534",
  "metsaregister:isearasused": "#7c3aed",
  "metsaregister:kahjustused": "#b91c1c",
};

export function registryLayerColor(descriptor: MapLayerDescriptor) {
  return descriptor.transport === "MVT" ? LAYER_COLORS[descriptor.key] ?? VECTOR_LAYER_COLOR : RASTER_LAYER_COLOR;
}

export function contextLayerColor(descriptor: ContextLayerDescriptor) {
  return descriptor.datasetFamily === "ADMIN" ? ADMIN_LAYER_COLOR : ETAK_LAYER_COLOR;
}

export function rasterLayerId(key: string) {
  return LAYER_PREFIX + key;
}

export function vectorLayerId(key: string, geometry: "fill" | "line" | "point") {
  return `${LAYER_PREFIX}${key}:${geometry}`;
}

export function vectorLayerIds(key: string) {
  return [vectorLayerId(key, "fill"), vectorLayerId(key, "line"), vectorLayerId(key, "point")];
}

export class MetsaregisterMapLayerAdapter {
  private readonly enabled = new Set<string>();
  private readonly enabledContext = new Set<string>();

  constructor(
    private readonly map: Map,
    private readonly onFeatureSelect?: (cadastralCode: string) => void,
    private readonly onLayerLoaded?: () => void,
  ) {}

  addLayer(descriptor: MapLayerDescriptor, opacity: number, orderedLayers: MapLayerDescriptor[]) {
    if (this.enabled.has(descriptor.key) || !descriptor.available || !descriptor.tileUrlTemplate) return;
    const sourceId = SOURCE_PREFIX + descriptor.key;
    const tile = `${window.location.origin}${descriptor.tileUrlTemplate}`;

    if (descriptor.transport === "MVT") {
      if (!this.map.getSource(sourceId)) {
        this.map.addSource(sourceId, {
          type: "vector",
          tiles: [tile],
          minzoom: descriptor.minZoom,
          maxzoom: descriptor.maxZoom,
          attribution: descriptor.attribution,
        });
      }
      this.addVectorRenderLayers(descriptor, sourceId, opacity);
    } else {
      if (!this.map.getSource(sourceId)) {
        this.map.addSource(sourceId, {
          type: "raster",
          tiles: [tile],
          tileSize: 256,
          minzoom: descriptor.minZoom,
          maxzoom: descriptor.maxZoom,
          attribution: descriptor.attribution,
        });
      }
      this.map.addLayer({
        id: rasterLayerId(descriptor.key),
        type: "raster",
        source: sourceId,
        minzoom: descriptor.minZoom,
        maxzoom: descriptor.maxZoom,
        paint: { "raster-opacity": opacity },
      }, this.beforeOverlayAnchor());
    }
    this.enabled.add(descriptor.key);
    this.reorderEnabledLayers(orderedLayers);
    this.onLayerLoaded?.();
  }

  addContextLayer(descriptor: ContextLayerDescriptor, opacity: number) {
    if (!descriptor.available || !descriptor.tileUrlTemplate || this.enabledContext.has(descriptor.sourceKey)) return;
    const sourceId = CONTEXT_SOURCE_PREFIX + descriptor.sourceKey;
    const layerId = CONTEXT_LAYER_PREFIX + descriptor.sourceKey;
    if (!this.map.getSource(sourceId)) {
      this.map.addSource(sourceId, {
        type: "vector",
        tiles: [`${window.location.origin}${descriptor.tileUrlTemplate}`],
        minzoom: descriptor.minZoom,
        maxzoom: descriptor.maxZoom,
        attribution: "Maa- ja Ruumiamet",
      });
    }
    if (descriptor.geometryType === "LINE") {
      this.map.addLayer({
        id: layerId,
        type: "line",
        source: sourceId,
        "source-layer": "context",
        minzoom: descriptor.minZoom,
        maxzoom: descriptor.maxZoom + 1,
        paint: {
          "line-color": descriptor.datasetFamily === "ADMIN" ? "#334155" : ETAK_LAYER_COLOR,
          "line-opacity": opacity,
          "line-width": 1.5,
        },
      }, this.beforeOperationalLayers());
    } else {
      this.map.addLayer({
        id: layerId,
        type: "fill",
        source: sourceId,
        "source-layer": "context",
        minzoom: descriptor.minZoom,
        maxzoom: descriptor.maxZoom + 1,
        paint: {
          "fill-color": descriptor.datasetFamily === "ADMIN" ? ADMIN_LAYER_COLOR : "#15803d",
          "fill-opacity": opacity,
          "fill-outline-color": descriptor.datasetFamily === "ADMIN" ? "#334155" : "#166534",
        },
      }, this.beforeOperationalLayers());
    }
    this.enabledContext.add(descriptor.sourceKey);
    this.onLayerLoaded?.();
  }

  removeLayer(key: string) {
    for (const id of [rasterLayerId(key), ...vectorLayerIds(key)]) {
      if (this.map.getLayer(id)) this.map.removeLayer(id);
    }
    const sourceId = SOURCE_PREFIX + key;
    if (this.map.getSource(sourceId)) this.map.removeSource(sourceId);
    this.enabled.delete(key);
  }

  removeContextLayer(sourceKey: string) {
    const layerId = CONTEXT_LAYER_PREFIX + sourceKey;
    const sourceId = CONTEXT_SOURCE_PREFIX + sourceKey;
    if (this.map.getLayer(layerId)) this.map.removeLayer(layerId);
    if (this.map.getSource(sourceId)) this.map.removeSource(sourceId);
    this.enabledContext.delete(sourceKey);
  }

  removeAll() {
    for (const key of [...this.enabled]) this.removeLayer(key);
    for (const key of [...this.enabledContext]) this.removeContextLayer(key);
  }

  applyOpacity(descriptor: MapLayerDescriptor, opacity: number) {
    const raster = rasterLayerId(descriptor.key);
    if (this.map.getLayer(raster)) this.map.setPaintProperty(raster, "raster-opacity", opacity);
    const fill = vectorLayerId(descriptor.key, "fill");
    if (this.map.getLayer(fill)) this.map.setPaintProperty(fill, "fill-opacity", opacity * 0.35);
    const line = vectorLayerId(descriptor.key, "line");
    if (this.map.getLayer(line)) this.map.setPaintProperty(line, "line-opacity", opacity);
    const point = vectorLayerId(descriptor.key, "point");
    if (this.map.getLayer(point)) this.map.setPaintProperty(point, "circle-opacity", opacity);
  }

  applyContextOpacity(descriptor: ContextLayerDescriptor, value: number) {
    const layerId = CONTEXT_LAYER_PREFIX + descriptor.sourceKey;
    if (!this.map.getLayer(layerId)) return;
    if (descriptor.geometryType === "LINE") this.map.setPaintProperty(layerId, "line-opacity", value);
    else this.map.setPaintProperty(layerId, "fill-opacity", value);
  }

  private addVectorRenderLayers(descriptor: MapLayerDescriptor, sourceId: string, opacity: number) {
    if (!descriptor.sourceLayer) return;
    const before = this.beforeOverlayAnchor();
    const color = registryLayerColor(descriptor);
    const common = {
      source: sourceId,
      "source-layer": descriptor.sourceLayer,
      minzoom: descriptor.minZoom,
      maxzoom: descriptor.maxZoom,
    };

    const fillId = vectorLayerId(descriptor.key, "fill");
    this.map.addLayer({
      id: fillId,
      type: "fill",
      ...common,
      filter: ["==", ["geometry-type"], "Polygon"],
      paint: { "fill-color": color, "fill-opacity": opacity * 0.45 },
    }, before);
    this.map.addLayer({
      id: vectorLayerId(descriptor.key, "line"),
      type: "line",
      ...common,
      filter: ["==", ["geometry-type"], "LineString"],
      paint: { "line-color": color, "line-opacity": opacity, "line-width": 1.5 },
    }, before);
    this.map.addLayer({
      id: vectorLayerId(descriptor.key, "point"),
      type: "circle",
      ...common,
      filter: ["==", ["geometry-type"], "Point"],
      paint: { "circle-color": color, "circle-opacity": opacity, "circle-radius": 4 },
    }, before);

    if (descriptor.queryable && this.onFeatureSelect) {
      for (const layerId of vectorLayerIds(descriptor.key)) {
        this.map.on("click", layerId, (event) => {
          const cadastralCode = event.features?.[0]?.properties?.cadastralCode;
          if (cadastralCode) this.onFeatureSelect?.(String(cadastralCode));
        });
      }
    }
  }

  private reorderEnabledLayers(orderedLayers: MapLayerDescriptor[]) {
    if (typeof this.map.moveLayer !== "function") return;
    const enabled = orderedLayers.filter(layer => this.enabled.has(layer.key));
    const before = this.beforeOverlayAnchor();
    for (const descriptor of enabled) {
      const ids = descriptor.transport === "MVT" ? vectorLayerIds(descriptor.key) : [rasterLayerId(descriptor.key)];
      for (const id of ids) {
        if (this.map.getLayer(id)) this.map.moveLayer(id, before);
      }
    }
  }

  private beforeOverlayAnchor() {
    for (const id of ["parcel-detail-highlight-fill", "parcel-detail-highlight-line", "parcels-selected", "parcels-label"]) {
      if (this.map.getLayer(id)) return id;
    }
    return undefined;
  }

  private beforeOperationalLayers() {
    return this.beforeOverlayAnchor();
  }
}
