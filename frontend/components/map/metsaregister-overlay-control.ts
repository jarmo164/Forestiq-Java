import type { IControl, Map } from "maplibre-gl";
import {
  CONTEXT_SOURCE_PREFIX,
  MetsaregisterMapLayerAdapter,
  SOURCE_PREFIX,
  contextLayerColor,
  registryLayerColor,
} from "./metsaregister-overlay-map-layers";
import type {
  ContextLayerDescriptor,
  LayerRegistry,
  MapLayerDescriptor,
} from "./metsaregister-overlay-types";
import {
  CONTEXT_LAYER_PREFERENCE_KEY,
  MAP_LAYER_PREFERENCE_KEY,
  contextLayerPreference,
  layerPreference,
  orderedByContextPreference,
  orderedByLayerPreference,
  readPreferenceStore,
  writePreferencePatch,
} from "@/lib/map/metsaregister-overlay-preferences";
import type {
  ContextLayerPreference,
  ContextLayerPreferences,
  LayerPreference,
  LayerPreferences,
} from "@/lib/map/metsaregister-overlay-preferences";

/**
 * Descriptor-driven MapLibre workspace. The browser only receives ForestIQ map routes;
 * provider endpoints, credentials and arbitrary proxy parameters remain server-side.
 */
export class MetsaregisterOverlayControl implements IControl {
  private map?: Map;
  private container?: HTMLDivElement;
  private panel?: HTMLDivElement;
  private toggle?: HTMLButtonElement;
  private body?: HTMLDivElement;
  private warning?: HTMLDivElement;
  private registry?: LayerRegistry;
  private contextLayers: ContextLayerDescriptor[] = [];
  private layerAdapter?: MetsaregisterMapLayerAdapter;
  private readonly accessToken?: string;
  private readonly onFeatureSelect?: (cadastralCode: string) => void;
  private preferences: LayerPreferences = this.readPreferences();
  private contextPreferences: ContextLayerPreferences = this.readContextPreferences();

  private readonly onSourceDataError = (event: unknown) => {
    const sourceId = (event as { sourceId?: string } | null)?.sourceId;
    if (!sourceId || !this.warning) return;
    if (sourceId.startsWith(CONTEXT_SOURCE_PREFIX)) {
      this.warning.textContent = "ETAK/haldusjaotuse kontekstikihti ei õnnestunud laadida. Kinnistu põhivaade ja detailandmed töötavad edasi.";
      this.warning.hidden = false;
      return;
    }
    if (!sourceId.startsWith(SOURCE_PREFIX)) return;
    const key = sourceId.slice(SOURCE_PREFIX.length);
    const descriptor = this.registry?.layers.find(layer => layer.key === key);
    this.warning.textContent = descriptor?.category === "METSAREGISTER_EXTERNAL"
      ? "Väline Metsaregistri kaardikiht ei ole hetkel kättesaadav. ForestIQ kohalikud kihid ja kinnistuandmed töötavad edasi."
      : "ForestIQ metsaandmete kaardikiht ei ole hetkel kättesaadav. Kinnistu põhivaade töötab edasi.";
    this.warning.hidden = false;
  };

  constructor(accessToken?: string, onFeatureSelect?: (cadastralCode: string) => void) {
    this.accessToken = accessToken;
    this.onFeatureSelect = onFeatureSelect;
  }

