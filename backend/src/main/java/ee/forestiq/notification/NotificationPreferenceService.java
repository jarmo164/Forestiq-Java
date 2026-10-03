package ee.forestiq.notification;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.notification.NotificationDtos.NotificationPreference;
import ee.forestiq.notification.NotificationDtos.UpdateNotificationPreferenceRequest;
import ee.forestiq.security.OrganizationContext;

@Service
public class NotificationPreferenceService {

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;

    public NotificationPreferenceService(DSLContext dsl, OrganizationContext orgCtx) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
    }

    @Transactional(readOnly = true)
    public NotificationPreference get() {
        UUID orgId = orgCtx.requiredOrganizationId();
        String subject = orgCtx.subject();
        var row = dsl.fetchOne("""
            SELECT lead_minutes, in_app_enabled, email_enabled, email_address
            FROM user_notification_preference
            WHERE organization_id = ? AND user_subject = ?
            """, orgId, subject);
        if (row == null) return new NotificationPreference(60, true, false, null);
        return new NotificationPreference(
            row.get("lead_minutes", Integer.class),
            Boolean.TRUE.equals(row.get("in_app_enabled", Boolean.class)),
            Boolean.TRUE.equals(row.get("email_enabled", Boolean.class)),
            row.get("email_address", String.class)
        );
    }

    @Transactional
    public NotificationPreference update(UpdateNotificationPreferenceRequest request) {
        String email = trimToNull(request.emailAddress());
        if (request.emailEnabled() && email == null) {
            throw new ResponseStatusException(BAD_REQUEST, "E-posti teavituste jaoks on e-posti aadress kohustuslik.");
        }
        UUID orgId = orgCtx.requiredOrganizationId();
        String subject = orgCtx.subject();
        dsl.execute("""
            INSERT INTO user_notification_preference
                (organization_id, user_subject, lead_minutes, in_app_enabled, email_enabled, email_address, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (organization_id, user_subject) DO UPDATE SET
                lead_minutes = EXCLUDED.lead_minutes,
                in_app_enabled = EXCLUDED.in_app_enabled,
                email_enabled = EXCLUDED.email_enabled,
                email_address = EXCLUDED.email_address,
                updated_at = now()
            """, orgId, subject, request.leadMinutes(), request.inAppEnabled(), request.emailEnabled(), email);
        return new NotificationPreference(request.leadMinutes(), request.inAppEnabled(), request.emailEnabled(), email);
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
