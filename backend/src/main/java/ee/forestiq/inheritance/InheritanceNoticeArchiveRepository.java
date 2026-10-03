package ee.forestiq.inheritance;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Stores every parsed Ametlikud Teadaanded inheritance notice, independently of tenant owner matching. */
@Repository
class InheritanceNoticeArchiveRepository {
    private final DSLContext dsl;

    InheritanceNoticeArchiveRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    void archive(LocalDate feedDate, List<ParsedInheritanceNotice> notices) {
        for (ParsedInheritanceNotice notice : notices) {
            if (notice.noticeNumber() == null || notice.noticeNumber().isBlank()) continue;
            dsl.execute("""
                INSERT INTO external_inheritance_notice (
                    notice_number, feed_date, announcement_date, source_url, personal_code,
                    death_date, certification_deadline, notary_name, notary_phone
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (notice_number) DO UPDATE SET
                    feed_date = EXCLUDED.feed_date,
                    announcement_date = EXCLUDED.announcement_date,
                    source_url = EXCLUDED.source_url,
                    personal_code = EXCLUDED.personal_code,
                    death_date = EXCLUDED.death_date,
                    certification_deadline = EXCLUDED.certification_deadline,
                    notary_name = EXCLUDED.notary_name,
                    notary_phone = EXCLUDED.notary_phone,
                    last_seen_at = CURRENT_TIMESTAMP
                """, notice.noticeNumber(), feedDate, notice.announcementDate(), blankToNull(notice.sourceUrl()),
                blankToNull(notice.personalCode()), notice.deathDate(), notice.certificationDeadline(),
                blankToNull(notice.notary()), blankToNull(notice.phone()));
        }
    }

    List<ArchivedInheritanceNotice> findRecent(UUID organizationId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return dsl.fetch("""
                SELECT n.notice_number,
                       n.feed_date,
                       n.announcement_date,
                       n.source_url,
                       n.death_date,
                       n.certification_deadline,
                       n.notary_name,
                       n.notary_phone,
                       n.personal_code IS NOT NULL AS has_personal_code,
                       EXISTS (
                           SELECT 1
                             FROM owner o
                            WHERE o.organization_id = ?
                              AND o.personal_or_registry_code = n.personal_code
                       ) AS owner_matched,
                       EXISTS (
                           SELECT 1
                             FROM inheritance_notice_import i
                            WHERE i.organization_id = ?
                              AND i.notice_number = n.notice_number
                       ) AS imported
                  FROM external_inheritance_notice n
                 ORDER BY n.feed_date DESC, n.announcement_date DESC NULLS LAST, n.notice_number DESC
                 LIMIT ?
                """, organizationId, organizationId, safeLimit).stream()
            .map(record -> new ArchivedInheritanceNotice(
                record.get("notice_number", String.class),
                record.get("feed_date", LocalDate.class),
                record.get("announcement_date", LocalDate.class),
                record.get("source_url", String.class),
                record.get("death_date", LocalDate.class),
                record.get("certification_deadline", LocalDate.class),
                record.get("notary_name", String.class),
                record.get("notary_phone", String.class),
                Boolean.TRUE.equals(record.get("has_personal_code", Boolean.class)),
                Boolean.TRUE.equals(record.get("owner_matched", Boolean.class)),
                Boolean.TRUE.equals(record.get("imported", Boolean.class))))
            .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record ArchivedInheritanceNotice(
        String noticeNumber,
        LocalDate feedDate,
        LocalDate announcementDate,
        String sourceUrl,
        LocalDate deathDate,
        LocalDate certificationDeadline,
        String notaryName,
        String notaryPhone,
        boolean hasPersonalCode,
        boolean ownerMatched,
        boolean imported
    ) { }
}