  onAdd(map: Map): HTMLElement {
    this.map = map;
    this.layerAdapter = new MetsaregisterMapLayerAdapter(map, this.onFeatureSelect, () => {
      if (this.warning) this.warning.hidden = true;
    });
    this.container = document.createElement("div");
    this.container.className = "maplibregl-ctrl relative text-xs";
    this.container.setAttribute("aria-label", "Kaardikihtide töölaud");

    this.toggle = document.createElement("button");
    this.toggle.type = "button";
    this.toggle.className = "grid h-10 w-10 place-items-center rounded-md border border-slate-200 bg-white text-lg font-semibold text-slate-700 shadow hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-slate-900";
    this.toggle.setAttribute("aria-label", "Ava kaardikihid");
    this.toggle.setAttribute("aria-expanded", "false");
    this.toggle.title = "Kaardikihid";
    this.toggle.textContent = "▧";
    this.toggle.addEventListener("click", () => this.setPanelOpen(!this.panel || this.panel.hidden));
    this.container.appendChild(this.toggle);

    this.panel = document.createElement("div");
    this.panel.className = "absolute left-0 top-12 max-h-[55vh] w-80 overflow-auto rounded-md border border-slate-200 bg-white p-2 text-xs shadow-xl";
    this.panel.hidden = true;

    const header = document.createElement("div");
    header.className = "mb-2 px-1 text-[11px] font-semibold uppercase tracking-wide text-slate-500";
    header.textContent = "Kaardikihid";
    this.panel.appendChild(header);

    this.warning = document.createElement("div");
    this.warning.className = "mb-2 rounded border border-amber-300 bg-amber-50 p-2 text-amber-900";
    this.warning.hidden = true;
    this.warning.setAttribute("role", "status");
    this.panel.appendChild(this.warning);

    this.body = document.createElement("div");
    const loading = document.createElement("div");
    loading.textContent = "Laadin kaardikihte…";
    loading.className = "text-slate-500";
    this.body.appendChild(loading);
    this.panel.appendChild(this.body);
    this.container.appendChild(this.panel);

    map.on("error", this.onSourceDataError);
    void this.loadRegistry();
    return this.container;
  }

  onRemove(): void {
    if (this.map) {
      this.map.off("error", this.onSourceDataError);
      this.layerAdapter?.removeAll();
    }
    this.container?.remove();
    this.map = undefined;
    this.container = undefined;
    this.panel = undefined;
    this.toggle = undefined;
    this.body = undefined;
    this.warning = undefined;
    this.layerAdapter = undefined;
    this.registry = undefined;
    this.contextLayers = [];
  }

  private setPanelOpen(open: boolean) {
    if (!this.panel || !this.toggle) return;
    this.panel.hidden = !open;
    this.toggle.setAttribute("aria-expanded", String(open));
    this.toggle.setAttribute("aria-label", open ? "Sulge kaardikihid" : "Ava kaardikihid");
  }


  /**
   * Reveals an already-approved context layer from parcel detail. Requests made before
   * descriptors finish loading are persisted and applied when the registry renders.
   */
  setContextLayerVisible(sourceKey: string, visible = true): void {
    this.setContextPreference(sourceKey, { visible });
    const descriptor = this.contextLayers.find(layer => layer.sourceKey === sourceKey);
    if (descriptor && this.map) {
      if (visible) this.layerAdapter?.addContextLayer(descriptor, this.contextPreference(descriptor).opacity);
      else this.layerAdapter?.removeContextLayer(sourceKey);
    }
    if (this.contextLayers.length > 0) this.renderRegistry();
  }

  private async loadRegistry() {
    if (!this.body) return;
    const headers = this.accessToken ? { Authorization: `Bearer ${this.accessToken}` } : undefined;
    const [registryResult, contextResult] = await Promise.allSettled([
      fetch("/api/v1/map/metsaregister/layers", { headers, cache: "no-store" }),
      fetch("/api/v1/map/context/layers", { headers, cache: "no-store" }),
    ]);

    if (registryResult.status === "fulfilled" && registryResult.value.ok) {
      this.registry = (await registryResult.value.json()) as LayerRegistry;
    }
    if (contextResult.status === "fulfilled" && contextResult.value.ok) {
      this.contextLayers = (await contextResult.value.json()) as ContextLayerDescriptor[];
    }

    if (!this.registry && this.contextLayers.length === 0) {
      if (!this.body) return;
      this.body.replaceChildren();
      const failure = document.createElement("div");
      failure.textContent = "Kaardikihtide loend ei ole hetkel kättesaadav.";
      failure.className = "rounded border border-amber-300 bg-amber-50 p-2 text-amber-900";
      this.body.appendChild(failure);
      return;
    }

    this.renderRegistry();
    if ((!this.registry || registryResult.status === "rejected" || !registryResult.value.ok) && this.warning) {
      this.warning.textContent = "Metsaregistri kihtide loend ei ole hetkel kättesaadav.";
      this.warning.hidden = false;
    }
    if ((contextResult.status === "rejected" || !contextResult.value.ok) && this.warning) {
      this.warning.textContent = this.warning.hidden
        ? "ETAK/haldusjaotuse kihtide loend pole hetkel kättesaadav."
        : `${this.warning.textContent} ETAK/haldusjaotuse kihtide loend pole hetkel kättesaadav.`;
      this.warning.hidden = false;
    }
  }

