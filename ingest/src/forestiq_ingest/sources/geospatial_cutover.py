from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class GeospatialDatasetParity:
    dataset_key: str
    current_row_count: int
    python_row_count: int
    identity_match: bool
    geometry_bounds_match: bool
    sample_match: bool

    @property
    def row_delta(self) -> int:
        return self.python_row_count - self.current_row_count

    @property
    def passed(self) -> bool:
        return (
            self.row_delta == 0
            and self.identity_match
            and self.geometry_bounds_match
            and self.sample_match
        )


@dataclass(frozen=True)
class GeospatialCutoverEvidence:
    datasets: tuple[GeospatialDatasetParity, ...]
    failed_import_preserved_current_generation: bool
    unchanged_snapshot_was_noop: bool
    java_read_model_compatible: bool
    successful_python_runs: int
    notes: str = ""

    @property
    def ready(self) -> bool:
        return (
            bool(self.datasets)
            and all(dataset.passed for dataset in self.datasets)
            and self.failed_import_preserved_current_generation
            and self.unchanged_snapshot_was_noop
            and self.java_read_model_compatible
            and self.successful_python_runs >= 2
        )

    def failures(self) -> list[str]:
        failures = [
            f"dataset:{dataset.dataset_key}"
            for dataset in self.datasets
            if not dataset.passed
        ]
        if not self.datasets:
            failures.append("datasets:missing")
        if not self.failed_import_preserved_current_generation:
            failures.append("failed_import:last_known_good_not_proven")
        if not self.unchanged_snapshot_was_noop:
            failures.append("checksum:no_op_not_proven")
        if not self.java_read_model_compatible:
            failures.append("java_read_model:compatibility_not_proven")
        if self.successful_python_runs < 2:
            failures.append("successful_python_runs:<2")
        return failures

    def to_dict(self) -> dict[str, object]:
        return {
            "ready": self.ready,
            "failures": self.failures(),
            "failedImportPreservedCurrentGeneration": self.failed_import_preserved_current_generation,
            "unchangedSnapshotWasNoop": self.unchanged_snapshot_was_noop,
            "javaReadModelCompatible": self.java_read_model_compatible,
            "successfulPythonRuns": self.successful_python_runs,
            "notes": self.notes,
            "datasets": [
                asdict(dataset)
                | {"row_delta": dataset.row_delta, "passed": dataset.passed}
                for dataset in self.datasets
            ],
        }


def evaluate_cutover(
    datasets: Iterable[GeospatialDatasetParity],
    *,
    failed_import_preserved_current_generation: bool,
    unchanged_snapshot_was_noop: bool,
    java_read_model_compatible: bool,
    successful_python_runs: int,
    notes: str = "",
) -> GeospatialCutoverEvidence:
    return GeospatialCutoverEvidence(
        tuple(datasets),
        failed_import_preserved_current_generation,
        unchanged_snapshot_was_noop,
        java_read_model_compatible,
        successful_python_runs,
        notes,
    )


def require_cutover_ready(evidence: GeospatialCutoverEvidence) -> None:
    if not evidence.ready:
        raise RuntimeError(
            "Geospatial Python cutover evidence is incomplete: "
            + ", ".join(evidence.failures())
        )


def write_evidence(path: Path, evidence: GeospatialCutoverEvidence) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(evidence.to_dict(), indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
