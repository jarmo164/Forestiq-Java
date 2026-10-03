package ee.forestiq.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class OwnerImportFileParserTest {

    private final OwnerImportFileParser parser = new OwnerImportFileParser();

    @Test
    void parsesQuotedSemicolonCsvAndKeepsHeaders() {
        String csv = "Nimi;Registrikood;Info\n\"Metsa; OÜ\";12345678;\"rida 1\n rida 2\"\n";
        var file = new MockMultipartFile("file", "owners.csv", "text/csv", csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var parsed = parser.parse(file);

        assertThat(parsed.headers()).containsExactly("Nimi", "Registrikood", "Info");
        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().getFirst()).containsEntry("Nimi", "Metsa; OÜ")
            .containsEntry("Registrikood", "12345678")
            .containsEntry("Info", "rida 1\n rida 2");
    }

    @Test
    void rejectsDuplicateHeaders() {
        var file = new MockMultipartFile("file", "owners.csv", "text/csv", "Nimi,nimi\nA,B\n".getBytes());

        assertThatThrownBy(() -> parser.parse(file))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("korduv veerupäis");
    }

    @Test
    void readsXlsxButRejectsFormulaCells() throws Exception {
        byte[] safeWorkbook;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Owners");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Nimi");
            header.createCell(1).setCellValue("Registrikood");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Mari Mets");
            row.createCell(1).setCellValue("48901010011");
            workbook.write(output);
            safeWorkbook = output.toByteArray();
        }
        var safe = new MockMultipartFile("file", "owners.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", safeWorkbook);
        assertThat(parser.parse(safe).rows()).hasSize(1);

        byte[] formulaWorkbook;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Owners");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("Nimi");
            header.createCell(1).setCellValue("Registrikood");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Mari Mets");
            row.createCell(1).setCellFormula("1+1");
            workbook.write(output);
            formulaWorkbook = output.toByteArray();
        }
        var formula = new MockMultipartFile("file", "owners.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", formulaWorkbook);

        assertThatThrownBy(() -> parser.parse(formula))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("valemid ei ole impordis lubatud");
    }
}
