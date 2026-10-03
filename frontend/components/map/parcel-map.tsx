"use client";

import { useEffect, useMemo, useRef } from "react";
import maplibregl, { type Map } from "maplibre-gl";
import { MaaruumBasemapControl } from "@/components/map/maaruum-basemap-control";
import { MetsaregisterOverlayControl } from "@/components/map/metsaregister-overlay-control";
import { ParcelTileLimitControl } from "@/components/map/parcel-tile-limit-control";
import { useMapGeneration } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { MapDetailGeometrySelection } from "@/lib/map/detail-geometry";
import type { LandParcel } from "@/lib/types";

type SelectedParcelLocation = {
  west?: number | null;
  south?: number | null;
  east?: number | null;
  north?: number | null;
  longitude?: number | null;
  latitude?: number | null;
};

type SearchFocusLocation = {
  longitude: number;
  latitude: number;
};

type ContextLayerRequest = {
  sourceKey: string;
  requestId: number;
};

type ParcelMapProps = {
  parcels?: LandParcel[];
  cadastralCodes?: string[];
  className?: string;
  onParcelClick?: (cadastralCode: string) => void;
  statusFilter?: string;
  selectedCadastralCode?: string;
  selectedParcelLocation?: SelectedParcelLocation;
  searchFocusLocation?: SearchFocusLocation;
  detailGeometry?: MapDetailGeometrySelection | null;
  contextLayerRequest?: ContextLayerRequest | null;
  dataVersion?: number;
};

const OVERVIEW_MIN_ZOOM = 6;
const DETAIL_MIN_ZOOM = 11;
const LABEL_MIN_ZOOM = 13;
const DETAIL_HIGHLIGHT_SOURCE = "parcel-detail-highlight";
const DETAIL_HIGHLIGHT_FILL = "parcel-detail-highlight-fill";
const DETAIL_HIGHLIGHT_LINE = "parcel-detail-highlight-line";

/** Neutral style: approved basemaps are added only through the ForestIQ proxy control. */
function blankStyle(): maplibregl.StyleSpecification {
  return {
    version: 8,
    sources: {},
    layers: [{
      id: "forestiq-map-background",
      type: "background",
      paint: { "background-color": "#eef2f3" },
    }],
  };
}

/**
 * generationKey is a cache-buster learned from the server. The backend owns source selection;
 * the client can never choose a staging generation.
 */
function parcelTiles(generationKey: string) {
  return [`${window.location.origin}/api/v1/map/land-parcels/{z}/{x}/{y}.pbf?generation=${encodeURIComponent(generationKey)}`];
}

function parcelOverviewTiles(generationKey: string) {
  return [`${window.location.origin}/api/v1/map/land-parcels-overview/{z}/{x}/{y}.pbf?generation=${encodeURIComponent(generationKey)}`];
}

const ownerStatusColor: maplibregl.ExpressionSpecification = [
  "match", ["get", "owner_status"],
  "CONTRACTED", "#15803d", "NEGOTIATION", "#d97706", "VALUED", "#0f766e",
  "EVALUATION", "#2563eb", "WAITS_FOR_EVALUATION", "#7c3aed", "INTERESTED", "#16a34a",
  "CONTACTED", "#f59e0b", "ASSIGNED", "#0f766e", "NEW", "#64748b",
  "NOT_INTERESTED", "#94a3b8", "DECEASED", "#4b5563", "#64748b",
];

