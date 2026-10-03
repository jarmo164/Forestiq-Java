// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  type LayerPreferences,
  MAP_LAYER_PREFERENCE_KEY,
  clampOpacity,
  contextLayerPreference,
  layerPreference,
  orderedByContextPreference,
  orderedByLayerPreference,
  readPreferenceStore,
  writePreferencePatch,
} from "./metsaregister-overlay-preferences";

const layer = {
  key: "metsaregister:teatis",
  title: "Teatis",
  defaultVisible: false,
  defaultOpacity: 0.7,
  order: 10,
};

beforeEach(() => {
  window.localStorage.clear();
});

describe("Metsaregister overlay preferences", () => {
  it("clamps opacity and keeps mandatory layers visible", () => {
    expect(clampOpacity(2, 0.5)).toBe(1);
    expect(clampOpacity(-1, 0.5)).toBe(0);
    expect(layerPreference({ ...layer, mandatory: true }, {
      [layer.key]: { visible: false, opacity: 2, order: 3 },
    })).toEqual({ visible: true, opacity: 1, order: 3 });
  });

  it("orders map and context layers through pure preference helpers", () => {
    expect(orderedByLayerPreference([
      { ...layer, key: "b", title: "B", order: 2 },
      { ...layer, key: "a", title: "A", order: 1 },
    ], { b: { order: 0 } }).map(item => item.key)).toEqual(["b", "a"]);

    expect(orderedByContextPreference([
      { sourceKey: "later", title: "Later", defaultOpacity: 0.3 },
      { sourceKey: "first", title: "First", defaultOpacity: 0.3 },
    ], { first: { order: -1 } }).map(item => item.sourceKey)).toEqual(["first", "later"]);
  });

  it("reads and writes preference patches defensively", () => {
    const next = writePreferencePatch<LayerPreferences>(MAP_LAYER_PREFERENCE_KEY, {}, layer.key, { visible: true });

    expect(next[layer.key]).toEqual({ visible: true });
    expect(readPreferenceStore(MAP_LAYER_PREFERENCE_KEY)).toEqual(next);

    vi.spyOn(Storage.prototype, "setItem").mockImplementationOnce(() => {
      throw new Error("blocked");
    });
    expect(writePreferencePatch(MAP_LAYER_PREFERENCE_KEY, next, layer.key, { opacity: 0.4 })[layer.key])
      .toEqual({ visible: true, opacity: 0.4 });
  });

  it("builds context defaults from caller-provided order", () => {
    expect(contextLayerPreference({ sourceKey: "etak", title: "ETAK", defaultOpacity: 0.28 }, {}, 7))
      .toEqual({ visible: false, opacity: 0.28, order: 7 });
  });
});
