package ee.forestiq.inheritance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class InheritanceOfficialNoticeClientTest {

    @Test
    void reportsConfigurationStateAndBatchSizeFromProperties() {
        InheritanceSourceProperties properties = new InheritanceSourceProperties();
        properties.setAutoRefreshBatchSize(37);
        InheritanceOfficialNoticeClient client = new InheritanceOfficialNoticeClient(RestClient.builder(), properties);

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.autoRefreshBatchSize()).isEqualTo(37);

        properties.setBaseUrl("https://inheritance.example.test");
        properties.setApiToken("test-token");

        assertThat(client.isConfigured()).isTrue();
    }

    @Test
    void fetchByNoticeNumberRejectsMissingConfigurationBeforeHttpCall() {
        InheritanceOfficialNoticeClient client = new InheritanceOfficialNoticeClient(RestClient.builder(), new InheritanceSourceProperties());

        assertThatThrownBy(() -> client.fetchByNoticeNumber("PN-2026-001"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("ei ole seadistatud");
    }

    @Test
    void fetchByNoticeNumberRequiresNoticeNumber() {
        InheritanceSourceProperties properties = new InheritanceSourceProperties();
        properties.setBaseUrl("https://inheritance.example.test");
        properties.setApiToken("test-token");
        InheritanceOfficialNoticeClient client = new InheritanceOfficialNoticeClient(RestClient.builder(), properties);

        assertThatThrownBy(() -> client.fetchByNoticeNumber(" "))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("number on kohustuslik");
    }
}
