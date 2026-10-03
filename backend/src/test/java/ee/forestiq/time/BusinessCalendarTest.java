package ee.forestiq.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class BusinessCalendarTest {

    private final BusinessCalendar calendar = new BusinessCalendar();

    @Test
    void fridayPlusThreeBusinessDaysLandsOnWednesday() {
        Instant friday = Instant.parse("2026-08-21T09:00:00Z");

        Instant result = calendar.addBusinessDays(friday, 3);

        assertThat(result).isEqualTo(Instant.parse("2026-08-26T09:00:00Z"));
    }

    @Test
    void weekendsAreNotBusinessDays() {
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 8, 22))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 8, 23))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 8, 25))).isTrue();
    }

    @Test
    void fixedEstonianPublicHolidaysAreNotBusinessDays() {
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 2, 24))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 6, 23))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 6, 24))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 8, 20))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 12, 24))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 12, 25))).isFalse();
    }

    @Test
    void movableGoodFridayIsNotBusinessDay() {
        assertThat(BusinessCalendar.easterSunday(2026)).isEqualTo(LocalDate.of(2026, 4, 5));
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 4, 3))).isFalse();
    }

    @Test
    void businessDaysSkipChristmasAndNewYear() {
        Instant december23MorningUtc = Instant.parse("2026-12-23T08:00:00Z");

        Instant result = calendar.addBusinessDays(december23MorningUtc, 2);

        assertThat(result.atZone(BusinessCalendar.BUSINESS_ZONE).toLocalDate()).isEqualTo(LocalDate.of(2026, 12, 29));
    }

    @Test
    void nextBusinessTimeUsesTallinnClockOnBusinessDate() {
        Instant beforeIndependenceDay = Instant.parse("2026-02-23T08:00:00Z");

        Instant result = calendar.nextBusinessTime(beforeIndependenceDay, 1, LocalTime.of(10, 0));

        assertThat(result.atZone(BusinessCalendar.BUSINESS_ZONE).toLocalDate()).isEqualTo(LocalDate.of(2026, 2, 25));
        assertThat(result.atZone(BusinessCalendar.BUSINESS_ZONE).toLocalTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void configuredExceptionsCanAddHolidayOrRestoreWorkingDay() {
        calendar.setAdditionalHolidays("2026-09-01");
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 9, 1))).isFalse();

        calendar.setWorkingDayOverrides("2026-09-01");
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 9, 1))).isTrue();
    }
}
