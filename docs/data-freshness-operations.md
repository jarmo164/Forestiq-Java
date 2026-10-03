# Andmete värskuse operatsioonimudel

ForestIQ konkurentsieelis tekib siis, kui ametlikest allikatest pärinev muutus jõuab kinnistu- ja omanikuandmetesse **enne** juhi filtrit ning helistaja tööjärjekorda. Värskus ei ole käsitsi tehtav kontroll, vaid jälgitav teenusetase.

Rakenduse HTTP readiness ja andmevoogude värskus on teadlikult eraldi signaalid. Ajutine välise provideri tõrge ei muuda kogu API-d automaatselt `unready`, kuid andmevoog muutub admini vaates `DEGRADED` või `STALE` ning Prometheus saab sama seisundi kohta alertimiseks sobivad mõõdikud.

## Tootmise värskuse SLO-d

| Integratsioon | Job key | Värskuse SLO | Tüüpiline ajakava | Mõju SLO rikkumisel |
| --- | --- | ---: | --- | --- |
| Katastri avalikud andmed ja geomeetria | `CADASTRE_PUBLIC_DATA` | 168 h | iga 6 h | Kinnistu pindala-, aadressi- või piiriandmed võivad olla vananenud. |
| Metsateatised | `FOREST_NOTIFICATIONS` | 24 h | iga 6 h | Uus raie- või muu metsatöö signaal võib puududa töövalikust. |
| Metsakorralduskavad ja eraldised | `FOREST_PLAN` | 24 h | iga 6 h | Metsaandmete muutus ei pruugi jõuda hindamise või juhi filtrini. |
| MetsIS / Forestek omaniku-katastri snapshot | `OWNER_CADASTRE_RELATIONS` | 36 h | iga 24 h | Uued või lõppenud omandisuhted võivad töövalikus puududa. |
| Weaseli omandiüleminekud | `WEASEL_OWNERSHIP_TRANSITIONS` | 12 h | iga 6 h | Omandimuutuse töösignaalid ja cursor võivad providerist maha jääda. |
| Pärimisteated | `INHERITANCE_NOTICES` | 2 h | iga 30 min | Pärimisteate sobitamine ja surnud omaniku töövoog võivad hilineda. |

SLO mõõdab aega viimasest **kinnitatud edukast** integratsioonijooksust. Cursor-põhiste voogude puhul kontrollitakse lisaks cursor-lag'i; backlog või järjestikused `FAILED`/`PARTIAL` jooksud muudavad värske voogi `DEGRADED` olekusse.

## Registri catch-up maht ja jätkusuutlik piir

Vaikeseadistus on teadlikult bounded: scheduler käib iga 6 tunni järel, ühe jooksu kohta lubatakse kuni 10 batch'i ning providerit ei proovita lõputult sama tsükli sees. Teoreetiline maksimaalne stale-järjekorra töömaht on seega:

| Voog | Batch | Max batch'e jooksus | Jookse päevas | Teoreetiline maht päevas | Värskusaken | Teoreetiline jätkusuutlik portfell |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Maa- ja Ruumiameti katastriandmed | 25 katastrit | 10 | 4 | 1 000 katastrit/päev | 168 h / 7 päeva | kuni ~7 000 katastrit |
| Metsaregistri teatised | 50 katastrit | 10 | 4 | 2 000 katastrit/päev | 24 h / 1 päev | kuni ~2 000 katastrit |
| Metsaregistri kavad/eraldised | 50 katastrit | 10 | 4 | 2 000 katastrit/päev | 24 h / 1 päev | kuni ~2 000 katastrit |

Need arvud on **ülemised planeerimispiirid**, mitte providerile garanteeritud läbilase. Reaalne jätkusuutlik maht on väiksem, kui WFS vastused on aeglased, tekib rate-limit, osa katastritest ebaõnnestub või ühe scheduler-tsükli kestus hakkab lähenema järgmise tsükli algusele. Metsaregistri bulk töö kasutab lisaks pagineerimist/checkpoint'i, mistõttu ühe katastri taga võib olla mitu WFS lehekülge.

