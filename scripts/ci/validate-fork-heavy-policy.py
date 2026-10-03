#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HEAVY = ROOT / ".github" / "workflows" / "heavy-verification.yml"
E2E = ROOT / ".github" / "workflows" / "e2e-compose.yml"


def require(text, needle, message):
    if needle not in text:
        raise SystemExit("fork heavy policy failed: " + message)


def main():
    heavy = HEAVY.read_text(encoding="utf-8")
    e2e = E2E.read_text(encoding="utf-8")

    dispatch = heavy.split("workflow_dispatch:", 1)[1].split("permissions:", 1)[0]
    require(dispatch, "approved_pr_number:", "manual approval PR input missing")
    require(dispatch, "approved_head_sha:", "manual exact SHA input missing")
    require(heavy, '[[ "$actual_head" == "$APPROVED_HEAD_SHA" ]]', "approval is not tied to current fork head SHA")
    require(heavy, 'echo "checkout_ref=$merge_sha"', "approved fork is not pinned to server merge commit")
    require(heavy, "head_repo", "fork repository identity check missing")

    trusted_job_gate = "needs.changes.outputs.heavy == 'true' && needs.changes.outputs.trusted == 'true'"
    require(heavy, trusted_job_gate, "heavy implementation jobs must require trusted execution")
    require(
        heavy,
        'needs.changes.outputs.heavy }}" == "true" && "${{ needs.changes.outputs.trusted }}" != "true"',
        "aggregate gate must reject an unapproved heavy fork PR",
    )
    require(heavy, "needs.changes.result", "aggregate gate must fail when classification fails")
    require(heavy, "scripts/ci/validate_gate_results.py", "aggregate heavy result validator is missing")
    require(heavy, "ref: ${{ needs.changes.outputs.checkout_ref }}", "self-hosted checkout is not pinned")

    require(e2e, "checkout_ref:", "E2E reusable workflow lacks verified ref input")
    require(e2e, "ref: ${{ inputs.checkout_ref || github.sha }}", "E2E checkout is not pinned to verified ref")

    if "${{ secrets." in heavy:
        raise SystemExit("fork heavy policy failed: Heavy CI references repository secrets")

    print("Safe fork Heavy CI policy is valid.")


if __name__ == "__main__":
    main()
