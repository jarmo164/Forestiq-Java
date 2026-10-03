# ForestIQ 2.0

ForestIQ on organisatsioonipõhine metsaomanike CRM ja georuumiline töölaud. See monorepo on täielik ümberkirjutus, mis asendab vana Angular 9 ja Spark Java rakenduse **Next.js 15**, **Spring Boot 3 / Java 21** ning **PostgreSQL + PostGIS** arhitektuuriga.

> Rakendus katab Keycloak OIDC autentimise, organisatsiooniga piiratud omanike CRM-i, PostgreSQL Full-Text Searchi, PostGIS kinnistud, MapLibre'i autentitud MVT kaardi, reaalajas staatuse sündmused, välisregistrite sünkroniseerimise ning muudetavatel HTML-mallidel põhinevate PDF-lepingute loomise.

## Tehnoloogia

| Kiht | Rakendatud tehnoloogia |
| --- | --- |
| Frontend | Next.js, React, TypeScript, Tailwind CSS, shadcn/ui, TanStack Query, TanStack Table |
| Kaart | MapLibre GL JS, MapTiler, Spring/PostGIS Mapbox Vector Tiles |
| Backend | Java 21, Spring Boot 3, MVC, Security, Data JPA/Hibernate, jOOQ, Flyway, springdoc-openapi |
| Andmed | PostgreSQL 17, PostGIS 3.5, PostgreSQL Full-Text Search; PgBouncer on täisrežiimi valikuline ühendusepool |
| Identiteet | Keycloak, OpenID Connect, OAuth 2.0, rolli- ja organisatsioonipõhine autoriseerimine |
| Asünkroonne töö | Spring `@Scheduled`, PostgreSQL advisory lock'id ja WebSocket/STOMP; Redis ning RabbitMQ on täisrežiimi valikulised teenused |
| Failid | S3-ühilduv salvestus; MinIO lokaalselt, S3 või Cloudflare R2 tootmises |
| Vaatluspõhisus | OpenTelemetry, Prometheus, Grafana, Loki, Sentry DSN-i tugi |
| Kvaliteet | JUnit 5, Mockito, Testcontainers, RestAssured sõltuvus, Vitest, Playwright |
| Taristu | Docker Compose ja Render Blueprint (`render.yaml`) |

## Kiirkäivitus

Vajalikud on Docker Compose, Java 21 JDK ning Node.js 22 koos pnpm-iga. Vaikekäsk käivitab **piloodi põhisüsteemi**: PostgreSQL/PostGIS, Keycloak, MinIO, API, kasutajaliidese ning eraldatud Python `ingest-scheduler` ja `ingest-worker` protsessid. Redis, RabbitMQ, ühendusepool ning lokaalne observability-virn ei ole piloodi tööks vajalikud.

```bash
cp .env.example .env
# Täida .env failis tühjad paroolid ja lisa NEXT_PUBLIC_MAPTILER_KEY, kui soovid MapTileri aluskaarti.
docker compose up --build
```

| Teenus | Aadress | Lokaalne konto |
| --- | --- | --- |
| ForestIQ frontend | http://localhost:3000 | kasutaja luuakse Keycloakis |
| Spring Boot API | http://localhost:8080 | JWT Bearer-token vajalik |
| OpenAPI / Swagger | http://localhost:8080/api/swagger-ui.html | avalik API contract |
| Keycloak | http://localhost:8180 | `.env` `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` |
| MinIO konsool | http://localhost:9001 | `.env` MinIO konto |
| Ingest scheduler | taustprotsess | enqueue-only, provider secret'e ei kasuta |
| Ingest worker | taustprotsess | `.env` eraldi ingest-DB konto ja provider credentials |

Täieliku lokaalse integratsiooni- ja observability-keskkonna käivitamiseks kasuta eraldi kihti:

```bash
docker compose -f compose.yaml -f compose.full.yaml up --build
```

See lisab PgBounceri, Redis-e, RabbitMQ, OpenTelemetry Collectori, Prometheuse, Loki, Promtaili ja Grafana. Seda režiimi kasuta ainult sõnumside-, jõudlus- või vaatlustestiks.

## Kohalik arendus ilma konteineriteta

Käivita esmalt põhisõltuvused `docker compose up postgres keycloak minio minio-setup`. Seejärel käivita API ja klient eraldi terminalides. API käivita piloodiprofiiliga, mis ei nõua Redis-e ega RabbitMQ-d.

```bash
cd backend
SPRING_PROFILES_ACTIVE=core ./gradlew bootRun

cd ../frontend
pnpm install
pnpm dev
```

Gradle tuvastab Java 21 JDK automaatselt. Kui see ei õnnestu, määra enne käivitamist keskkonnamuutuja `JAVA_HOME` oma JDK asukohale.

## Kvaliteedikontroll

