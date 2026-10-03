# ForestIQ operatsioonilise käivituse valmisoleku audit

**Hinnatud haru:** `rewrite/next-spring-postgis`  
**Kuupäev:** 21. august 2026  
**Otsus:** **tingimuslik piloteerimisvalmidus; mitte veel iseteeninduslikuks täismeeskonna käivituseks valmis.**

## Otsuse tähendus

ForestIQ rakenduse põhifunktsioonid on piisavalt küpsed, et alustada **piiratud pilotiga** pärast kontrollitud kasutajate, organisatsiooniväidete ja eelnevalt valideeritud andmestiku seadistamist. Müügimees saab olemasolevaid omanikuandmeid otsida, prioriseeritud järjekorda kasutada, kõnetulemusi registreerida ja järgmised tegevused automaatselt luua. Juht saab jälgida koormust ja sekkumisriske; hindaja saab kasutada kaardi- ja registrivärskenduse töövooge.

Rakendust ei tohi siiski veel anda suuremale meeskonnale sõnumiga „sisestage või importige oma pärisandmed vabalt”. Puuduvad turvaline tootmisandmete esmailaadimise protsess, kasutajaliidese CSV-/Excel-import, pärisandmete ja demoandmete selge eraldus, käivitatud täislahenduse e2e-tõend ning operatsiooniline kasutaja/onboarding-protsess. Need ei ole kosmeetilised parandused: nende puudumine võib tekitada vale organisatsiooni alla laaditud andmeid, segada demo- ja pärisandmeid või takistada kasutajat sisselogimise järel täiesti töötamast.

## Valmiduse tulemuskaart

| Valdkond | Seis | Tõend ja tähendus | Käivituse mõju |
| --- | --- | --- | --- |
| CRM-i põhivoog | **Valmis pilotiks** | Omanike otsing, detailvaade, staatuse muutus, märkus, määramine, meeldetuletus, isiklik müügipult ja juhi ülevaade on olemas. | Meeskond saab töödelda **juba süsteemis olevaid** kontakte. |
| Andmemudel ja organisatsioonipiir | **Valmis** | `organization_id` on põhitabelites ja JWT-kontekst piirab päringuid organisatsiooniga. | Õige identiteediseadistusega on eraldatus tugev. |
| Uute kasutajate haldus | **Valmis tingimuslikult** | Keycloak Admin API loomine lisab kasutajale `organization_id`, nõuab ajutist parooli ja määrab ForestIQ rollid. | Vajab töötavat Keycloaki teenusekontot ning `KEYCLOAK_ADMIN_API_CLIENT_SECRET` seadistust. |
| Välisandmete üksikkinnistu värskendus | **Valmis pilotiks** | Maa-ameti ja metsaregistri ametlikud WFS-adapterid, päringu logi, tervisekontroll ning `Värskenda kõik` tegevus on olemas. | Piloodi ajal võib kinnistuandmeid rikastada kontrollitult. |
| Pidev välisandmete sünkroniseerimine | **Valmis, kuid vaikimisi välja lülitatud** | Aegumisepõhine tausttöö on olemas, kuid nõuab püsivat juurutuskeskkonda ja teadlikku aktiveerimist. | Enne pilooti võib jääda välja; enne täiskäivitust tuleb seadistada ja jälgida. |
| Käsitsi andmesisestus | **Osaliselt valmis** | Omaniku loomise API on olemas, kuid omanike loendivaates puudub kasutajaliidese loomise vorm. | Ärge planeerige massilist käsitsi sisestust praeguse UI kaudu. |
| CSV/Excel-import meeskonnale | **Blokeerija** | Olemas on ainult operaatori käivitatav Docker/`psql`-põhine Legacy-importija. See eeldab kohalikke faili asukohti, käsureaõigust ning vaikimisi demoorganisatsiooni ID-d. | Meeskond ei saa pärisandmeid iseseisvalt ja ohutult laadida. |
| Demo- ja pärisandmete eraldus | **Blokeerija** | Esimene Flyway migratsioon lisab alati organisatsiooni `ForestIQ Demo`, kaks demo-omanikku ja kaks demokinnistut. | Tootmiskäivituses tuleb enne kinnitada eraldi demo-/tootmisseemnete strateegia. |
| Identiteedi esmakonfigureerimine | **Blokeerija enne kasutajate sisselogimist** | Iga API töövoog nõuab JWT `organization_id` väidet; puuduva või vigase väitega token annab juurdepääsukeelu. | Enne kasutajate kutsumist tuleb teha ühe päriskasutajaga tokeni- ja rollisuitsutest. |
| Integreeritud täislahenduse e2e | **Blokeerija enne täiskäivitust** | Lähtekoodi testid läbivad, kuid auditi keskkonnas puudus Docker; PostgreSQL/PostGIS, Keycloak, S3 ja välisregistrite kooskäivitust ei käivitatud. | Piloodis ainult kontrollitud kasutajate ja andmetega; enne laia kasutust on e2e kohustuslik. |
| CI nähtavus | **Ebaselge** | Kvaliteediväravad on versioonihalduses olemas, kuid selle auditi GitHubi tunnus ei saanud Actionsi käituste olekut lugeda. | Enne käivitust peab omanik kinnitama viimase haru rohelise CI-jooksu. |

