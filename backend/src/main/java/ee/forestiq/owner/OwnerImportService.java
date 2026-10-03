package ee.forestiq.owner;

import static ee.forestiq.jooq.Tables.OUTBOX_EVENT;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;

@Service
public class OwnerImportService {

    private static final String BATCH_AGGREGATE = "OWNER_IMPORT_BATCH";
    private static final String BATCH_EVENT = "OWNER_IMPORT_COMMITTED";
    private static final Set<String> SUPPORTED_FIELDS = Set.of(
        "displayName", "personalOrRegistryCode", "ownerType", "email", "phone", "address", "info");
    private static final List<String> REQUIRED_FIELDS = List.of("displayName", "personalOrRegistryCode");

    private final OwnerImportFileParser parser;
    private final OwnerRepository ownerRepository;
    private final OwnerActivityRepository activityRepository;
    private final OwnerFollowingRepository followingRepository;
    private final OrganizationContext organizationContext;
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public OwnerImportService(OwnerImportFileParser parser, OwnerRepository ownerRepository,
                              OwnerActivityRepository activityRepository, OwnerFollowingRepository followingRepository,
                              OrganizationContext organizationContext, DSLContext dsl, ObjectMapper objectMapper) {
        this.parser = parser;
        this.ownerRepository = ownerRepository;
        this.activityRepository = activityRepository;
        this.followingRepository = followingRepository;
        this.organizationContext = organizationContext;
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public ImportInspect inspect(MultipartFile file) {
        byte[] bytes = bytes(file);
        OwnerImportFileParser.ParsedFile parsed = parser.parse(file);
        return new ImportInspect(
            safeFilename(file), sha256(bytes), parsed.headers(), suggestMapping(parsed.headers()), parsed.rows().size(),
            parsed.rows().stream().limit(5).toList());
    }

    @Transactional(readOnly = true)
    public ImportPreview preview(MultipartFile file, ImportMapping request) {
        byte[] bytes = bytes(file);
        OwnerImportFileParser.ParsedFile parsed = parser.parse(file);
        Map<String, String> mapping = validateMapping(parsed.headers(), request);
        UUID orgId = organizationContext.requiredOrganizationId();

        List<MappedRow> mappedRows = new ArrayList<>();
        Map<String, Integer> codeCounts = new LinkedHashMap<>();
        for (int index = 0; index < parsed.rows().size(); index++) {
            MappedRow row = mapped(index + 2, parsed.rows().get(index), mapping);
            mappedRows.add(row);
            if (!row.personalOrRegistryCode().isBlank()) {
                codeCounts.merge(row.personalOrRegistryCode(), 1, Integer::sum);
            }
        }

        Set<String> codes = new LinkedHashSet<>(codeCounts.keySet());
        Map<String, Owner> existingByCode = new LinkedHashMap<>();
        if (!codes.isEmpty()) {
            for (Owner owner : ownerRepository.findByOrganizationIdAndPersonalOrRegistryCodeIn(orgId, codes)) {
                existingByCode.put(owner.getPersonalOrRegistryCode(), owner);
            }
        }

        List<ImportRowResult> results = new ArrayList<>();
        int ready = 0;
        int duplicates = 0;
        int invalid = 0;
        for (MappedRow row : mappedRows) {
            List<String> errors = validateRow(row);
            if (!row.personalOrRegistryCode().isBlank()
                && codeCounts.getOrDefault(row.personalOrRegistryCode(), 0) > 1) {
                errors.add("Sama isiku- või registrikood esineb failis mitu korda.");
            }
            Owner existing = existingByCode.get(row.personalOrRegistryCode());
            RowStatus status;
            UUID existingOwnerId = null;
            if (!errors.isEmpty()) {
                status = RowStatus.INVALID;
                invalid++;
            } else if (existing != null) {
                status = RowStatus.DUPLICATE;
                existingOwnerId = existing.getId();
                duplicates++;
            } else {
                status = RowStatus.READY;
                ready++;
            }
            results.add(new ImportRowResult(row.rowNumber(), row.displayName(), row.personalOrRegistryCode(),
                status, List.copyOf(errors), existingOwnerId, row.values()));
        }

        return new ImportPreview(
            safeFilename(file), sha256(bytes), parsed.headers(), mapping, parsed.rows().size(), ready, duplicates, invalid,
            results);
    }

    @Transactional
    public ImportCommitResult commit(MultipartFile file, ImportMapping mapping, String confirmedSha256) {
        ImportPreview preview = preview(file, mapping);
        if (confirmedSha256 == null || !MessageDigest.isEqual(
                preview.sha256().getBytes(StandardCharsets.US_ASCII), confirmedSha256.trim().getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Impordifail erineb eelvaates kinnitatud failist. Tee eelvaade uuesti.");
        }
        if (preview.readyRows() == 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Impordis ei ole ühtegi kirjutamiseks valmis rida.");
        }

        UUID orgId = organizationContext.requiredOrganizationId();
        String subject = organizationContext.subject();
        UUID batchId = UUID.randomUUID();
        List<Owner> created = new ArrayList<>();
        for (ImportRowResult row : preview.rows()) {
            if (row.status() != RowStatus.READY) continue;
            Map<String, String> values = row.values();
            Owner owner = new Owner(UUID.randomUUID(), orgId, values.get("displayName"), values.get("personalOrRegistryCode"),
                blankToNull(values.get("email")), blankToNull(values.get("phone")), OwnerStatus.NEW, null);
            owner.setOwnerType(blankToNull(values.get("ownerType")));
            owner.setAddress(blankToNull(values.get("address")));
            owner.setInfo(blankToNull(values.get("info")));
            created.add(owner);
        }

        try {
            ownerRepository.saveAll(created);
            ownerRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Omanike andmed muutusid pärast eelvaadet. Tee import uuesti eelvaatest alates.", exception);
        }

        for (Owner owner : created) {
            activityRepository.addActivity(orgId, owner.getId(), "SYSTEM",
                "Omanik imporditud batchist " + batchId + ".", subject);
            followingRepository.addFollowing(owner.getId(), subject);
        }

        List<UUID> createdIds = created.stream().map(Owner::getId).toList();
        List<ImportRowResult> rejectedRows = preview.rows().stream()
            .filter(row -> row.status() != RowStatus.READY)
            .toList();
        BatchAudit audit = new BatchAudit(
            batchId, orgId, subject, preview.filename(), preview.sha256(), Instant.now(), preview.mapping(),
            preview.totalRows(), createdIds.size(), preview.duplicateRows(), preview.invalidRows(), createdIds, rejectedRows);
        storeBatchAudit(audit);

        return new ImportCommitResult(batchId, preview.filename(), preview.sha256(), createdIds.size(),
            preview.duplicateRows(), preview.invalidRows(), createdIds);
    }

    @Transactional(readOnly = true)
    public List<ImportBatchSummary> recentBatches() {
        UUID orgId = organizationContext.requiredOrganizationId();
        return dsl.select(OUTBOX_EVENT.AGGREGATE_ID, OUTBOX_EVENT.PAYLOAD, OUTBOX_EVENT.CREATED_AT)
            .from(OUTBOX_EVENT)
            .where(OUTBOX_EVENT.ORGANIZATION_ID.eq(orgId)
                .and(OUTBOX_EVENT.AGGREGATE_TYPE.eq(BATCH_AGGREGATE))
                .and(OUTBOX_EVENT.EVENT_TYPE.eq(BATCH_EVENT)))
            .orderBy(OUTBOX_EVENT.CREATED_AT.desc())
            .limit(50)
            .fetch(record -> {
                BatchAudit audit = readAudit(record.value2());
                return new ImportBatchSummary(record.value1(), audit.filename(), audit.sha256(), audit.userSubject(),
                    audit.committedAt(), audit.totalRows(), audit.createdCount(), audit.duplicateCount(), audit.invalidCount());
            });
    }

    @Transactional(readOnly = true)
    public String batchErrorsCsv(UUID batchId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        JSONB payload = dsl.select(OUTBOX_EVENT.PAYLOAD)
            .from(OUTBOX_EVENT)
            .where(OUTBOX_EVENT.ORGANIZATION_ID.eq(orgId)
                .and(OUTBOX_EVENT.AGGREGATE_ID.eq(batchId))
                .and(OUTBOX_EVENT.AGGREGATE_TYPE.eq(BATCH_AGGREGATE))
                .and(OUTBOX_EVENT.EVENT_TYPE.eq(BATCH_EVENT)))
            .fetchOne(OUTBOX_EVENT.PAYLOAD);
        if (payload == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Impordibatchi ei leitud.");
        BatchAudit audit = readAudit(payload);
        StringBuilder csv = new StringBuilder("row,status,displayName,personalOrRegistryCode,existingOwnerId,errors\n");
        for (ImportRowResult row : audit.rejectedRows()) {
            csv.append(row.rowNumber()).append(',')
                .append(row.status()).append(',')
                .append(csv(row.displayName())).append(',')
                .append(csv(row.personalOrRegistryCode())).append(',')
                .append(row.existingOwnerId() == null ? "" : row.existingOwnerId()).append(',')
                .append(csv(String.join("; ", row.errors()))).append('\n');
        }
        return csv.toString();
    }

    private void storeBatchAudit(BatchAudit audit) {
        try {
            String json = objectMapper.writeValueAsString(audit);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            dsl.insertInto(OUTBOX_EVENT)
                .columns(OUTBOX_EVENT.ID, OUTBOX_EVENT.ORGANIZATION_ID, OUTBOX_EVENT.AGGREGATE_TYPE,
                    OUTBOX_EVENT.AGGREGATE_ID, OUTBOX_EVENT.EVENT_TYPE, OUTBOX_EVENT.PAYLOAD, OUTBOX_EVENT.PUBLISHED_AT)
                .values(UUID.randomUUID(), audit.organizationId(), BATCH_AGGREGATE, audit.batchId(), BATCH_EVENT,
                    JSONB.valueOf(json), now)
                .execute();
        } catch (Exception exception) {
            throw new IllegalStateException("Owner import audit could not be serialized", exception);
        }
    }

    private BatchAudit readAudit(JSONB payload) {
        try {
            return objectMapper.readValue(payload.data(), BatchAudit.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Owner import audit could not be read", exception);
        }
    }

    private static Map<String, String> validateMapping(List<String> headers, ImportMapping request) {
        if (request == null || request.columns() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Veergude mapping puudub.");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : request.columns().entrySet()) {
            String field = entry.getKey();
            String header = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!SUPPORTED_FIELDS.contains(field)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tundmatu omaniku väli mappingus: " + field);
            }
            if (header.isBlank()) continue;
            if (!headers.contains(header)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mapping viitab puuduvale veerule: " + header);
            }
            if (result.containsValue(header)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ühte failiveergu ei saa kasutada mitme välja jaoks: " + header);
            }
            result.put(field, header);
        }
        for (String required : REQUIRED_FIELDS) {
            if (!result.containsKey(required)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kohustuslik mapping puudub: " + required);
            }
        }
        return Map.copyOf(result);
    }

    private static MappedRow mapped(int rowNumber, Map<String, String> source, Map<String, String> mapping) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String field : SUPPORTED_FIELDS) {
            String header = mapping.get(field);
            values.put(field, header == null ? "" : source.getOrDefault(header, "").trim());
        }
        return new MappedRow(rowNumber, values.get("displayName"), values.get("personalOrRegistryCode"), Map.copyOf(values));
    }

    private static List<String> validateRow(MappedRow row) {
        Map<String, String> v = row.values();
        List<String> errors = new ArrayList<>();
        required(errors, "Nimi", v.get("displayName"), 300);
        required(errors, "Isiku- või registrikood", v.get("personalOrRegistryCode"), 32);
        max(errors, "Omaniku tüüp", v.get("ownerType"), 64);
        max(errors, "E-post", v.get("email"), 320);
        max(errors, "Telefon", v.get("phone"), 64);
        max(errors, "Aadress", v.get("address"), 4000);
        max(errors, "Info", v.get("info"), 8000);
        String email = v.get("email");
        if (email != null && !email.isBlank() && (!email.contains("@") || email.startsWith("@") || email.endsWith("@"))) {
            errors.add("E-posti formaat ei ole korrektne.");
        }
        return errors;
    }

    private static void required(List<String> errors, String label, String value, int max) {
        if (value == null || value.isBlank()) errors.add(label + " on kohustuslik.");
        else if (value.length() > max) errors.add(label + " on pikem kui " + max + " märki.");
    }

    private static void max(List<String> errors, String label, String value, int max) {
        if (value != null && value.length() > max) errors.add(label + " on pikem kui " + max + " märki.");
    }

    private static Map<String, String> suggestMapping(List<String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String header : headers) {
            String normalized = normalize(header);
            suggest(result, "displayName", header, normalized, Set.of("displayname", "name", "nimi", "owner", "ownername", "omanik"));
            suggest(result, "personalOrRegistryCode", header, normalized, Set.of("personalorregistrycode", "code", "isikukood", "registrikood", "isikuvõiregistrikood"));
            suggest(result, "ownerType", header, normalized, Set.of("ownertype", "type", "tüüp", "tyyp"));
            suggest(result, "email", header, normalized, Set.of("email", "epost", "e-post"));
            suggest(result, "phone", header, normalized, Set.of("phone", "telefon", "tel"));
            suggest(result, "address", header, normalized, Set.of("address", "aadress"));
            suggest(result, "info", header, normalized, Set.of("info", "notes", "note", "märkused", "markused"));
        }
        return result;
    }

