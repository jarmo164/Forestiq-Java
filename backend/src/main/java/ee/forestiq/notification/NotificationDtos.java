package ee.forestiq.notification;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record NotificationPreference(
        int leadMinutes,
        boolean inAppEnabled,
        boolean emailEnabled,
        String emailAddress
    ) {}

    public record UpdateNotificationPreferenceRequest(
        @Min(0) @Max(10080) int leadMinutes,
        boolean inAppEnabled,
        boolean emailEnabled,
        @Email @Size(max = 320) String emailAddress
    ) {}
}
