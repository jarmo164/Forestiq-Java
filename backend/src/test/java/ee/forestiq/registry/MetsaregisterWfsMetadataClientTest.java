package ee.forestiq.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class MetsaregisterWfsMetadataClientTest {

    @Test
    void parsesFeatureTypesFromWfs20Capabilities() {
        var layers = MetsaregisterWfsMetadataClient.parseCapabilities("""
            <?xml version="1.0" encoding="UTF-8"?>
            <wfs:WFS_Capabilities xmlns:wfs="http://www.opengis.net/wfs/2.0">
              <wfs:FeatureTypeList>
                <wfs:FeatureType>
                  <wfs:Name>metsaregister:eraldis</wfs:Name>
                  <wfs:Title>Metsaregistri eraldised</wfs:Title>
                  <wfs:DefaultCRS>urn:ogc:def:crs:EPSG::3301</wfs:DefaultCRS>
                </wfs:FeatureType>
                <wfs:FeatureType>
                  <wfs:Name>metsaregister:teatis</wfs:Name>
                  <wfs:Title>Kehtivad metsateatised</wfs:Title>
                  <wfs:DefaultCRS>urn:ogc:def:crs:EPSG::3301</wfs:DefaultCRS>
                </wfs:FeatureType>
              </wfs:FeatureTypeList>
            </wfs:WFS_Capabilities>
            """);

        assertEquals(2, layers.size());
        assertEquals("metsaregister:eraldis", layers.get(0).name());
        assertEquals("Metsaregistri eraldised", layers.get(0).title());
        assertEquals("urn:ogc:def:crs:EPSG::3301", layers.get(0).defaultCrs());
        assertEquals("metsaregister:teatis", layers.get(1).name());
    }

    @Test
    void parsesDescribeFeatureTypeIncludingGeometry() {
        var schema = MetsaregisterWfsMetadataClient.parseDescribeFeatureType("metsaregister:eraldis", """
            <?xml version="1.0" encoding="UTF-8"?>
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                        xmlns:gml="http://www.opengis.net/gml/3.2">
              <xsd:complexType name="eraldisType">
                <xsd:complexContent>
                  <xsd:extension base="gml:AbstractFeatureType">
                    <xsd:sequence>
                      <xsd:element name="sys_id" type="xsd:long" minOccurs="0"/>
                      <xsd:element name="katastri_nr" type="xsd:string" minOccurs="0"/>
                      <xsd:element name="geom" type="gml:MultiSurfacePropertyType" minOccurs="0"/>
                    </xsd:sequence>
                  </xsd:extension>
                </xsd:complexContent>
              </xsd:complexType>
            </xsd:schema>
            """);

        assertEquals("metsaregister:eraldis", schema.typeName());
        assertEquals(3, schema.properties().size());
        assertEquals("geom", schema.geometryProperty());
        assertEquals("gml:MultiSurfacePropertyType", schema.geometryType());
        assertFalse(schema.properties().get(0).geometry());
        assertTrue(schema.properties().get(2).geometry());
    }

    @Test
    void buildsWfs20GetCapabilitiesRequest() {
        RegistryProperties properties = new RegistryProperties();
        AtomicReference<URI> requested = new AtomicReference<>();
        MetsaregisterWfsMetadataClient client = new MetsaregisterWfsMetadataClient(properties, uri -> {
            requested.set(uri);
            return """
                <wfs:WFS_Capabilities xmlns:wfs="http://www.opengis.net/wfs/2.0">
                  <wfs:FeatureTypeList><wfs:FeatureType>
                    <wfs:Name>metsaregister:eraldis</wfs:Name>
                    <wfs:Title>Eraldis</wfs:Title>
                  </wfs:FeatureType></wfs:FeatureTypeList>
                </wfs:WFS_Capabilities>
                """;
        });

        client.getCapabilities();

        String query = requested.get().getQuery();
        assertTrue(query.contains("service=WFS"));
        assertTrue(query.contains("version=2.0.0"));
        assertTrue(query.contains("request=GetCapabilities"));
    }

    @Test
    void buildsDescribeFeatureTypeRequestForValidatedLayer() {
        RegistryProperties properties = new RegistryProperties();
        AtomicReference<URI> requested = new AtomicReference<>();
        MetsaregisterWfsMetadataClient client = new MetsaregisterWfsMetadataClient(properties, uri -> {
            requested.set(uri);
            return """
                <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema">
                  <xsd:complexType name="lookupType"><xsd:sequence>
                    <xsd:element name="id" type="xsd:long"/>
                  </xsd:sequence></xsd:complexType>
                </xsd:schema>
                """;
        });

        client.describeFeatureType("metsaregister:kl_puuliik");

        String query = requested.get().getQuery();
        assertTrue(query.contains("request=DescribeFeatureType"));
        assertTrue(query.contains("typeNames=metsaregister:kl_puuliik"));
    }

    @Test
    void rejectsDoctypeAndExternalEntityPayloads() {
        assertThrows(ExternalRegistryService.RegistryException.class, () ->
            MetsaregisterWfsMetadataClient.parseCapabilities("""
                <?xml version="1.0"?>
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <wfs:WFS_Capabilities xmlns:wfs="http://www.opengis.net/wfs/2.0">
                  <wfs:FeatureTypeList><wfs:FeatureType>
                    <wfs:Name>metsaregister:eraldis</wfs:Name>
                    <wfs:Title>&xxe;</wfs:Title>
                  </wfs:FeatureType></wfs:FeatureTypeList>
                </wfs:WFS_Capabilities>
                """));
    }
}
