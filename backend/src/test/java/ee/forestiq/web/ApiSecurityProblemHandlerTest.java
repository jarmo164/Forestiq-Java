package ee.forestiq.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

class ApiSecurityProblemHandlerTest {
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final ApiSecurityProblemHandler handler = new ApiSecurityProblemHandler(objectMapper);

    @Test
    void authenticationFailureUsesStandardEnvelopeAndCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/owners");
        request.setAttribute(ApiRequestIdFilter.ATTRIBUTE, "request-401");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(request, response, new BadCredentialsException("bad token"));

        var body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(body.path("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(body.path("correlationId").asText()).isEqualTo("request-401");
        assertThat(body.path("message").asText()).isNotBlank();
    }

    @Test
    void authorizationFailureUsesStandardEnvelopeAndCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        request.setAttribute(ApiRequestIdFilter.ATTRIBUTE, "request-403");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("forbidden"));

        var body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.path("code").asText()).isEqualTo("ACCESS_DENIED");
        assertThat(body.path("correlationId").asText()).isEqualTo("request-403");
    }
}
