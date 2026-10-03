# ForestIQ välisandmete ja sünkroniseerimise audit

## Kontrollitud ametlikud andmeallikad

| Andmeallikas | Ametlik teenus | ForestIQ kasutus | Seis ja vajalik parandus |
| --- | --- | --- | --- |
| Maa- ja Ruumiamet / maakatastri andmed | Avalik WFS; Maa- ja Ruumiamet kirjeldab teenust tasuta avaliku ruumiandmeteenusena | Katastriüksuse geomeetria, pindalad, aadress ja haldusjaotus | Olemas on WFS-adapter, kuid selle vaikesätted ja atribuudikaardistus tuleb muuta võimekusepõhiseks ning lisada päringu ajalimiit, uuesti proovimine ja tervisekontroll. |
| Metsaregister | Avalik WFS/WMS `https://gsavalik.envir.ee/geoserver/metsaregister/ows?` | Metsateatised ning metsakorralduskava eraldised | Praegune adapter kasutab dokumenteerimata Metsaportaali REST-radasid. Tuleb eelistada ametlikku WFS-i ja lasta kihtide nimed leida GetCapabilities päringust. |
| ADS / In-ADS | Maa- ja Ruumiameti aadressiotsingu teenus | Aadressi normaliseerimine ja kliendi/kinnistu otsingu kvaliteet | Praegu puudub. Lisada kasutajaliidesesse ametlik aadressiotsingu allikas ning serverisse normaliseerimise adapter ainult siis, kui teenuseleping seda võimaldab. |
| RIK kinnistusraamat | Lepinguline SOAP teenus | Kinnistusraamatu kontroll ja omandiga seotud töövood | Adapter on olemas ja vajab teenusemandaate. Seda ei tohi käitada ilma organisatsiooni teenuselepinguta; seadistus peab jääma ainult serveripoolele. |
| EELIS | Avalik WFS/WMS `https://gsavalik.envir.ee/geoserver/eelis/ows?` | Looduskaitse- ja piirangukihi riskiinfo | Praegu puudub. Lisada valikuline kaardikihi/tunnuse adapter, sest see mõjutab metsatehingu hinnangut, kuid ei tohi blokeerida põhisünkroniseerimist. |

## Tehniline otsus

Välisandmete töö on reeglipõhine ja kasvava mahuga, mistõttu sünkroniseerimine peab töötama rakenduse püsivas serverikeskkonnas tausttööna, mitte käsitsi avatud brauseri või ühekordse agendikäivitusena. Rakendus peab toetama kahte režiimi: kasutaja algatatud üksikkinnistu värskendus kohe ning ajastatud aegunud andmete kontroll, kui kasutusele võetakse juurutuskeskkonna tausttöö.

Ametlikud allikad tuleb käsitleda kui eraldiseisvaid adaptereid. Iga adapter peab salvestama päringu URL-i, tulemuse väljavõtte, aja, kirjete arvu ja tõrke põhjuse olemasolevasse `registry_sync_log` tabelisse. Välisallika tõrge ei tohi tühjendada seni kehtivaid kinnistu- või metsaregistri andmeid.

## Allikad

