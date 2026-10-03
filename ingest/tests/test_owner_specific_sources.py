from datetime import UTC, date, datetime, timedelta
from uuid import uuid4

from forestiq_ingest.pipelines.base import PipelineContext
from forestiq_ingest.sources.inheritance_notices import InheritanceNoticeAdapter, InheritanceNoticeConfig
from forestiq_ingest.sources.owner_domain_contract import (
    OwnerParcelRelationRecord,
    RelationAction,
    relation_identity,
    should_preserve_manual_override,
)
from forestiq_ingest.sources.watermark import IncrementalCheckpoint
from forestiq_ingest.sources.weasel_ownership import WeaselOwnershipAdapter, WeaselOwnershipConfig


class NoopHttp:
    pass


def context(job_key):
    return PipelineContext(uuid4(), job_key, job_key)


def test_multi_owner_relation_identity_keeps_owner_dimension():
    org = uuid4()
    first = OwnerParcelRelationRecord(org, "39001010000", "A", "PERSON", "12345:001:0001", "src-a")
    second = OwnerParcelRelationRecord(org, "39001010001", "B", "PERSON", "12345:001:0001", "src-b")

    assert relation_identity(first) != relation_identity(second)


def test_manual_override_is_preserved_for_provider_change():
    assert should_preserve_manual_override(True, RelationAction.END) is True
    assert should_preserve_manual_override(False, RelationAction.END) is False


def test_weasel_incremental_overlap_rewinds_checkpoint():
    adapter = WeaselOwnershipAdapter(WeaselOwnershipConfig("https://weasel.example", uuid4(), overlap_hours=6), NoopHttp(), "token")
    checkpoint = IncrementalCheckpoint("weasel", "transitions", datetime(2026, 9, 17, 12, tzinfo=UTC))

    assert adapter.updated_since(checkpoint) == datetime(2026, 9, 17, 6, tzinfo=UTC)


def test_inheritance_days_keep_overlap_and_catchup_limit():
    adapter = InheritanceNoticeAdapter(InheritanceNoticeConfig("/{0}/{1}/{2}.xml", overlap_days=1, max_days_per_run=3), NoopHttp())

    assert adapter.days_to_fetch(date(2026, 9, 15), date(2026, 9, 20)) == [
        date(2026, 9, 14),
        date(2026, 9, 15),
        date(2026, 9, 16),
    ]


def test_weasel_normalize_preserves_old_and_new_owner_sets():
    adapter = WeaselOwnershipAdapter(
        WeaselOwnershipConfig("https://weasel.example", uuid4()),
        NoopHttp(),
        "token",
    )
    raw = adapter._validate_provider_row({
        "id": 42,
        "cadastreNo": "12345:001:0001",
        "createdTime": "2026-09-17T12:00:00Z",
        "oldOwners": ["11111111111"],
        "newOwners": ["22222222222"],
    })

    record = list(adapter.normalize(context("WEASEL_OWNERSHIP_TRANSITIONS"), [raw]))[0]

    assert record.metadata.source_id == "42"
    assert record.payload["old_owner_identifiers"] == ["11111111111"]
    assert record.payload["new_owner_identifiers"] == ["22222222222"]


def test_inheritance_normalize_uses_notice_number_and_full_metadata():
    adapter = InheritanceNoticeAdapter(InheritanceNoticeConfig("/{0}/{1}/{2}.xml"), NoopHttp())
    xml = b"""
    <root>
      <teadaanne>
        <teate_number>AT-123</teate_number>
        <url>https://example.test/AT-123</url>
        <andmeandja><nimi>Example Notary</nimi></andmeandja>
        <andmeandja_kontaktandmed><telefon>5550000</telefon></andmeandja_kontaktandmed>
        <puudutatud_isik>
          <eesnimi>Test</eesnimi><nimi>Person</nimi><kood>11111111111</kood>
        </puudutatud_isik>
        <sisendid>
          <sisendirida><nimi>parandaja_surmakuupaev</nimi><vaartus>1.9.2026</vaartus></sisendirida>
          <sisendirida><nimi>toestamise_tahtpaev</nimi><vaartus>1.12.2026</vaartus></sisendirida>
        </sisendid>
        <kinnitatud_sisu>surnud Test Person (isikukood 11111111111)</kinnitatud_sisu>
      </teadaanne>
    </root>
    """
    rows = [{"announcement_date": "2026-09-17", "xml": xml}]

    records = list(adapter.normalize(context("INHERITANCE_NOTICES"), rows))

    assert len(records) == 1
    assert records[0].metadata.source_id == "AT-123"
    assert records[0].payload["personal_code"] == "11111111111"
    assert records[0].payload["death_date"] == "2026-09-01"
    assert records[0].payload["certification_deadline"] == "2026-12-01"
    assert records[0].payload["notary_name"] == "Example Notary"
