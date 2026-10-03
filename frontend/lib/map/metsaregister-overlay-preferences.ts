export type LayerPreference = {
  visible?: boolean;
  opacity?: number;
  order?: number;
};

export type LayerPreferences = Record<string, LayerPreference>;
export type ContextLayerPreference = LayerPreference;
export type ContextLayerPreferences = Record<string, ContextLayerPreference>;

export type LayerPreferenceDescriptor = {
  key: string;
  title: string;
  defaultVisible: boolean;
  defaultOpacity: number;
  order: number;
  mandatory?: boolean;
};

export type ContextLayerPreferenceDescriptor = {
  sourceKey: string;
  title: string;
  defaultOpacity: number;
};

export const MAP_LAYER_PREFERENCE_KEY = "forestiq.map-layer-preferences.v1";
export const CONTEXT_LAYER_PREFERENCE_KEY = "forestiq.etak-admin-overlays.v1";

export function clampOpacity(value: number | undefined, fallback: number): number {
  return Math.max(0, Math.min(1, value ?? fallback));
}

export function layerPreference(descriptor: LayerPreferenceDescriptor, preferences: LayerPreferences): Required<LayerPreference> {
  const saved = preferences[descriptor.key] ?? {};
  return {
    visible: descriptor.mandatory ? true : saved.visible ?? descriptor.defaultVisible,
    opacity: clampOpacity(saved.opacity, descriptor.defaultOpacity),
    order: saved.order ?? descriptor.order,
  };
}

export function contextLayerPreference(
  descriptor: ContextLayerPreferenceDescriptor,
  preferences: ContextLayerPreferences,
  defaultOrder: number,
): Required<ContextLayerPreference> {
  const saved = preferences[descriptor.sourceKey] ?? {};
  return {
    visible: saved.visible ?? false,
    opacity: clampOpacity(saved.opacity, descriptor.defaultOpacity),
    order: saved.order ?? defaultOrder,
  };
}

export function orderedByLayerPreference<T extends LayerPreferenceDescriptor>(layers: T[], preferences: LayerPreferences): T[] {
  return [...layers].sort((a, b) =>
    layerPreference(a, preferences).order - layerPreference(b, preferences).order || a.title.localeCompare(b.title));
}

export function orderedByContextPreference<T extends ContextLayerPreferenceDescriptor>(
  layers: T[],
  preferences: ContextLayerPreferences,
): T[] {
  return [...layers].sort((a, b) =>
    contextLayerPreference(a, preferences, contextDefaultOrder(layers, a.sourceKey)).order
    - contextLayerPreference(b, preferences, contextDefaultOrder(layers, b.sourceKey)).order
    || a.title.localeCompare(b.title));
}

export function contextDefaultOrder<T extends { sourceKey: string }>(layers: T[], sourceKey: string): number {
  const index = layers.findIndex(layer => layer.sourceKey === sourceKey);
  return index >= 0 ? index : 0;
}

export function readPreferenceStore<T extends Record<string, LayerPreference>>(key: string): T {
  if (typeof window === "undefined") return {} as T;
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? JSON.parse(raw) as T : {} as T;
  } catch {
    return {} as T;
  }
}

export function writePreferencePatch<T extends Record<string, LayerPreference>>(
  key: string,
  preferences: T,
  preferenceKey: string,
  patch: LayerPreference,
): T {
  const next = {
    ...preferences,
    [preferenceKey]: { ...preferences[preferenceKey], ...patch },
  };
  try {
    window.localStorage.setItem(key, JSON.stringify(next));
  } catch {
    // Storage may be unavailable in private/restricted browser contexts; map use must still work.
  }
  return next as T;
}