export function ParcelMap({
  parcels = [],
  cadastralCodes,
  className = "h-[520px]",
  onParcelClick,
  statusFilter,
  selectedCadastralCode,
  selectedParcelLocation,
  searchFocusLocation,
  detailGeometry,
  contextLayerRequest,
  dataVersion = 0,
}: ParcelMapProps) {
  const { accessToken } = useAuth();
  const generationQuery = useMapGeneration(dataVersion);
  const generationKey = useMemo(() => {
    const info = generationQuery.data;
    if (!info?.available || !info.generationId) return "unavailable";
    return `${info.generationId}:r${info.representationVersion}`;
  }, [generationQuery.data]);

  const container = useRef<HTMLDivElement>(null);
  const map = useRef<Map | null>(null);
  const markers = useRef<maplibregl.Marker[]>([]);
  const layerControl = useRef<MetsaregisterOverlayControl | null>(null);
  const onParcelClickRef = useRef(onParcelClick);
  const initialGenerationKey = useRef(generationKey);

  useEffect(() => {
    onParcelClickRef.current = onParcelClick;
  }, [onParcelClick]);

  useEffect(() => {
    if (generationKey === "unavailable") return;
    if (!container.current || map.current) return;
    initialGenerationKey.current = generationKey;
    map.current = new maplibregl.Map({
      container: container.current,
      style: blankStyle(),
      center: [25.5, 58.7],
      zoom: 7,
      transformRequest: (url) =>
        url.includes("/api/v1/map/")
          ? { url, headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined }
          : { url },
    });
    const workspaceControl = new MetsaregisterOverlayControl(
      accessToken,
      (cadastralCode) => onParcelClickRef.current?.(cadastralCode),
    );
    layerControl.current = workspaceControl;
    // Keep MapLibre navigation above the expandable layer workspace. If the
    // layer control is added first, its absolute panel opens underneath the
    // navigation group and pointer events are intercepted by zoom controls.
    map.current.addControl(new maplibregl.NavigationControl(), "top-left");
    map.current.addControl(workspaceControl, "top-left");
    map.current.addControl(new MaaruumBasemapControl(), "bottom-right");
    map.current.addControl(new ParcelTileLimitControl(accessToken, () => initialGenerationKey.current), "bottom-right");

    map.current.on("load", () => {
      const currentMap = map.current;
      if (!currentMap || currentMap.getSource("parcels")) return;

      currentMap.addSource("parcels-overview", {
        type: "vector",
        tiles: parcelOverviewTiles(initialGenerationKey.current),
        minzoom: OVERVIEW_MIN_ZOOM,
        maxzoom: DETAIL_MIN_ZOOM - 1,
      });
      currentMap.addLayer({
        id: "parcels-overview",
        type: "circle",
        source: "parcels-overview",
        "source-layer": "land_parcel_overview",
        minzoom: OVERVIEW_MIN_ZOOM,
        maxzoom: DETAIL_MIN_ZOOM,
        paint: {
          "circle-color": ownerStatusColor,
          "circle-radius": [
            "interpolate", ["linear"], ["get", "parcel_count"],
            1, 4,
            10, 7,
            100, 11,
            1000, 16,
          ],
          "circle-opacity": 0.68,
          "circle-stroke-color": "#ffffff",
          "circle-stroke-width": 1,
        },
      });

      currentMap.addSource("parcels", {
        type: "vector",
        tiles: parcelTiles(initialGenerationKey.current),
        minzoom: DETAIL_MIN_ZOOM,
        maxzoom: 22,
      });
      currentMap.addLayer({
        id: "parcels-fill",
        type: "fill",
        source: "parcels",
        "source-layer": "land_parcel",
        minzoom: DETAIL_MIN_ZOOM,
        paint: {
          "fill-color": ownerStatusColor,
          "fill-opacity": ["case", ["==", ["get", "marked"], true], 0.55, 0.30],
        },
      });
      currentMap.addLayer({
        id: "parcels-line",
        type: "line",
        source: "parcels",
        "source-layer": "land_parcel",
        minzoom: DETAIL_MIN_ZOOM,
        paint: { "line-color": "#1f5c42", "line-width": 1.5 },
      });
      currentMap.addLayer({
        id: "parcels-selected",
        type: "line",
        source: "parcels",
        "source-layer": "land_parcel",
        minzoom: DETAIL_MIN_ZOOM,
        filter: ["==", ["get", "cadastral_code"], ""],
        paint: { "line-color": "#0f172a", "line-width": 4 },
      });
      currentMap.addLayer({
        id: "parcels-label",
        type: "symbol",
        source: "parcels",
        "source-layer": "land_parcel",
        minzoom: LABEL_MIN_ZOOM,
        layout: {
          "text-field": ["get", "cadastral_code"],
          "text-size": 11,
          "text-offset": [0, -1.2],
          "text-anchor": "bottom",
        },
        paint: {
          "text-color": "#1f5c42",
          "text-halo-color": "#ffffff",
          "text-halo-width": 1.5,
        },
      });

      currentMap.addSource(DETAIL_HIGHLIGHT_SOURCE, {
        type: "geojson",
        data: { type: "FeatureCollection", features: [] },
      });
      currentMap.addLayer({
        id: DETAIL_HIGHLIGHT_FILL,
        type: "fill",
        source: DETAIL_HIGHLIGHT_SOURCE,
        paint: { "fill-color": "#0f172a", "fill-opacity": 0.16 },
      });
      currentMap.addLayer({
        id: DETAIL_HIGHLIGHT_LINE,
        type: "line",
        source: DETAIL_HIGHLIGHT_SOURCE,
        paint: { "line-color": "#0f172a", "line-width": 3, "line-dasharray": [2, 1] },
      });

      currentMap.on("click", "parcels-overview", (event) => {
        currentMap.easeTo({ center: event.lngLat, zoom: Math.max(DETAIL_MIN_ZOOM + 1, currentMap.getZoom() + 2), duration: 500 });
      });
      currentMap.on("click", "parcels-fill", (e) => {
        if (onParcelClickRef.current && e.features?.[0]?.properties?.cadastral_code) {
          onParcelClickRef.current(e.features[0].properties.cadastral_code);
        }
      });
      ["parcels-overview", "parcels-fill"].forEach(layer => {
        currentMap.on("mouseenter", layer, () => {
          if (currentMap.getCanvas()) currentMap.getCanvas().style.cursor = "pointer";
        });
        currentMap.on("mouseleave", layer, () => {
          if (currentMap.getCanvas()) currentMap.getCanvas().style.cursor = "";
        });
      });
    });

    return () => {
      markers.current.forEach((marker) => marker.remove());
      markers.current = [];
      layerControl.current = null;
      map.current?.remove();
      map.current = null;
    };
  }, [accessToken, generationKey]);

  useEffect(() => {
    const applyFilter = () => {
      const currentMap = map.current;
      if (!currentMap || !currentMap.getLayer("parcels-fill")) return;
      const statusExpression: maplibregl.FilterSpecification | null = statusFilter === "__NO_OWNER__"
        ? ["!", ["has", "owner_status"]]
        : statusFilter
          ? ["==", ["get", "owner_status"], statusFilter]
          : null;
      const ownerExpression: maplibregl.FilterSpecification | null = cadastralCodes
        ? cadastralCodes.length > 0
          ? ["in", ["get", "cadastral_code"], ["literal", cadastralCodes]]
          : ["==", ["get", "cadastral_code"], "__NO_OWNER_PARCELS__"]
        : null;
      const filter: maplibregl.FilterSpecification | null = statusExpression && ownerExpression
        ? ["all", statusExpression, ownerExpression]
        : statusExpression ?? ownerExpression;
      ["parcels-fill", "parcels-line", "parcels-label"].forEach(layer => {
        if (currentMap.getLayer(layer)) currentMap.setFilter(layer, filter);
      });
      if (currentMap.getLayer("parcels-overview")) {
        currentMap.setLayoutProperty("parcels-overview", "visibility", cadastralCodes ? "none" : "visible");
      }
    };
    if (map.current?.isStyleLoaded()) applyFilter();
    else map.current?.once("load", applyFilter);
  }, [statusFilter, cadastralCodes]);

  useEffect(() => {
    const applySelection = () => {
      const currentMap = map.current;
      if (!currentMap || !currentMap.getLayer("parcels-selected")) return;
      currentMap.setFilter("parcels-selected", ["==", ["get", "cadastral_code"], selectedCadastralCode ?? ""]);
    };
    if (map.current?.isStyleLoaded()) applySelection();
    else map.current?.once("load", applySelection);
  }, [selectedCadastralCode]);

  useEffect(() => {
    const applyDetailGeometry = () => {
      const source = map.current?.getSource(DETAIL_HIGHLIGHT_SOURCE) as maplibregl.GeoJSONSource | undefined;
      if (!source) return;
      source.setData(detailGeometry ? {
        type: "Feature",
        properties: { id: detailGeometry.id, label: detailGeometry.label },
        geometry: detailGeometry.geometry,
      } : { type: "FeatureCollection", features: [] });
    };
    if (map.current?.isStyleLoaded()) applyDetailGeometry();
    else map.current?.once("load", applyDetailGeometry);
  }, [detailGeometry]);

  useEffect(() => {
    if (!contextLayerRequest) return;
    layerControl.current?.setContextLayerVisible(contextLayerRequest.sourceKey, true);
  }, [contextLayerRequest]);

  useEffect(() => {
    if (generationKey === initialGenerationKey.current) return;
    const reloadTiles = () => {
      const currentMap = map.current;
      const overviewSource = currentMap?.getSource("parcels-overview") as maplibregl.VectorTileSource | undefined;
      overviewSource?.setTiles(parcelOverviewTiles(generationKey));
      const source = currentMap?.getSource("parcels") as maplibregl.VectorTileSource | undefined;
      source?.setTiles(parcelTiles(generationKey));
      initialGenerationKey.current = generationKey;
    };
    if (map.current?.isStyleLoaded()) reloadTiles();
    else map.current?.once("load", reloadTiles);
  }, [generationKey]);

  useEffect(() => {
    const currentMap = map.current;
    if (!selectedCadastralCode) {
      container.current?.removeAttribute("data-focused-cadastral-code");
      return;
    }
    if (!currentMap || !selectedParcelLocation) return;
    const { west, south, east, north, longitude, latitude } = selectedParcelLocation;
    const validBounds = [west, south, east, north].every(value => typeof value === "number" && Number.isFinite(value));
    if (validBounds) {
      const padding = window.innerWidth >= 1280
        ? { top: 64, bottom: 64, left: 64, right: 520 }
        : 64;
      currentMap.fitBounds([[west!, south!], [east!, north!]], { padding, maxZoom: 16, duration: 650 });
      container.current?.setAttribute("data-focused-cadastral-code", selectedCadastralCode);
      return;
    }
    if (typeof longitude === "number" && typeof latitude === "number") {
      currentMap.flyTo({ center: [longitude, latitude], zoom: Math.max(currentMap.getZoom(), 14), duration: 650 });
      container.current?.setAttribute("data-focused-cadastral-code", selectedCadastralCode);
    }
  }, [selectedCadastralCode, selectedParcelLocation]);

  useEffect(() => {
    if (!map.current || !searchFocusLocation) return;
    map.current.flyTo({
      center: [searchFocusLocation.longitude, searchFocusLocation.latitude],
      zoom: Math.max(map.current.getZoom(), 15),
      duration: 650,
    });
  }, [searchFocusLocation]);

  useEffect(() => {
    markers.current.forEach((marker) => marker.remove());
    markers.current = [];
    if (!map.current || parcels.length === 0) return;
    const bounds = new maplibregl.LngLatBounds();
    parcels.forEach((parcel) => {
      if (!parcel.centroid || typeof parcel.centroid.longitude !== "number" || typeof parcel.centroid.latitude !== "number") return;
      const popup = new maplibregl.Popup({ offset: 25 }).setHTML(
        `<strong>${parcel.cadastralCode}</strong><br/>${parcel.areaHectares.toFixed(2)} ha` +
          (parcel.name ? `<br/>${parcel.name}` : "") +
          (parcel.county ? `<br/>${parcel.county}` : "")
      );
      const marker = new maplibregl.Marker({ color: parcel.marked ? "#dc2626" : "#b45309" })
        .setLngLat([parcel.centroid.longitude, parcel.centroid.latitude])
        .setPopup(popup)
        .addTo(map.current!);
      if (onParcelClickRef.current) {
        marker.getElement().addEventListener("click", () => onParcelClickRef.current?.(parcel.cadastralCode));
      }
      markers.current.push(marker);
      bounds.extend([parcel.centroid.longitude, parcel.centroid.latitude]);
    });
    if (!bounds.isEmpty()) map.current.fitBounds(bounds, { padding: 48, maxZoom: 14 });
    return () => {
      markers.current.forEach((marker) => marker.remove());
      markers.current = [];
    };
  }, [parcels]);

  return (
    <div
      ref={container}
      className={`overflow-hidden rounded-lg border ${className}`}
      aria-label="Kinnistute kaart"
      data-selected-cadastral-code={selectedCadastralCode ?? ""}
      data-detail-geometry-id={detailGeometry?.id ?? ""}
      data-map-version={dataVersion}
      data-map-generation={generationKey}
      data-map-detail-minzoom={DETAIL_MIN_ZOOM}
    />
  );
}
