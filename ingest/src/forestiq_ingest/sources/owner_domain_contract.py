from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from uuid import UUID


class RelationAction(StrEnum):
    ACTIVATE = "ACTIVATE"
    END = "END"
    CONFLICT = "CONFLICT"


@dataclass(frozen=True)
class OwnerParcelRelationRecord:
    organization_id: UUID
    owner_code: str
    owner_name: str
    owner_type: str
    cadastral_code: str | None
    source_external_key: str
    active: bool = True


@dataclass(frozen=True)
class OwnershipTransitionRecord:
    organization_id: UUID
    source_event_id: str
    owner_code: str
    cadastral_code: str
    transition_at: str
    action: RelationAction


def relation_identity(record: OwnerParcelRelationRecord) -> tuple[UUID, str, str | None, str]:
    return (
        record.organization_id,
        record.owner_code,
        record.cadastral_code,
        record.source_external_key,
    )


def should_preserve_manual_override(manual_override: bool, incoming_action: RelationAction) -> bool:
    return manual_override and incoming_action in {RelationAction.ACTIVATE, RelationAction.END}
