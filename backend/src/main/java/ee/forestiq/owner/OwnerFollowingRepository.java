package ee.forestiq.owner;

import static ee.forestiq.jooq.Tables.OWNER_FOLLOWING;

import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class OwnerFollowingRepository {

    private final DSLContext dsl;

    public OwnerFollowingRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<String> findFollowings(UUID ownerId) {
        return dsl.select(OWNER_FOLLOWING.USER_SUBJECT)
            .from(OWNER_FOLLOWING)
            .where(OWNER_FOLLOWING.OWNER_ID.eq(ownerId))
            .fetchInto(String.class);
    }

    public void addFollowing(UUID ownerId, String userSubject) {
        dsl.insertInto(OWNER_FOLLOWING)
            .columns(OWNER_FOLLOWING.OWNER_ID, OWNER_FOLLOWING.USER_SUBJECT)
            .values(ownerId, userSubject)
            .onConflictDoNothing()
            .execute();
    }

    public void removeFollowing(UUID ownerId, String userSubject) {
        dsl.deleteFrom(OWNER_FOLLOWING)
            .where(OWNER_FOLLOWING.OWNER_ID.eq(ownerId).and(OWNER_FOLLOWING.USER_SUBJECT.eq(userSubject)))
            .execute();
    }
}
