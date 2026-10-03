package ee.forestiq.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.owner.OwnerDtos.MessageDto;
import ee.forestiq.owner.OwnerDtos.SendMessageRequest;
import ee.forestiq.owner.Pagination;
import ee.forestiq.security.OrganizationContext;

import static org.jooq.impl.DSL.*;

@Service
public class MessageService {

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OrganizationIdentityDirectory identityDirectory;

    public MessageService(DSLContext dsl, OrganizationContext orgCtx, OrganizationIdentityDirectory identityDirectory) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.identityDirectory = identityDirectory;
    }

    @Transactional
    public void send(SendMessageRequest req) {
        UUID orgId = orgCtx.requiredOrganizationId();
        String recipient = req.recipient().trim();
        identityDirectory.requireActiveOrganizationUser(orgId, recipient);
        dsl.insertInto(table("message"))
            .columns(field(name("organization_id")), field(name("sender")), field(name("recipient")), field(name("message_text")))
            .values(orgId, orgCtx.subject(), recipient, req.message().trim())
            .execute();
    }

    public void sendSystem(UUID orgId, String sender, String recipient, String text) {
        dsl.insertInto(table("message"))
            .columns(field(name("organization_id")), field(name("sender")), field(name("recipient")), field(name("message_text")))
            .values(orgId, sender, recipient, text)
            .execute();
    }

    @Transactional(readOnly = true)
    public List<MessageDto> received(int page, int size) {
        Pagination.PageRequest paging = Pagination.normalize(page, size);
        UUID orgId = orgCtx.requiredOrganizationId();
        String me = orgCtx.subject();
        return dsl.select(field(name("id"), Long.class), field(name("message_text"), String.class),
                field(name("sender"), String.class), field(name("recipient"), String.class),
                field(name("created_at"), Instant.class), field(name("noticed_at"), Instant.class))
            .from(table("message"))
            .where(field(name("organization_id"), UUID.class).eq(orgId).and(field(name("recipient"), String.class).eq(me)))
            .orderBy(field(name("created_at")).desc())
            .limit(paging.size()).offset(paging.offset())
            .fetch(r -> new MessageDto(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
    }

    @Transactional(readOnly = true)
    public List<MessageDto> sent(int page, int size) {
        Pagination.PageRequest paging = Pagination.normalize(page, size);
        UUID orgId = orgCtx.requiredOrganizationId();
        String me = orgCtx.subject();
        return dsl.select(field(name("id"), Long.class), field(name("message_text"), String.class),
                field(name("sender"), String.class), field(name("recipient"), String.class),
                field(name("created_at"), Instant.class), field(name("noticed_at"), Instant.class))
            .from(table("message"))
            .where(field(name("organization_id"), UUID.class).eq(orgId).and(field(name("sender"), String.class).eq(me)))
            .orderBy(field(name("created_at")).desc())
            .limit(paging.size()).offset(paging.offset())
            .fetch(r -> new MessageDto(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
    }

    @Transactional
    public void markNoticed(Instant upTo) {
        UUID orgId = orgCtx.requiredOrganizationId();
        String me = orgCtx.subject();
        dsl.update(table("message"))
            .set(field(name("noticed_at")), Instant.now())
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("recipient"), String.class).eq(me))
                .and(field(name("created_at"), Instant.class).lessOrEqual(upTo))
                .and(field(name("noticed_at")).isNull()))
            .execute();
    }

    @Transactional(readOnly = true)
    public long unnoticedCount() {
        UUID orgId = orgCtx.requiredOrganizationId();
        String me = orgCtx.subject();
        return dsl.selectCount().from(table("message"))
            .where(field(name("organization_id"), UUID.class).eq(orgId)
                .and(field(name("recipient"), String.class).eq(me))
                .and(field(name("noticed_at")).isNull()))
            .fetchOne(0, long.class);
    }

    /**
     * Active destinations come from Keycloak rather than historical message or
     * owner-assignment strings. Old messages remain readable because their raw
     * sender/recipient subject values are not rewritten or deleted.
     */
    @Transactional(readOnly = true)
    public List<Map<String, String>> recipients() {
        UUID orgId = orgCtx.requiredOrganizationId();
        return identityDirectory.listActiveOrganizationUsers(orgId).stream()
            .map(user -> Map.of("id", user.subject(), "name", user.displayName()))
            .toList();
    }
}
