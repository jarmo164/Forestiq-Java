package ee.forestiq.owner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
class OwnerImportFileParser {

    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final int MAX_DATA_ROWS = 10_000;
    private static final int MAX_COLUMNS = 100;

    ParsedFile parse(MultipartFile file) {
        if (file == null || file.isEmpty()) throw badRequest("Vali CSV- või XLSX-fail.");
        if (file.getSize() > MAX_FILE_BYTES) throw badRequest("Impordifail võib olla kuni 10 MB.");
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = file.getBytes();
            if (filename.endsWith(".csv")) return parseCsv(bytes);
            if (filename.endsWith(".xlsx")) return parseXlsx(bytes);
            throw badRequest("Toetatud failivormingud on .csv ja .xlsx.");
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Impordifaili ei õnnestunud lugeda.", exception);
        }
    }

    private ParsedFile parseCsv(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        char delimiter = detectDelimiter(text);
        List<List<String>> records = csvRecords(text, delimiter);
        if (records.isEmpty()) throw badRequest("CSV-failis puudub päiserida.");
        List<String> headers = validateHeaders(records.getFirst());
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.size() > headers.size()) throw badRequest("CSV real " + (i + 1) + " on rohkem veerge kui päisereas.");
            Map<String, String> row = row(headers, record);
            if (!blank(row)) rows.add(row);
            if (rows.size() > MAX_DATA_ROWS) throw badRequest("Impordifailis võib olla kuni 10 000 andmerida.");
        }
        return new ParsedFile(headers, rows);
    }

    private ParsedFile parseXlsx(byte[] bytes) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) throw badRequest("XLSX-failis puudub tööleht.");
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) throw badRequest("XLSX-failis puudub päiserida.");
            int width = headerRow.getLastCellNum();
            if (width <= 0 || width > MAX_COLUMNS) throw badRequest("Impordifaili päisereas peab olema 1–100 veergu.");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> rawHeaders = new ArrayList<>();
            for (int column = 0; column < width; column++) {
                rawHeaders.add(cell(formatter, headerRow.getCell(column), headerRow.getRowNum() + 1, column + 1));
            }
            List<String> headers = validateHeaders(rawHeaders);
            List<Map<String, String>> rows = new ArrayList<>();
            for (int rowIndex = headerRow.getRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row source = sheet.getRow(rowIndex);
                if (source == null) continue;
                List<String> values = new ArrayList<>();
                for (int column = 0; column < headers.size(); column++) {
                    values.add(cell(formatter, source.getCell(column), rowIndex + 1, column + 1));
                }
                Map<String, String> row = row(headers, values);
                if (!blank(row)) rows.add(row);
                if (rows.size() > MAX_DATA_ROWS) throw badRequest("Impordifailis võib olla kuni 10 000 andmerida.");
            }
            return new ParsedFile(headers, rows);
        }
    }

    private String cell(DataFormatter formatter, Cell cell, int row, int column) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) {
            throw badRequest("XLSX valemid ei ole impordis lubatud (rida " + row + ", veerg " + column + ").");
        }
        return formatter.formatCellValue(cell).trim();
    }

    private static List<String> validateHeaders(List<String> raw) {
        if (raw.isEmpty() || raw.size() > MAX_COLUMNS) throw badRequest("Impordifaili päisereas peab olema 1–100 veergu.");
        List<String> headers = raw.stream().map(value -> value == null ? "" : value.trim()).toList();
        if (headers.stream().anyMatch(String::isBlank)) throw badRequest("Kõigil imporditavatel veergudel peab olema päis.");
        Set<String> normalized = new LinkedHashSet<>();
        for (String header : headers) {
            String key = header.toLowerCase(Locale.ROOT);
            if (!normalized.add(key)) throw badRequest("Failis on korduv veerupäis: " + header);
        }
        return headers;
    }

    private static Map<String, String> row(List<String> headers, List<String> values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            result.put(headers.get(i), i < values.size() && values.get(i) != null ? values.get(i).trim() : "");
        }
        return result;
    }

    private static boolean blank(Map<String, String> row) {
        return row.values().stream().allMatch(String::isBlank);
    }

    private static char detectDelimiter(String text) {
        boolean quoted = false;
        int commas = 0;
        int semicolons = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') i++;
                else quoted = !quoted;
            } else if (!quoted && (c == '\n' || c == '\r')) {
                break;
            } else if (!quoted && c == ',') commas++;
            else if (!quoted && c == ';') semicolons++;
        }
        return semicolons > commas ? ';' : ',';
    }

    private static List<List<String>> csvRecords(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        value.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    value.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                row.add(value.toString().trim());
                value.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(value.toString().trim());
                value.setLength(0);
                if (row.stream().anyMatch(cell -> !cell.isBlank())) rows.add(row);
                row = new ArrayList<>();
            } else {
                value.append(c);
            }
        }
        if (quoted) throw badRequest("CSV-failis on lõpetamata jutumärkidega väli.");
        row.add(value.toString().trim());
        if (row.stream().anyMatch(cell -> !cell.isBlank())) rows.add(row);
        return rows;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    record ParsedFile(List<String> headers, List<Map<String, String>> rows) { }
}
