package ee.forestiq.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiProblemContractTest {

    @Test
    void requestIdFilterPreservesSafeCallerIdAndExposesItToProblemEnvelope() throws Exception {
        ApiRequestIdFilter filter = new ApiRequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/owners");
        request.addHeader(ApiRequestIdFilter.HEADER, "client-request-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(ApiRequestIdFilter.HEADER)).isEqualTo("client-request-42");
        var problem = ApiProblemFactory.create(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", request);
        assertThat(problem.getProperties()).containsEntry("code", "INVALID_REQUEST");
        assertThat(problem.getProperties()).containsEntry("message", "Invalid request");
        assertThat(problem.getProperties()).containsEntry("correlationId", "client-request-42");
    }

    @Test
    void unsafeRequestIdIsReplacedWithServerGeneratedValue() throws Exception {
        ApiRequestIdFilter filter = new ApiRequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/owners");
        request.addHeader(ApiRequestIdFilter.HEADER, "bad request id with spaces\nforged-log-line");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String requestId = response.getHeader(ApiRequestIdFilter.HEADER);
        assertThat(requestId).isNotBlank().doesNotContain(" ", "\n");
        assertThat(request.getAttribute(ApiRequestIdFilter.ATTRIBUTE)).isEqualTo(requestId);
    }
}
