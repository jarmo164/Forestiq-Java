const STATUS_LABELS: Record<string, string> = {
  NEW: "Uus",
  CONTACTED: "Kontakteeritud",
  INTERESTED: "Huvitatud",
  NOT_INTERESTED: "Ei huvita",
  EVALUATION: "Hindamisel",
  WAITS_FOR_EVALUATION: "Ootab hindamist",
  ASSIGNED: "Määratud",
  CONTRACTED: "Leping",
  VALUED: "Hinnastatud",
  NEGOTIATION: "Läbirääkimised",
  DECEASED: "Pärimismenetlus",
};

export const CADASTRE_LABELS: Record<string, string> = {
  CONSERVATION_AREA: "Kaitseala",
  NATURA_2000: "Natura 2000",
  VALUABLE_FOREST: "Vääriselupaik / väärtuslik mets",
  PROTECTED_SPECIES: "Kaitsealune liik",
  DRAINAGE: "Maaparandus",
  ROAD_ACCESS: "Juurdepääsutee",
  BORDER_ZONE: "Piiranguvöönd",
  OTHER: "Muu",
};

export function formatArea(value?: number | null) {
  return value === undefined || value === null ? "—" : `${new Intl.NumberFormat("et-EE", { maximumFractionDigits: 2 }).format(value)} ha`;
}

export function formatNumber(value?: number | null, suffix = "") {
  return value === undefined || value === null ? "—" : `${new Intl.NumberFormat("et-EE", { maximumFractionDigits: 2 }).format(value)}${suffix}`;
}

export function formatPercent(value?: number | null) {
  return value === undefined || value === null ? "—" : `${new Intl.NumberFormat("et-EE", { maximumFractionDigits: 1 }).format(value)}%`;
}

export function formatRegistryDate(value?: number | null) {
  if (value === undefined || value === null) return "—";
  const raw = String(value);
  if (/^\d{8}$/.test(raw)) {
    const year = Number(raw.slice(0, 4));
    const month = Number(raw.slice(4, 6));
    const day = Number(raw.slice(6, 8));
    if (year >= 1900 && month >= 1 && month <= 12 && day >= 1 && day <= 31) return `${day}.${month}.${year}`;
  }
  const millis = value < 10_000_000_000 ? value * 1000 : value;
  const date = new Date(millis);
  return Number.isNaN(date.getTime()) ? raw : new Intl.DateTimeFormat("et-EE", { dateStyle: "medium" }).format(date);
}

export function formatIsoDate(value?: string | null) {
  if (!value) return "teadmata";
  const date = new Date(`${value}T00:00:00Z`);
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat("et-EE", { dateStyle: "medium" }).format(date);
}

export function displayValue(value?: string | null) {
  return value?.trim() || "—";
}

export function statusLabel(status?: string | null) {
  return status ? STATUS_LABELS[status] ?? status : "CRM omanik puudub";
}

export function freshnessLabel(value?: string | null) {
  switch (value) {
    case "FRESH": return "värske";
    case "STALE": return "aegunud";
    case "DEGRADED": return "viimane uuendus ebaõnnestus";
    case "FAILED_REFRESH": return "viimane uuendus ebaõnnestus";
    case "UNKNOWN": return "värskus teadmata";
    default: return value?.toLowerCase().replaceAll("_", " ") || "värskus teadmata";
  }
}
