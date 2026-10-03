package ee.forestiq.time;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Single ForestIQ working-day policy for Estonia.
 *
 * National public holidays are calculated locally so SLA calculations do not
 * depend on an external service. Deployments may add exceptional non-working
 * dates or explicitly mark a date as working through configuration when policy
 * changes without requiring an application release.
 */
@Service
public class BusinessCalendar {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Tallinn");

    private Set<LocalDate> additionalHolidays = Set.of();
    private Set<LocalDate> workingDayOverrides = Set.of();

    @Value("${forestiq.business-calendar.additional-holidays:}")
    void setAdditionalHolidays(String configuredDates) {
        this.additionalHolidays = parseDates(configuredDates);
    }

    @Value("${forestiq.business-calendar.working-day-overrides:}")
    void setWorkingDayOverrides(String configuredDates) {
        this.workingDayOverrides = parseDates(configuredDates);
    }

    public Instant addBusinessDays(Instant start, int businessDays) {
        if (start == null) throw new IllegalArgumentException("start is required");
        if (businessDays < 0) throw new IllegalArgumentException("businessDays must be non-negative");

        ZonedDateTime current = start.atZone(BUSINESS_ZONE);
        int remaining = businessDays;
        while (remaining > 0) {
            current = current.plusDays(1);
            if (isBusinessDay(current.toLocalDate())) remaining--;
        }
        return current.toInstant();
    }

    public Instant nextBusinessTime(Instant start, int businessDays, LocalTime time) {
        if (time == null) throw new IllegalArgumentException("time is required");
        ZonedDateTime target = addBusinessDays(start, businessDays).atZone(BUSINESS_ZONE);
        return target.withHour(time.getHour()).withMinute(time.getMinute())
            .withSecond(time.getSecond()).withNano(0).toInstant();
    }

    public boolean isBusinessDay(LocalDate date) {
        if (date == null) throw new IllegalArgumentException("date is required");
        if (workingDayOverrides.contains(date)) return true;
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        return !isEstonianPublicHoliday(date) && !additionalHolidays.contains(date);
    }

    public boolean isEstonianPublicHoliday(LocalDate date) {
        int year = date.getYear();
        if (date.equals(LocalDate.of(year, Month.JANUARY, 1))
            || date.equals(LocalDate.of(year, Month.FEBRUARY, 24))
            || date.equals(LocalDate.of(year, Month.MAY, 1))
            || date.equals(LocalDate.of(year, Month.JUNE, 23))
            || date.equals(LocalDate.of(year, Month.JUNE, 24))
            || date.equals(LocalDate.of(year, Month.AUGUST, 20))
            || date.equals(LocalDate.of(year, Month.DECEMBER, 24))
            || date.equals(LocalDate.of(year, Month.DECEMBER, 25))
            || date.equals(LocalDate.of(year, Month.DECEMBER, 26))) {
            return true;
        }
        LocalDate easterSunday = easterSunday(year);
        return date.equals(easterSunday.minusDays(2)) // Good Friday
            || date.equals(easterSunday)              // Easter Sunday
            || date.equals(easterSunday.plusDays(49)); // Pentecost Sunday
    }

    /** Gregorian computus (Meeus/Jones/Butcher), valid for modern Estonia. */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }

    private static Set<LocalDate> parseDates(String configuredDates) {
        if (configuredDates == null || configuredDates.isBlank()) return Set.of();
        Set<LocalDate> dates = new HashSet<>();
        Arrays.stream(configuredDates.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(LocalDate::parse)
            .forEach(dates::add);
        return Set.copyOf(dates);
    }
}
