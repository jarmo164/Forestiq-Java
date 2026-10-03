package ee.forestiq.decision;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class DecisionSignalRulesTest {

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    @Test
    void freshForestNotificationUsesDocumentedThirtyDayWindow() {
        assertThat(DecisionSignalRules.isFreshNotification(Instant.parse("2026-08-04T12:00:00Z"), NOW)).isTrue();
        assertThat(DecisionSignalRules.isFreshNotification(Instant.parse("2026-08-04T11:59:59Z"), NOW)).isFalse();
    }

    @Test
    void registryIsStaleWhenMissingOrOlderThanSevenDays() {
        assertThat(DecisionSignalRules.isStaleRegistry(null, NOW)).isTrue();
        assertThat(DecisionSignalRules.isStaleRegistry(Instant.parse("2026-08-27T12:00:00Z"), NOW)).isFalse();
        assertThat(DecisionSignalRules.isStaleRegistry(Instant.parse("2026-08-27T11:59:59Z"), NOW)).isTrue();
    }

    @Test
    void restrictionLabelsAreExplicitKeywordsNotOpaqueScoring() {
        assertThat(DecisionSignalRules.isRestrictionLabel("Looduskaitse kitsendus")).isTrue();
        assertThat(DecisionSignalRules.isRestrictionLabel("Restriction area")).isTrue();
        assertThat(DecisionSignalRules.isRestrictionLabel("Müügi prioriteet")).isFalse();
    }

    @Test
    void offerDeadlineSignalUsesInclusiveSevenDayWindow() {
        LocalDate today = LocalDate.of(2026, 9, 3);
        assertThat(DecisionSignalRules.isApproachingOfferDeadline(today, today)).isTrue();
        assertThat(DecisionSignalRules.isApproachingOfferDeadline(today.plusDays(7), today)).isTrue();
        assertThat(DecisionSignalRules.isApproachingOfferDeadline(today.plusDays(8), today)).isFalse();
        assertThat(DecisionSignalRules.isApproachingOfferDeadline(today.minusDays(1), today)).isFalse();
    }
}
