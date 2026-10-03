package ee.forestiq.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CadastreCsvImportServiceTest {

    @Test
    void detectsForestIqSemicolonDelimiter() {
        assertThat(CadastreCsvImportService.detectDelimiter(
            "cadastral_code;address;boundary_geojson")).isEqualTo(';');
    }

    @Test
    void parsesQuotedSemicolonsAndGeoJsonQuotes() {
        String record = "17101:001:2307;\"Tallinn; test\";\"{\"\"type\"\":\"\"Polygon\"\",\"\"coordinates\"\":[[]]}\"";

        var values = CadastreCsvImportService.parseRecord(record, ';');

        assertThat(values).containsExactly(
            "17101:001:2307",
            "Tallinn; test",
            "{\"type\":\"Polygon\",\"coordinates\":[[]]}"
        );
    }

    @Test
    void parsesCommaSeparatedHeaderWhenNeeded() {
        assertThat(CadastreCsvImportService.detectDelimiter(
            "cadastral_code,address,county")).isEqualTo(',');
    }
}
