from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable

from forestiq_ingest.sources.metsaregister_shadow import LayerShadowStats


@dataclass(frozen=True)
class MetsaregisterCutoverEvidence:
    layers: tuple[LayerShadowStats, ...]
    successful_python_runs: int
    notification_samples_match: bool
    forest_plan_samples_match: bool
    notes: str = ""

    @property
    def ready(self) -> bool:
        return (
            bool(self.layers)
            and all(layer.passed for layer in self.layers)
            and self.successful_python_runs >= 2
            and self.notification_samples_match
            and self.forest_plan_samples_match
        )

    def failures(self) -> list[str]:
        failures = [f"layer:{layer.layer_name}" for layer in self.layers if not layer.passed]
        if not self.layers:
            failures.append("layers:missing")
        if self.successful_python_runs < 2:
            failures.append("successful_python_runs:<2")
        if not self.notification_samples_match:
            failures.append("notification_samples:mismatch")
        if not self.forest_plan_samples_match:
            failures.append("forest_plan_samples:mismatch")
        return failures

    def to_dict(self) -> dict[str, object]:
        return {
            "ready": self.ready,
            "failures": self.failures(),
            "successfulPythonRuns": self.successful_python_runs,
            "notificationSamplesMatch": self.notification_samples_match,
            "forestPlanSamplesMatch": self.forest_plan_samples_match,
            "notes": self.notes,
            "layers": [asdict(layer) | {"passed": layer.passed, "row_delta": layer.row_delta} for layer in self.layers],
        }


def evaluate_cutover(
    layers: Iterable[LayerShadowStats],
    *,
    successful_python_runs: int,
    notification_samples_match: bool,
    forest_plan_samples_match: bool,
    notes: str = "",
) -> MetsaregisterCutoverEvidence:
    return MetsaregisterCutoverEvidence(
        tuple(layers),
        successful_python_runs,
        notification_samples_match,
        forest_plan_samples_match,
        notes,
    )


def write_evidence(path: Path, evidence: MetsaregisterCutoverEvidence) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(evidence.to_dict(), indent=2, sort_keys=True) + "\n", encoding="utf-8")


def require_cutover_ready(evidence: MetsaregisterCutoverEvidence) -> None:
    if not evidence.ready:
        raise RuntimeError(
            "Metsaregister Python cutover evidence is incomplete: " + ", ".join(evidence.failures())
        )
