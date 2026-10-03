from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class OwnerSourceParity:
    job_key: str
    current_count: int
    python_count: int
    stable_ids_match: bool
    selected_business_cases_match: bool

    @property
    def passed(self) -> bool:
        return (
            self.current_count == self.python_count
            and self.stable_ids_match
            and self.selected_business_cases_match
        )


@dataclass(frozen=True)
class OwnerSourceCutoverEvidence:
    sources: tuple[OwnerSourceParity, ...]
    multi_owner_preserved: bool
    relation_end_reactivate_preserved: bool
    manual_override_preserved: bool
    retry_idempotent: bool
    failed_run_preserved_watermark: bool
    successful_python_runs_per_source: int
    notes: str = ""

    @property
    def ready(self) -> bool:
        required = {
            "OWNER_CADASTRE_RELATIONS",
            "WEASEL_OWNERSHIP_TRANSITIONS",
            "INHERITANCE_NOTICES",
        }
        present = {source.job_key for source in self.sources}
        return (
            required == present
            and all(source.passed for source in self.sources)
            and self.multi_owner_preserved
            and self.relation_end_reactivate_preserved
            and self.manual_override_preserved
            and self.retry_idempotent
            and self.failed_run_preserved_watermark
            and self.successful_python_runs_per_source >= 2
        )

    def failures(self) -> list[str]:
        required = {
            "OWNER_CADASTRE_RELATIONS",
            "WEASEL_OWNERSHIP_TRANSITIONS",
            "INHERITANCE_NOTICES",
        }
        present = {source.job_key for source in self.sources}
        failures = [f"source:{source.job_key}" for source in self.sources if not source.passed]
        for missing in sorted(required - present):
            failures.append(f"source:{missing}:missing")
        if not self.multi_owner_preserved:
            failures.append("domain:multi_owner")
        if not self.relation_end_reactivate_preserved:
            failures.append("domain:end_reactivate")
        if not self.manual_override_preserved:
            failures.append("domain:manual_override")
        if not self.retry_idempotent:
            failures.append("retry:not_idempotent")
        if not self.failed_run_preserved_watermark:
            failures.append("watermark:advanced_on_failure")
        if self.successful_python_runs_per_source < 2:
            failures.append("successful_python_runs_per_source:<2")
        return failures

    def to_dict(self) -> dict[str, object]:
        return {
            "ready": self.ready,
            "failures": self.failures(),
            "multiOwnerPreserved": self.multi_owner_preserved,
            "relationEndReactivatePreserved": self.relation_end_reactivate_preserved,
            "manualOverridePreserved": self.manual_override_preserved,
            "retryIdempotent": self.retry_idempotent,
            "failedRunPreservedWatermark": self.failed_run_preserved_watermark,
            "successfulPythonRunsPerSource": self.successful_python_runs_per_source,
            "notes": self.notes,
            "sources": [asdict(source) | {"passed": source.passed} for source in self.sources],
        }


def evaluate_cutover(
    sources: Iterable[OwnerSourceParity],
    *,
    multi_owner_preserved: bool,
    relation_end_reactivate_preserved: bool,
    manual_override_preserved: bool,
    retry_idempotent: bool,
    failed_run_preserved_watermark: bool,
    successful_python_runs_per_source: int,
    notes: str = "",
) -> OwnerSourceCutoverEvidence:
    return OwnerSourceCutoverEvidence(
        tuple(sources),
        multi_owner_preserved,
        relation_end_reactivate_preserved,
        manual_override_preserved,
        retry_idempotent,
        failed_run_preserved_watermark,
        successful_python_runs_per_source,
        notes,
    )


def require_cutover_ready(evidence: OwnerSourceCutoverEvidence) -> None:
    if not evidence.ready:
        raise RuntimeError(
            "Owner-source Python cutover evidence is incomplete: "
            + ", ".join(evidence.failures())
        )


def write_evidence(path: Path, evidence: OwnerSourceCutoverEvidence) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(evidence.to_dict(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
