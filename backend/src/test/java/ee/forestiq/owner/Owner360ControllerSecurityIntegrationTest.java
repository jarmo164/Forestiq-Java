package ee.forestiq.owner;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import ee.forestiq.owner.Owner360Dtos.Owner360Overview;
import ee.forestiq.owner.Owner360Dtos.OwnerIdentity;
import ee.forestiq.owner.Owner360Dtos.ParcelRelationsPage;
import ee.forestiq.owner.Owner360Dtos.PortfolioKpis;
import ee.forestiq.security.SecurityConfig;
import ee.forestiq.web.ApiExceptionHandler;

@WebMvcTest(controllers = Owner360Controller.class, properties = "forestiq.cors.allowed-origins=http://localhost:3000")
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class Owner360ControllerSecurityIntegrationTest {

    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired private MockMvc mockMvc;

    @MockBean private JwtDecoder jwtDecoder;
    @MockBean private Owner360Service owner360Service;
    @MockBean private Owner360MapService owner360MapService;

    @Test
    void viewerCanReadRedactedOwnerOverview() throws Exception {
        when(jwtDecoder.decode("viewer-token")).thenReturn(jwtWithRole("viewer-token", "VIEWER"));
        when(owner360Service.overview(OWNER_ID)).thenReturn(overview(false));

        mockMvc.perform(get("/api/v1/owners/{ownerId}/360", OWNER_ID)
                .header("Authorization", "Bearer viewer-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.owner.displayName").value("Mari Mets"))
            .andExpect(jsonPath("$.owner.sensitiveDetailsVisible").value(false))
            .andExpect(jsonPath("$.owner.personalOrRegistryCode").doesNotExist())
            .andExpect(jsonPath("$.portfolio.activeParcelCount").value(2));

        verify(owner360Service).overview(OWNER_ID);
    }

    @Test
    void relationQueryKeepsExplicitHistoryAndPaginationContract() throws Exception {
        when(jwtDecoder.decode("manager-token")).thenReturn(jwtWithRole("manager-token", "CRM_MANAGER"));
        when(owner360Service.relations(OWNER_ID, true, 20, 25))
            .thenReturn(new ParcelRelationsPage(List.of(), 20, 25, false));

        mockMvc.perform(get("/api/v1/owners/{ownerId}/360/relations", OWNER_ID)
                .queryParam("includeHistorical", "true")
                .queryParam("offset", "20")
                .queryParam("limit", "25")
                .header("Authorization", "Bearer manager-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.offset").value(20))
            .andExpect(jsonPath("$.limit").value(25))
            .andExpect(jsonPath("$.hasMore").value(false));

        verify(owner360Service).relations(OWNER_ID, true, 20, 25);
    }

    @Test
    void anonymousRequestCannotReachOwner360Services() throws Exception {
        mockMvc.perform(get("/api/v1/owners/{ownerId}/360", OWNER_ID))
            .andExpect(status().isUnauthorized());

        verifyNoInteractions(owner360Service, owner360MapService);
    }

    @Test
    void unrelatedRoleCannotReachOwner360Services() throws Exception {
        when(jwtDecoder.decode("unrelated-token")).thenReturn(jwtWithRole("unrelated-token", "AUDITOR"));

        mockMvc.perform(get("/api/v1/owners/{ownerId}/360/map", OWNER_ID)
                .header("Authorization", "Bearer unrelated-token"))
            .andExpect(status().isForbidden());

        verifyNoInteractions(owner360Service, owner360MapService);
    }

    private static Owner360Overview overview(boolean sensitive) {
        OwnerIdentity owner = new OwnerIdentity(
            OWNER_ID,
            "Mari Mets",
            sensitive,
            sensitive ? "48901010011" : null,
            "PERSON",
            "NEW",
            sensitive ? "mari@example.test" : null,
            sensitive ? "+37255500101" : null,
            null,
            null,
            null,
            null,
            Instant.parse("2026-09-16T04:00:00Z"));
        PortfolioKpis portfolio = new PortfolioKpis(2, 1, null, null, null, null, 0L);
        return new Owner360Overview(
            owner,
            portfolio,
            List.of(),
            List.of(),
            Map.of("summary", Owner360Dtos.SectionState.ready()),
            Instant.parse("2026-09-16T04:00:00Z"));
    }

    private static Jwt jwtWithRole(String token, String role) {
        return new Jwt(
            token,
            Instant.now(),
            Instant.now().plusSeconds(60),
            Map.of("alg", "none"),
            Map.of(
                "sub", "test-subject",
                "organization_id", ORGANIZATION_ID.toString(),
                "realm_access", Map.of("roles", List.of(role))));
    }
}
