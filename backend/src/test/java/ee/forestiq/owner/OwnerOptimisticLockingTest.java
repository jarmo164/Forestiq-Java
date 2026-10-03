package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

@DataJpaTest(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false"
})
class OwnerOptimisticLockingTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void staleJpaWriteFailsWithOptimisticLockingConflict() {
        UUID ownerId = createOwner();

        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            first.getTransaction().begin();
            second.getTransaction().begin();
            Owner firstCopy = first.find(Owner.class, ownerId);
            Owner secondCopy = second.find(Owner.class, ownerId);

            firstCopy.setPhone("+37255500111");
            first.getTransaction().commit();

            secondCopy.setPhone("+37255500222");
            assertThatThrownBy(() -> second.getTransaction().commit())
                .isInstanceOfAny(ObjectOptimisticLockingFailureException.class, jakarta.persistence.RollbackException.class);
        } finally {
            if (first.getTransaction().isActive()) first.getTransaction().rollback();
            if (second.getTransaction().isActive()) second.getTransaction().rollback();
            first.close();
            second.close();
        }
    }

    private UUID createOwner() {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        try {
            entityManager.getTransaction().begin();
            Owner owner = new Owner(null, ORGANIZATION_ID, "Mari Mets", "48901010011",
                "mari@example.test", "+37255500101", OwnerStatus.NEW, null);
            entityManager.persist(owner);
            entityManager.getTransaction().commit();
            return owner.getId();
        } finally {
            if (entityManager.getTransaction().isActive()) entityManager.getTransaction().rollback();
            entityManager.close();
        }
    }
}
