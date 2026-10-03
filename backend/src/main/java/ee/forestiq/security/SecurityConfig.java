package ee.forestiq.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import ee.forestiq.web.ApiSecurityProblemHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final List<String> allowedOrigins;
    private final String resourceClientId;

    public SecurityConfig(@Value("${forestiq.cors.allowed-origins}") String allowedOrigins,
                          @Value("${forestiq.security.resource-client-id:forestiq-api}") String resourceClientId) {
        this.allowedOrigins = List.of(allowedOrigins.split(",")).stream()
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .toList();
        this.resourceClientId = resourceClientId;
    }

    @Bean
    ApiSecurityProblemHandler apiSecurityProblemHandler(ObjectMapper objectMapper) {
        return new ApiSecurityProblemHandler(objectMapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiSecurityProblemHandler securityProblems) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(securityProblems)
                .accessDeniedHandler(securityProblems))
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/api/openapi/**", "/api/swagger-ui.html", "/api/swagger-ui/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                .authenticationEntryPoint(securityProblems)
                .accessDeniedHandler(securityProblems)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
            .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRoleConverter(resourceClientId));
        return converter;
    }

    private static final class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        private final String resourceClientId;

        private KeycloakRoleConverter(String resourceClientId) {
            this.resourceClientId = resourceClientId;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Set<String> roles = new LinkedHashSet<>();
            Object realmAccess = jwt.getClaim("realm_access");
            if (realmAccess instanceof Map<?, ?> realmMap && realmMap.get("roles") instanceof Collection<?> realmRoles) {
                realmRoles.stream().map(Object::toString).forEach(roles::add);
            }
            Object resourceAccess = jwt.getClaim("resource_access");
            if (resourceAccess instanceof Map<?, ?> resources
                && resources.get(resourceClientId) instanceof Map<?, ?> resource
                && resource.get("roles") instanceof Collection<?> resourceRoles) {
                resourceRoles.stream().map(String::valueOf).forEach(roles::add);
            }
            return roles.stream()
                .filter(role -> role.startsWith("ORG_") || role.equals("CRM_MANAGER") || role.equals("EVALUATOR") || role.equals("CALLER") || role.equals("VIEWER"))
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toUnmodifiableSet());
        }
    }
}