## Kontrollitud kvaliteediväravad

Auditi ajal läbis backend `./gradlew check`. Frontend läbis lintimise, TypeScripti kontrolli, Vitesti testid ja Next.js tootmisehituse. Need on hea lähtekvaliteedi tõend, kuid nad **ei asenda** töötavatest konteineritest tehtud kasutajateekonna tõendit.

CI töövoog käivitab backend’i `check`-i ning frontend’i sõltuvuste paigalduse, lintimise, tüübitesti, üksustestid, tootmisehituse ja kõrge taseme sõltuvusauditi. Compose-töövoog valideerib konfiguratsiooni ja ehitab API ning kliendi pildid, kuid kirjelduses puudub käivitatud teenuste vastu tehtud autentimise-, andmebaasi migratsiooni-, importimise- ja põhitöövoo test. [1]

## Go/no-go kontrollnimekiri

| Järjekord | Enne pilooti kohustuslik tegevus | Omanik | Valmiskriteerium |
| --- | --- | --- | --- |
| **1** | Loo eraldi tootmisorganisatsioon ja otsusta demoandmete poliitika. Ära kasuta tootmises vaikimisi demoorganisatsiooni. | Tehniline omanik | Pärisorganisatsiooni UUID, nimi ja andmete kustutus-/säilitamisreegel on dokumenteeritud. |
| **2** | Seadista kõik vajalikud tootmissaladused: PostgreSQL, Keycloak admin, Keycloak Admin API kliendisaladus, RabbitMQ, MinIO/S3 ning vähemalt valmidus Sentry jaoks. | DevOps | Saladused on salahaldures, mitte `.env`-is ega Git’is; teenused on terved. |
| **3** | Tee ühe päris rolliga kasutaja Keycloak-suitsutest. | Administraator | JWT sisaldab korrektset `organization_id`, õiged rollid ning kasutaja näeb ainult oma organisatsiooni andmeid. |
| **4** | Koosta ja testi andmete esmailaadimise protseduur koopiaandmetega. | Andmeomanik + tehniline omanik | Etappimine, deduplitseerimine, vigade raport, tagasipööramine ja pärisorganisatsiooni ID on testitud. |
| **5** | Käivita täielik Compose/juurutuskeskkond ning e2e suitsutest. | QA/DevOps | Sisselogimine, kasutaja loomine, omanik/kinnistu, kaardivaade, registrivärskendus, müügitegevus ja dokument on läbi testitud. |
| **6** | Loo esimene tööjuhend müügimehele, hindajale ja juhile. | Äriomanik | Iga roll teab, kuidas alustada päeva, määrata tegevust ja teatada andmeveast. |
| **7** | Kinnita piloodi mõõdikud ja tugikanal. | Büroo juht | Näiteks aktiivsed kontaktid, aegunud järeltegevused, andmevead ning reageerimisaeg on iganädalaselt jälgitavad. |

## Soovitatud käivitusrada

**Piloot:** 2–5 kasutajat, üks organisatsioon, eelnevalt puhastatud piiratud andmestik, käsitsi jälgitud välisregistri värskendused ja igapäevane tagasiside. Piloodi jooksul on eesmärk tõendada, et müügimees liigub otsingust kontaktini ning kontaktist järgmise tegevuseni ilma kõrvalise tabelita.

**Täiskäivitamine:** alles pärast seda, kui on olemas meeskonnale sobiv import/onboarding, demoandmete tootmiseraldus, roheline käivitatud e2e-tõend ning rolli- ja organisatsiooniväidete protsess. Massiline andmetäitmine enne neid samme kasvataks tagasipööramise, isikuandmete ja tööseisaku riski põhjendamatult.

## Viited

[1]: ../.github/workflows/ci.yml "ForestIQ CI töövoog"
[2]: ../scripts/import_forestiq_legacy_data.py "Legacy-andmete operaatori importija"
[3]: ../backend/src/main/resources/db/migration/V1__core_crm_and_geospatial_schema.sql "Põhiskeem ja demoandmed"
[4]: ../backend/src/main/java/ee/forestiq/security/OrganizationContext.java "JWT organisatsioonikontekst"
[5]: ../backend/src/main/java/ee/forestiq/admin/KeycloakAdminService.java "Keycloak kasutaja- ja rollihaldus"
