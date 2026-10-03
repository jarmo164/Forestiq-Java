package ee.forestiq.inheritance;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class InheritanceNoticeIngestionService {
    private static final String SYSTEM_SUBJECT = "system-inheritance-notice-sync";

    private final InheritanceOfficialNoticeClient client;
    private final InheritanceNoticeParser parser;
    private final InheritanceNoticeArchiveRepository archiveRepository;
    private final InheritanceRepository repository;
    private final InheritanceService inheritanceService;
    private final TransactionTemplate transactions;

    InheritanceNoticeIngestionService(InheritanceOfficialNoticeClient client, InheritanceNoticeParser parser,
                                      InheritanceNoticeArchiveRepository archiveRepository,
                                      InheritanceRepository repository, InheritanceService inheritanceService,
                                      PlatformTransactionManager transactionManager) {
        this.client = client;
        this.parser = parser;
        this.archiveRepository = archiveRepository;
        this.repository = repository;
        this.inheritanceService = inheritanceService;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    SyncSummary synchronize(UUID organizationId, List<LocalDate> days) {
        int parsed = 0;
        int matched = 0;
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        for (LocalDate day : days) {
            List<ParsedInheritanceNotice> notices = parser.parse(client.fetchXml(day), day);
            archiveRepository.archive(day, notices);
            parsed += notices.size();
            for (ParsedInheritanceNotice notice : notices) {
                if (notice.personalCode() == null || !notice.personalCode().matches("\\d{11}")) {
                    skipped++;
                    continue;
                }
                InheritanceRepository.OwnerSnapshot owner =
                    repository.findOwnerByPersonalCode(organizationId, notice.personalCode());
                if (owner == null) {
                    skipped++;
                    continue;
                }
                matched++;
                if (repository.noticeAlreadyImported(organizationId, notice.noticeNumber())) {
                    skipped++;
                    continue;
                }
                try {
                    transactions.executeWithoutResult(ignored -> importOne(organizationId, owner.id(), notice));
                    imported++;
                } catch (RuntimeException exception) {
                    failed++;
                }
            }
        }
        return new SyncSummary(days.size(), parsed, matched, imported, skipped, failed);
    }

    void importOne(UUID orgId, UUID ownerId, ParsedInheritanceNotice notice) {
        inheritanceService.importNormalizedNotice(orgId, ownerId, payload(notice), SYSTEM_SUBJECT);
        repository.recordImportedNotice(orgId, ownerId, notice);
        repository.recordNoticeCheck(orgId, ownerId, notice.noticeNumber(), null);
    }

    private static Map<String, Object> payload(ParsedInheritanceNotice notice) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("notice_number", notice.noticeNumber());
        result.put("source_url", notice.sourceUrl());
        result.put("announcement_date", notice.announcementDate());
        result.put("death_date", notice.deathDate());
        result.put("certification_deadline", notice.certificationDeadline());
        result.put("notary_name", notice.notary());
        result.put("notary_phone", notice.phone());
        result.put("personal_code", notice.personalCode());
        return result;
    }

    record SyncSummary(int checkedDays, int parsed, int matched, int imported, int skipped, int failed) { }
}
