export type DetailPolygonGeometry =
  | { type: "Polygon"; coordinates: number[][][] }
  | { type: "MultiPolygon"; coordinates: number[][][][] };

export type MapDetailGeometrySelection = {
  id: string;
  label: string;
  geometry: DetailPolygonGeometry;
};

function isPosition(value: unknown): value is number[] {
  return Array.isArray(value)
    && value.length >= 2
    && value.every(item => typeof item === "number" && Number.isFinite(item));
}

function isPolygonCoordinates(value: unknown): value is number[][][] {
  return Array.isArray(value)
    && value.length > 0
    && value.every(ring => Array.isArray(ring) && ring.length >= 4 && ring.every(isPosition));
}

function isMultiPolygonCoordinates(value: unknown): value is number[][][][] {
  return Array.isArray(value) && value.length > 0 && value.every(isPolygonCoordinates);
}

function fromGeoJson(value: unknown): DetailPolygonGeometry | null {
  if (!value || typeof value !== "object") return null;
  const candidate = value as { type?: string; geometry?: unknown; coordinates?: unknown };
  if (candidate.type === "Feature") return fromGeoJson(candidate.geometry);
  if (candidate.type === "Polygon" && isPolygonCoordinates(candidate.coordinates)) {
    return { type: "Polygon", coordinates: candidate.coordinates };
  }
  if (candidate.type === "MultiPolygon" && isMultiPolygonCoordinates(candidate.coordinates)) {
    return { type: "MultiPolygon", coordinates: candidate.coordinates };
  }
  return null;
}

function stripOuterParentheses(value: string): string {
  const trimmed = value.trim();
  if (!trimmed.startsWith("(") || !trimmed.endsWith(")")) return trimmed;
  let depth = 0;
  for (let index = 0; index < trimmed.length; index += 1) {
    const char = trimmed[index];
    if (char === "(") depth += 1;
    if (char === ")") depth -= 1;
    if (depth === 0 && index < trimmed.length - 1) return trimmed;
  }
  return trimmed.slice(1, -1).trim();
}

function splitTopLevel(value: string): string[] {
  const result: string[] = [];
  let depth = 0;
  let start = 0;
  for (let index = 0; index < value.length; index += 1) {
    const char = value[index];
    if (char === "(") depth += 1;
    else if (char === ")") depth -= 1;
    else if (char === "," && depth === 0) {
      result.push(value.slice(start, index).trim());
      start = index + 1;
    }
  }
  result.push(value.slice(start).trim());
  return result.filter(Boolean);
}

function parsePosition(value: string): number[] | null {
  const parts = value.trim().split(/\s+/).map(Number);
  if (parts.length < 2 || !parts.every(Number.isFinite)) return null;
  return parts.slice(0, 2);
}

function parseRing(value: string): number[][] | null {
  const positions = splitTopLevel(stripOuterParentheses(value)).map(parsePosition);
  if (positions.some(position => position === null)) return null;
  const ring = positions as number[][];
  return ring.length >= 4 ? ring : null;
}

function parsePolygonBody(value: string): number[][][] | null {
  const rings = splitTopLevel(stripOuterParentheses(value)).map(parseRing);
  if (rings.some(ring => ring === null)) return null;
  return rings as number[][][];
}

function fromWkt(raw: string): DetailPolygonGeometry | null {
  const normalized = raw.trim();
  const polygon = normalized.match(/^POLYGON\s*(\(.+\))$/is);
  if (polygon) {
    const coordinates = parsePolygonBody(polygon[1]);
    return coordinates ? { type: "Polygon", coordinates } : null;
  }

  const multiPolygon = normalized.match(/^MULTIPOLYGON\s*(\(.+\))$/is);
  if (!multiPolygon) return null;
  const polygonBodies = splitTopLevel(stripOuterParentheses(multiPolygon[1]));
  const coordinates = polygonBodies.map(parsePolygonBody);
  if (coordinates.some(item => item === null)) return null;
  return { type: "MultiPolygon", coordinates: coordinates as number[][][][] };
}

/**
 * Parses only polygonal geometry returned by bounded parcel-detail endpoints.
 * Unknown/invalid payloads deliberately fail closed instead of placing bad geometry on the map.
 */
export function parseDetailGeometry(raw?: string | null): DetailPolygonGeometry | null {
  if (!raw?.trim()) return null;
  const trimmed = raw.trim();
  if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
    try {
      return fromGeoJson(JSON.parse(trimmed));
    } catch {
      return null;
    }
  }
  return fromWkt(trimmed);
}
