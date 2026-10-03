package ee.forestiq.decision;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class DecisionSignalRules {
    public static final long FRESH_NOTIFICATION_DAYS = 30;
    public static final long STALE_REGISTRY_DAYS = 7;
    public static final long OFFER_DEADLINE_DAYS = 7;

    private DecisionSignalRules() {}

    public static boolean isFreshNotification(Instant observedAt, Instant now) {
        return observedAt != null && !observedAt.isBefore(now.minus(FRESH_NOTIFICATION_DAYS, ChronoUnit.DAYS));
    }

    public static boolean isStaleRegistry(Instant observedAt, Instant now) {
        return observedAt == null || observedAt.isBefore(now.minus(STALE_REGISTRY_DAYS, ChronoUnit.DAYS));
    }

    public static boolean isRestrictionLabel(String label) {
        if (label == null) return false;
        String normalized = label.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("kitsendus") || normalized.contains("restriction") || normalized.contains("piirang");
    }

    public static boolean isApproachingOfferDeadline(LocalDate validUntil, LocalDate today) {
        if (validUntil == null) return false;
        return !validUntil.isBefore(today) && !validUntil.isAfter(today.plusDays(OFFER_DEADLINE_DAYS));
    }
}
