package ee.forestiq.ownership;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class OwnerParcelRelationControllerSecurityContractTest {

    @Test
    void readEndpointsAllowAdminManagerAndEvaluator() throws Exception {
        assertAuthorization(method("search", String.class, Boolean.class, String.class, int.class),
            "hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')");
        assertAuthorization(method("audit", UUID.class, UUID.class),
            "hasAnyRole('ORG_ADMIN','CRM_MANAGER','EVALUATOR')");
    }

    @Test
    void mutationEndpointsAreRestrictedToAdminAndManager() throws Exception {
        assertAuthorization(method("create", OwnerParcelRelationController.CreateRelationRequest.class),
            "hasAnyRole('ORG_ADMIN','CRM_MANAGER')");
        assertAuthorization(method("end", UUID.class, UUID.class, OwnerParcelRelationController.ReasonRequest.class),
            "hasAnyRole('ORG_ADMIN','CRM_MANAGER')");
        assertAuthorization(method("resolve", UUID.class, OwnerParcelRelationController.ResolveConflictRequest.class),
            "hasAnyRole('ORG_ADMIN','CRM_MANAGER')");
    }

    private Method method(String name, Class<?>... parameterTypes) throws Exception {
        return OwnerParcelRelationController.class.getMethod(name, parameterTypes);
    }

    private void assertAuthorization(Method method, String expected) {
        PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
            .as("%s must stay method-authorized", method.getName())
            .isNotNull();
        assertThat(annotation.value()).isEqualTo(expected);
    }
}
