package ee.forestiq.security;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import ee.forestiq.deal.DealController;
import ee.forestiq.deal.DealLifecycleService;
import ee.forestiq.deal.DealService;
import ee.forestiq.identity.OrganizationIdentityDirectory;
import ee.forestiq.web.ApiExceptionHandler;

@WebMvcTest(controllers = DealController.class, properties = "forestiq.cors.allowed-origins=http://localhost:3000")
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class DealControllerSecurityTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private DealService dealService;
    @MockBean private DealLifecycleService lifecycleService;
    @MockBean private OrganizationIdentityDirectory identityDirectory;
    @MockBean private OrganizationContext organizationContext;

    @Test
    void evaluatorCanReadEvaluationQueue() throws Exception {
        when(jwtDecoder.decode("evaluator-token")).thenReturn(jwtWithRealmRoles("evaluator-token", "EVALUATOR"));
        when(dealService.evaluationQueue()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/deals/evaluation-queue").header("Authorization", "Bearer evaluator-token"))
            .andExpect(status().isOk());

        verify(dealService).evaluationQueue();
    }

    @Test
    void viewerCannotReadEvaluationQueue() throws Exception {
        when(jwtDecoder.decode("viewer-token")).thenReturn(jwtWithResourceRoles("viewer-token", "VIEWER"));

        mockMvc.perform(get("/api/v1/deals/evaluation-queue").header("Authorization", "Bearer viewer-token"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(dealService);
    }

    private static Jwt jwtWithRealmRoles(String token, String... roles) {
        return jwt(token, Map.of("realm_access", Map.of("roles", List.of(roles))));
    }

    private static Jwt jwtWithResourceRoles(String token, String... roles) {
        return jwt(token, Map.of("resource_access", Map.of("forestiq-api", Map.of("roles", List.of(roles)))));
    }

    private static Jwt jwt(String token, Map<String, Object> extraClaims) {
        var claims = new java.util.LinkedHashMap<String, Object>();
        claims.put("sub", "user-subject");
        claims.put("organization_id", "11111111-1111-1111-1111-111111111111");
        claims.putAll(extraClaims);
        return new Jwt(token, Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }
}
