package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.OwnerCursorRepository.CursorSlice;
import ee.forestiq.owner.OwnerDtos.OwnerListItem;
import ee.forestiq.security.OrganizationContext;

@ExtendWith(MockitoExtension.class)
class OwnerCursorServiceTest {

    private static final UUID ORG_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant UPDATED_AT = Instant.parse("2026-08-28T10:15:30.123456Z");

    @Mock private OwnerCursorRepository repository;
    @Mock private OrganizationContext organizationContext;
    @Mock private OwnerAuthorizationService authorizationService;

    @Test
    void searchPreservesVisibilityRestrictionAndBuildsNextCursor() {
        OwnerListItem owner = new OwnerListItem(
            OWNER_ID, "Mari Mets", "48901010011", "NEW", 3L, UPDATED_AT,
            "caller-subject", "+37255500101", UPDATED_AT, List.of());
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.of("caller-subject"));
        when(repository.findOwners(ORG_ID, "Mari", "caller-subject", null, null, 20))
            .thenReturn(new CursorSlice(List.of(owner), true, UPDATED_AT, OWNER_ID));

        OwnerCursorPage page = service().search("Mari", 20, null);

        assertThat(page.content()).containsExactly(owner);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        OwnerCursorService.CursorPosition decoded = OwnerCursorService.decodeCursor(page.nextCursor());
        assertThat(decoded.updatedAt()).isEqualTo(UPDATED_AT);
        assertThat(decoded.id()).isEqualTo(OWNER_ID);
        verify(repository).findOwners(ORG_ID, "Mari", "caller-subject", null, null, 20);
    }

    @Test
    void suppliedCursorIsDecodedWithoutLosingInstantPrecision() {
        String cursor = OwnerCursorService.encodeCursor(UPDATED_AT, OWNER_ID);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORG_ID);
        when(authorizationService.assignedSubjectRestriction()).thenReturn(Optional.empty());
        when(repository.findOwners(ORG_ID, "", null, UPDATED_AT, OWNER_ID, 20))
            .thenReturn(new CursorSlice(List.of(), false, null, null));

        OwnerCursorPage page = service().search("", 20, cursor);

        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
        verify(repository).findOwners(ORG_ID, "", null, UPDATED_AT, OWNER_ID, 20);
    }

    @Test
    void malformedCursorIsRejected() {
        assertThatThrownBy(() -> service().search("", 20, "not-a-valid-cursor"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Invalid owner cursor");
    }

    private OwnerCursorService service() {
        return new OwnerCursorService(repository, organizationContext, authorizationService);
    }
}
