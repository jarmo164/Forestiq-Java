package ee.forestiq.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Creates the stable RFC 9457-based ForestIQ API error envelope. */
public final class ApiProblemFactory {
    private ApiProblemFactory() { }

    public static ProblemDetail create(HttpStatus status, String code, String message, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        problem.setProperty("message", message);
        problem.setProperty("correlationId", ApiRequestIdFilter.correlationId(request));
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        return problem;
    }
}
