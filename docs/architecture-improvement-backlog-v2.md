# ForestIQ arhitektuuri ja funktsionaalsuse parenduste backlog v2

**Haru:** `rewrite/next-spring-postgis`  
**Audit:** 24. august 2026  
**Versioon:** v2  
**Eesmärk:** hinnata esimese hardening-backlog'i tegelikku valmimist, koondada teise auditi leiud ning määrata järgmine arendusjärjekord enne laiemat pilooti ja production-kasutust.

## Kokkuvõte

Java/Spring + Next.js rewrite on pärast esimest auditit selgelt paranenud. Keskne owner authorization, optimistlik lukustus ownerile, jOOQ codegen, modulaarsem frontend API kiht, Keycloaki token cache, Trivy kontrollid ning Compose-põhine Playwright CI on päriselt lisatud. Seetõttu ei ole mõistlik uut rewrite'i alustada.

Samas näitas teine audit, et mitu varem suletud issue't täitis acceptance criteria ainult osaliselt. Kõige olulisem uus risk on see, et owner-level authorization ei kata kogu owneriga seotud andmepinda: CALLER saab map/parcel-summary kaudu ligi organisatsiooni teiste omanike kontaktandmetele ning multi-role token võib praeguse policy puhul CALLER-i write-restriction'i eemaldada.

Järgmine arenduslaine peab keskenduma kolmele eesmärgile:

1. **sulgeda authorization ja workflow correctness lüngad**;
2. **viia tehingujuhtum lõpuni pakkumise, vastupakkumise, close'i ja lepinguni**;
3. **teha onboarding ning CI piisavalt usaldusväärseks, et süsteemi saaks pärisandmete ja laiema kasutajaskonnaga piloteerida**.

---

## Eelmise backlog'i järelkontroll

