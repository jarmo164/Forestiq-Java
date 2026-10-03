export type TileCoordinate = { z: number; x: number; y: number };
export type TileLimitInfo = {
  layer: string;
  zoom: number;
  featureLimit: number;
  observedFeatures: number;
  truncated: boolean;
};

type BoundsLike = { west: number; south: number; east: number; north: number };

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function lonToTileX(longitude: number, zoom: number) {
  const n = 2 ** zoom;
  return clamp(Math.floor(((longitude + 180) / 360) * n), 0, n - 1);
}

function latToTileY(latitude: number, zoom: number) {
  const n = 2 ** zoom;
  const latitudeRadians = (clamp(latitude, -85.05112878, 85.05112878) * Math.PI) / 180;
  const value = (1 - Math.asinh(Math.tan(latitudeRadians)) / Math.PI) / 2;
  return clamp(Math.floor(value * n), 0, n - 1);
}

export function visibleTileCoordinates(bounds: BoundsLike, zoom: number, maxTiles = 32): TileCoordinate[] {
  const z = Math.max(0, Math.floor(zoom));
  const minX = lonToTileX(bounds.west, z);
  const maxX = lonToTileX(bounds.east, z);
  const minY = latToTileY(bounds.north, z);
  const maxY = latToTileY(bounds.south, z);
  const result: TileCoordinate[] = [];
  for (let x = minX; x <= maxX && result.length < maxTiles; x += 1) {
    for (let y = minY; y <= maxY && result.length < maxTiles; y += 1) result.push({ z, x, y });
  }
  return result;
}

export function firstTruncatedTile(results: TileLimitInfo[]) {
  return results.find(result => result.truncated);
}