Tootmises jälgi iga jooksu `batches`, `considered`/`cadastres`, `rows`, `failed` ja `backlogRemaining` väärtusi. Kui `backlogRemaining=true` püsib vähemalt kahe järjestikuse scheduler-tsükli jooksul või backlog ei vähene, tuleb enne limiitide tõstmist mõõta tegelik drain rate. Turvaline häälestusjärjekord on: kontrolli provider latency/rate-limit → kontrolli DB/executori ressursse → tõsta vajadusel `REGISTRY_AUTO_REFRESH_MAX_BATCHES_PER_RUN` või batch'i suurust → kinnita soak/production mõõtmisega, et järgmine tsükkel ei kattu eelnevaga. `max-batches-per-run` on koodis lisaks piiratud väärtusega 100, et vale konfiguratsioon ei tekitaks piiramatut providerikoormust.

## Integratsiooni executori sizing

Scheduled callback ise providerit ei oota, vaid lisab töö bounded `integrationTaskExecutor` järjekorda. Tootmise baas-seadistus on `corePoolSize=2`, `maxPoolSize=4`, `queueCapacity=50`. See lubab kuni neljal sõltumatul provideritööl samaaegselt edeneda, samal ajal kui per-organization/job advisory lock ja aktiivse run'i unikaalsus takistavad sama töö dubleerimist.

50-kohaline järjekord on mõeldud lühikeste deploy/recovery burst'ide neelamiseks, mitte püsiva backlog'i peitmiseks. Kui järjekord saab täis, uut run'i ei jäeta `QUEUED` olekusse: see lõpetatakse `FAILED` olekus operatiivse põhjusega ning scheduler saab järgmisel tsüklil uuesti proovida. Suurenda executori concurrency't alles siis, kui CPU/RAM, Hikari/Postgres ühenduste arv ja providerite rate-limit lubavad seda; püsiv saturatsioon tähendab esmalt capacity või provider-latency probleemi, mitte vajadust piiramatult järjekorda kasvatada.

## Tervise olekud

- `HEALTHY` — viimane edukas jooks ja cursor on SLO piires, failure streak puudub ning backlog pole tuvastatud.
- `DEGRADED` — andmed on veel SLO piires, kuid viimased jooksud on ebaõnnestunud/osalised, catch-up jätkub või käimasolev töö on ebatavaliselt pikk.
- `STALE` — edukas jooks puudub või viimane edu/cursor ületab SLO-d.
- `DISABLED` — automaatne ajakava on teadlikult välja lülitatud.
- `MISCONFIGURED` — provider või vajalik teenusekonto/tenant pole täielikult seadistatud.

Admini **Andmeallikad ja sünkroonimine** vaade näitab iga feed'i tervise olekut, põhjendust, SLO-d, viimase edu vanust, failure streak'i, backlog'i ja cursor-lag'i.

## Prometheus mõõdikud

`/actuator/prometheus` ekspordib madala kardinaalsusega mõõdikud. Ainus dünaamiline label on piiratud enum `job`; organisatsiooni-ID-sid metric label'itena ei kasutata.

- `forestiq_dataflow_enabled{job=...}` — `1`, kui feed on seadistatud ja automaatne ajakava aktiivne.
- `forestiq_dataflow_stale{job=...}` — `1`, kui aktiivne feed on SLO järgi stale.
- `forestiq_dataflow_latest_success_age_seconds{job=...}` — organisatsioonide halvim viimase edu vanus; `-1`, kui edu pole veel olnud.
- `forestiq_dataflow_failure_streak{job=...}` — halvim järjestikuste `FAILED`/`PARTIAL` jooksude arv pärast viimast edu.
- `forestiq_dataflow_backlog{job=...}` — `1`, kui durable checkpoint või run-metric näitab lõpetamata catch-up'i.
- `forestiq_dataflow_cursor_lag_seconds{job=...}` — Weaseli/pärimise halvim cursor-lag; `-1`, kui cursor pole selle feed'i jaoks rakendatav või veel loodud.

