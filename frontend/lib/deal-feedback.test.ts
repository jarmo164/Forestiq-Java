import { describe, expect, it } from "vitest";
import { qualificationFeedback } from "@/lib/deal-feedback";

describe("qualificationFeedback", () => {
  it("does not show success feedback when the dialog is cancelled", () => {
    expect(qualificationFeedback({ kind: "cancelled" })).toBe("");
  });

  it("mentions the evaluator queue only when evaluation was requested", () => {
    expect(qualificationFeedback({ kind: "created", deal: { stage: "EVALUATION_REQUESTED" } })).toContain("hindaja tööjärjekord");
    expect(qualificationFeedback({ kind: "created", deal: { stage: "QUALIFIED" } })).not.toContain("hindaja tööjärjekord");
  });
});
