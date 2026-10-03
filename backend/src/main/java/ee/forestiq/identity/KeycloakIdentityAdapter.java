package ee.forestiq.identity;

import ee.forestiq.integration.ExternalSystemBoundary;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Server-side adapter for Keycloak user and realm-role administration. */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.PLATFORM_ADMIN_CLIENT,
    reason = "Keycloak admin API is an identity-platform boundary, not batch data ingestion."
)
@Service
public class KeycloakIdentityAdapter implements OrganizationIdentityDirectory, OrganizationIdentityAdministration {

    private static final Set<String> FORESTIQ_ROLES = Set.of("ORG_ADMIN", "CRM_MANAGER", "EVALUATOR", "CALLER", "VIEWER");
    private static final int PAGE_SIZE = 100;
    private static final int MAX_DIRECTORY_USERS = 20_000;
    private static final Duration DIRECTORY_CACHE_TTL = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final KeycloakIdentityProperties properties;
    private final Map<String, Map<String, Object>> roleRepresentationCache = new ConcurrentHashMap<>();
    private final Map<UUID, CachedUsers> organizationUsersCache = new ConcurrentHashMap<>();
    private volatile CachedToken cachedToken;

    @org.springframework.beans.factory.annotation.Autowired
    public KeycloakIdentityAdapter(RestClient.Builder restClientBuilder, KeycloakIdentityProperties properties) {
        this(restClientBuilder.build(), properties);
    }

    KeycloakIdentityAdapter(RestClient restClient, KeycloakIdentityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * Returns enabled ForestIQ users belonging to one organization. Keycloak's
     * Admin API is paginated even when callers omit paging parameters, so every
     * page is consumed up to a defensive hard limit and the result is cached for
     * a short bounded period to avoid repeating the same directory scan for every
     * assignment/recipient picker.
     */
    @Override
    public List<OrganizationUser> listActiveOrganizationUsers(UUID organizationId) {
        ensureConfigured();
        CachedUsers cached = organizationUsersCache.get(organizationId);
        if (cached != null && cached.isUsable()) {
            return cached.users();
        }

        List<Map<String, Object>> users = pagedGet("/users", PAGE_SIZE, MAX_DIRECTORY_USERS);
        Map<String, List<String>> rolesByUser = forestIqRolesByUser();
        List<OrganizationUser> result = new ArrayList<>();
        for (Map<String, Object> user : users) {
            if (belongsToOrganization(user, organizationId) && isEnabled(user)) {
                String id = requiredText(user, "id");
                result.add(toOrganizationUser(user, rolesByUser.getOrDefault(id, List.of())));
            }
        }
        List<OrganizationUser> immutable = result.stream()
            .sorted(Comparator.comparing(OrganizationUser::username, String.CASE_INSENSITIVE_ORDER))
            .toList();
        organizationUsersCache.put(organizationId, new CachedUsers(immutable, Instant.now().plus(DIRECTORY_CACHE_TTL)));
        return immutable;
    }

    @Override
    public OrganizationUser requireActiveOrganizationUser(UUID organizationId, String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Kasutaja on kohustuslik.");
        }
        return listActiveOrganizationUsers(organizationId).stream()
            .filter(user -> user.subject().equals(userId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST,
                "Kasutajat ei leitud aktiivse organisatsiooni aktiivsete kasutajate hulgast."));
    }

    @Override
    public OrganizationUser requireAssignableUser(UUID organizationId, String userId,
                                                   Set<OrganizationRole> allowedRoles) {
        OrganizationUser user = requireActiveOrganizationUser(organizationId, userId);
        if (allowedRoles != null && !allowedRoles.isEmpty()
            && user.roles().stream().noneMatch(allowedRoles::contains)) {
            throw new ResponseStatusException(BAD_REQUEST, "Kasutajal puudub selle töö määramiseks sobiv roll.");
        }
        return user;
    }

