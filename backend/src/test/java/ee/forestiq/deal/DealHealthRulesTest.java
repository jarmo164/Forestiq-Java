package ee.forestiq.deal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class DealHealthRulesTest {
    private static final Instant NOW = Instant.parse("2026-09-04T08:00:00Z");

    @Test
    void missingNextActionIsCritical() {
        var reasons = DealWorkService.healthReasons("QUALIFIED", "", null, null, null, NOW.minusSeconds(60), NOW);
        assertThat(reasons).extracting(DealWorkDtos.HealthReason::code).contains("MISSING_NEXT_ACTION");
        assertThat(reasons.stream().filter(r -> r.code().equals("MISSING_NEXT_ACTION")).findFirst().orElseThrow().severity())
            .isEqualTo("CRITICAL");
    }

    @Test
    void overdueEvaluationAndExpiredOfferAreExplained() {
        var reasons = DealWorkService.healthReasons(
            "EVALUATING", "Kontrolli hindamine", NOW.plusSeconds(3600), NOW.minusSeconds(1),
            LocalDate.ofInstant(NOW, ZoneOffset.UTC).minusDays(1), NOW.minusSeconds(3600), NOW);
        assertThat(reasons).extracting(DealWorkDtos.HealthReason::code)
            .contains("EVALUATION_SLA_OVERDUE", "OFFER_EXPIRED");
    }

    @Test
    void offerWithinSevenDaysAndSevenDayInactivityNeedAttention() {
        var reasons = DealWorkService.healthReasons(
            "OFFER_SENT", "Helista kliendile", NOW.plusSeconds(86400), null,
            LocalDate.ofInstant(NOW, ZoneOffset.UTC).plusDays(7), NOW.minusSeconds(8 * 86400L), NOW);
        assertThat(reasons).extracting(DealWorkDtos.HealthReason::code)
            .contains("OFFER_EXPIRING", "INACTIVE");
        assertThat(reasons).allMatch(reason -> reason.recommendedAction() != null && !reason.recommendedAction().isBlank());
    }

    @Test
    void healthyDealHasNoReasons() {
        var reasons = DealWorkService.healthReasons(
            "QUALIFIED", "Helista homme", NOW.plusSeconds(86400), null, null, NOW.minusSeconds(3600), NOW);
        assertThat(reasons).isEmpty();
    }
}
