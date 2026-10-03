package ee.forestiq.inheritance;

import java.time.LocalDate;

record ParsedInheritanceNotice(
    LocalDate announcementDate,
    String noticeNumber,
    String sourceUrl,
    String notary,
    String phone,
    String deceasedName,
    String personalCode,
    LocalDate birthDate,
    LocalDate deathDate,
    LocalDate certificationDeadline
) { }

