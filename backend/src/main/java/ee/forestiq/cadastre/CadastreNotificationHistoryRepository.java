package ee.forestiq.cadastre;

import static ee.forestiq.jooq.Tables.CADASTRE_NOTIFICATION;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationArchivePage;
import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationDto;
import ee.forestiq.cadastre.CadastreDtos.CadastreNotificationPage;

/**
 * Read-optimized notification history contract. The common current-state path
 * never traverses archived history; archive browsing is time-bounded and uses a
 * stable (archive_date,id) keyset cursor.
 */
@Repository
public class CadastreNotificationHistoryRepository {

    private final DSLContext dsl;

    public CadastreNotificationHistoryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public CadastreNotificationPage findCurrentPage(UUID orgId, String cadastralCode, Long cursor, int limit) {
        Condition condition = CADASTRE_NOTIFICATION.ORGANIZATION_ID.eq(orgId)
            .and(CADASTRE_NOTIFICATION.CADASTRAL_CODE.eq(cadastralCode))
            .and(CADASTRE_NOTIFICATION.ARCHIVED.isFalse());
        if (cursor != null) {
            condition = condition.and(CADASTRE_NOTIFICATION.ID.lt(cursor));
        }

        List<CadastreNotificationDto> rows = fetch(condition, limit + 1, false);
        boolean hasMore = rows.size() > limit;
        List<CadastreNotificationDto> items = hasMore
            ? List.copyOf(rows.subList(0, limit))
            : List.copyOf(rows);
        Long nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).id() : null;
        return new CadastreNotificationPage(items, nextCursor, hasMore);
    }

    public CadastreNotificationArchivePage findArchivePage(
        UUID orgId,
        String cadastralCode,
        long fromArchiveDate,
        long toArchiveDate,
        Long cursorArchiveDate,
        Long cursorId,
        int limit
    ) {
        Condition condition = CADASTRE_NOTIFICATION.ORGANIZATION_ID.eq(orgId)
            .and(CADASTRE_NOTIFICATION.CADASTRAL_CODE.eq(cadastralCode))
            .and(CADASTRE_NOTIFICATION.ARCHIVED.isTrue())
            .and(CADASTRE_NOTIFICATION.ARCHIVE_DATE.isNotNull())
            .and(CADASTRE_NOTIFICATION.ARCHIVE_DATE.between(fromArchiveDate, toArchiveDate));

        if (cursorArchiveDate != null && cursorId != null) {
            condition = condition.and(
                CADASTRE_NOTIFICATION.ARCHIVE_DATE.lt(cursorArchiveDate)
                    .or(CADASTRE_NOTIFICATION.ARCHIVE_DATE.eq(cursorArchiveDate)
                        .and(CADASTRE_NOTIFICATION.ID.lt(cursorId))));
        }

        List<CadastreNotificationDto> rows = fetch(condition, limit + 1, true);
        boolean hasMore = rows.size() > limit;
        List<CadastreNotificationDto> items = hasMore
            ? List.copyOf(rows.subList(0, limit))
            : List.copyOf(rows);
        CadastreNotificationDto last = hasMore && !items.isEmpty() ? items.get(items.size() - 1) : null;
        return new CadastreNotificationArchivePage(
            items,
            last == null ? null : last.archiveDate(),
            last == null ? null : last.id(),
            hasMore);
    }

    private List<CadastreNotificationDto> fetch(Condition condition, int limit, boolean archiveOrder) {
        var query = dsl.select(
                CADASTRE_NOTIFICATION.ID,
                CADASTRE_NOTIFICATION.CADASTRAL_CODE,
                CADASTRE_NOTIFICATION.NOTIFICATION_NUMBER,
                CADASTRE_NOTIFICATION.AMOUNT_TO_BE_CUT,
                CADASTRE_NOTIFICATION.AREA,
                CADASTRE_NOTIFICATION.CADASTRE_SUBPART_CODE,
                CADASTRE_NOTIFICATION.DAMAGE_CODE,
                CADASTRE_NOTIFICATION.CONFIRMATION_DATE,
                CADASTRE_NOTIFICATION.REGISTRATION_DATE,
                CADASTRE_NOTIFICATION.DECISION,
                CADASTRE_NOTIFICATION.STATE,
                CADASTRE_NOTIFICATION.WORK_CODE,
                CADASTRE_NOTIFICATION.ARCHIVED,
                CADASTRE_NOTIFICATION.ARCHIVE_DATE)
            .from(CADASTRE_NOTIFICATION)
            .where(condition);

        if (archiveOrder) {
            return query
                .orderBy(CADASTRE_NOTIFICATION.ARCHIVE_DATE.desc(), CADASTRE_NOTIFICATION.ID.desc())
                .limit(limit)
                .fetch(this::toNotification);
        }
        return query
            .orderBy(CADASTRE_NOTIFICATION.ID.desc())
            .limit(limit)
            .fetch(this::toNotification);
    }

    private CadastreNotificationDto toNotification(Record record) {
        return new CadastreNotificationDto(
            record.get(CADASTRE_NOTIFICATION.ID),
            record.get(CADASTRE_NOTIFICATION.CADASTRAL_CODE),
            stringValue(record.get(CADASTRE_NOTIFICATION.NOTIFICATION_NUMBER)),
            doubleValue(record.get(CADASTRE_NOTIFICATION.AMOUNT_TO_BE_CUT)),
            doubleValue(record.get(CADASTRE_NOTIFICATION.AREA)),
            record.get(CADASTRE_NOTIFICATION.CADASTRE_SUBPART_CODE),
            record.get(CADASTRE_NOTIFICATION.DAMAGE_CODE),
            record.get(CADASTRE_NOTIFICATION.CONFIRMATION_DATE),
            record.get(CADASTRE_NOTIFICATION.REGISTRATION_DATE),
            record.get(CADASTRE_NOTIFICATION.DECISION),
            stringValue(record.get(CADASTRE_NOTIFICATION.STATE)),
            record.get(CADASTRE_NOTIFICATION.WORK_CODE),
            Boolean.TRUE.equals(record.get(CADASTRE_NOTIFICATION.ARCHIVED)),
            record.get(CADASTRE_NOTIFICATION.ARCHIVE_DATE));
    }

    private static Double doubleValue(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}