    @Override
    public OrganizationUser createUser(UUID organizationId, CreateOrganizationUser request) {
        ensureConfigured();
        validateCreateCommand(request);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", request.username().trim());
        payload.put("enabled", true);
        payload.put("firstName", request.displayName().trim());
        payload.put("attributes", Map.of("organization_id", List.of(organizationId.toString())));
        payload.put("requiredActions", List.of("UPDATE_PASSWORD"));
        payload.put("credentials", List.of(Map.of("type", "password", "value", request.temporaryPassword(), "temporary", true)));
        try {
            String location = restClient.post().uri(adminUri("/users"))
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .body(payload).retrieve().toBodilessEntity().getHeaders().getFirst("Location");
            if (location == null || location.isBlank()) {
                throw new ResponseStatusException(BAD_GATEWAY, "Keycloak ei tagastanud loodud kasutaja ID-d.");
            }
            String userId = location.substring(location.lastIndexOf('/') + 1);
            updateRoles(organizationId, userId, request.roles(), null);
            invalidateOrganizationUsers(organizationId);
            return getUser(organizationId, userId);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            if (messageContains(exception, "409")) {
                throw new ResponseStatusException(CONFLICT, "Sellise kasutajanimega kasutaja on juba olemas.", exception);
            }
            throw keycloakFailure("Kasutaja loomine ebaõnnestus", exception);
        }
    }

    @Override
    public void updateRoles(UUID organizationId, String userId, Set<OrganizationRole> requestedRoles, String currentSubject) {
        ensureConfigured();
        if (Objects.equals(userId, currentSubject)) {
            throw new ResponseStatusException(BAD_REQUEST, "Enda rolle ei saa ForestIQ haldusest muuta.");
        }
        Map<String, Object> user = rawUser(userId);
        assertOrganization(user, organizationId);
        List<String> currentRoleNames = realmRoles(userId);
        List<String> nextRoleNames = roleNames(requestedRoles);
        if (currentRoleNames.contains("ORG_ADMIN") && !nextRoleNames.contains("ORG_ADMIN")) {
            long activeAdministrators = listActiveOrganizationUsers(organizationId).stream()
                .filter(candidate -> candidate.roles().contains(OrganizationRole.ORG_ADMIN))
                .count();
            if (activeAdministrators <= 1) {
                throw new ResponseStatusException(BAD_REQUEST, "Organisatsioonil peab olema vähemalt üks aktiivne administraator.");
            }
        }
        List<Map<String, Object>> currentForestIqRoles = realmRoleRepresentations(currentRoleNames);
        if (!currentForestIqRoles.isEmpty()) {
            delete("/users/" + userId + "/role-mappings/realm", currentForestIqRoles);
        }
        List<Map<String, Object>> nextRoles = realmRoleRepresentations(nextRoleNames);
        if (!nextRoles.isEmpty()) {
            post("/users/" + userId + "/role-mappings/realm", nextRoles);
        }
        invalidateOrganizationUsers(organizationId);
    }

    @Override
    public void deleteUser(UUID organizationId, String userId, String currentSubject) {
        ensureConfigured();
        if (Objects.equals(userId, currentSubject)) {
            throw new ResponseStatusException(BAD_REQUEST, "Enda kasutajat ei saa ForestIQ haldusest kustutada.");
        }
        assertOrganization(rawUser(userId), organizationId);
        try {
            restClient.delete().uri(adminUri("/users/" + userId))
                .headers(headers -> headers.setBearerAuth(accessToken())).retrieve().toBodilessEntity();
            invalidateOrganizationUsers(organizationId);
        } catch (Exception exception) {
            throw keycloakFailure("Kasutaja kustutamine ebaõnnestus", exception);
        }
    }

    private OrganizationUser getUser(UUID organizationId, String userId) {
        Map<String, Object> user = rawUser(userId);
        assertOrganization(user, organizationId);
        if (!isEnabled(user)) {
            throw new ResponseStatusException(NOT_FOUND, "Keycloaki kasutaja ei ole aktiivne.");
        }
        return toOrganizationUser(user, realmRoles(userId));
    }

