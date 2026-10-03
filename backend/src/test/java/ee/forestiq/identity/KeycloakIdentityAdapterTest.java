package ee.forestiq.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KeycloakIdentityAdapterTest {
    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void returnsUsersAndRoleMappingsBeyondFirstKeycloakPage() throws Exception {
        KeycloakIdentityProperties properties = configuredProperties();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        KeycloakIdentityAdapter adapter = new KeycloakIdentityAdapter(builder.build(), properties);

        server.expect(requestTo("https://keycloak.test/realms/forestiq/protocol/openid-connect/token"))
            .andExpect(method(POST))
            .andRespond(withSuccess("{\"access_token\":\"token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        List<Map<String, Object>> firstUsers = new ArrayList<>();
        for (int i = 0; i < 100; i++) firstUsers.add(user("user-" + i));
        server.expect(requestTo("https://keycloak.test/admin/realms/forestiq/users?first=0&max=100"))
            .andExpect(method(GET)).andRespond(withSuccess(json.writeValueAsString(firstUsers), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://keycloak.test/admin/realms/forestiq/users?first=100&max=100"))
            .andExpect(method(GET)).andRespond(withSuccess(json.writeValueAsString(List.of(user("user-100"))), MediaType.APPLICATION_JSON));

        List<Map<String, Object>> firstCallerMembers = new ArrayList<>();
        for (int i = 0; i < 100; i++) firstCallerMembers.add(Map.of("id", "user-" + i));
        server.expect(requestTo("https://keycloak.test/admin/realms/forestiq/roles/CALLER/users?first=0&max=100"))
            .andExpect(method(GET)).andRespond(withSuccess(json.writeValueAsString(firstCallerMembers), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://keycloak.test/admin/realms/forestiq/roles/CALLER/users?first=100&max=100"))
            .andExpect(method(GET)).andRespond(withSuccess("[{\"id\":\"user-100\"}]", MediaType.APPLICATION_JSON));

        for (String role : List.of("ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "VIEWER")) {
            server.expect(requestTo("https://keycloak.test/admin/realms/forestiq/roles/" + role + "/users?first=0&max=100"))
                .andExpect(method(GET)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }

        var users = adapter.listActiveOrganizationUsers(ORG);

        assertThat(users).hasSize(101);
        assertThat(users).anySatisfy(user -> {
            assertThat(user.subject()).isEqualTo("user-100");
            assertThat(user.roles()).containsExactly(OrganizationRole.CALLER);
        });
        server.verify();
    }

    @Test
    void directoryResultIsCachedForBoundedTtl() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        KeycloakIdentityAdapter adapter = new KeycloakIdentityAdapter(builder.build(), configuredProperties());

        server.expect(requestTo("https://keycloak.test/realms/forestiq/protocol/openid-connect/token"))
            .andRespond(withSuccess("{\"access_token\":\"token\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://keycloak.test/admin/realms/forestiq/users?first=0&max=100"))
            .andRespond(withSuccess(json.writeValueAsString(List.of(user("user-1"))), MediaType.APPLICATION_JSON));
        for (String role : List.of("ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "CALLER", "VIEWER")) {
            server.expect(requestTo("https://keycloak.test/admin/realms/forestiq/roles/" + role + "/users?first=0&max=100"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }

        assertThat(adapter.listActiveOrganizationUsers(ORG)).hasSize(1);
        assertThat(adapter.listActiveOrganizationUsers(ORG)).hasSize(1);
        server.verify();
    }

    private static KeycloakIdentityProperties configuredProperties() {
        KeycloakIdentityProperties properties = new KeycloakIdentityProperties();
        properties.setBaseUrl("https://keycloak.test");
        properties.setRealm("forestiq");
        properties.setClientId("admin-api");
        properties.setClientSecret("secret");
        return properties;
    }

    private Map<String, Object> user(String id) {
        return Map.of(
            "id", id,
            "username", id,
            "enabled", true,
            "attributes", Map.of("organization_id", List.of(ORG.toString())));
    }
}