| Issue | Seis v2 auditis | Järeldus |
| --- | --- | --- |
| [#1](https://github.com/jarmo164/ForestIQ/issues/1) CALLER authorization | **Reopened / P0** | OwnerService'i piirang töötab, kuid map/cadastre andmepind ja multi-role kombinatsioonid jätavad bypass'i. |
| [#2](https://github.com/jarmo164/ForestIQ/issues/2) Backend coverage/security tests | **Reopened / P1** | Gate tõusis 5%/1% → 21%/13%, kuid algne 60–70% siht ja kriitilised workflow testid pole veel saavutatud. |
| [#3](https://github.com/jarmo164/ForestIQ/issues/3) Playwright E2E | **Reopened / P1** | Päris Compose E2E on olemas, kuid authorization-, assignment-, deal- ja contract-flowd on veel liiga nõrgalt kaetud. |
| [#4](https://github.com/jarmo164/ForestIQ/issues/4) My Work parcel count/pagination | **Completed** | Parcel count, query repository ja jagatud pagination on parandatud. |
| [#5](https://github.com/jarmo164/ForestIQ/issues/5) Optimistic locking | **Reopened / P2** | Owner on kaitstud; deal/evaluation concurrency on endiselt kaitsmata. |
| [#6](https://github.com/jarmo164/ForestIQ/issues/6) jOOQ codegen/repositories | **Reopened / P2** | Codegen on olemas, kuid Deal/Inheritance/Registry kasutavad endiselt palju stringipõhist jOOQ-d/raw SQL-i. |
| [#7](https://github.com/jarmo164/ForestIQ/issues/7) Large service split | **Reopened / P2** | Repository extraction on alanud, kuid DealService/InheritanceService/RegistrySyncService on jätkuvalt liiga laia vastutusega. |
| [#8](https://github.com/jarmo164/ForestIQ/issues/8) Frontend OpenAPI contract | **Reopened / P2** | API moodulid ja generation on olemas; manual DTO duplication/castid ning CI drift-check on veel alles. |
| [#9](https://github.com/jarmo164/ForestIQ/issues/9) Validation/scheduling/prod hardening | **Reopened / P2** | Keycloak ja Trivy on paranenud; request validation, distributed workers ja fail-fast production config on pooleli. |

---

# Prioriteeditabel v2

## P0 — enne järgmist laiemat funktsionaalsust

| Priority | Teema | Miks oluline | Tulemus | GitHub |
| --- | --- | --- | --- | --- |
| **P0** | Resource-level authorization kogu owner-andmepinnale | CALLER saab praegu map/parcel-summary kaudu teise kasutaja owneri nime, telefoni ja aadressi; multi-role token võib write-restriction'i eemaldada. | Üks action/resource policy owner, map, cadastre, deal ja seotud API-de jaoks; role × resource × action testimaatriks. | [#1](https://github.com/jarmo164/ForestIQ/issues/1) |

### P0 Definition of Done

- CALLER ei saa ühegi alternatiivse endpointi kaudu teise CALLER-i owneri kontaktandmeid.
- `CALLER + VIEWER` või `CALLER + EVALUATOR` ei anna rohkem write-õigust kui CALLER-il peaks olema.
- Kaardi äriline kasutusreegel on selgelt otsustatud: kas CALLER näeb kõiki kinnistuid anonüümselt või ainult talle määratud ownerite kinnistuid.
- Owner, parcel summary, cadastre ja deal resource-access on testitud nii lubatud kui keelatud kombinatsioonidega.

---

## P1 — järgmine arenduslaine

| Priority | Teema | Miks oluline | Tulemus | GitHub |
| --- | --- | --- | --- | --- |
| **P1** | Deal qualification UX + React Query cache | Cancel võib näidata vale success teadet ning valed query key'd jätavad UI vanasse seisu. | Eraldi cancel/success callbackid, keskne query-key factory, õige cache invalidation. | [#14](https://github.com/jarmo164/ForestIQ/issues/14) |
| **P1** | Hindaja assignment + päris tööpäevade SLA | Praegu on evaluaatori järjekord ühine ning “3 tööpäeva” arvutatakse kolme kalendripäevana. | Claim/assignment/reassign workflow ja jagatud BusinessCalendar. | [#15](https://github.com/jarmo164/ForestIQ/issues/15) |
| **P1** | Üks canonical evaluation flow | Vana `SEND_TO_EVALUATION` võib muuta owneri EVALUATION staatuseks ilma deal/evaluation taskita. | Legacy tee eemaldatud või suunatud Deal use-case'i; üks evaluation state machine. | [#16](https://github.com/jarmo164/ForestIQ/issues/16) |
| **P1** | Offer → counteroffer → WON/LOST/CANCELLED | Deal stage'id on mudelis olemas, kuid kasutusvoog peatub sisuliselt NEGOTIATION etapis. | Versioonitud pakkumised, vastupakkumised, loss reason ja terminal states. | [#17](https://github.com/jarmo164/ForestIQ/issues/17) |
| **P1** | Contract ↔ Deal/accepted offer | Lepingu hind, müüja ja kinnistud sisestatakse pärast tehinguvoogu uuesti käsitsi. | Deal-ist eeltäidetud leping ja auditeeritav seos accepted offeriga. | [#18](https://github.com/jarmo164/ForestIQ/issues/18) |
| **P1** | Owner creation UI + CSV/XLSX onboarding | API on olemas, kuid UI-st ownerit lisada ei saa ja bulk import on operaatori käsurea töö. | Add Owner UI + preview/dry-run/validation/import batch audit. | [#19](https://github.com/jarmo164/ForestIQ/issues/19) |
| **P1** | Demoandmed production Flyway'st välja | Fresh DB saab alati ForestIQ Demo ownerid/kinnistud. | Schema migration ja dev/test seed on lahutatud. | [#20](https://github.com/jarmo164/ForestIQ/issues/20) |
| **P1** | Rewrite branch CI | Push `rewrite/next-spring-postgis` harusse ei käivita automaatselt sama CI-d kui main/PR. | Iga rewrite commit või merge candidate saab backend/frontend/Compose/E2E kontrolli. | [#21](https://github.com/jarmo164/ForestIQ/issues/21) |
| **P1** | Backend test coverage + security tests | 21% line / 13% branch on hea esimene samm, kuid kriitilise CRM/deal süsteemi jaoks madal. | Staged gate 30% → 45% → 60%+ ja kriitilised permission/workflow testid. | [#2](https://github.com/jarmo164/ForestIQ/issues/2) |
| **P1** | Critical E2E coverage | Praegused E2E-d tõestavad stacki, kuid mitte veel põhilist authorization + deal lifecycle'i. | Assignment → caller → qualification → evaluation → negotiation + forbidden access + contract smoke. | [#3](https://github.com/jarmo164/ForestIQ/issues/3) |

### P1 soovitatud teostusjärjekord

1. **#14** — paranda vale success feedback ja cache invalidation; väike töö, otsene UX-kasu.
2. **#15** — evaluator assignment ja BusinessCalendar.
3. **#16** — eemalda paralleelne legacy evaluation path.
4. **#17** — ehita offer/counteroffer/close state machine.
5. **#18** — seo contract accepted deal/offeriga.
6. **#19 + #20** — tee pärisandmete onboarding turvaliseks ning eemalda production demo seed.
7. **#21** — taga, et rewrite'i iga oluline commit läbiks reaalselt CI.
8. **#2 + #3** — tõsta regressioonikaitse koos uute workflow'dega, mitte alles pärast neid.

Praktikas tuleb #2 ja #3 teste lisada paralleelselt iga ülaltoodud feature'iga; neid ei tohiks käsitleda eraldi “QA lõpuetapina”.

---

## P2 — arhitektuuri ja production hardening

| Priority | Teema | Miks oluline | Tulemus | GitHub |
| --- | --- | --- | --- | --- |
| **P2** | Deal/evaluation optimistic concurrency | Deal transitionid ja `MAX(revision)+1` evaluation revision võivad paralleelselt võistelda. | Version/expected-state locking, unique revision guard, 409 conflict. | [#5](https://github.com/jarmo164/ForestIQ/issues/5) |
| **P2** | jOOQ generated schema lõpuni | Mitmed kriitilised domeenid kasutavad endiselt runtime string table/field kirjeldusi. | Generated tables core domain CRUD/querydes + CI stale-generation check. | [#6](https://github.com/jarmo164/ForestIQ/issues/6) |
| **P2** | Suurte service'ite decomposition | Deal/Inheritance/Registry ühendavad workflow, SQL, validation ja adapter-loogikat. | Selged use-case/application service'id ja persistence/integration adapterid. | [#7](https://github.com/jarmo164/ForestIQ/issues/7) |
| **P2** | Frontend generated API contract lõpuni | `lib/types.ts` dubleerib jätkuvalt API DTO-sid ning castid võivad contract drift'i peita. | Generated request/response types otse API moodulites + CI generation diff. | [#8](https://github.com/jarmo164/ForestIQ/issues/8) |
| **P2** | Validation, workers ja production config | Request validation on ebaühtlane; workerite distributed guarantee pole ühtlane; dev defaults on base configis. | Ühtlane Bean Validation/ProblemDetail, üks scheduler-strateegia, fail-fast production profile. | [#9](https://github.com/jarmo164/ForestIQ/issues/9) |

---

## P3 — pärast põhilise workflow stabiliseerumist

P3 ei ole praegu eraldi issue'deks jagatud, sest need sobivad paremini vastavate P1/P2 tööde järeltegevusteks.

- Keycloak admin kasutajaloendi pagination/bulk-mahtude tugevdamine, kui organisatsioonide kasutajate arv kasvab.
- Dokumentatsiooni järjepidev uuendamine: `implemented / partial / planned` staatused arhitektuuri-, readiness- ja workflow dokumentides.
- Performance baselines: owner search, MVT tiles, admin search, registry recovery batch ja sales queue.
- PostgreSQL RLS-i hindamine defense-in-depth meetmena, kui multi-tenant andmete tundlikkus või deployment mudel seda õigustab.
- Observability alerting: registry failure rate, stale parcel count, worker failure, E2E failure ja deal SLA breach.

---

# Funktsionaalsed lüngad, mida v2 prioriteedid lahendavad

## 1. Kontakt → hindamine

Praegu on uus Deal flow oluliselt parem kui vana owner-status põhine mudel, kuid kaks rada eksisteerivad korraga. Pärast #15 ja #16 peaks ainus tee olema:

`ASSIGNED → kontakt → kvalifitseeritud Deal → evaluator assignment → EVALUATION_REQUESTED → EVALUATING → VALUED`

Owner status jääb CRM-i kokkuvõtteks; workflow source of truth on Deal.

## 2. Hindamine → pakkumine

Praegu saab Deal liikuda `VALUED → NEGOTIATION`, kuid `OFFER_PREPARED` ja `OFFER_SENT` stage'id pole täisväärtuslikult kasutatavad. #17 lisab eraldi pakkumise ajaloo, et sisemine hindamisotsus ja kliendile tehtud pakkumine ei oleks sama asi.

## 3. Läbirääkimine → close

Pärast #17 peab iga aktiivne tehing omama järgmist sammu ning sulguma ühe selge tulemusega:

- `WON`
- `LOST` + standardne kaotuse põhjus
- `CANCELLED`

## 4. Close → contract

#18 eemaldab topeltsisestuse. Leping peab kasutama tehingus juba kinnitatud ownerit, kinnistuid, ostjat ja accepted offerit ning säilitama allikaseose auditiks.

## 5. Pärisandmete onboarding

#19 ja #20 muudavad rakenduse “demo/piloodi koodibaasist” päriselt hallatavaks süsteemiks:

- üksiku owneri lisamine UI-st;
- bulk CSV/XLSX import preview ja validationiga;
- import batch audit;
- production schema ilma automaatsete demo klientideta.

---

# Soovitatud järgmine milestone

## Milestone: `Pilot-ready deal workflow`

Milestone'i võiks lugeda valmis, kui:

- [ ] #1 resource authorization on suletud ja security testid rohelised;
- [ ] #14 qualification UX/cache bugid on parandatud;
- [ ] #15 evaluator assignment + business-day SLA töötab;
- [ ] #16 legacy evaluation path on eemaldatud;
- [ ] #17 offer/counteroffer/WON/LOST workflow töötab;
- [ ] #18 contract saab tekkida deal/offer kontekstist;
- [ ] #19 owner creation + kontrollitud import on olemas;
- [ ] #20 production DB ei saa automaatselt demoandmeid;
- [ ] #21 rewrite commitid saavad automaatse CI tõendi;
- [ ] #2/#3 testid katavad ülaltoodud põhivoo ja forbidden-access stsenaariumi.

Pärast seda milestone'i on ForestIQ põhiline äriahel terviklik:

**kontakt → kvalifitseerimine → hindamine → pakkumine → vastupakkumine/läbirääkimine → võit/kaotus → leping**.

---

# Arhitektuuriline suund

Rewrite'i ei ole vaja uuesti ümber kirjutada. Olemasolev Java 21 + Spring Boot 3 + PostgreSQL/PostGIS + Next.js alus on piisavalt tugev. Edasine arhitektuuritöö peab vähendama olemasolevat coupling'ut, mitte looma uut paralleelset platvormi.

Põhireeglid järgmiste feature'ite jaoks:

1. **authorization on resource/action põhine**, mitte ainult controlleri role-check;
2. **Deal on müügiprotsessi source of truth**, owner status on CRM summary;
3. **workflow transitionid käivad ühe use-case/state-machine tee kaudu**, mitte mitmest endpointist otse staatust muutes;
4. **API wire contract tuleb OpenAPI-st**, käsitsi frontend DTO ainult teadliku view-model erinevuse korral;
5. **SQL kasutab generated jOOQ skeemi**, välja arvatud põhjendatud PostGIS/PostgreSQL spetsiifilised päringud;
6. **iga feature tuleb koos testiga**, mitte “test coverage” eraldi hilisema projektina;
7. **production migration ei sisalda demoandmeid ega vaikimisi saladusi**;
8. **iga tausttöö omab selget distributed/single-instance garantiid**.

## Seos v1 backlog'iga

`docs/architecture-improvement-backlog.md` jääb esimese auditi ajalooliseks lähtekohaks. Käesolev v2 dokument on 24.08.2026 teise auditi järel kehtiv prioriteetide koondvaade ning peaks olema järgmise arenduslaine peamine tehniline backlog-indeks.