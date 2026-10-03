"use client";

import type { OwnerStatusDefinition } from "@/lib/types";

const STATUS_LABELS: Record<string, string> = {
  NEW: "Uus",
  CONTACTED: "Kontakteeritud",
  INTERESTED: "Huvitatud",
  NOT_INTERESTED: "Ei huvita",
  EVALUATION: "Hindamisel",
  VALUED: "Hinnastatud",
  NEGOTIATION: "Läbirääkimised",
  CONTRACTED: "Leping",
  ASSIGNED: "Määratud",
  WAITS_FOR_EVALUATION: "Ootab hindamist",
  DECEASED: "Surnud – pärimismenetlus",
};

export function ownerStatusLabel(status: string) {
  return STATUS_LABELS[status] ?? status;
}

export function OwnerStatusTransitionSelect({
  definitions,
  value,
  onChange,
  loading = false,
  disabled = false,
}: {
  definitions?: OwnerStatusDefinition[];
  value: string;
  onChange: (value: string) => void;
  loading?: boolean;
  disabled?: boolean;
}) {
  return (
    <select
      aria-label="Uus staatus"
      className="h-10 rounded-md border bg-background px-3 text-sm"
      value={value}
      onChange={event => onChange(event.target.value)}
      disabled={disabled || loading}
    >
      <option value="">{loading ? "Laadin lubatud staatusi..." : "Vali staatus..."}</option>
      {(definitions ?? []).map(definition => (
        <option key={definition.id} value={definition.id}>{ownerStatusLabel(definition.id)}</option>
      ))}
    </select>
  );
}