  private renderRegistry() {
    if (!this.body) return;
    this.body.replaceChildren();
    this.renderContextLayers();
    if (!this.registry) return;
    const groups = new globalThis.Map<string, MapLayerDescriptor[]>();
    for (const descriptor of this.registry.layers) {
      const values = groups.get(descriptor.category) ?? [];
      values.push(descriptor);
      groups.set(descriptor.category, values);
    }

    for (const descriptors of groups.values()) {
      for (const descriptor of this.ordered(descriptors)) {
        this.body.appendChild(this.layerRow(descriptor));
      }
    }
  }

  private renderContextLayers() {
    if (!this.body || this.contextLayers.length === 0) return;
    const grouped = new globalThis.Map<string, ContextLayerDescriptor[]>();
    for (const layer of this.contextLayers) {
      const items = grouped.get(layer.datasetFamily) ?? [];
      items.push(layer);
      grouped.set(layer.datasetFamily, items);
    }

    for (const layers of grouped.values()) {
      for (const descriptor of this.orderedContext(layers)) this.body.appendChild(this.contextLayerRow(descriptor));
    }
  }

  private layerRow(descriptor: MapLayerDescriptor) {
    const row = document.createElement("div");
    row.className = "border-b border-slate-100 py-1.5 last:border-b-0";
    row.dataset.layerKey = descriptor.key;

    const visible = descriptor.mandatory || this.preference(descriptor).visible;
    const top = document.createElement("div");
    top.className = "flex min-h-7 items-center gap-1.5";

    const visibility = document.createElement("button");
    visibility.type = "button";
    visibility.className = "grid h-7 w-7 shrink-0 place-items-center rounded text-[13px] text-slate-600 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-40";
    visibility.disabled = descriptor.mandatory || !descriptor.available;
    visibility.setAttribute("aria-label", `${descriptor.title} nähtavus`);
    visibility.setAttribute("aria-pressed", String(visible));
    visibility.title = visible ? "Peida kiht" : "Näita kihti";
    visibility.textContent = "👁";
    visibility.style.opacity = visible ? "1" : "0.45";
    visibility.addEventListener("click", () => {
      const next = !this.preference(descriptor).visible;
      this.setPreference(descriptor.key, { visible: next });
      if (next) this.layerAdapter?.addLayer(descriptor, this.preference(descriptor).opacity, this.ordered(this.registry?.layers ?? []));
      else this.layerAdapter?.removeLayer(descriptor.key);
      range.disabled = !next;
      visibility.setAttribute("aria-pressed", String(next));
      visibility.title = next ? "Peida kiht" : "Näita kihti";
      visibility.style.opacity = next ? "1" : "0.45";
    });

    const swatch = document.createElement("span");
    swatch.className = "h-3 w-3 shrink-0 rounded-sm border border-slate-300";
    swatch.style.background = registryLayerColor(descriptor);
    swatch.title = "Kaardil kasutatav värv";
    swatch.setAttribute("aria-label", `${descriptor.title} värv kaardil`);

    const text = document.createElement("span");
    text.className = "min-w-0 flex-1 truncate text-[12px] font-medium text-slate-800";
    text.textContent = descriptor.title;

    const info = document.createElement("button");
    info.type = "button";
    info.textContent = "?";
    info.className = "grid h-6 w-6 shrink-0 place-items-center rounded-full text-[11px] font-semibold text-slate-500 hover:bg-slate-100";
    info.setAttribute("aria-label", `${descriptor.title} info`);
    info.setAttribute("aria-expanded", "false");

    top.append(visibility, text, swatch, info);
    row.appendChild(top);

    const opacity = this.preference(descriptor).opacity;
    const range = document.createElement("input");
    range.type = "range";
    range.min = "0";
    range.max = "100";
    range.value = String(Math.round(opacity * 100));
    range.className = "mt-2 w-full";
    range.disabled = !visible || !descriptor.available;
    range.setAttribute("aria-label", `${descriptor.title} läbipaistvus`);
    range.addEventListener("input", () => {
      const value = Number(range.value) / 100;
      this.setPreference(descriptor.key, { opacity: value });
      this.layerAdapter?.applyOpacity(descriptor, value);
    });

    const meta = document.createElement("div");
    meta.className = "mt-2 text-[10px] leading-tight text-slate-500";
    const ownership = descriptor.category === "FORESTIQ_FORESTRY"
      ? "ForestIQ queryable data"
      : "väline kuvakiht";
    meta.textContent = `${ownership} · ${descriptor.transport} · zoom ${descriptor.minZoom}–${descriptor.maxZoom} · ${descriptor.freshnessTarget} · ${descriptor.attribution}`;

    const state = document.createElement("div");
    state.className = "mt-1 text-[10px] leading-tight text-slate-500";
    if (descriptor.category === "FORESTIQ_FORESTRY") {
      const freshness = descriptor.freshnessState === "STALE_USABLE"
        ? "kohalik koopia on aegunud, kuid kasutatav"
        : descriptor.freshnessState === "FRESH"
          ? "kohalik koopia värske"
          : descriptor.localAvailability === "UNAVAILABLE"
            ? "kohalik koopia puudub"
            : "kohaliku koopia värskus teadmata";
      const date = descriptor.sourceDate ? ` · allika kuupäev ${descriptor.sourceDate}` : "";
      state.textContent = `${freshness}${date}`;
      if (descriptor.freshnessState === "STALE_USABLE" || descriptor.localAvailability === "UNAVAILABLE") {
        state.className = "mt-1 text-[10px] leading-tight text-amber-700";
      }
    } else {
      const external = descriptor.externalAvailability === "UNAVAILABLE"
        ? "väline provider ei ole kättesaadav"
        : descriptor.externalAvailability === "AVAILABLE"
          ? "väline provider kättesaadav"
          : "välise provideri seisund pole veel kontrollitud";
      state.textContent = `${external} · ${descriptor.provider}`;
      if (descriptor.externalAvailability === "UNAVAILABLE") state.className = "mt-1 text-[10px] leading-tight text-amber-700";
    }
    const details = document.createElement("div");
    details.hidden = true;
    details.className = "mt-1.5 rounded border border-slate-100 bg-slate-50 px-2 py-1.5";
    details.append(range, meta, state);

    info.addEventListener("click", () => {
      details.hidden = !details.hidden;
      info.setAttribute("aria-expanded", String(!details.hidden));
    });
    row.appendChild(details);

    if (!descriptor.available) {
      const unavailable = document.createElement("div");
      unavailable.className = "mt-1 text-[10px] text-amber-700";
      unavailable.textContent = "Kiht ei ole veel avaldatud / hetkel saadaval.";
      details.appendChild(unavailable);
    }

    if (visible && descriptor.available) this.layerAdapter?.addLayer(descriptor, this.preference(descriptor).opacity, this.ordered(this.registry?.layers ?? []));
    return row;
  }

