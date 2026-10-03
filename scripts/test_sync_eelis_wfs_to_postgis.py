"""Offline unit tests for the EELIS WFS-to-PostGIS importer."""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPT_PATH = Path(__file__).with_name("sync_eelis_wfs_to_postgis.py")
SPEC = importlib.util.spec_from_file_location("eelis_importer", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
IMPORTER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = IMPORTER
SPEC.loader.exec_module(IMPORTER)


SCHEMA = b"""<?xml version='1.0' encoding='UTF-8'?>
<xsd:schema xmlns:xsd='http://www.w3.org/2001/XMLSchema'
            xmlns:gml='http://www.opengis.net/gml/3.2'>
  <xsd:complexType name='natura_elupaikType'>
    <xsd:complexContent>
      <xsd:extension base='gml:AbstractFeatureType'>
        <xsd:sequence>
          <xsd:element name='shape' type='gml:GeometryPropertyType' nillable='true'/>
          <xsd:element name='sys_id' type='xsd:int' nillable='false'/>
          <xsd:element name='nimi' type='xsd:string' nillable='true'/>
        </xsd:sequence>
      </xsd:extension>
    </xsd:complexContent>
  </xsd:complexType>
  <xsd:element name='natura_elupaik' type='eelis:natura_elupaikType'/>
</xsd:schema>
"""


VALID_PAGE = {
    "type": "FeatureCollection",
    "numberMatched": 2,
    "numberReturned": 2,
    "crs": {"type": "name", "properties": {"name": "urn:ogc:def:crs:EPSG::3301"}},
    "features": [
        {
            "type": "Feature",
            "id": "natura_elupaik.1",
            "properties": {"sys_id": 1, "nimi": "Esimene"},
            "geometry": {"type": "Point", "coordinates": [500000.0, 6500000.0]},
        },
        {
            "type": "Feature",
            "id": "natura_elupaik.2",
            "properties": {"sys_id": 2, "nimi": "Teine"},
            "geometry": {"type": "Point", "coordinates": [500001.0, 6500001.0]},
        },
    ],
}


class EelisImporterTests(unittest.TestCase):
    def test_parse_schema_identifies_geometry_and_has_stable_fingerprint(self) -> None:
        schema_a = IMPORTER.parse_schema("eelis:natura_elupaik", SCHEMA)
        schema_b = IMPORTER.parse_schema("eelis:natura_elupaik", SCHEMA)

        self.assertEqual(schema_a.geometry_field, "shape")
        self.assertIn(("sys_id", "xsd:int", False), schema_a.fields)
        self.assertEqual(schema_a.fingerprint, schema_b.fingerprint)
        IMPORTER.assert_schema_is_compatible(schema_a, "sys_id")

    def test_parse_schema_rejects_missing_sort_field(self) -> None:
        schema = IMPORTER.parse_schema("eelis:natura_elupaik", SCHEMA)
        with self.assertRaisesRegex(IMPORTER.SyncError, "does not exist"):
            IMPORTER.assert_schema_is_compatible(schema, "puudub")

    def test_rows_from_page_preserves_stable_source_identity(self) -> None:
        rows = IMPORTER.rows_from_page(VALID_PAGE, "sys_id")

        self.assertEqual([row.feature_id for row in rows], ["natura_elupaik.1", "natura_elupaik.2"])
        self.assertEqual([row.source_id for row in rows], ["1", "2"])
        self.assertIn('"nimi":"Esimene"', rows[0].properties_json)

    def test_rows_from_page_rejects_duplicate_feature_id(self) -> None:
        page = {**VALID_PAGE, "features": [VALID_PAGE["features"][0], VALID_PAGE["features"][0]]}
        with self.assertRaisesRegex(IMPORTER.SyncError, "duplicate feature id"):
            IMPORTER.rows_from_page(page, "sys_id")

    def test_rows_from_page_requires_stable_sort_identity(self) -> None:
        feature = {**VALID_PAGE["features"][0], "properties": {"nimi": "Ilma võtmeta"}}
        page = {**VALID_PAGE, "numberReturned": 1, "features": [feature]}
        with self.assertRaisesRegex(IMPORTER.SyncError, "required for stable paging"):
            IMPORTER.rows_from_page(page, "sys_id")

    def test_page_validations_reject_count_and_crs_drift(self) -> None:
        IMPORTER.validate_page_count(VALID_PAGE, 2)
        IMPORTER.validate_geojson_crs(VALID_PAGE, 3301)

        with self.assertRaisesRegex(IMPORTER.SyncError, "source changed"):
            IMPORTER.validate_page_count({**VALID_PAGE, "numberMatched": 3}, 2)
        with self.assertRaisesRegex(IMPORTER.SyncError, "did not match"):
            IMPORTER.validate_geojson_crs(
                {**VALID_PAGE, "crs": {"properties": {"name": "EPSG:4326"}}}, 3301
            )

    def test_derived_source_key_and_argument_defaults(self) -> None:
        args = IMPORTER.parse_args(["--layer", "eelis:natura_elupaik", "--dry-run"])
        self.assertEqual(args.source_key, "EELIS_NATURA_ELUPAIK")
        self.assertEqual(IMPORTER.derive_source_key("eelis:kr_puurkaev"), "EELIS_KR_PUURKAEV")

    def test_non_dry_run_requires_best_effort_acknowledgement(self) -> None:
        args = IMPORTER.parse_args(["--layer", "eelis:natura_elupaik"])
        with self.assertRaisesRegex(SystemExit, "best-effort-snapshot"):
            IMPORTER.validate_arguments(args)

    def test_empty_replacement_requires_explicit_reason(self) -> None:
        args = IMPORTER.parse_args(
            ["--layer", "eelis:natura_elupaik", "--best-effort-snapshot"]
        )
        with self.assertRaisesRegex(IMPORTER.SyncError, "Refusing to replace"):
            IMPORTER.assert_empty_source_is_allowed(0, args)

        allowed = IMPORTER.parse_args(
            [
                "--layer",
                "eelis:natura_elupaik",
                "--best-effort-snapshot",
                "--allow-empty",
                "--empty-reason",
                "Provider confirmed the layer is intentionally empty",
            ]
        )
        IMPORTER.validate_arguments(allowed)
        IMPORTER.assert_empty_source_is_allowed(0, allowed)


if __name__ == "__main__":
    unittest.main()
