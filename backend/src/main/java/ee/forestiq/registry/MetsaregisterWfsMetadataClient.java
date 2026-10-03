package ee.forestiq.registry;

import ee.forestiq.integration.ExternalSystemBoundary;
import java.io.StringReader;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Reads provider metadata separately from GeoJSON feature paging. */
@ExternalSystemBoundary(
    value = ExternalSystemBoundary.Kind.LEGACY_JAVA_INGESTION,
    reason = "Metsaregister WFS metadata ingestion remains Java-only until Python WFS cutover evidence is complete."
)
@Component
class MetsaregisterWfsMetadataClient {
    private static final int MAX_METADATA_BYTES = 4 * 1024 * 1024;
    private static final Pattern SAFE_TYPE_NAME = Pattern.compile("^[A-Za-z0-9_-]+:[A-Za-z0-9_-]+$");

    private final RegistryProperties.Metsaregister properties;
    private final MetadataFetcher fetcher;

    @Autowired
    MetsaregisterWfsMetadataClient(RegistryProperties registryProperties) {
        this.properties = registryProperties.getMetsaregister();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getConnectTimeoutSeconds()));
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        RestClient client = RestClient.builder().requestFactory(requestFactory).build();
        this.fetcher = uri -> client.get().uri(uri)
            .accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
            .retrieve().body(String.class);
    }

    MetsaregisterWfsMetadataClient(RegistryProperties registryProperties, MetadataFetcher fetcher) {
        this.properties = registryProperties.getMetsaregister();
        this.fetcher = fetcher;
    }

    List<FeatureType> getCapabilities() {
        URI uri = UriComponentsBuilder.fromUriString(properties.getWfsUrl())
            .queryParam("service", "WFS")
            .queryParam("version", "2.0.0")
            .queryParam("request", "GetCapabilities")
            .encode()
            .build()
            .toUri();
        return parseCapabilities(fetcher.fetch(uri));
    }

    FeatureSchema describeFeatureType(String typeName) {
        validateTypeName(typeName);
        URI uri = UriComponentsBuilder.fromUriString(properties.getWfsUrl())
            .queryParam("service", "WFS")
            .queryParam("version", "2.0.0")
            .queryParam("request", "DescribeFeatureType")
            .queryParam("typeNames", typeName)
            .encode()
            .build()
            .toUri();
        return parseDescribeFeatureType(typeName, fetcher.fetch(uri));
    }

    static List<FeatureType> parseCapabilities(String xml) {
        var document = parseXml(xml, "GetCapabilities");
        NodeList featureTypes = document.getElementsByTagNameNS("*", "FeatureType");
        List<FeatureType> result = new ArrayList<>();
        for (int i = 0; i < featureTypes.getLength(); i++) {
            Node node = featureTypes.item(i);
            if (!(node instanceof Element element)) continue;
            String name = childText(element, "Name");
            if (name == null || !SAFE_TYPE_NAME.matcher(name).matches()) continue;
            String title = childText(element, "Title");
            String defaultCrs = childText(element, "DefaultCRS");
            if (defaultCrs == null) defaultCrs = childText(element, "DefaultSRS");
            result.add(new FeatureType(name, title == null ? name : title, defaultCrs));
        }
        if (result.isEmpty()) {
            throw new ExternalRegistryService.RegistryException(
                "Metsaregister WFS GetCapabilities contained no usable feature types");
        }
        return List.copyOf(result);
    }

    static FeatureSchema parseDescribeFeatureType(String typeName, String xml) {
        validateTypeName(typeName);
        var document = parseXml(xml, "DescribeFeatureType");
        Map<String, Property> properties = new LinkedHashMap<>();
        NodeList sequences = document.getElementsByTagNameNS("*", "sequence");
        for (int i = 0; i < sequences.getLength(); i++) {
            NodeList children = sequences.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (!(child instanceof Element element) || !"element".equals(element.getLocalName())) continue;
                String name = normalizedAttribute(element, "name");
                if (name == null) continue;
                String type = normalizedAttribute(element, "type");
                if (type == null) type = normalizedAttribute(element, "ref");
                if (type == null) type = "unknown";
                properties.putIfAbsent(name, new Property(name, type, isGeometryType(type)));
            }
        }
        if (properties.isEmpty()) {
            throw new ExternalRegistryService.RegistryException(
                "Metsaregister WFS DescribeFeatureType contained no properties for " + typeName);
        }
        Property geometry = properties.values().stream().filter(Property::geometry).findFirst().orElse(null);
        return new FeatureSchema(typeName, List.copyOf(properties.values()),
            geometry == null ? null : geometry.name(), geometry == null ? null : geometry.type());
    }

    private static org.w3c.dom.Document parseXml(String xml, String operation) {
        if (xml == null || xml.isBlank()) {
            throw new ExternalRegistryService.RegistryException("Metsaregister WFS " + operation + " returned no XML");
        }
        String payload = xml.strip();
        if (payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) {
            throw new ExternalRegistryService.RegistryException("Metsaregister WFS metadata exceeded the safety limit");
        }
        try {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(payload)));
        } catch (Exception exception) {
            throw new ExternalRegistryService.RegistryException(
                "Metsaregister WFS " + operation + " XML could not be parsed", exception);
        }
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static void validateTypeName(String typeName) {
        if (typeName == null || !SAFE_TYPE_NAME.matcher(typeName).matches()) {
            throw new IllegalArgumentException("Metsaregister WFS typeName is invalid");
        }
    }

    private static String childText(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) return null;
        String value = nodes.item(0).getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizedAttribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isGeometryType(String type) {
        String normalized = type.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("gml:") && (normalized.contains("geometry") || normalized.contains("point")
            || normalized.contains("curve") || normalized.contains("line") || normalized.contains("surface")
            || normalized.contains("polygon"));
    }

    @FunctionalInterface
    interface MetadataFetcher { String fetch(URI uri); }

    record FeatureType(String name, String title, String defaultCrs) { }
    record Property(String name, String type, boolean geometry) { }
    record FeatureSchema(String typeName, List<Property> properties, String geometryProperty, String geometryType) { }
}
