import json
from uuid import UUID, uuid4

from forestiq_ingest.pipelines.base import PipelineContext
from forestiq_ingest.sources.metsaregister_mirror import _decode_geojson_page, _feature_hash, _feature_key
from forestiq_ingest.sources.metsaregister_shadow import LayerShadowStats, summarize_shadow


def test_decodes_geojson_feature_collection():
    body = json.dumps(
        {
            "type": "FeatureCollection",
            "numberReturned": 1,
            "numberMatched": "12",
            "features": [{"id": "a", "properties": {"sys_id": "1"}}],
        }
    ).encode()

    _payload, features, number_returned, number_matched = _decode_geojson_page(body)

    assert features[0]["id"] == "a"
    assert number_returned == 1
    assert number_matched == 12


def test_feature_key_prefers_stable_provider_identity():
    feature = {"properties": {"sys_id": "stable"}}

    assert _feature_key(feature, feature["properties"], "sys_id", 7) == "stable"


def test_feature_hash_is_deterministic():
    left = {"properties": {"b": 2, "a": 1}}
    right = {"properties": {"a": 1, "b": 2}}

    assert _feature_hash(left) == _feature_hash(right)


def test_shadow_summary_marks_failed_layers_visible():
    ok = LayerShadowStats("metsaregister:eraldis", "j1", "p1", 10, 10, 10, True, True)
    bad = LayerShadowStats("metsaregister:teatis", "j2", "p2", 10, 8, 8, True, True)

    assert summarize_shadow([ok, bad]) == {
        "layers": 2,
        "passed": 1,
        "failed": ["metsaregister:teatis"],
        "rowDeltaTotal": -2,
    }
