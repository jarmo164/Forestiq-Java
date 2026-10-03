package ee.forestiq.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import ee.forestiq.contract.ContractPdfRenderer.Company;
import ee.forestiq.contract.ContractPdfRenderer.ContractTemplateContext;
import ee.forestiq.contract.ContractPdfRenderer.Parcel;
import ee.forestiq.contract.ContractPdfRenderer.Seller;

class ContractPdfRendererTest {

    private final ContractPdfRenderer renderer = new ContractPdfRenderer();

    @Test
    void rendersEscapedContractValuesAndReviewedTermsIntoValidPdf() {
        String template = "<html><body><h1>{{company.legalName}}</h1><p>{{seller.name}}</p><p>{{contract.number}}</p><p>{{contract.terms}}</p><table><tbody>{{contract.parcelsRows}}</tbody></table></body></html>";
        ContractTemplateContext context = new ContractTemplateContext(
            "L-42",
            LocalDate.of(2026, 8, 19),
            "Tallinn",
            BigDecimal.valueOf(1999.99),
            "Tasumine notaris & valduse üleandmine 5 päevaga",
            new Seller("Mari & Mets", "48901010011", "Metsa tee 1", "EE123"),
            new Company("ForestIQ OÜ", "14999999", "Tallinn", "info@example.test", "+3725550", "Metsa Ekspert", "juhatuse liige"),
            List.of(new Parcel("78401:101:0101", "Kaarli pst 2b", BigDecimal.valueOf(0.0854))));

        String html = renderer.apply(template, context);
        byte[] pdf = renderer.render(template, context);

        assertThat(html)
            .contains("ForestIQ OÜ")
            .contains("Mari &amp; Mets")
            .contains("Tasumine notaris &amp; valduse üleandmine 5 päevaga")
            .contains("78401:101:0101");
        assertThat(pdf).startsWith("%PDF".getBytes());
        assertThat(pdf.length).isGreaterThan(500);
    }
}
