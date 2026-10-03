# ForestIQ konteinerarhitektuuri DevOps-audit

## Otsus

ForestIQ praegune `compose.yaml` kirjeldab **13 püsivat teenust** ning üht ühekordset MinIO ettevalmistuskonteinerit. See on hea täisfunktsionaalne lokaalne integratsioonikeskkond, kuid väikese meeskonna piloodi ega esialgse tootmiskäivituse jaoks ei ole nii suur hulk iseseisvalt hallatavaid konteinereid põhjendatud.

> Praegust faili ei tule pimesi kärpida. Enne teenuse eemaldamist tuleb luua teadlikud `core`, `full` ja `observability` Compose-profiilid ning muuta rakenduse sõltuvused tingimuslikuks. Vastasel juhul võib lihtne `docker compose up` ebaõnnestuda puuduvate Redis-, RabbitMQ- või S3-ühenduste tõttu.

## Teenuste tegelik roll

| Teenus | Praegune roll | Kas see on piloodis vältimatu? | Soovitus |
| --- | --- | --- | --- |
| `postgres` (PostGIS) | Kõik CRM-, kinnistu-, geomeetria- ja töövooandmed. | **Jah** | Hoida. Tootmises eelistada hallatud PostgreSQL + PostGIS-tuge. |
| `pgbouncer` | Andmebaasi ühenduste multipleksimine. | Ei, 2–20 kasutaja puhul. | Eemaldada piloodiprofiilist; võtta kasutusele alles mitme API-replika või ühendussurve korral. |
| `keycloak` | Sisselogimine, rollid, organisatsiooniväide ja kasutajahaldus. | **Jah**, kui mitte kasutada hallatud OIDC-teenust. | Hoida ühe organisatsiooni piloodis; tootmises hallata eraldi või asendada hallatud OIDC-ga. |
| `redis` | Redis-vahemälu konfiguratsioon. | Mitte praeguste põhitöövoogude jaoks. | Muuta valikuliseks; `core`-profiilis kasutada lihtsat lokaalmälu-vahemälu või väljalülitatud vahemälu. |
| `rabbitmq` | Omaniku staatuse muutuse asünkroonne sündmus ning reaalaja teavituse duplikaatkanal. | Ei. Põhi-CRM töövoog jätkub ka siis, kui avaldamine ebaõnnestub. | Muuta valikuliseks; hoida alles alles siis, kui tekib eraldi tööline, integreerimine või kõrge sündmusmaht. |
| `minio` | PDF-lepingute objektisalvestus. | Jah, kui lepingute loomine ja allalaadimine on piloodis kasutusel. | Lokaalses piloodis hoida; tootmises asendada hallatud S3/R2/B2-ühilduva salvestusega. |
| `minio-setup` | Loob MinIO ämbri. | Ainult lokaalarenduses. | Hoida ühekordse init-tööna `dev`-profiilis; see ei ole püsiv teenus. |
| `api` | Spring Booti CRM, georuumi- ja integratsiooni-API. | **Jah** | Hoida. |
| `frontend` | Next.js kasutajaliides ja API-proxy. | **Jah** | Hoida või tulevikus ühendada üheks deployable’iks koos API gateway’ga. |
| `otel-collector` | Telemeetria vahendaja. | Ei. | Kasutada ainult siis, kui OTLP-siht või observability-platvorm on olemas. |
| `prometheus` | Mõõdikute kogumine. | Ei piloodis. | Väljastada `/actuator/prometheus`; kasutada hallatud monitooringut või aktiveerida eraldi profiilina. |
| `loki` | Logide andmebaas. | Ei piloodis. | Saata struktureeritud logid platvormi logiteenusesse või aktiveerida eraldi profiilina. |
| `promtail` | Logide saatja Lokisse. | Ei. | Koos Lokiga ainult self-hosted observability-profiilis. |
| `grafana` | Mõõdikute ja logide vaatamine. | Ei piloodis. | Kasutada hiljem või hallatud Grafanat. |

## Soovitatud keskkonnaprofiilid

