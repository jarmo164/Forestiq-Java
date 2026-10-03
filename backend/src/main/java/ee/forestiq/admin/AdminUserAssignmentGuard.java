package ee.forestiq.admin;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Centralizes references that make a Keycloak subject an active ForestIQ work owner.
 * Historical audit/message subject strings are deliberately not counted: they remain
 * immutable identifiers after the identity itself has been removed from Keycloak.
 */
@Service
public class AdminUserAssignmentGuard {

    private final DSLContext dsl;

    public AdminUserAssignmentGuard(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional(readOnly = true)
    public UserDeletionImpact impact(UUID organizationId, String userId) {
        return new UserDeletionImpact(
            userId,
            count("owner", "assigned_to_subject", organizationId, userId),
            count("inheritance_case", "assigned_to_subject", organizationId, userId),
            count("deal_case", "evaluator_subject", organizationId, userId),
            countFollowedOwners(organizationId, userId),
            count("reminder", "creator", organizationId, userId)
        );
    }

    @Transactional(readOnly = true)
    public void requireSafeToDelete(UUID organizationId, String userId) {
        UserDeletionImpact impact = impact(organizationId, userId);
        if (impact.blockingAssignments() > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Kasutajal on aktiivseid ForestIQ tööseoseid. Määra need enne kustutamist ümber: "
                    + "owners=" + impact.owners()
                    + ", inheritanceCases=" + impact.inheritanceCases()
                    + ", evaluatorDeals=" + impact.evaluatorDeals()
                    + ", followedOwners=" + impact.followedOwners()
                    + ", reminders=" + impact.reminders() + ".");
        }
    }

    private int count(String tableName, String subjectColumn, UUID organizationId, String userId) {
        return dsl.fetchCount(table(name(tableName)),
            field(name("organization_id"), UUID.class).eq(organizationId)
                .and(field(name(subjectColumn), String.class).eq(userId)));
    }

    private int countFollowedOwners(UUID organizationId, String userId) {
        var following = table(name("owner_following")).as("f");
        var owner = table(name("owner")).as("o");

        return dsl.fetchCount(
            following.join(owner)
                .on(field(name("f", "owner_id"), UUID.class)
                    .eq(field(name("o", "id"), UUID.class))),
            field(name("o", "organization_id"), UUID.class).eq(organizationId)
                .and(field(name("f", "user_subject"), String.class).eq(userId))
        );
    }

    public record UserDeletionImpact(
        String userId,
        int owners,
        int inheritanceCases,
        int evaluatorDeals,
        int followedOwners,
        int reminders
    ) {
        public int blockingAssignments() {
            return owners + inheritanceCases + evaluatorDeals + followedOwners + reminders;
        }

        public boolean safeToDelete() {
            return blockingAssignments() == 0;
        }
    }
}
