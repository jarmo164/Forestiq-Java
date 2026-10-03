package ee.forestiq.inheritance;

import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

@Component
class InheritanceNoticeParser {
    private static final Pattern PERSONAL_CODE = Pattern.compile("\\b(\\d{11})\\b");
    private static final Pattern NAME_AND_CODE = Pattern.compile(
        "surnud\\s+([A-ZÄÖÜÕŠŽa-zäöüõšž][^()]*?)\\s*\\(isikukood\\s+(\\d{11})\\)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE = Pattern.compile("(\\d{1,2}\\.\\d{1,2}\\.\\d{4})");
    private static final Pattern DEADLINE = Pattern.compile(
        "tõestamise kavandatav tähtpäev on (\\d{1,2}\\.\\d{1,2}\\.\\d{4})",
        Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter ESTONIAN_DATE = DateTimeFormatter.ofPattern("d.M.uuuu");

    List<ParsedInheritanceNotice> parse(String xml, LocalDate announcementDate) {
        if (xml == null || xml.isBlank()) return List.of();
        try {
            Document document = secureFactory().newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            List<ParsedInheritanceNotice> result = new ArrayList<>();
            var nodes = document.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node instanceof Element element && hasName(element, "teadaanne")) {
                    ParsedInheritanceNotice notice = parseNotice(element, announcementDate);
                    if (notice != null) result.add(notice);
                }
            }
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("Pärimisteadete XML-i parsimine ebaõnnestus.", exception);
        }
    }

    private ParsedInheritanceNotice parseNotice(Element element, LocalDate announcementDate) {
        String noticeNumber = childText(element, "teate_number");
        String raw = childText(element, "kinnitatud_sisu");
        String notary = nestedText(element, "andmeandja", "nimi");
        if (notary.isBlank()) notary = childText(element, "andmeandja_nimi");
        if (noticeNumber.isBlank() || (notary.isBlank() && raw.isBlank())) return null;

        Element affected = child(element, "puudutatud_isik");
        String name = "";
        String personalCode = "";
        if (affected != null) {
            name = normalize(childText(affected, "eesnimi") + " " + childText(affected, "nimi"));
            personalCode = validCode(childText(affected, "kood"));
        }
        Matcher rawIdentity = NAME_AND_CODE.matcher(normalize(raw));
        if (rawIdentity.find()) {
            if (name.isBlank()) name = normalize(rawIdentity.group(1));
            if (personalCode.isBlank()) personalCode = validCode(rawIdentity.group(2));
        }
        if (personalCode.isBlank()) {
            Matcher code = PERSONAL_CODE.matcher(raw);
            if (code.find()) personalCode = validCode(code.group(1));
        }

        LocalDate birthDate = null;
        LocalDate deathDate = null;
        LocalDate deadline = null;
        Element inputs = child(element, "sisendid");
        if (inputs != null) {
            for (Element row : children(inputs, "sisendirida")) {
                String key = childText(row, "nimi");
                LocalDate value = parseDate(childText(row, "vaartus"));
                if ("parandaja_surmakuupaev".equals(key)) deathDate = value;
                if ("parandaja_sunnikuupaev".equals(key)) birthDate = value;
                if ("toestamise_tahtpaev".equals(key)) deadline = value;
            }
        }
        if (deadline == null) {
            Matcher match = DEADLINE.matcher(raw);
            if (match.find()) deadline = parseDate(match.group(1));
        }
        return new ParsedInheritanceNotice(announcementDate, noticeNumber, childText(element, "url"), notary,
            nestedText(element, "andmeandja_kontaktandmed", "telefon"), name, personalCode,
            birthDate, deathDate, deadline);
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static String nestedText(Element element, String parent, String child) {
        Element parentElement = child(element, parent);
        return parentElement == null ? "" : childText(parentElement, child);
    }
    private static Element child(Element element, String name) {
        for (int i = 0; i < element.getChildNodes().getLength(); i++) {
            Node node = element.getChildNodes().item(i);
            if (node instanceof Element child && hasName(child, name)) return child;
        }
        return null;
    }
    private static List<Element> children(Element element, String name) {
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < element.getChildNodes().getLength(); i++) {
            Node node = element.getChildNodes().item(i);
            if (node instanceof Element child && hasName(child, name)) result.add(child);
        }
        return result;
    }
    private static String childText(Element element, String name) {
        Element child = child(element, name);
        return child == null ? "" : normalize(child.getTextContent());
    }
    private static boolean hasName(Node node, String name) {
        return name.equals(node.getLocalName()) || name.equals(node.getNodeName())
            || node.getNodeName().endsWith(":" + name);
    }
    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
    private static String validCode(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\D", "");
        return normalized.matches("\\d{11}") ? normalized : "";
    }
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        Matcher match = DATE.matcher(value);
        if (!match.find()) return null;
        try { return LocalDate.parse(match.group(1), ESTONIAN_DATE); }
        catch (DateTimeParseException ignored) { return null; }
    }
}

