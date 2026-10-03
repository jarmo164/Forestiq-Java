import type { IControl, Map as MapLibreMap } from "maplibre-gl";

export type BasemapDescriptor = {
  key: string;
  title: string;
  minZoom: number;
  maxZoom: number;
  attribution: string;
  sourceDate?: string | null;
  freshness: string;
  tileUrlTemplate: string;
  defaultSelected: boolean;
};

type BasemapCatalog = {
  version: string;
  layers: BasemapDescriptor[];
};

const STORAGE_KEY = "forestiq.basemap.v1";
const SOURCE_PREFIX = "forestiq-basemap-source-";
const LAYER_PREFIX = "forestiq-basemap-layer-";

export class MaaruumBasemapControl implements IControl {
  private map?: MapLibreMap;
  private container?: HTMLDivElement;
  private select?: HTMLSelectElement;
  private meta?: HTMLDivElement;
  private warning?: HTMLDivElement;
  private layers: BasemapDescriptor[] = [];
  private errorHandler?: (event: unknown) => void;

  onAdd(map: MapLibreMap): HTMLElement {
    this.map = map;
    const container = document.createElement("div");
    container.className = "maplibregl-ctrl maplibregl-ctrl-group forestiq-basemap-control";
    container.setAttribute("data-testid", "basemap-control");
    container.style.padding = "8px";
    container.style.background = "rgba(255,255,255,0.96)";
    container.style.minWidth = "180px";

    const label = document.createElement("label");
    label.textContent = "Aluskaart";
    label.style.display = "block";
    label.style.fontSize = "12px";
    label.style.fontWeight = "600";

    const select = document.createElement("select");
    select.setAttribute("aria-label", "Vali Maa- ja Ruumiameti aluskaart");
    select.style.width = "100%";
    select.style.marginTop = "4px";
    select.disabled = true;
    select.addEventListener("change", () => this.activate(select.value, true));
    label.appendChild(select);

    const meta = document.createElement("div");
    meta.style.marginTop = "5px";
    meta.style.fontSize = "10px";
    meta.style.lineHeight = "1.25";
    meta.style.color = "#475569";

    const warning = document.createElement("div");
    warning.setAttribute("role", "status");
    warning.style.display = "none";
    warning.style.marginTop = "5px";
    warning.style.fontSize = "10px";
    warning.style.color = "#9a3412";

    container.append(label, meta, warning);
    this.container = container;
    this.select = select;
    this.meta = meta;
    this.warning = warning;

    this.errorHandler = (event: unknown) => {
      const candidate = event as { sourceId?: string; source?: { id?: string } };
      const sourceId = candidate.sourceId ?? candidate.source?.id ?? "";
      if (sourceId.startsWith(SOURCE_PREFIX)) this.showProviderWarning();
    };
    map.on("error", this.errorHandler);
    void this.loadCatalog();
    return container;
  }

  onRemove(): void {
    if (this.map && this.errorHandler) this.map.off("error", this.errorHandler);
    this.container?.remove();
    this.container = undefined;
    this.select = undefined;
    this.meta = undefined;
    this.warning = undefined;
    this.map = undefined;
  }

  private async loadCatalog(): Promise<void> {
    try {
      const response = await fetch("/api/v1/map/basemaps", {
        credentials: "same-origin",
        headers: { Accept: "application/json" },
      });
      if (!response.ok) throw new Error(`Basemap catalog ${response.status}`);
      const catalog = await response.json() as BasemapCatalog;
      this.layers = Array.isArray(catalog.layers) ? catalog.layers.filter(layer => Boolean(layer.tileUrlTemplate)) : [];
      if (this.layers.length === 0) throw new Error("No basemaps configured");
      this.populateSelect();
      const persisted = window.localStorage.getItem(STORAGE_KEY);
      const selected = this.layers.find(layer => layer.key === persisted)?.key
        ?? this.layers.find(layer => layer.defaultSelected)?.key
        ?? this.layers[0].key;
      this.activateWhenReady(selected);
    } catch {
      this.showProviderWarning("Aluskaartide loendit ei õnnestunud laadida. ForestIQ andmekihid jäävad kasutatavaks.");
    }
  }

  private populateSelect(): void {
    if (!this.select) return;
    this.select.replaceChildren();
    for (const layer of this.layers) {
      const option = document.createElement("option");
      option.value = layer.key;
      option.textContent = layer.title;
      this.select.appendChild(option);
    }
    this.select.disabled = false;
  }

  private activateWhenReady(key: string): void {
    const apply = () => this.activate(key, false);
    if (this.map?.isStyleLoaded()) apply();
    else this.map?.once("load", apply);
  }

  private activate(key: string, persist: boolean): void {
    const map = this.map;
    if (!map) return;
    const selected = this.layers.find(layer => layer.key === key);
    if (!selected) return;

    for (const descriptor of this.layers) {
      const sourceId = SOURCE_PREFIX + descriptor.key;
      const layerId = LAYER_PREFIX + descriptor.key;
      if (!map.getSource(sourceId)) {
        map.addSource(sourceId, {
          type: "raster",
          tiles: [descriptor.tileUrlTemplate],
          tileSize: 256,
          minzoom: descriptor.minZoom,
          maxzoom: descriptor.maxZoom,
          attribution: descriptor.attribution,
        });
      }
      if (!map.getLayer(layerId)) {
        const before = map.getLayer("parcels-overview")
          ? "parcels-overview"
          : map.getLayer("parcels-fill") ? "parcels-fill" : undefined;
        map.addLayer({
          id: layerId,
          type: "raster",
          source: sourceId,
          minzoom: descriptor.minZoom,
          maxzoom: descriptor.maxZoom + 1,
          layout: { visibility: descriptor.key === key ? "visible" : "none" },
        }, before);
      } else {
        map.setLayoutProperty(layerId, "visibility", descriptor.key === key ? "visible" : "none");
      }
    }

    if (this.select) this.select.value = selected.key;
    if (this.meta) {
      const date = selected.sourceDate ? ` · andmeseis ${selected.sourceDate}` : "";
      this.meta.textContent = `${selected.attribution}${date}`;
    }
    if (this.warning) this.warning.style.display = "none";
    if (persist) window.localStorage.setItem(STORAGE_KEY, selected.key);
  }

  private showProviderWarning(message = "Maa- ja Ruumiameti aluskaart ei ole praegu saadaval. ForestIQ kinnistud ja detailandmed jäävad kasutatavaks."): void {
    if (!this.warning) return;
    this.warning.textContent = message;
    this.warning.style.display = "block";
  }
}
