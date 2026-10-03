import type { DealCase } from "@/lib/types";

export type QualificationDialogResult =
  | { kind: "cancelled" }
  | { kind: "created"; deal: Pick<DealCase, "stage"> };

export function qualificationFeedback(result: QualificationDialogResult): string {
  if (result.kind === "cancelled") return "";

  return result.deal.stage === "EVALUATION_REQUESTED" || result.deal.stage === "EVALUATING"
    ? "Tehingujuhtum salvestati ning hindaja tööjärjekord on uuendatud."
    : "Tehingujuhtum salvestati. Järgmine samm on kliendi huvi täpsustamine.";
}