    private Map<String, Object> rawUser(String userId) {
        try {
            return get("/users/" + userId, new ParameterizedTypeReference<>() {});
        } catch (Exception exception) {
            if (messageContains(exception, "404")) {
                throw new ResponseStatusException(NOT_FOUND, "Keycloaki kasutajat ei leitud.", exception);
            }
            throw keycloakFailure("Kasutaja pärimine ebaõnnestus", exception);
        }
    }

    private List<String> realmRoles(String userId) {
        List<Map<String, Object>> roles = get("/users/" + userId + "/role-mappings/realm", new ParameterizedTypeReference<>() {});
        return roles.stream().map(role -> text(role, "name")).filter(FORESTIQ_ROLES::contains).sorted().toList();
    }

    private Map<String, List<String>> forestIqRolesByUser() {
        Map<String, List<String>> rolesByUser = new LinkedHashMap<>();
        for (String role : FORESTIQ_ROLES) {
            List<Map<String, Object>> members = pagedGet("/roles/" + role + "/users", PAGE_SIZE, MAX_DIRECTORY_USERS);
            for (Map<String, Object> member : members) {
                String userId = text(member, "id");
                if (userId == null || userId.isBlank()) continue;
                rolesByUser.computeIfAbsent(userId, ignored -> new ArrayList<>()).add(role);
            }
        }
        rolesByUser.values().forEach(roles -> roles.sort(String::compareTo));
        return rolesByUser;
    }

