package ee.forestiq.inheritance;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.LEGACY_JAVA_INGESTION,
    reason = "Official notice ingestion remains Java-only until inheritance notices are cut over to Python."
)
@Service
public class InheritanceOfficialNoticeClient {
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final Pattern XML_ENCODING = Pattern.compile(
        "encoding\\s*=\\s*['\"]([A-Za-z0-9._-]+)['\"]", Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;
    private final InheritanceSourceProperties properties;
    private final InheritanceNoticeParser parser;

    @Autowired
    public InheritanceOfficialNoticeClient(RestClient.Builder builder, InheritanceSourceProperties properties,
                                           InheritanceNoticeParser parser) {
        this.properties = properties;
        this.parser = parser;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds())));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())));
        this.restClient = builder.requestFactory(factory).build();
    }

    InheritanceOfficialNoticeClient(RestClient.Builder builder, InheritanceSourceProperties properties) {
        this(builder, properties, new InheritanceNoticeParser());
    }

    public boolean isConfigured() { return properties.isConfigured(); }
    public int autoRefreshBatchSize() { return properties.getAutoRefreshBatchSize(); }

    public String fetchXml(LocalDate day) {
        if (!properties.isDirectConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Pärimisteadete otsene XML-allikas ei ole seadistatud.");
        }
        String url = String.format(properties.getUriTemplate(), day.getYear(), day.getMonthValue(), day.getDayOfMonth());
        ResponseEntity<byte[]> response = restClient.get().uri(url)
            .accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML, MediaType.ALL)
            .header(HttpHeaders.USER_AGENT, properties.getUserAgent())
            .retrieve().toEntity(byte[].class);
        String text = decode(response.getBody() == null ? new byte[0] : response.getBody(),
            response.getHeaders().getContentType());
        String trimmed = text.stripLeading().toLowerCase();
        MediaType contentType = response.getHeaders().getContentType();
        if ((contentType != null && contentType.toString().toLowerCase().contains("html"))
            || trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html")) {
            throw new IllegalStateException("Ametlikud Teadaanded tagastas XML-i asemel HTML-i.");
        }
        return text;
    }

    public List<Map<String, Object>> findByPersonalCode(String personalCode) {
        if (properties.isLegacyConfigured()) {
            String base = properties.getBaseUrl().replaceAll("/+$", "");
            Map<String, Object> response = restClient.get()
                .uri(base + "/api/v1/notices/?personal_code=" + personalCode)
                .headers(headers -> headers.setBearerAuth(properties.getApiToken()))
                .retrieve().body(new ParameterizedTypeReference<>() {});
            return resultList(response);
        }
        LocalDate today = LocalDate.now(java.time.ZoneId.of(properties.getZoneId()));
        return List.of(today, today.minusDays(1)).stream()
            .flatMap(day -> parser.parse(fetchXml(day), day).stream())
            .filter(notice -> personalCode.equals(notice.personalCode()))
            .map(InheritanceOfficialNoticeClient::asMap)
            .toList();
    }

    public Map<String, Object> fetchByNoticeNumber(String noticeNumber) {
        if (noticeNumber == null || noticeNumber.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ametliku teate number on kohustuslik.");
        }
        if (!properties.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Pärimisteadete serveritevaheline liides ei ole seadistatud.");
        }
        if (properties.isLegacyConfigured()) {
            String base = properties.getBaseUrl().replaceAll("/+$", "");
            Map<String, Object> payload = restClient.get()
                .uri(base + "/api/v1/notices/" + java.net.URLEncoder.encode(noticeNumber.trim(),
                    StandardCharsets.UTF_8) + "/")
                .headers(headers -> headers.setBearerAuth(properties.getApiToken()))
                .retrieve().body(new ParameterizedTypeReference<>() {});
            if (payload == null || !noticeNumber.trim().equals(text(payload, "notice_number"))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Pärimisteate allikas tagastas ootamatu teate.");
            }
            return payload;
        }
        LocalDate today = LocalDate.now(java.time.ZoneId.of(properties.getZoneId()));
        return List.of(today, today.minusDays(1)).stream()
            .flatMap(day -> parser.parse(fetchXml(day), day).stream())
            .filter(notice -> noticeNumber.equals(notice.noticeNumber()))
            .findFirst().map(InheritanceOfficialNoticeClient::asMap)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Pärimisteadet tänase ega eilse voo hulgast ei leitud."));
    }

    private static String decode(byte[] body, MediaType contentType) {
        if (body.length == 0) return "";
        if (startsWithBom(body)) return new String(body, UTF8_BOM.length, body.length - UTF8_BOM.length,
            StandardCharsets.UTF_8);
        List<Charset> candidates = new java.util.ArrayList<>();
        if (contentType != null && contentType.getCharset() != null) candidates.add(contentType.getCharset());
        String header = new String(body, 0, Math.min(body.length, 512), StandardCharsets.US_ASCII);
        var match = XML_ENCODING.matcher(header);
        if (match.find()) {
            try { candidates.add(Charset.forName(match.group(1).replace("UTF8", "UTF-8"))); }
            catch (RuntimeException ignored) { }
        }
        candidates.add(StandardCharsets.UTF_8);
        for (Charset charset : candidates) {
            try {
                return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            } catch (CharacterCodingException ignored) { }
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static boolean startsWithBom(byte[] body) {
        return body.length >= 3 && body[0] == UTF8_BOM[0] && body[1] == UTF8_BOM[1] && body[2] == UTF8_BOM[2];
    }
    private static Map<String, Object> asMap(ParsedInheritanceNotice notice) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("notice_number", notice.noticeNumber());
        result.put("source_url", notice.sourceUrl());
        result.put("announcement_date", notice.announcementDate());
        result.put("death_date", notice.deathDate());
        result.put("certification_deadline", notice.certificationDeadline());
        result.put("notary_name", notice.notary());
        result.put("notary_phone", notice.phone());
        result.put("personal_code", notice.personalCode());
        result.put("deceased_name", notice.deceasedName());
        return result;
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resultList(Map<String, Object> response) {
        if (response == null || !(response.get("results") instanceof List<?> raw)) return List.of();
        return raw.stream().filter(Map.class::isInstance).map(item -> {
            Map<String, Object> result = new LinkedHashMap<>();
            ((Map<?, ?>) item).forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }).toList();
    }
    private static String text(Map<String, Object> source, String field) {
        Object value = source.get(field);
        return value == null ? null : String.valueOf(value);
    }
}
