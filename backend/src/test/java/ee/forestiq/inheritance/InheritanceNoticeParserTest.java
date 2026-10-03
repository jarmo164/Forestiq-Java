package ee.forestiq.inheritance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class InheritanceNoticeParserTest {
    private final InheritanceNoticeParser parser = new InheritanceNoticeParser();

    @Test
    void parsesNamespacedOfficialNoticeAndNormalizesOnlyRequiredFields() {
        String xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <at:teated xmlns:at="urn:ametlikud-teadaanded">
              <at:teadaanne>
                <at:teate_number>PN-2026-0042</at:teate_number>
                <at:url>https://www.ametlikudteadaanded.ee/notice/42</at:url>
                <at:andmeandja><at:nimi>Notar Mari Maasik</at:nimi></at:andmeandja>
                <at:andmeandja_kontaktandmed><at:telefon>+372 600 0000</at:telefon></at:andmeandja_kontaktandmed>
                <at:puudutatud_isik>
                  <at:eesnimi>Jaan</at:eesnimi><at:nimi>Tamm</at:nimi><at:kood>37605030299</at:kood>
                </at:puudutatud_isik>
                <at:sisendid>
                  <at:sisendirida><at:nimi>parandaja_surmakuupaev</at:nimi><at:vaartus>20.08.2026</at:vaartus></at:sisendirida>
                  <at:sisendirida><at:nimi>toestamise_tahtpaev</at:nimi><at:vaartus>30.09.2026</at:vaartus></at:sisendirida>
                </at:sisendid>
                <at:kinnitatud_sisu>Pärimismenetlus on algatatud.</at:kinnitatud_sisu>
              </at:teadaanne>
            </at:teated>
            """;

        var notices = parser.parse(xml, LocalDate.of(2026, 8, 24));

        assertThat(notices).singleElement().satisfies(notice -> {
            assertThat(notice.noticeNumber()).isEqualTo("PN-2026-0042");
            assertThat(notice.personalCode()).isEqualTo("37605030299");
            assertThat(notice.deceasedName()).isEqualTo("Jaan Tamm");
            assertThat(notice.deathDate()).isEqualTo(LocalDate.of(2026, 8, 20));
            assertThat(notice.certificationDeadline()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(notice.notary()).isEqualTo("Notar Mari Maasik");
        });
    }

    @Test
    void rejectsDoctypeAndExternalEntities() {
        String xml = """
            <!DOCTYPE data [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <teated><teadaanne><teate_number>1</teate_number><kinnitatud_sisu>&xxe;</kinnitatud_sisu></teadaanne></teated>
            """;

        assertThatThrownBy(() -> parser.parse(xml, LocalDate.now()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("parsimine ebaõnnestus");
    }

    @Test
    void acceptsEmptyFeed() {
        assertThat(parser.parse("  ", LocalDate.now())).isEmpty();
    }
}