1. Maa- ja Ruumiameti [avalikud WMS/WFS teenused](https://geoportaal.maaamet.ee/eng/services/public-wms-wfs-p346.html).
2. Keskkonnaportaali [GeoServeri teenused](https://keskkonnaportaal.ee/et/avaandmed/geoserver), mis kinnitab metsaregistri WFS/WMS teenuse URL-i ja OGC WFS versioonide toe.
3. Maa- ja Ruumiameti [In-ADS teenus](https://geoportaal.maaamet.ee/eng/services/in-ads-p660.html).

## Elusate teenuselepingute kontroll

2026-08-21 kontrollis ForestIQ arendusprotsess ametlike GetCapabilities päringutega mõlema WFS-teenuse HTTP 200 vastust. Maa-ameti maakatastri WFS avaldab muu hulgas `kataster:ky_kehtiv`, `kataster:ky_aadress`, `kataster:ky_registriosa` ja `kataster:ky_omandivorm` kihid. Kontrollitud `kataster:ky_kehtiv` skeem sisaldab ForestIQ jaoks vajalikke välju `tunnus`, `l_aadress`, `mk_nimi`, `ov_nimi`, `pindala`, `haritav`, `mets`, `ouemaa`, `muumaa`, `kinnistu`, `omvorm`, `ads_oid` ja `geom`.

Metsaregistri ametlik WFS avaldab teiste seas `metsaregister:teatis` ja `metsaregister:eraldis` kihid. Teatise kihis on `teatise_nr`, `katastri_nr`, `pindala`, `too_kood`, `raiutav_maht`, `otsus` ja `kehtiv_kuni`; eraldise kihis on `katastri_nr`, `eraldise_nr`, `pindala`, `invent_kp`, `peapuuliik_kood`, `keskm_vanus`, `tagavara_y_ha` ning muud metsakorralduse tunnused. Rakendus peab neid välju kasutama WFS-st otse, mitte eeldama portaalisisesi REST-radu.

## Rakendatud töökindluse täiustused

ForestIQ kasutab nüüd Maa-ameti maakatastri ning metsaregistri jaoks ametlikke OGC WFS-i lõpp-punkte. Adapterid valideerivad katastritunnuse vormingut, kasutavad eraldi ühendus- ja lugemisajalimiite ning teevad kontrollitud, piiratud taaskatsed. Metsaregistri teatised ja eraldised päritakse ametlikelt kihtidelt `metsaregister:teatis` ja `metsaregister:eraldis`; enam ei sõltuta portaali sisemistest REST-radadest.

Lisandus `GET /api/v1/registry/health`, mis kontrollib GetCapabilities vastuse põhjal Maa-ameti katastrikihi ning mõlema metsaregistri kihi kättesaadavust, ja märgib RIK-i lepingulise ühenduse vaid seadistatuks/seadistamata. `POST /api/v1/registry/cadastres/{cadastralCode}/refresh-all` värskendab ühe kinnistu katastri, metsateatiste ja eraldiste andmed ühe töövooga. Täisvärskendus tagastab iga allika tulemuse eraldi: ühe teenuse tõrge ei peata teisi värskendusi ega kustuta varem kehtinud andmeid.

Tausttöötaja kontrollib konfigureeritud intervalliga ainult puuduvaid või aegunud andmekirjeid piiratud partiidena. See on vaikimisi väljas; tootmises tuleb see sisse lülitada `REGISTRY_AUTO_REFRESH_ENABLED=true` alles pärast teenusepakkuja kasutustingimuste, mahu ja juurutuskeskkonna püsimise kontrolli. Interaktiivne värskendus töötab sellest sõltumata ning kinnistuvaates on uus **Värskenda kõik** tegevus.

## Valideerimine

Maa-ameti ja metsaregistri GetCapabilities päringud tagastasid kontrolli ajal HTTP 200. Backendi Gradle testid, kliendi lintimine, TypeScripti kontroll, Vitesti testid ja Next.js tootmisehitus läbisid. Täielik andmebaasi- ja Keycloaki e2e tuleb käivitada Dockeriga CI-s või püsivas arenduskeskkonnas, sest Docker ei ole selles töötluskeskkonnas saadaval.

# Järgmise integratsioonikihi uurimismärkus

## Kontrollitud lisaliidestused

| Andmekiht | Kontrollitud ametlik võimalus | Liidestusotsus |
| --- | --- | --- |
| Kinnisvaratehingute võrdlusandmed | Maa- ja Ruumiameti tehingute statistika tugineb notarite esitatud tehinguõienditele; avalik statistika ei kuva üksiktehinguid ning väärtused avaldatakse vaid vähemalt viie tehingu korral. | Kasuta portfelli-/piirkonnatasandi turuindeksi ja võrdlustehingute trendi jaoks, mitte üksikkinnistu automaatse hindamise ainsa alusena. Detailandmete juurdepääs tuleb eraldi õiguslikult ja lepinguliselt hinnata. |
| ADS aadressinormaliseerimine | In-ADS on ametlik ADS-aadressiotsingu API ja veebikomponent, mida saab kasutada normaliseeritud aadressi ning metaandmete leidmiseks. | P1: lisa kliendi/kinnistu otsingusse ja andmesisestusse; eelistada ametlikku komponenti või dokumenteeritud teenuse integreerimist. |
| Äriregister | RIK avaliku API/XML-teenuse kasutamiseks on vajalik leping; teenus on lepingu sõlmimisel tasuta, kuid kehtivad 50 000 vastuse päeva- ja ühe paralleelpäringu piirangud. Hulgilaadimiseks soovitab RIK öösel uuenevaid andmefaile. | P1: ettevõttest omaniku registrikoodi, staatuse ja esindusõiguse kontroll; rakendada järjekord, vahemälu ja piirangute austamine. |
| Kinnistusraamat | E-kinnistusraamat võimaldab harva üksikpäringuid ja suurema igapäevase mahu korral lepingulist kasutust; andmed hõlmavad omanikku, servituute, hüpoteeke ja märkusi. | P0 ärilise hoolsuskontrolli jaoks, kuid alles pärast lepingu, õigusliku aluse, auditilogimise ja juurdepääsukontrolli lahendamist. |
| Ilma- ja hüdroandmed | Ilmateenistus avaldab seireandmeid masinloetavalt iga 10 minuti või tunni järel ning prognoosi/hoiatusi XML-voona. Keskkonnaandmete teenus pakub JSON/PostgREST liidest, OpenAPI kirjeldust ning nõuab ajafiltreid; avalik EELIS API on veel arengus. | P1 operatiivsete tormi-, tuleohu-, sademete- ja väljaveoriskihoiatuste jaoks. Rakendada ainult kinnistu lähima jaama või georuumi piirkonna järgi ning ajapiiranguga päringud. |
| Puiduhinnad | Erametsakeskuse avalik hinnainfo sisaldab kuni 2022. aasta ajaloolisi Exceli/PDF ülevaateid, mitte ajakohast API-voogu. | Mitte automatiseerida tootmishinnaks. Hoida seda ajaloolise viiteallikana ja otsida eraldi litsentsitud/partneripõhist ajakohast hinnavoogu. |

## Uued allikad

4. Maa- ja Ruumiameti [kinnisvaratehingute statistika](https://maaruum.ee/maakataster-ja-maa-hindamine/kinnisvaratehingud/kinnisvaratehingute-statistika).
5. Maa- ja Ruumiameti [In-ADS teenus](https://geoportaal.maaamet.ee/eng/services/in-ads-p660.html).
6. RIK-i [e-Äriregistri API teenus](https://avaandmed.ariregister.rik.ee/en/open-data-api/introduction-api-services).
7. RIK-i [e-kinnistusraamatu teenus](https://www.rik.ee/et/e-kinnistusraamat/e-kinnistusraamat).
8. Ilmateenistuse [vaatlusandmete XML](https://www.ilmateenistus.ee/teenused/ilmainfo/eesti-vaatlusandmed-xml/) ja [prognoosi XML](https://www.ilmateenistus.ee/teenused/ilmainfo/eesti-prognoos-xml/).
9. Keskkonnaportaali [keskkonna ja ilma andmeteenused](https://keskkonnaportaal.ee/et/avaandmed/keskkonna-ja-ilma-valdkonna-andmeteenused).
10. Erametsakeskuse [puidu hinnainfo](https://www.eramets.ee/uuringud-ja-statistika/hinnainfo/).
