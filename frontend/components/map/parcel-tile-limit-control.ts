import type { IControl, Map as MapLibreMap } from "maplibre-gl";
import { firstTruncatedTile, visibleTileCoordinates, type TileLimitInfo } from "@/lib/map/tile-limits";

const DETAIL_MIN_ZOOM = 11;

/** MapLibre control that surfaces server-side parcel feature caps for the current viewport. */
export class ParcelTileLimitControl implements IControl {
  private map?: MapLibreMap;
  private container?: HTMLDivElement;
  private requestSerial = 0;

  constructor(
    private readonly accessToken: string | undefined,
    private readonly generationKey: () => string,
  ) {}

  onAdd(map: MapLibreMap): HTMLElement {
    this.map = map;
    this.container = document.createElement("div");
    this.container.className = "maplibregl-ctrl max-w-80 rounded-md border border-amber-300 bg-amber-50 p-3 text-xs text-amber-950 shadow";
    this.container.setAttribute("role", "status");
    this.container.setAttribute("aria-live", "polite");
    this.container.hidden = true;
    map.on("moveend", this.refresh);
    window.requestAnimationFrame(() => void this.refresh());
    return this.container;
  }

  onRemove(): void {
    this.requestSerial += 1;
    this.map?.off("moveend", this.refresh);
    this.container?.remove();
    this.map = undefined;
    this.container = undefined;
  }

  private readonly refresh = async () => {
    const map = this.map;
    const container = this.container;
    if (!map || !container) return;
    const zoom = Math.floor(map.getZoom());
    const generation = this.generationKey();
    if (zoom < DETAIL_MIN_ZOOM || generation === "unavailable") {
      container.hidden = true;
      return;
    }

    const bounds = map.getBounds();
    const coordinates = visibleTileCoordinates({
      west: bounds.getWest(),
      south: bounds.getSouth(),
      east: bounds.getEast(),
      north: bounds.getNorth(),
    }, zoom);
    const serial = ++this.requestSerial;

    try {
      const results = await Promise.all(coordinates.map(async tile => {
        const response = await fetch(
          `/api/v1/map/land-parcels/${tile.z}/${tile.x}/${tile.y}/meta?generation=${encodeURIComponent(generation)}`,
          { headers: this.accessToken ? { Authorization: `Bearer ${this.accessToken}` } : undefined },
        );
        if (!response.ok) return undefined;
        return await response.json() as TileLimitInfo;
      }));
      if (serial !== this.requestSerial || !this.container) return;
      const truncated = firstTruncatedTile(results.filter((result): result is TileLimitInfo => Boolean(result)));
      if (!truncated) {
        this.container.hidden = true;
        return;
      }
      this.container.innerHTML = "<strong>Kinnistute detailkiht on selles vaates piiratud.</strong><br>Suurenda kaarti või täpsusta filtrit/otsingut, et näha kõiki objekte.";
      this.container.hidden = false;
    } catch {
      // A metadata probe must never make the map itself unusable.
    }
  };
}
