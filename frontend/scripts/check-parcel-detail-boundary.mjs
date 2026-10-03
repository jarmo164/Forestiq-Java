import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

const root = resolve(import.meta.dirname, "..");
const source = await readFile(resolve(root, "lib/api/map-cadastre.ts"), "utf8");

const start = source.indexOf("export type CompleteMapParcelSummary");
const end = source.indexOf("export type CompleteCadastreNotification");
if (start < 0 || end < 0 || end <= start) {
  throw new Error("CompleteMapParcelSummary contract block was not found.");
}

const summary = source.slice(start, end);
const forbidden = [
  "notifications?:",
  "subParts?:",
  "forestFeatures?:",
  "geometry?:",
  "attributes?:",
];

for (const token of forbidden) {
  if (summary.includes(token)) {
    throw new Error(
      `Parcel overview became unbounded: CompleteMapParcelSummary contains ${token}`,
    );
  }
}

for (const token of [
  "owners?: CompleteMapParcelOwnerSummary[]",
  "generationId?:",
  "sourceDate?:",
  "ownerCount?:",
  "subPartCount?:",
  "activeNotificationCount?:",
  "forestFeatureCount?:",
]) {
  if (!summary.includes(token)) {
    throw new Error(`Parcel overview contract lost required bounded field: ${token}`);
  }
}

const requiredPagedHooks = [
  "useCadastreAllNotificationPages",
  "useCadastreNotificationPages",
  "useCadastreNotificationArchivePages",
  "useCadastreForestFeaturePages",
  "useCadastreSubPartPages",
];
for (const hook of requiredPagedHooks) {
  if (!source.includes(`export function ${hook}`)) {
    throw new Error(`Missing bounded parcel-detail hook: ${hook}`);
  }
}

for (const limit of ['limit: "20"', 'limit: "50"']) {
  if (!source.includes(limit)) {
    throw new Error(`Parcel-detail paging limit contract drifted: ${limit}`);
  }
}

if (!source.includes("useCadastreForestFeatureRaw")) {
  throw new Error("Forest feature raw payload must remain explicit/on-demand.");
}
if (!source.includes("useCadastreSubPartGeometry")) {
  throw new Error("Sub-part geometry must remain explicit/on-demand.");
}

console.log("Parcel detail bounded read-model contract is valid.");