| Profiil | Püsikonteinerid | Milleks sobib | Märkus |
| --- | --- | --- | --- |
| `dev-minimal` | Postgres, Keycloak, MinIO, API, frontend | Arendaja töö, funktsionaalne demo | Vajab väikest konfiguratsioonimuudatust, et Redis ja RabbitMQ oleksid valikulised. |
| `pilot` | Postgres, Keycloak, MinIO, API, frontend | 2–10 kasutaja kontrollitud piloot | **Soovitatud lähtepunkt.** MinIO init jookseb vaid käivitusel. |
| `full-local` | Kõik praeguse Compose’i teenused | Integratsiooni- ja vaatluskeskkond | Sobib CI/e2e katseks või DevOps-harjutuseks, mitte vaikimisi töörežiimiks. |
| `production-managed` | API, frontend; vajadusel Keycloak | Päris kasutus | PostgreSQL/PostGIS, S3, monitooring ja võimalusel OIDC tuleb võtta hallatud teenusena. Redis/RabbitMQ lisada alles mõõdetud vajaduse korral. |

## Miks praegune hulk on ülemäärane

Praeguses mahus peab väike meeskond haldama kuut andmemahtu (`postgres`, `redis`, `minio`, `prometheus`, `loki`, `grafana`), nelja eraldi turbe- või salajaste konfiguratsioonide komplekti ning mitut sõltuvusgraafi. Kõik see lisab varunduse, uuenduste, turvapaikade, monitooringu ja veaotsingu koormust enne, kui rakendusest saadav äriväärtus seda õigustab.

Teisalt ei tohi PostgreSQL/PostGIS-i, OIDC-autentimist ega objektisalvestust lihtsalt eemaldada, sest need toetavad otseselt põhiandmeid, organisatsioonipiire ja lepingudokumente. `pgbouncer`, RabbitMQ ning täielik Prometheus/Loki/Grafana komplekt lahendavad tuleviku skaleerimis- ja vaatlusküsimusi, kuid nende järele pole praeguse kasutajamahu ega funktsioonide põhjal tõendatud vältimatut vajadust.

## Teostusjärjekord

Esmalt tuleb lisada Compose-profiilid ja kaks Springi käitusrežiimi: `core` kasutab lokaalset vahemälu ning sündmuste ainult WebSocketi-kanalit; `full` aktiveerib Redis-e, RabbitMQ ja täisvaatluse. Seejärel tuleb piloot teha `pilot`-profiiliga ja jälgida API latentsust, andmebaasi ühenduste arvu, töövoo sündmuste mahtu ning logimahtu vähemalt kaks nädalat. Ainult nende mõõdikute põhjal tasub otsustada, kas Redis, RabbitMQ või Pgbouncer tuleb püsivalt tagasi lisada.

Lõpuks tuleb ehitada eraldi tootmisseadistus, mis ei publitseeri andmebaasi, Keycloaki, MinIO ega vaatlusteenuste porte internetti. Tootmises peab varundus, TLS, saladuste haldus ja taastetest olema rakendusest eraldi vastutusala.

## Rakendatud käitusmudel

Refaktor on rakendatud järgmiste failidega.

| Fail | Tulemus |
| --- | --- |
| `compose.yaml` | Vaikekäsk `docker compose up --build` käivitab ainult PostgreSQL/PostGIS-i, Keycloaki, MinIO, API ja frontendi. `minio-setup` on ühekordne ämbri loomise töö. |
| `compose.full.yaml` | Lisab vajaduse korral PgBounceri, Redis-e, RabbitMQ ning lokaalse OpenTelemetry/Prometheus/Loki/Promtail/Grafana komplekti. |
| `application-core.yml` | Lülitab piloodirežiimis välja Redis-e ja RabbitMQ automaatkonfiguratsiooni ning kasutab lihtsat lokaalset vahemälu. |
| `OwnerEventPublisher` | Aktiveerub ainult siis, kui RabbitMQ `RabbitTemplate` on olemas; seetõttu jääb põhi-CRM töövoog minimaalses režiimis toimivaks. |
| `.github/workflows/ci.yml` | Valideerib nii piloodi kui täisrežiimi Compose-konfiguratsiooni ja käivitab backend’i kontrollid `core` profiiliga ka rewrite-harus. |

Piloodi käsk on `docker compose up --build`. Täisrežiimi käsk on `docker compose -f compose.yaml -f compose.full.yaml up --build`.
