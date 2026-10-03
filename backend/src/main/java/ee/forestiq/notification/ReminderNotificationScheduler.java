package ee.forestiq.notification;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ee.forestiq.messaging.MessageService;

@Component
@ConditionalOnExpression("'${forestiq.runtime.role:all}' == 'all' || '${forestiq.runtime.role:all}' == 'scheduler'")
public class ReminderNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderNotificationScheduler.class);
    private static final int MAX_ATTEMPTS = 5;

    private final DSLContext dsl;
    private final MessageService messageService;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;
    private final String appBaseUrl;
    private final String fromAddress;

    public ReminderNotificationScheduler(
        DSLContext dsl,
        MessageService messageService,
        ObjectProvider<JavaMailSender> mailSenderProvider,
        @Value("${forestiq.notifications.app-base-url:http://localhost:3000}") String appBaseUrl,
        @Value("${forestiq.notifications.from:no-reply@forestiq.local}") String fromAddress
    ) {
        this.dsl = dsl;
        this.messageService = messageService;
        this.mailSenderProvider = mailSenderProvider;
        this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
        this.fromAddress = fromAddress;
    }

    @Scheduled(fixedDelayString = "${forestiq.notifications.scan-delay-ms:60000}")
    public void scanAndDeliver() {
        try {
            createDueDeliveries();
            deliverReadyRows();
        } catch (Exception exception) {
            log.error("Reminder notification scan failed", exception);
        }
    }

    void createDueDeliveries() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        var candidates = dsl.fetch("""
            SELECT r.id, r.organization_id, r.assignee_subject, r.due_time,
                   COALESCE(p.lead_minutes, 60) AS lead_minutes,
                   COALESCE(p.in_app_enabled, TRUE) AS in_app_enabled,
                   COALESCE(p.email_enabled, FALSE) AS email_enabled
            FROM reminder r
            LEFT JOIN user_notification_preference p
              ON p.organization_id = r.organization_id
             AND p.user_subject = r.assignee_subject
            WHERE r.status = 'OPEN'
              AND r.assignee_subject IS NOT NULL
              AND r.due_time <= now() + (COALESCE(p.lead_minutes, 60) * interval '1 minute')
              AND r.due_time >= now() - interval '30 days'
            ORDER BY r.due_time
            LIMIT 1000
            """);

        for (Record row : candidates) {
            long reminderId = row.get("id", Long.class);
            UUID orgId = row.get("organization_id", UUID.class);
            String recipient = row.get("assignee_subject", String.class);
            OffsetDateTime dueTime = row.get("due_time", OffsetDateTime.class);
            String kind = dueTime.isBefore(now) ? "OVERDUE" : "DUE_SOON";
            if (Boolean.TRUE.equals(row.get("in_app_enabled", Boolean.class))) {
                insertDelivery(orgId, reminderId, recipient, "IN_APP", kind, dueTime);
            }
            if (Boolean.TRUE.equals(row.get("email_enabled", Boolean.class))) {
                insertDelivery(orgId, reminderId, recipient, "EMAIL", kind, dueTime);
            }
        }
    }

    private void insertDelivery(UUID orgId, long reminderId, String recipient, String channel,
                                String kind, OffsetDateTime dueTime) {
        dsl.execute("""
            INSERT INTO reminder_notification_delivery
                (organization_id, reminder_id, recipient_subject, channel, notification_kind, reminder_due_time)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (organization_id, reminder_id, recipient_subject, channel, notification_kind, reminder_due_time)
            DO NOTHING
            """, orgId, reminderId, recipient, channel, kind, dueTime);
    }

    void deliverReadyRows() {
        var deliveries = dsl.fetch("""
            SELECT id
            FROM reminder_notification_delivery
            WHERE status = 'PENDING'
               OR (status = 'FAILED' AND attempts < ?
                   AND (last_attempt_at IS NULL OR last_attempt_at < now() - interval '5 minutes'))
            ORDER BY created_at, id
            LIMIT 200
            """, MAX_ATTEMPTS);

        for (Record row : deliveries) {
            UUID deliveryId = row.get("id", UUID.class);
            if (!claim(deliveryId)) continue;
            try {
                deliverClaimed(deliveryId);
            } catch (Exception exception) {
                log.error("Reminder notification delivery {} failed unexpectedly", deliveryId, exception);
                markFailed(deliveryId, exception);
            }
        }
    }

    private boolean claim(UUID deliveryId) {
        Record claimed = dsl.fetchOne("""
            UPDATE reminder_notification_delivery
               SET status = 'PROCESSING', attempts = attempts + 1, last_attempt_at = now(), error_message = NULL
             WHERE id = ?
               AND (status = 'PENDING'
                    OR (status = 'FAILED' AND attempts < ?
                        AND (last_attempt_at IS NULL OR last_attempt_at < now() - interval '5 minutes')))
            RETURNING id
            """, deliveryId, MAX_ATTEMPTS);
        return claimed != null;
    }

    private void deliverClaimed(UUID deliveryId) {
        Record row = dsl.fetchOne("""
            SELECT d.organization_id, d.reminder_id, d.recipient_subject, d.channel,
                   d.notification_kind, d.reminder_due_time,
                   r.status AS reminder_status, r.assignee_subject AS current_assignee, r.due_time AS current_due_time,
                   COALESCE(p.in_app_enabled, TRUE) AS in_app_enabled,
                   COALESCE(p.email_enabled, FALSE) AS email_enabled,
                   p.email_address
            FROM reminder_notification_delivery d
            LEFT JOIN reminder r
              ON r.organization_id = d.organization_id AND r.id = d.reminder_id
            LEFT JOIN user_notification_preference p
              ON p.organization_id = d.organization_id AND p.user_subject = d.recipient_subject
            WHERE d.id = ? AND d.status = 'PROCESSING'
            """, deliveryId);
        if (row == null) return;

        String channel = row.get("channel", String.class);
        String recipient = row.get("recipient_subject", String.class);
        OffsetDateTime deliveryDue = row.get("reminder_due_time", OffsetDateTime.class);
        OffsetDateTime currentDue = row.get("current_due_time", OffsetDateTime.class);
        boolean channelEnabled = "IN_APP".equals(channel)
            ? Boolean.TRUE.equals(row.get("in_app_enabled", Boolean.class))
            : Boolean.TRUE.equals(row.get("email_enabled", Boolean.class));

        if (!"OPEN".equals(row.get("reminder_status", String.class))
            || !Objects.equals(recipient, row.get("current_assignee", String.class))
            || !sameInstant(deliveryDue, currentDue)
            || !channelEnabled) {
            markSuppressed(deliveryId, "Tegevuse olek, vastutaja, tähtaeg või teavituseelistus on muutunud.");
            return;
        }

        String kind = row.get("notification_kind", String.class);
        String humanKind = "OVERDUE".equals(kind) ? "tähtaja ületanud tegevus" : "peatselt tähtajaks saav tegevus";
        String safeText = "Sul on ForestIQ-s " + humanKind + ". Ava autentitud töövaade: /my-work";

        if ("IN_APP".equals(channel)) {
            messageService.sendSystem(row.get("organization_id", UUID.class), "system-reminder", recipient, safeText);
            markSent(deliveryId);
            return;
        }

        String email = row.get("email_address", String.class);
        if (email == null || email.isBlank()) {
            markSuppressed(deliveryId, "E-posti aadress puudub.");
            return;
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender == null) {
            markFailed(deliveryId, new IllegalStateException("SMTP ei ole konfigureeritud."));
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject("ForestIQ: " + ("OVERDUE".equals(kind) ? "tähtaja ületanud tegevus" : "tegevuse tähtaeg läheneb"));
        message.setText("Sul on ForestIQ-s tähelepanu vajav tegevus.\n\nAva töövaade: " + appBaseUrl + "/my-work\n\nKliendi või kinnistu tundlikku infot selles e-kirjas ei kuvata.");
        try {
            mailSender.send(message);
        } catch (Exception exception) {
            markFailed(deliveryId, exception);
            return;
        }
        // Marking SENT is deliberately outside the SMTP exception block. If the
        // database is unavailable after SMTP accepted the message, the row stays
        // PROCESSING instead of being retried and risking a duplicate email.
        markSent(deliveryId);
    }

    private void markSent(UUID id) {
        dsl.execute("""
            UPDATE reminder_notification_delivery
               SET status = 'SENT', sent_at = now(), error_message = NULL
             WHERE id = ? AND status = 'PROCESSING'
            """, id);
    }

    private void markSuppressed(UUID id, String reason) {
        dsl.execute("""
            UPDATE reminder_notification_delivery
               SET status = 'SUPPRESSED', error_message = ?
             WHERE id = ? AND status = 'PROCESSING'
            """, truncate(reason), id);
    }

    private void markFailed(UUID id, Exception exception) {
        dsl.execute("""
            UPDATE reminder_notification_delivery
               SET status = 'FAILED', error_message = ?
             WHERE id = ? AND status = 'PROCESSING'
            """, truncate(exception.getMessage()), id);
    }

    private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        return left != null && right != null && left.toInstant().equals(right.toInstant());
    }

    private static String truncate(String value) {
        if (value == null || value.isBlank()) return "Tundmatu viga";
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
