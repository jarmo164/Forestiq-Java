from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

DEFAULT_MANIFEST_PATH = Path(__file__).with_name("layers.json")


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def source_hash(feature: Any) -> str:
    return hashlib.sha256(canonical_json(feature).encode("utf-8")).hexdigest()


def scalar_text(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (str, int, float)):
        text = str(value)
        return text if text.strip() else None
    return None


def feature_key(properties: dict[str, Any], identity_fields: Iterable[str]) -> str:
    fields = [field.strip() for field in identity_fields if field and field.strip()]
    if not fields:
        raise ValueError("Identity field list is empty")
    values: list[tuple[str, str]] = []
    present_values = 0
    for field in fields:
        value = scalar_text(properties.get(field))
        if value is None and len(fields) == 1:
            raise ValueError(f"Feature is missing identity property {field!r}")
        if value is None:
            value = "<NULL>"
        else:
            present_values += 1
        values.append((field, value))
    if present_values == 0:
        raise ValueError("Feature is missing every identity property")
    if len(values) == 1:
        key = values[0][1]
    else:
        key = "".join(
            f"{len(field)}:{field}={len(value)}:{value};"
            for field, value in values
        )
    if len(key) > 320:
        raise ValueError("Feature identity exceeds ForestIQ 320-character key limit")
    return key


def normalize_layer_name(value: str) -> str:
    value = value.strip()
    if not value:
        raise ValueError("Layer name is empty")
    return value if ":" in value else f"metsaregister:{value}"


def layer_slug(layer_name: str) -> str:
    return layer_name.replace(":", "__").replace("/", "_")


def load_layer_manifest(path: str | os.PathLike[str] | None = None) -> dict[str, Any]:
    manifest_path = Path(path) if path else DEFAULT_MANIFEST_PATH
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    if data.get("version") != 1:
        raise ValueError(f"Unsupported layer manifest version: {data.get('version')!r}")
    layers = data.get("layers")
    if not isinstance(layers, list) or not layers:
        raise ValueError("Layer manifest contains no layers")
    seen: set[str] = set()
    for layer in layers:
        name = normalize_layer_name(str(layer.get("name", "")))
        if name in seen:
            raise ValueError(f"Duplicate layer in manifest: {name}")
        seen.add(name)
        identity = layer.get("identity")
        if not isinstance(identity, list) or not identity:
            raise ValueError(f"Layer {name} has no identity fields")
        layer["name"] = name
    return data


def layer_config(manifest: dict[str, Any], layer_name: str) -> dict[str, Any]:
    normalized = normalize_layer_name(layer_name)
    for layer in manifest["layers"]:
        if layer["name"] == normalized:
            return layer
    raise KeyError(f"Layer is not configured: {normalized}")


def atomic_write_json(path: Path, payload: dict[str, Any]) -> None:
    temp = path.with_suffix(path.suffix + ".tmp")
    temp.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temp, path)
