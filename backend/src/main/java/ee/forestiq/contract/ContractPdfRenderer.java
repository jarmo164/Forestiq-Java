package ee.forestiq.contract;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

/** Renders a user-managed HTML contract template into a PDF without shelling out to external software. */
@Component
public class ContractPdfRenderer {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final NumberFormat MONEY = NumberFormat.getNumberInstance(new Locale("et", "EE"));

    public byte[] render(String htmlTemplate, ContractTemplateContext context) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(apply(htmlTemplate, context), null);
            builder.toStream(output);
            builder.run();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new ContractGenerationException("PDF-lepingu genereerimine ebaõnnestus", exception);
        }
    }

    /** Supported placeholders are documented in the template management endpoint response. */
    String apply(String template, ContractTemplateContext context) {
        String parcelRows = context.parcels().stream()
            .map(parcel -> "<tr><td>" + html(parcel.cadastralCode()) + "</td><td>" + html(parcel.address())
                + "</td><td>" + (parcel.areaHectares() == null ? "" : parcel.areaHectares()) + "</td></tr>")
            .reduce("", String::concat);
        Map<String, String> replacements = Map.ofEntries(
            Map.entry("{{contract.number}}", html(context.contractNumber())),
            Map.entry("{{contract.date}}", html(context.contractDate() == null ? DATE_FORMAT.format(LocalDate.now()) : DATE_FORMAT.format(context.contractDate()))),
            Map.entry("{{contract.place}}", html(context.place())),
            Map.entry("{{contract.price}}", html(context.price() == null ? "" : MONEY.format(context.price()))),
            Map.entry("{{contract.terms}}", html(context.terms())),
            Map.entry("{{contract.parcelsRows}}", parcelRows),
            Map.entry("{{seller.name}}", html(context.seller().name())),
            Map.entry("{{seller.code}}", html(context.seller().code())),
            Map.entry("{{seller.address}}", html(context.seller().address())),
            Map.entry("{{seller.iban}}", html(context.seller().iban())),
            Map.entry("{{company.legalName}}", html(context.company().legalName())),
            Map.entry("{{company.registryCode}}", html(context.company().registryCode())),
            Map.entry("{{company.address}}", html(context.company().address())),
            Map.entry("{{company.email}}", html(context.company().email())),
            Map.entry("{{company.phone}}", html(context.company().phone())),
            Map.entry("{{company.representativeName}}", html(context.company().representativeName())),
            Map.entry("{{company.representativeRole}}", html(context.company().representativeRole()))
        );
        String result = template;
        for (Map.Entry<String, String> entry : replacements.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
        return result;
    }

    private static String html(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    public record ContractTemplateContext(
        String contractNumber, LocalDate contractDate, String place, BigDecimal price, String terms,
        Seller seller, Company company, List<Parcel> parcels
    ) {
        public ContractTemplateContext(String contractNumber, LocalDate contractDate, String place, BigDecimal price,
                                       Seller seller, Company company, List<Parcel> parcels) {
            this(contractNumber, contractDate, place, price, null, seller, company, parcels);
        }
    }
    public record Seller(String name, String code, String address, String iban) { }
    public record Company(String legalName, String registryCode, String address, String email, String phone,
                          String representativeName, String representativeRole) { }
    public record Parcel(String cadastralCode, String address, BigDecimal areaHectares) { }

    public static class ContractGenerationException extends RuntimeException {
        public ContractGenerationException(String message, Throwable cause) { super(message, cause); }
    }
}
