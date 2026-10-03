# Pärimisjuhtumi töövoog ForestIQ-s

## Eesmärk ja piirid

ForestIQ käsitleb ametlikku pärimisteadet **müügi- ja tööjärjekorra otsusetoena**, mitte pärimisõiguse, omandiõiguse ega pärijate lõpliku tõendina. Iga pärimisjuhtum on seotud ühe omaniku, ühe organisatsiooni ja vajadusel ametliku teate numbriga. Organisatsiooni kasutaja peab enne mistahes tehinguotsust kontrollima pärimisõigust pädevast allikast ning järgima oma organisatsiooni isikuandmete töötlemise reegleid.

Pärijate kontaktandmed ja pärimisjuhtumi sündmused on nähtavad ainult `ORG_ADMIN`, `CRM_MANAGER`, `EVALUATOR` ja tööks määratud `CALLER` rollidega sama organisatsiooni kasutajatele. API ei võimalda klientidel ise organisatsiooni identifikaatorit valida; see tuletatakse sisselogitud kasutaja JWT `organization_id` väitest.

## Töövoog

| Etapp | ForestIQ tegevus | Ajaloos talletatav sündmus |
| --- | --- | --- |
| Teade saabub | Ajastatud import loeb Ametlike Teadaannete kuupäevapõhist XML-voogu või administraator käivitab sama töö käsitsi. | — |
| Ametliku teate import | ForestIQ normaliseerib vajaliku metaandmestiku, sobitab täpse isikukoodi alusel olemasoleva omanikuga ja avab idempotentselt juhtumi. | `CASE_STARTED`, `OFFICIAL_NOTICE_IMPORTED` |
| Pärijate selgitamine | Kasutaja lisab või täiendab pärija nime, seose, osa, kontakti ja kontaktistaatuse. | `HEIR_ADDED`, `HEIR_UPDATED` |
| Kontaktitöö | Juhtumi olekuks määratakse näiteks `HEIRS_CONTACTED` või `NEGOTIATION`; kokkulepped lisatakse tegevuslogisse. | `STATUS_CHANGED`, `NOTE` |
| Lõpetamine | Juhtum märgitakse `SETTLED`, `CLOSED` või `NOT_RELEVANT`; süsteem salvestab lõpuaja. | `STATUS_CHANGED` koos `ended_at` väärtusega |

Juhtumil saab korraga olla ainult üks lõpetamata pärimisjuhtum omaniku kohta. See väldib sama surmajuhtumi paralleelset töötlemist eri inimestel. Lõpetatud juhtum ei kao, vaid jääb ajaloona alles.

## Otsene Ametlike Teadaannete ühendus

ForestIQ loeb otse Ametlike Teadaannete kuupäevapõhist XML-voogu. Vaikimisi URL-mall on:

```text
https://www.ametlikudteadaanded.ee/ee/-/parimisteated/parimismenetluse-algatamine/%04d/%02d/%02d/xml
```

XML parsitakse XXE-vastase seadistusega. Püsivalt säilitatakse ainult töövoo jaoks vajalikud normaliseeritud väljad: teate number, URL, kuupäevad, notari kontakt ja omaniku seos. Toor-XML-i ega teate täisteksti andmebaasi ei salvestata. Vaste luuakse ainult ForestIQ-s juba oleva täpse 11-kohalise isikukoodi põhjal.

```env
INHERITANCE_SOURCE_URI_TEMPLATE=https://www.ametlikudteadaanded.ee/ee/-/parimisteated/parimismenetluse-algatamine/%04d/%02d/%02d/xml
INHERITANCE_SOURCE_USER_AGENT=ForestIQ/2.0
INHERITANCE_SOURCE_ZONE_ID=Europe/Tallinn
INHERITANCE_SOURCE_TIMEOUT_SECONDS=30
INHERITANCE_SOURCE_MAX_BACKFILL_DAYS=31
```

## Käivitus- ja kontrollnimekiri

| Kontroll | Valmiskriteerium |
| --- | --- |
| Mõlemad teenused on HTTPS-i taga | Token ega pärimisandmed ei liigu krüptimata ühendusel. |
| Identifitseeritav User-Agent on määratud | Allika haldaja saab ForestIQ päringuid tuvastada. |
| Üks testteade on kontrollitud | ForestIQ töökaardilt importimisel tekib juhtum, pärijad ja ajaloos on importimise sündmus. |
| Rollid on kontrollitud | Volitamata või teise organisatsiooni kasutaja ei näe juhtumit ega pärijaid. |
| Andmekaitse töökorraldus on kinnitatud | Meeskond teab, kes tohib pärijate andmeid lisada, muuta ja millal juhtum lõpetatakse. |
| Õiguslik kontroll on protsessis | Tegevuslogi ja ametlik teade ei asenda pärimisõiguse või omandiõiguse kontrolli pädevast registrist. |

## Andmete säilitamine

Pärimisjuhtumi sündmused on lisatavad auditikirjed. Paranduse või täpsustuse korral lisatakse uus tegevus, mitte ei kirjutata varasemat otsust üle. Pärija kontaktandmeid tuleb hoida ainult nii kaua, kui dokumenteeritud äriline ja õiguslik töötlusalus seda lubab. Tootmiskeskkonna säilitus-, kustutus- ja ligipääsureeglid tuleb kinnitada organisatsiooni andmekaitsekorra osana.

## Automaatne ametliku teate kontroll ja surnud-isiku staatus

Pärimisplokk alustab nüüd ametliku teate kontrolliga. ForestIQ saadab pärimisteadete teenusele ainult omaniku täpse 11-kohalise isikukoodi ning ei muuda kliendiandmeid siis, kui ametlik vaste puudub. Kui allikas tagastab sama isikukoodiga teate, teeb süsteem ühe tehinguna järgmised tegevused: märgib omaniku staatuseks `DECEASED`, lisab tavapärasesse kliendiajalukku süsteemse tegevuse, salvestab staatusemuudatuse analüütikasse, avab pärimisjuhtumi `NOTICE_RECEIVED` seisus ja impordib teatel olemasolevad pärijad.

Surnud isikut ei saa tavapärase staatusemuutmisega müügitorusse tagasi viia. Pärimisega seotud tegevused, pärijate kontaktistaatus ja menetluse lõpud jäävad pärimisjuhtumi ajajoonele. Juhtum näitab alati algusaega ning lõppstaatused `SETTLED`, `CLOSED` ja `NOT_RELEVANT` salvestavad lõpuaja.

### Taustkontroll

Automaatne ametlike teadete kontroll on vaikimisi väljas. Püsivas tootmiskeskkonnas võib selle aktiveerida ainult pärast dokumenteeritud andmekasutuse alust ja allika kasutustingimuste kontrollimist:

```bash
INHERITANCE_SOURCE_AUTO_REFRESH_ENABLED=true
INHERITANCE_SOURCE_AUTO_REFRESH_DELAY_MS=1800000
INHERITANCE_SOURCE_AUTO_REFRESH_BATCH_SIZE=100
```

Tööline loeb vaikimisi tänase ja eilse voo, sobitab teated olemasolevate omanikega ning kasutab `(organization_id, notice_number)` idempotentsusvõtit. Vigane või kättesaamatu allikas ei muuda omaniku ega pärimisjuhtumi andmeid. Kõigi juhtumite tööjärjekord asub lehel **Pärimisregister**, kus saab filtreerida staatuse, tähtaja ja määrangu järgi, avada ajajoone ning määrata vastutaja.