    private List<Map<String, Object>> pagedGet(String path, int pageSize, int hardLimit) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int first = 0; first < hardLimit; first += pageSize) {
            String separator = path.contains("?") ? "&" : "?";
            List<Map<String, Object>> page = get(path + separator + "first=" + first + "&max=" + pageSize,
                new ParameterizedTypeReference<>() {});
            if (page == null || page.isEmpty()) break;
            result.addAll(page);
            if (page.size() < pageSize) break;
        }
        if (result.size() >= hardLimit) {
            throw new ResponseStatusException(BAD_GATEWAY,
                "Keycloaki kasutajakataloog ületas ohutuspiiri; täpsusta organisatsiooni kasutajamudelit või suurenda kontrollitud limiiti.");
        }
        return result;
    }

    private List<Map<String, Object>> realmRoleRepresentations(Collection<String> roleNames) {
        List<Map<String, Object>> representations = new ArrayList<>();
        for (String roleName : roleNames) {
            if (!FORESTIQ_ROLES.contains(roleName)) continue;
            representations.add(roleRepresentationCache.computeIfAbsent(roleName,
                key -> get("/roles/" + key, new ParameterizedTypeReference<>() {})));
        }
        return representations;
    }

    private OrganizationUser toOrganizationUser(Map<String, Object> user, List<String> roles) {
        String username = requiredText(user, "username");
        String firstName = text(user, "firstName");
        String lastName = text(user, "lastName");
        String name = String.join(" ", Stream.of(firstName, lastName).filter(value -> value != null && !value.isBlank()).toList());
        return new OrganizationUser(requiredText(user, "id"), username, name.isBlank() ? username : name,
            roles.stream().map(OrganizationRole::parse).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    private boolean belongsToOrganization(Map<String, Object> user, UUID organizationId) {
        Object attributes = user.get("attributes");
        if (!(attributes instanceof Map<?, ?> attributeMap)) return false;
        Object orgs = attributeMap.get("organization_id");
        if (orgs instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).anyMatch(organizationId.toString()::equals);
        }
        return organizationId.toString().equals(String.valueOf(orgs));
    }

    private boolean isEnabled(Map<String, Object> user) {
        Object enabled = user.get("enabled");
        return enabled == null || Boolean.parseBoolean(String.valueOf(enabled));
    }

    private void assertOrganization(Map<String, Object> user, UUID organizationId) {
        if (!belongsToOrganization(user, organizationId)) {
            throw new ResponseStatusException(NOT_FOUND, "Kasutaja ei kuulu aktiivsesse organisatsiooni.");
        }
    }

    private void invalidateOrganizationUsers(UUID organizationId) {
        organizationUsersCache.remove(organizationId);
    }

    private void validateCreateCommand(CreateOrganizationUser request) {
        if (request == null || request.username() == null || request.username().isBlank()
            || request.displayName() == null || request.displayName().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Kasutajanimi ja täisnimi on kohustuslikud.");
        }
        if (request.temporaryPassword() == null || request.temporaryPassword().length() < 12) {
            throw new ResponseStatusException(BAD_REQUEST, "Ajutine parool peab olema vähemalt 12 märki pikk.");
        }
    }

    private static List<String> roleNames(Set<OrganizationRole> roles) {
        if (roles == null || roles.isEmpty()) return List.of();
        return roles.stream().map(OrganizationRole::name).sorted().toList();
    }

    private String accessToken() {
        CachedToken current = cachedToken;
        if (current != null && current.isUsable()) {
            return current.value();
        }
        synchronized (this) {
            current = cachedToken;
            if (current != null && current.isUsable()) {
                return current.value();
            }
            cachedToken = fetchAccessToken();
            return cachedToken.value();
        }
    }

    private CachedToken fetchAccessToken() {
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", properties.getClientId());
            form.add("client_secret", properties.getClientSecret());
            Map<String, Object> token = restClient.post().uri(tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().body(new ParameterizedTypeReference<>() {});
            String accessToken = token == null ? null : text(token, "access_token");
            if (accessToken == null || accessToken.isBlank()) {
                throw new ResponseStatusException(BAD_GATEWAY, "Keycloak ei tagastanud teenusetokenit.");
            }
            long expiresIn = token.get("expires_in") instanceof Number number ? number.longValue() : 60L;
            return new CachedToken(accessToken, Instant.now().plusSeconds(Math.max(1L, expiresIn)));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw keycloakFailure("Keycloaki teenuse autentimine ebaõnnestus", exception);
        }
    }

    private <T> T get(String path, ParameterizedTypeReference<T> type) {
        try {
            return restClient.get().uri(adminUri(path)).headers(headers -> headers.setBearerAuth(accessToken())).retrieve().body(type);
        } catch (Exception exception) {
            throw keycloakFailure("Keycloaki andmepäring ebaõnnestus", exception);
        }
    }

    private void post(String path, Object payload) {
        try {
            restClient.post().uri(adminUri(path)).contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(accessToken())).body(payload).retrieve().toBodilessEntity();
        } catch (Exception exception) {
            throw keycloakFailure("Keycloaki muudatus ebaõnnestus", exception);
        }
    }

    private void delete(String path, Object payload) {
        try {
            restClient.method(org.springframework.http.HttpMethod.DELETE).uri(adminUri(path)).contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(accessToken())).body(payload).retrieve().toBodilessEntity();
        } catch (Exception exception) {
            throw keycloakFailure("Keycloaki rollimuudatus ebaõnnestus", exception);
        }
    }

    private String tokenUri() {
        return normalizedBaseUrl() + "/realms/" + properties.getRealm() + "/protocol/openid-connect/token";
    }

    private String adminUri(String path) {
        return normalizedBaseUrl() + "/admin/realms/" + properties.getRealm() + path;
    }

    private String normalizedBaseUrl() {
        return properties.getBaseUrl().replaceAll("/+$", "");
    }

    private void ensureConfigured() {
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "Keycloak Admin API teenusemandaati ei ole seadistatud.");
        }
    }

    private ResponseStatusException keycloakFailure(String action, Exception exception) {
        return new ResponseStatusException(BAD_GATEWAY, action + ".", exception);
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String requiredText(Map<String, Object> source, String key) {
        String value = text(source, key);
        if (value == null || value.isBlank()) throw new ResponseStatusException(BAD_GATEWAY, "Keycloaki vastuses puudub " + key + ".");
        return value;
    }

    private static boolean messageContains(Exception exception, String fragment) {
        return exception.getMessage() != null && exception.getMessage().contains(fragment);
    }

    private record CachedToken(String value, Instant expiresAt) {
        private boolean isUsable() {
            return Instant.now().plusSeconds(60).isBefore(expiresAt);
        }
    }

    private record CachedUsers(List<OrganizationUser> users, Instant expiresAt) {
        private boolean isUsable() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