  private contextLayerRow(descriptor: ContextLayerDescriptor) {
    const row = document.createElement("div");
    row.className = "border-b border-slate-100 py-1.5 last:border-b-0";
    row.dataset.layerKey = descriptor.sourceKey;
    row.dataset.sourceKey = descriptor.sourceKey;

    const visible = this.contextPreference(descriptor).visible;
    const top = document.createElement("div");
    top.className = "flex min-h-7 items-center gap-1.5";

    const visibility = document.createElement("button");
    visibility.type = "button";
    visibility.className = "grid h-7 w-7 shrink-0 place-items-center rounded text-[13px] text-slate-600 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-40";
    visibility.disabled = !descriptor.available;
    visibility.setAttribute("aria-label", `${descriptor.title} nähtavus`);
    visibility.setAttribute("aria-pressed", String(visible));
    visibility.title = visible ? "Peida kiht" : "Näita kihti";
    visibility.textContent = "👁";
    visibility.style.opacity = visible ? "1" : "0.45";

    const swatch = document.createElement("span");
    swatch.className = "h-3 w-3 shrink-0 rounded-sm border border-slate-300";
    swatch.style.background = contextLayerColor(descriptor);
    swatch.title = "Kaardil kasutatav värv";
    swatch.setAttribute("aria-label", `${descriptor.title} värv kaardil`);

    const text = document.createElement("span");
    text.className = "min-w-0 flex-1 truncate text-[12px] font-medium text-slate-800";
    text.textContent = descriptor.title;

    const info = document.createElement("button");
    info.type = "button";
    info.textContent = "?";
    info.className = "grid h-6 w-6 shrink-0 place-items-center rounded-full text-[11px] font-semibold text-slate-500 hover:bg-slate-100";
    info.setAttribute("aria-label", `${descriptor.title} info`);
    info.setAttribute("aria-expanded", "false");

    top.append(visibility, text, swatch, info);
    row.appendChild(top);

    const range = document.createElement("input");
    range.type = "range";
    range.min = "0";
    range.max = "100";
    range.value = String(Math.round(this.contextPreference(descriptor).opacity * 100));
    range.disabled = !visible || !descriptor.available;
    range.className = "mt-2 w-full";
    range.setAttribute("aria-label", `${descriptor.title} läbipaistvus`);

    const meta = document.createElement("div");
    meta.className = "mt-2 text-[10px] leading-tight text-slate-500";
    meta.textContent = `${descriptor.geometryType} · zoom ${descriptor.minZoom}–${descriptor.maxZoom}`
      + (descriptor.sourceDate ? ` · andmeseis ${descriptor.sourceDate}` : "")
      + ` · ${descriptor.caveat}`;

    const details = document.createElement("div");
    details.hidden = true;
    details.className = "mt-1.5 rounded border border-slate-100 bg-slate-50 px-2 py-1.5";
    details.append(range, meta);

    if (!descriptor.available) {
      const unavailable = document.createElement("div");
      unavailable.className = "mt-1 text-[10px] text-amber-700";
      unavailable.textContent = "Avaldatud generatsioon puudub.";
      details.appendChild(unavailable);
    }

    visibility.addEventListener("click", () => {
      const next = !this.contextPreference(descriptor).visible;
      this.setContextPreference(descriptor.sourceKey, { visible: next });
      if (next) this.layerAdapter?.addContextLayer(descriptor, this.contextPreference(descriptor).opacity);
      else this.layerAdapter?.removeContextLayer(descriptor.sourceKey);
      range.disabled = !next;
      visibility.setAttribute("aria-pressed", String(next));
      visibility.title = next ? "Peida kiht" : "Näita kihti";
      visibility.style.opacity = next ? "1" : "0.45";
    });
    range.addEventListener("input", () => {
      const value = Number(range.value) / 100;
      this.setContextPreference(descriptor.sourceKey, { opacity: value });
      this.layerAdapter?.applyContextOpacity(descriptor, value);
    });
    info.addEventListener("click", () => {
      details.hidden = !details.hidden;
      info.setAttribute("aria-expanded", String(!details.hidden));
    });
    row.appendChild(details);

    if (visible && descriptor.available) this.layerAdapter?.addContextLayer(descriptor, this.contextPreference(descriptor).opacity);
    return row;
  }

