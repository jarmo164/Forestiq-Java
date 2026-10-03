package ee.forestiq.phonebook;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import ee.forestiq.owner.OwnerDtos.CreatePersonsDumpEntry;
import ee.forestiq.owner.OwnerDtos.PersonsDumpEntry;

@Repository
public class PhonebookRepository {

    private final DSLContext dsl;

    public PhonebookRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<PersonsDumpEntry> search(UUID orgId, String name, String phone, String code, int limit) {
        var query = dsl.select(
                field(name("id"), Long.class), field(name("source"), String.class),
                field(name("name"), String.class), field(name("phone"), String.class),
                field(name("address"), String.class), field(name("code"), String.class))
            .from(table("persons_dump"))
            .where(field(name("organization_id"), UUID.class).eq(orgId));
        if (!name.isBlank()) query = query.and(field(name("name"), String.class).likeIgnoreCase("%" + name + "%"));
        if (!phone.isBlank()) query = query.and(field(name("phone"), String.class).likeIgnoreCase("%" + phone + "%"));
        if (!code.isBlank()) query = query.and(field(name("code"), String.class).likeIgnoreCase("%" + code + "%"));
        return query.orderBy(field(name("name")).asc()).limit(Math.min(limit, 1000))
            .fetch(r -> new PersonsDumpEntry(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
    }

    public void create(UUID orgId, CreatePersonsDumpEntry entry) {
        dsl.insertInto(table("persons_dump"))
            .columns(field(name("organization_id")), field(name("code")), field(name("name")), field(name("phone")), field(name("address")))
            .values(orgId, entry.code(), entry.name(), entry.phone(), entry.address())
            .execute();
    }

    public void delete(UUID orgId, Long id) {
        dsl.deleteFrom(table("persons_dump"))
            .where(field(name("id"), Long.class).eq(id).and(field(name("organization_id"), UUID.class).eq(orgId)))
            .execute();
    }
}
