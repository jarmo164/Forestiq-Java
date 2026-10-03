package ee.forestiq.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WeaselOwnershipClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void missingItemsFailsClosed() throws Exception {
        assertBadGateway("{\"totalItems\":1}", "items");
    }

    @Test
    void nonArrayItemsFailsClosed() throws Exception {
        assertBadGateway("{\"items\":{},\"totalItems\":1}", "items");
    }

    @Test
    void invalidIdFailsClosed() throws Exception {
        assertBadGateway("""
            {"items":[{"id":0,"cadastreNo":"12345:001:0001","createdTime":"2026-08-20T10:00:00Z"}]}
            """, ".id");
    }

    @Test
    void blankCadastreFailsClosed() throws Exception {
        assertBadGateway("""
            {"items":[{"id":123,"cadastreNo":"   ","createdTime":"2026-08-20T10:00:00Z"}]}
            """, "cadastreNo");
    }

    @Test
    void invalidCreatedTimeFailsClosed() throws Exception {
        assertBadGateway("""
            {"items":[{"id":123,"cadastreNo":"12345:001:0001","createdTime":"not-a-time"}]}
            """, "createdTime");
    }

    @Test
    void validEmptyItemsArrayIsSuccessfulEmptyPage() throws Exception {
        JsonNode response = objectMapper.readTree("{\"items\":[],\"totalItems\":0}");

        var page = WeaselOwnershipClient.parseResponse(response);

        assertThat(page.totalItems()).isZero();
        assertThat(page.items()).isEmpty();
    }

    @Test
    void validTransitionIsReturnedWithoutDroppingRequiredFields() throws Exception {
        JsonNode response = objectMapper.readTree("""
            {
              "totalItems": 1,
              "items": [{
                "id": 12345,
                "cadastreNo": "12345:001:0001",
                "createdTime": "2026-08-20T10:00:00Z",
                "oldOwners": ["48901010011"],
                "newOwners": ["39001010022"]
              }]
            }
            """);

        var page = WeaselOwnershipClient.parseResponse(response);

        assertThat(page.totalItems()).isEqualTo(1);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().externalId()).isEqualTo(12345L);
        assertThat(page.items().getFirst().cadastralCode()).isEqualTo("12345:001:0001");
        assertThat(page.items().getFirst().oldOwnerIdentifiers()).containsExactly("48901010011");
        assertThat(page.items().getFirst().newOwnerIdentifiers()).containsExactly("39001010022");
    }

    @Test
    void retryPolicyRetriesRateLimitsTimeoutsAndServerErrors() {
        assertThat(WeaselOwnershipClient.isRetryableStatus(HttpStatus.TOO_MANY_REQUESTS)).isTrue();
        assertThat(WeaselOwnershipClient.isRetryableStatus(HttpStatus.REQUEST_TIMEOUT)).isTrue();
        assertThat(WeaselOwnershipClient.isRetryableStatus(HttpStatus.BAD_GATEWAY)).isTrue();
        assertThat(WeaselOwnershipClient.isRetryableStatus(HttpStatus.SERVICE_UNAVAILABLE)).isTrue();
    }

    @Test
    void retryPolicyDoesNotRetryOrdinaryClientErrors() {
        assertThat(WeaselOwnershipClient.isRetryableStatus(HttpStatus.BAD_REQUEST)).isFalse();
        assertThat(WeaselOwnershipClient.isRetryableStatus(HttpStatus.UNAUTHORIZED)).isFalse();
        assertThat(WeaselOwnershipClient.isRetryableStatus(HttpStatus.FORBIDDEN)).isFalse();
        assertThat(WeaselOwnershipClient.isRetryableStatus(HttpStatus.NOT_FOUND)).isFalse();
    }

    @Test
    void retryAfterSecondsAreRespected() {
        assertThat(WeaselOwnershipClient.parseRetryAfterMillis("12", Instant.parse("2026-09-05T15:00:00Z")))
            .isEqualTo(12_000L);
    }

    @Test
    void retryAfterHttpDateIsRespected() {
        Instant now = Instant.parse("2026-09-05T15:00:00Z");

        assertThat(WeaselOwnershipClient.parseRetryAfterMillis("Sat, 05 Sep 2026 15:00:30 GMT", now))
            .isEqualTo(30_000L);
    }

    @Test
    void invalidRetryAfterFallsBackToClientBackoff() {
        assertThat(WeaselOwnershipClient.parseRetryAfterMillis("not-a-delay", Instant.parse("2026-09-05T15:00:00Z")))
            .isNull();
    }

    private void assertBadGateway(String json, String messageFragment) throws Exception {
        JsonNode response = objectMapper.readTree(json);

        assertThatThrownBy(() -> WeaselOwnershipClient.parseResponse(response))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> {
                ResponseStatusException responseException = (ResponseStatusException) exception;
                assertThat(responseException.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                assertThat(responseException.getReason()).contains(messageFragment);
            });
    }
}