```bash
# Backend: JUnit, Mockito ja Testcontainersi PostGIS kontroll
cd backend && ./gradlew test --no-daemon

# Backend: uuenda committitud jOOQ klasse Flyway-migreeritud ajutisest PostGIS andmebaasist
cd backend && ./gradlew generateJooq --no-daemon

# Frontend: ESLint, TypeScript, Vitest ja tootmisehitus
cd frontend && pnpm lint && pnpm typecheck && pnpm test && pnpm build

# Frontend: uuenda backend OpenAPI contractist genereeritud TypeScript tüübid
# Vajab töötavat API-t; OPENAPI_URL võimaldab kasutada mittevaikimisi porti.
cd frontend && OPENAPI_URL=http://localhost:8080/api/openapi pnpm generate:api

# Frontend E2E: Playwright (pärast `pnpm exec playwright install --with-deps chromium`)
cd frontend && pnpm test:e2e
```

Python ingestion deployment contracti saab ilma välisvõrgu või secret'ideta kontrollida käsuga `python -m unittest scripts/test_ingestion_deployment_contract.py scripts/test_verify_production_ingestion.py`.

GitHub Actions on praegu peatatud. Kuni hosted Actionsi minuteid ei kasutata, tuleb asjakohased backend-, frontend-, Compose- ja E2E-kontrollid teha lokaalselt enne muudatuse pushimist või merge'imist. Täpsem töövoog on failis `docs/ci-development-workflow.md`.

## Välisregistrid

ForestIQ kasutab Maa- ja Ruumiameti avalikku WFS-i, et värskendada katastriüksuse piiri, aadressi, maakonna, omavalitsuse, omandivormi ja kõlvikuandmeid. Kinnistu detailvaates on selleks nupp **Maa-amet**. Metsaportaali avaliku otsinguliidese kaudu saab samas kohas värskendada metsateatisi ning metsaeraldisi; iga päringu tulemus või viga kirjutatakse `registry_sync_log` tabelisse.

RIK Kinnistusraamatu objektide arvu päring on implementeritud ametliku SOAP-operatsiooniga `objektide_arv`. See aktiveerub, kui organisatsioon on seadistanud oma lepingupõhise teenusekonto järgmiste muutujatega; neid väärtusi ei salvestata andmebaasi ega lähtekoodi.

```bash
RIK_KINNISTUSRAAMAT_USERNAME=<rik-teenuse-kasutajanimi>
RIK_KINNISTUSRAAMAT_PASSWORD=<rik-teenuse-parool>
```

| Liides | Vaikimisi teenus | Seadistus |
| --- | --- | --- |
| Maa- ja Ruumiamet WFS | `https://gsavalik.envir.ee/geoserver/kataster/wfs` | `MAAAMET_WFS_URL` |
| Metsaportaal | `https://register.metsad.ee/portaal/api/rest` | `METSAREGISTER_API_URL` |
| RIK Kinnistusraamat | SOAP WSDL | `RIK_KINNISTUSRAAMAT_USERNAME`, `RIK_KINNISTUSRAAMAT_PASSWORD` |

## Legacy andmeimport

Kui `andmed/` kaust sisaldab MetsIS-i ekspordifaile, saab lokaalse ForestIQ andmebaasi täita Docker Compose Postgresi vastu:

```bash
python3 scripts/import_forestiq_legacy_data.py all
```

Importer kirjutab uue skeemi tabelitesse `land_parcel`, `cadastre_sub_part`, `cadastre_notification` ja `forest_registry_feature`. Katastripiiride WKT teisendatakse EPSG:3301 koordinaatsüsteemist `land_parcel.boundary` EPSG:4326 PostGIS geomeetriaks.

## Ettevõtted, lepingumallid ja PDF-id

Organisatsiooni administraator avab **Haldus → Ettevõtted ja lepingumallid** (`/admin/contracts`). Seal saab luua mitu ettevõtteprofiili, määrata vaikimisi ostja, siduda malli konkreetse ettevõttega ning muuta PDF-i lähteks olevat HTML-i. Lubatud asendusväljad kuvatakse vaates; näiteks `{{company.legalName}}`, `{{seller.name}}` ja `{{contract.parcelsRows}}` asendatakse PDF-i loomisel automaatselt.

Lehekülg **Lepingud** (`/contracts`) võimaldab valida ettevõtte ja malli, sisestada müüja-, kinnistu- ning hinnainfo, genereerida PDF-i ja laadida selle turvaliselt alla. PDF talletatakse S3-ühilduvasse objektisalvestusse ning lepingute ajalugu jääb PostgreSQL-i.

## Turbe- ja andmemudel

Keycloak väljastab OIDC JWT-d. API tõlgib `ORG_*`, `CRM_MANAGER`, `EVALUATOR`, `CALLER` ja `VIEWER` rollid Spring Security õigusteks. Iga andmebaasipäring tuletab `organization_id` JWT väitest, mistõttu kasutaja sisend ei saa valida teise organisatsiooni andmeid. Flyway on skeemimuudatuste ainus allikas; Hibernate on `validate` režiimis.

## Ümberkirjutuse ulatus

`docs/architecture.md` kirjeldab sihtarhitektuuri ja põhilisi töövooge. Elasticsearch/OpenSearch jääb tulevaseks otsinguadapteriks; esialgne otsing kasutab PostgreSQL Full-Text Searchi.
