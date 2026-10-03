package ee.forestiq.web;

import java.util.List;
import java.util.Map;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({
        ObjectOptimisticLockingFailureException.class,
        OptimisticLockingFailureException.class,
        OptimisticLockException.class,
        StaleEntityException.class
    })
    ProblemDetail optimisticLockConflict(Exception exception, HttpServletRequest request) {
        return ApiProblemFactory.create(HttpStatus.CONFLICT, "WRITE_CONFLICT",
            "Andmed muutusid vahepeal. Värskenda vaade ja proovi uuesti.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequestBody(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = ApiProblemFactory.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            "Päringu väljad ei ole korrektsed.", request);
        // Preserve the existing public contract while enriching the envelope.
        problem.setTitle("Validation failed");
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
            .map(error -> Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "must be valid" : error.getDefaultMessage()))
            .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler({ ConstraintViolationException.class, HandlerMethodValidationException.class })
    ProblemDetail invalidRequestParameter(Exception exception, HttpServletRequest request) {
        return ApiProblemFactory.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
            "Päringu parameetrid ei ole korrektsed.", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableRequest(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return ApiProblemFactory.create(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
            "Päringu keha ei ole korrektne JSON.", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail accessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return ApiProblemFactory.create(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
            "Sul puudub selle tegevuse jaoks õigus.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail missingResource(NoResourceFoundException exception, HttpServletRequest request) {
        return ApiProblemFactory.create(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
            "Ressurssi ei leitud.", request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail responseStatus(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = exception.getReason();
        if (message == null || message.isBlank()) message = status.getReasonPhrase();
        return ApiProblemFactory.create(status, codeFor(status), message, request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        String correlationId = ApiRequestIdFilter.correlationId(request);
        log.error("Unhandled API exception correlationId={}", correlationId, exception);
        return ApiProblemFactory.create(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Serveris tekkis ootamatu viga.", request);
    }

    public static ResponseStatusException staleEntity() {
        return new StaleEntityException();
    }

    private static String codeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "INVALID_REQUEST";
            case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
            case FORBIDDEN -> "ACCESS_DENIED";
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            case CONFLICT -> "CONFLICT";
            case TOO_MANY_REQUESTS -> "RATE_LIMITED";
            case SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE";
            default -> status.is4xxClientError() ? "REQUEST_REJECTED" : "INTERNAL_ERROR";
        };
    }

    private static final class StaleEntityException extends ResponseStatusException {
        private StaleEntityException() {
            super(HttpStatus.CONFLICT, "Andmed muutusid vahepeal. Värskenda vaade ja proovi uuesti.");
        }
    }
}
