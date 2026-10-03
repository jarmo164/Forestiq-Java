package ee.forestiq.owner;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerCursorRepository.CursorSlice;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@Service
public class OwnerCursorService {

    private final OwnerCursorRepository repository;
    private final OrganizationContext organizationContext;
    private final OwnerAuthorizationService ownerAuthorizationService;

    public OwnerCursorService(OwnerCursorRepository repository, OrganizationContext organizationContext,
                              OwnerAuthorizationService ownerAuthorizationService) {
        this.repository = repository;
        this.organizationContext = organizationContext;
        this.ownerAuthorizationService = ownerAuthorizationService;
    }

    @Transactional(readOnly = true)
    public OwnerCursorPage search(String search, int size, String cursor) {
        int normalizedSize = Pagination.normalize(0, size).size();
        CursorPosition position = decodeCursor(cursor);
        UUID orgId = organizationContext.requiredOrganizationId();
        String assignedSubject = ownerAuthorizationService.assignedSubjectRestriction().orElse(null);

        CursorSlice slice = repository.findOwners(
            orgId,
            search,
            assignedSubject,
            position == null ? null : position.updatedAt(),
            position == null ? null : position.id(),
            normalizedSize
        );

        String nextCursor = slice.hasNext()
            ? encodeCursor(slice.nextUpdatedAt(), slice.nextId())
            : null;
        return new OwnerCursorPage(slice.content(), normalizedSize, slice.hasNext(), nextCursor);
    }

    static String encodeCursor(Instant updatedAt, UUID id) {
        if (updatedAt == null || id == null) return null;
        String raw = updatedAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator <= 0 || separator >= raw.length() - 1) throw new IllegalArgumentException("separator");
            return new CursorPosition(
                Instant.parse(raw.substring(0, separator)),
                UUID.fromString(raw.substring(separator + 1))
            );
        } catch (RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid owner cursor");
        }
    }

    record CursorPosition(Instant updatedAt, UUID id) { }
}
