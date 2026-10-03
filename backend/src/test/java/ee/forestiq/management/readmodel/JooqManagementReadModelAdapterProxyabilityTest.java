package ee.forestiq.management.readmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class JooqManagementReadModelAdapterProxyabilityTest {

    @Test
    void transactionalComponentRemainsProxyable() {
        Class<JooqManagementReadModelAdapter> type = JooqManagementReadModelAdapter.class;

        assertThat(type.getAnnotation(Transactional.class)).isNotNull();
        assertThat(Modifier.isFinal(type.getModifiers())).isFalse();
    }
}