Mõõdikud koondatakse job key tasemele, et need sobiksid Prometheuse alertideks ka mitme organisatsiooni korral ilma tenant-label'ite plahvatuseta.

## Soovitatud alertid

Minimaalne tootmise alerting:

1. **Critical stale feed**: `forestiq_dataflow_enabled == 1` ja `forestiq_dataflow_stale == 1` vähemalt 15 minutit.
2. **Growing backlog**: `forestiq_dataflow_backlog == 1` vähemalt kahe järjestikuse tavapärase scheduler-tsükli jooksul.
3. **Repeated failures**: `forestiq_dataflow_failure_streak >= 3`.
4. **Cursor lag**: `forestiq_dataflow_cursor_lag_seconds` ületab vastava feed'i SLO-d.
5. **Disabled production feed**: production dashboardil `forestiq_dataflow_enabled == 0` feed'i puhul, mis peab deployment policy järgi aktiivne olema.

Lühikese provideri katkestuse korral ei ole vaja API-d restartida. Esmalt kontrolli admini integratsioonivaates viimase run'i `errorSummary`, backlog'i ja cursor-lag'i; vajadusel kasuta kontrollitud käsikäivitust pärast provideri taastumist.

## Kuidas süsteem taastub

Automaatne ja käsitsi käivitatud taastamine kasutavad sama PostgreSQL tehingulist nõuandelukku. Seega ei käivita kaks paralleelset taastamispartiid sama organisatsiooni sama välisallika vastu kattuvat tööd. Ebaõnnestunud töö ei tohi cursorit valesti edasi nihutada.

Metsaregistri bulk WFS kasutab durable checkpoint'i ning atomaarset staging snapshot'i. Weasel jätkab katkestatud provider-window'd salvestatud leheküljelt. Pärimisteated hoiavad `lastCompletedDate` cursorit ja järeltöötlevad katkestuse järel puuduvaid päevi enne tavapärase rolling overlap'i jätkamist. MetsIS/Forestek snapshot rakendatakse live omandisuhetele alles pärast täieliku snapshot'i kättesaamist.

## Kohustuslik tootmisseadistus

Tootmise scheduler peab jooksma alati-on protsessis ning vajalikud feed'id peavad olema teadlikult sisse lülitatud:

```bash
REGISTRY_AUTO_REFRESH_ENABLED=true
METSIS_PORTFOLIO_AUTO_SYNC_ENABLED=true
WEASEL_AUTO_REFRESH_ENABLED=true
INHERITANCE_SOURCE_AUTO_REFRESH_ENABLED=true
```

Providerite URL-id, bearer-tokenid ja organisatsiooni-ID-d hoitakse deployment-keskkonna secret/config väärtustena, mitte Git repos. Täpne Renderi tootmisjuhend on failis `docs/production-render-deployment.md`.

## Operatiivne kontroll pärast deploy'd

1. Veendu, et API readiness on roheline.
2. Ava admini **Andmeallikad ja sünkroonimine** vaade ning kontrolli, et vajalikud feed'id on `ENABLED`.
3. Esmakäivituse ajal võib feed olla `STALE`, kuni esimene kinnitatud edukas jooks lõpetab.
4. Kontrolli, et pärast edukat jooksu muutub feed `HEALTHY` või põhjendatult `DEGRADED` olekusse.
5. Veendu, et Prometheus näeb `forestiq_dataflow_*` mõõdikuid.
6. Jäta rakendus ilma kasutajaliikluseta tööle ja tõenda vähemalt kaks järjestikust scheduler-tsüklit; scheduler ei tohi sõltuda veebiliiklusest.

`STALE` feed'i ei tohi käsitleda lihtsalt UI probleemina. Enne tööjaotust tuleb hinnata, milline äriline töövoog vananenud andmetest sõltub ning kas feed vajab provideri taastumist, backlog'i järeltöötlust või deployment-konfiguratsiooni parandamist.
