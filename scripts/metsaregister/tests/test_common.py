from __future__ import annotations

import sys
from pathlib import Path

import pytest

SCRIPT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from common import feature_key, load_layer_manifest, source_hash  # noqa: E402


def test_composite_identity_matches_java_mirror_format() -> None:
    key = feature_key({"eraldis_id": 123, "kood": "A"}, ["eraldis_id", "kood"])
    assert key == "10:eraldis_id=3:123;4:kood=1:A;"


def test_single_identity_uses_scalar_value() -> None:
    assert feature_key({"sys_id": 42}, ["sys_id"]) == "42"


def test_composite_identity_encodes_missing_value() -> None:
    key = feature_key({"eraldis_id": 123}, ["eraldis_id", "kood"])
    assert key == "10:eraldis_id=3:123;4:kood=6:<NULL>;"


def test_single_missing_identity_fails_closed() -> None:
    with pytest.raises(ValueError, match="missing identity"):
        feature_key({}, ["sys_id"])


def test_fully_missing_composite_identity_fails_closed() -> None:
    with pytest.raises(ValueError, match="missing every identity"):
        feature_key({}, ["eraldis_id", "kood"])


def test_source_hash_is_canonical_for_object_key_order() -> None:
    assert source_hash({"b": 2, "a": 1}) == source_hash({"a": 1, "b": 2})


def test_checked_in_manifest_contains_23_current_catalogue_layers() -> None:
    manifest = load_layer_manifest()
    assert len(manifest["layers"]) == 23
    disabled = {layer["name"] for layer in manifest["layers"] if not layer.get("enabled", True)}
    assert disabled == {"metsaregister:raadatud_metsateatised"}
