package ee.forestiq.registry;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Production adapters for Estonian official registry interfaces.
 *
 * <p>The cadastral and forest adapters consume the documented public OGC WFS
 * endpoints. This is more resilient than portal-internal REST paths because
 * feature types are discoverable through GetCapabilities and schema changes
 * can be verified through DescribeFeatureType. RIK remains opt-in because it
 * requires an organisation's contracted SOAP service account.</p>
 */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.REAL_TIME_DOMAIN_OPERATION,
    reason = "Parcel registry lookup is request-scoped domain support and not a scheduled batch ETL job."
)
@Service
public class ExternalRegistryService {

    private static final String RIK_NAMESPACE = "http://kinnistusraamat.rik.ee/krteenused/";
    private static final Pattern CADASTRAL_CODE = Pattern.compile("^\\d{5}:\\d{3}:\\d{4}$");

    private final RestClient maaametClient;
    private final RestClient metsaregisterClient;
    private final RestClient rikClient;
    private final ObjectMapper objectMapper;
    private final RegistryProperties properties;
    private final MetsaregisterWfsClient metsaregisterWfsClient;

    public ExternalRegistryService(ObjectMapper objectMapper, RegistryProperties properties,
                                   MetsaregisterWfsClient metsaregisterWfsClient) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metsaregisterWfsClient = metsaregisterWfsClient;
        this.maaametClient = RestClient.builder().requestFactory(requestFactory(properties.getMaaamet().getConnectTimeoutSeconds())).build();
        this.metsaregisterClient = RestClient.builder().requestFactory(requestFactory(properties.getMetsaregister().getConnectTimeoutSeconds())).build();
        this.rikClient = RestClient.builder().requestFactory(requestFactory(20)).build();
    }

    /** Fetches current cadastral attributes and GeoJSON boundary from official Maa-amet WFS. */
    public Optional<MaaametCadastre> fetchMaaametCadastre(String cadastralCode) {
        validateCadastreCode(cadastralCode);
        try {
            return withRetry("Maa-ameti WFS", properties.getMaaamet().getMaxAttempts(), () -> {
                URI uri = UriComponentsBuilder.fromUriString(properties.getMaaamet().getWfsUrl())
                    .queryParam("service", "WFS")
                    .queryParam("version", "2.0.0")
                    .queryParam("request", "GetFeature")
                    .queryParam("typeNames", "kataster:ky_kehtiv")
                    .queryParam("outputFormat", "application/json")
                    .queryParam("srsName", "EPSG:4326")
                    .queryParam("CQL_FILTER", "tunnus='" + escapeCql(cadastralCode) + "'")
                    .encode()
                    .build()
                    .toUri();
                JsonNode response = maaametClient.get().uri(uri).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
                if (response == null || !response.path("features").isArray() || response.path("features").isEmpty()) return Optional.empty();
                JsonNode feature = response.path("features").get(0);
                JsonNode p = feature.path("properties");
                return Optional.of(new MaaametCadastre(
                    cadastralCode, text(p, "l_aadress"), text(p, "mk_nimi"), text(p, "ov_nimi"), text(p, "kinnistu"),
                    text(p, "omvorm"), squareMetersToHectares(p.path("pindala")), squareMetersToHectares(p.path("mets")),
                    squareMetersToHectares(p.path("haritav")), squareMetersToHectares(p.path("ouemaa")),
                    squareMetersToHectares(p.path("muumaa")), serializeGeometry(feature.path("geometry")), response));
            });
        } catch (Exception exception) {
            throw new RegistryException("Maa-ameti WFS päring ebaõnnestus: " + cadastralCode, exception);
        }
    }

    /** Fetches public forest-notification features from the official Metsaregister WFS layer. */
    public ForestRegistryResponse fetchForestNotifications(String cadastralCode) {
        return fetchForestWfs("Metsateatiste", cadastralCode, "metsaregister:teatis");
    }

    /** Fetches public forest-compartment features from the official Metsaregister WFS layer. */
    public ForestRegistryResponse fetchForestPlan(String cadastralCode) {
        return fetchForestWfs("Metsakorralduskava", cadastralCode, "metsaregister:eraldis");
    }

    /**
     * Calls RIK's official objektide_arv SOAP operation. It is intentionally
     * unavailable until the organisation has configured its contracted service account.
     */
    public int fetchRikCadastreCount(String personalOrRegistryCode, String firstName, String lastNameOrCompany) {
        if (!properties.getRik().isConfigured()) throw new RegistryConfigurationException("RIK Kinnistusraamatu teenusekonto ei ole seadistatud.");
        String endpoint = properties.getRik().getSoapWsdlUrl().replaceAll("(?i)\\?WSDL$", "");
        String envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
              <soap:Body><objektide_arv xmlns="%s"><kood>%s</kood><eesnimi>%s</eesnimi><perenimi_firma>%s</perenimi_firma><kasutajanimi>%s</kasutajanimi><parool>%s</parool></objektide_arv></soap:Body>
            </soap:Envelope>
            """.formatted(RIK_NAMESPACE, xml(personalOrRegistryCode), xml(firstName), xml(lastNameOrCompany),
                xml(properties.getRik().getUsername()), xml(properties.getRik().getPassword()));
        try {
            String response = withRetry("RIK SOAP", 2, () -> rikClient.post().uri(endpoint).contentType(MediaType.TEXT_XML)
                .header("SOAPAction", RIK_NAMESPACE + "objektide_arv").body(envelope).retrieve().body(String.class));
            return parseRikCount(response);
        } catch (Exception exception) {
            throw new RegistryException("RIK Kinnistusraamatu SOAP päring ebaõnnestus", exception);
        }
    }

    /** Uses the documented GetCapabilities contract instead of hard-coding a portal health assumption. */
    public RegistryHealth checkHealth() {
        return new RegistryHealth(
            supportsLayer(properties.getMaaamet().getWfsUrl(), "kataster:ky_kehtiv", maaametClient, properties.getMaaamet().getMaxAttempts()),
            supportsLayer(properties.getMetsaregister().getWfsUrl(), "metsaregister:teatis", metsaregisterClient, properties.getMetsaregister().getMaxAttempts()),
            supportsLayer(properties.getMetsaregister().getWfsUrl(), "metsaregister:eraldis", metsaregisterClient, properties.getMetsaregister().getMaxAttempts()),
            properties.getRik().isConfigured());
    }

    private ForestRegistryResponse fetchForestWfs(String serviceName, String cadastralCode, String typeName) {
        validateCadastreCode(cadastralCode);
        try {
            String cqlFilter = "katastri_nr='" + escapeCql(cadastralCode) + "'";
            List<ForestRecord> records = new ArrayList<>();
            MetsaregisterWfsClient.FetchSummary summary = metsaregisterWfsClient.fetchPages(
                typeName, cqlFilter,
                page -> page.features().forEach(feature -> mapForestFeature(feature, records)));
            ObjectNode auditSummary = objectMapper.createObjectNode();
            auditSummary.put("typeName", typeName);
            auditSummary.put("cqlFilter", cqlFilter);
            auditSummary.put("pages", summary.pages());
            auditSummary.put("records", summary.records());
            if (summary.numberMatched() >= 0) auditSummary.put("numberMatched", summary.numberMatched());
            return new ForestRegistryResponse(serviceName, summary.lastRequestUrl(), auditSummary, List.copyOf(records));
        } catch (Exception exception) {
            throw new RegistryException(serviceName + " WFS päring ebaõnnestus: " + cadastralCode, exception);
        }
    }

    private boolean supportsLayer(String baseUrl, String expectedLayer, RestClient client, int attempts) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(baseUrl).queryParam("service", "WFS").queryParam("request", "GetCapabilities").encode().build().toUri();
            String xml = withRetry("WFS GetCapabilities", attempts, () -> client.get().uri(uri).accept(MediaType.APPLICATION_XML).retrieve().body(String.class));
            return xml != null && xml.contains(">" + expectedLayer + "<");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void mapForestFeature(JsonNode feature, List<ForestRecord> records) {
        JsonNode p = feature.path("properties");
        if (!p.isObject()) return;
        Long id = longValue(p, "sys_id", "id");
        String title = firstText(p, "teatise_nr", "eraldise_nr", "kinnistu_nimetus", "kinnistu_nr");
        records.add(new ForestRecord(id, title, text(p, "too_kood"), text(p, "otsus"),
            decimal(p, "pindala"), decimal(p, "raiutav_maht", "tagavara_y_ha"), p));
    }

    private static SimpleClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        factory.setReadTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)));
        return factory;
    }

    private static <T> T withRetry(String service, int configuredAttempts, Supplier<T> request) {
        int attempts = Math.max(1, Math.min(configuredAttempts, 5));
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try { return request.get(); }
            catch (RuntimeException exception) {
                last = exception;
                if (attempt < attempts) {
                    try { Thread.sleep(250L * attempt); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw exception; }
                }
            }
        }
        throw new RegistryException(service + " ei vastanud pärast " + attempts + " katset", last);
    }

    private String serializeGeometry(JsonNode geometry) {
        if (geometry == null || geometry.isMissingNode() || geometry.isNull() || !geometry.isObject()) {
            return null;
        }
        if (geometry.has("crs") && geometry instanceof ObjectNode objectNode) {
            objectNode = objectNode.deepCopy();
            objectNode.remove("crs");
            return serialize(objectNode);
        }
        return serialize(geometry);
    }

    private String serialize(JsonNode node) {
        try { return objectMapper.writeValueAsString(node); }
        catch (Exception exception) { throw new RegistryException("WFS geomeetria serialiseerimine ebaõnnestus", exception); }
    }

    private static void validateCadastreCode(String code) {
        if (code == null || !CADASTRAL_CODE.matcher(code).matches()) throw new RegistryException("Vigane katastritunnuse vorming");
    }
    private static BigDecimal squareMetersToHectares(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) return null;
        return node.decimalValue().divide(BigDecimal.valueOf(10_000), 4, RoundingMode.HALF_UP);
    }
    private static String text(JsonNode node, String key) { JsonNode value = node.path(key); return value.isMissingNode() || value.isNull() ? null : value.asText(); }
    private static String firstText(JsonNode node, String... keys) { for (String key : keys) { String value = text(node, key); if (value != null && !value.isBlank()) return value; } return null; }
    private static BigDecimal decimal(JsonNode node, String... keys) { for (String key : keys) { JsonNode value = node.path(key); if (value.isNumber()) return value.decimalValue(); if (value.isTextual()) try { return new BigDecimal(value.asText()); } catch (NumberFormatException ignored) { } } return null; }
    private static Long longValue(JsonNode node, String... keys) { for (String key : keys) { JsonNode value = node.path(key); if (value.isIntegralNumber()) return value.asLong(); if (value.isTextual()) try { return Long.valueOf(value.asText()); } catch (NumberFormatException ignored) { } } return null; }
    private static String escapeCql(String value) { return value.replace("'", "''"); }
    private static String xml(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
    private static int parseRikCount(String xml) {
        if (xml == null) throw new RegistryException("RIK teenus tagastas tühja vastuse");
        java.util.regex.Matcher error = java.util.regex.Pattern.compile("<veateade[^>]*>(.*?)</veateade>", java.util.regex.Pattern.DOTALL).matcher(xml);
        if (error.find() && !error.group(1).isBlank()) throw new RegistryException("RIK teenuse viga: " + error.group(1));
        java.util.regex.Matcher count = java.util.regex.Pattern.compile("<arv[^>]*>\\s*(\\d+)\\s*</arv>").matcher(xml);
        if (!count.find()) throw new RegistryException("RIK teenuse vastuses puudub objektide arv");
        return Integer.parseInt(count.group(1));
    }

    public record MaaametCadastre(String cadastralCode, String address, String county, String municipality, String registryPartNumber,
        String ownershipForm, BigDecimal areaHectares, BigDecimal forestHectares, BigDecimal arableHectares,
        BigDecimal yardHectares, BigDecimal otherHectares, String boundaryGeoJson, JsonNode rawResponse) { }
    public record ForestRecord(Long sourceId, String title, String workCode, String decision, BigDecimal area, BigDecimal volume, JsonNode raw) { }
    public record ForestRegistryResponse(String serviceName, String requestUrl, JsonNode rawResponse, List<ForestRecord> records) { }
    public record RegistryHealth(boolean maaametCadastreWfs, boolean metsaregisterNotificationsWfs, boolean metsaregisterCompartmentsWfs, boolean rikConfigured) { }
    public static class RegistryException extends RuntimeException { public RegistryException(String message) { super(message); } public RegistryException(String message, Throwable cause) { super(message, cause); } }
    public static final class RegistryConfigurationException extends RegistryException { public RegistryConfigurationException(String message) { super(message); } }
}