  private ordered(layers: MapLayerDescriptor[]) {
    return orderedByLayerPreference(layers, this.preferences);
  }

  private orderedContext(layers: ContextLayerDescriptor[]) {
    return orderedByContextPreference(layers, this.contextPreferences);
  }

  private preference(descriptor: MapLayerDescriptor) {
    return layerPreference(descriptor, this.preferences);
  }

  private setPreference(key: string, patch: LayerPreference) {
    this.preferences = writePreferencePatch(MAP_LAYER_PREFERENCE_KEY, this.preferences, key, patch);
  }

  private readPreferences(): LayerPreferences {
    return readPreferenceStore<LayerPreferences>(MAP_LAYER_PREFERENCE_KEY);
  }


  private contextPreference(descriptor: ContextLayerDescriptor) {
    const defaultOrder = this.contextLayers.findIndex(layer => layer.sourceKey === descriptor.sourceKey);
    return contextLayerPreference(descriptor, this.contextPreferences, defaultOrder >= 0 ? defaultOrder : 0);
  }

  private setContextPreference(sourceKey: string, patch: ContextLayerPreference) {
    this.contextPreferences = writePreferencePatch(CONTEXT_LAYER_PREFERENCE_KEY, this.contextPreferences, sourceKey, patch);
  }

  private readContextPreferences(): ContextLayerPreferences {
    return readPreferenceStore<ContextLayerPreferences>(CONTEXT_LAYER_PREFERENCE_KEY);
  }
}