    private static void suggest(Map<String, String> result, String field, String header, String normalized, Collection<String> aliases) {
        if (!result.containsKey(field) && aliases.stream().map(OwnerImportService::normalize).anyMatch(normalized::equals)) {
            result.put(field, header);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static String safeFilename(MultipartFile file) {
        String name = file == null ? null : file.getOriginalFilename();
        if (name == null || name.isBlank()) return "import";
        return name.replaceAll("[\\r\\n\\t]", "_");
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vali CSV- või XLSX-fail.");
            return file.getBytes();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Impordifaili ei õnnestunud lugeda.", exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public record ImportMapping(Map<String, String> columns) { }
    public record ImportInspect(String filename, String sha256, List<String> headers, Map<String, String> suggestedMapping,
                                int totalRows, List<Map<String, String>> sampleRows) { }
    public record ImportPreview(String filename, String sha256, List<String> headers, Map<String, String> mapping,
                                int totalRows, int readyRows, int duplicateRows, int invalidRows,
                                List<ImportRowResult> rows) { }
    public record ImportRowResult(int rowNumber, String displayName, String personalOrRegistryCode, RowStatus status,
                                  List<String> errors, UUID existingOwnerId, Map<String, String> values) { }
    public record ImportCommitResult(UUID batchId, String filename, String sha256, int createdCount,
                                     int duplicateCount, int invalidCount, List<UUID> createdOwnerIds) { }
    public record ImportBatchSummary(UUID batchId, String filename, String sha256, String userSubject, Instant committedAt,
                                     int totalRows, int createdCount, int duplicateCount, int invalidCount) { }
    public enum RowStatus { READY, DUPLICATE, INVALID }

    private record MappedRow(int rowNumber, String displayName, String personalOrRegistryCode, Map<String, String> values) { }
    private record BatchAudit(UUID batchId, UUID organizationId, String userSubject, String filename, String sha256,
                              Instant committedAt, Map<String, String> mapping, int totalRows, int createdCount,
                              int duplicateCount, int invalidCount, List<UUID> createdOwnerIds,
                              List<ImportRowResult> rejectedRows) { }
}
