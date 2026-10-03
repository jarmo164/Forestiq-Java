# ForestIQ arhitektuuri ja kvaliteedi parenduste backlog

**Haru:** `rewrite/next-spring-postgis`  
**Uuendatud:** 24. august 2026  
**Eesmärk:** koondada Java/Spring + Next.js rewrite'i olulisemad tehnilised parendused ühte prioriseeritud tööjärjekorda.

## Kokkuvõte

Rewrite'i tehnoloogiline alus on tugev: Java 21, Spring Boot 3, PostgreSQL/PostGIS, Flyway, jOOQ, Keycloak/OAuth2, Next.js/TypeScript ning konteineripõhine arendus- ja käivituskeskkond. Järgmine mõistlik samm ei ole uus rewrite, vaid olemasoleva lahenduse turvalisuse, testitavuse ja arhitektuursete piiride tugevdamine.

Kõige olulisem risk on owner-taseme autoriseerimine: `CALLER` roll pääseb praegu üldiste owner endpointide kaudu organisatsiooni piires laiemalt ligi kui talle määratud töövoog eeldab. Selle järel tulevad testikvaliteet, kriitilised E2E vood, My Work andmete korrektsus ja optimistlik lukustus.

## Prioriteeditabel

| Priority | Teema | Miks oluline | Soovitatud tulemus | GitHub issue |
| --- | --- | --- | --- | --- |
| **P0** | CALLER owner-level authorization | Kasutaja võib saada ligi või muuta sama organisatsiooni omanikku, kes ei ole talle määratud; staatuse muutmise DTO võimaldab ka assignee muutmist. | Keskne owner authorization policy; CALLER ainult talle määratud owneritele; assignment ainult lubatud rollidele; negatiivsed turvatestid. | [#1](https://github.com/jarmo164/ForestIQ/issues/1) |
| **P1** | Backend test coverage + security tests | JaCoCo väravad 5% line / 1% branch ei kaitse suure rewrite'i regressioonide eest. | Tõsta väravaid järk-järgult vähemalt ~60–70% line tasemele ning kata kriitilised turva- ja ärivood. | [#2](https://github.com/jarmo164/ForestIQ/issues/2) |
| **P1** | Playwright E2E CI-s | Praegune E2E tõend ei kata reaalseid kasutajavooge ega integreeritud stacki. | Login + owner + my-work + assignment/status + vähemalt üks registry/deal flow automaatselt töötava stacki vastu. | [#3](https://github.com/jarmo164/ForestIQ/issues/3) |
| **P1** | My Work parcel count ja pagination | `MyWorkController` tagastab parcel count väärtuseks alati `0`; page/size sisendid pole ühtlaselt valideeritud. | Päris parcel count, jagatud pagination reeglid ning piirväärtuste testid. | [#4](https://github.com/jarmo164/ForestIQ/issues/4) |
| **P1** | Optimistic locking | Mitme kasutaja ja background sync'i paralleelsed muudatused võivad üksteist märkamatult üle kirjutada. | `@Version`, Flyway migratsioon, HTTP 409 konflikt ning frontend refresh/retry käsitlus. | [#5](https://github.com/jarmo164/ForestIQ/issues/5) |
| **P2** | jOOQ codegen + repository split | Stringipõhine jOOQ kaotab compile-time skeemiohutuse; `OwnerQueryRepository` on liiga laia vastutusega. | Flyway skeemist jOOQ generated classes; väiksemad vastutuspõhised repository'd; SQL controlleritest välja. | [#6](https://github.com/jarmo164/ForestIQ/issues/6) |
| **P2** | Suurte backend service'ite jagamine | `InheritanceService`, `DealService`, `RegistrySyncService` jt sisaldavad liiga palju vastutusi. | Selged use-case/application service'id, eraldatud adapterid ja läbipaistvad transaction boundary'd. | [#7](https://github.com/jarmo164/ForestIQ/issues/7) |
| **P2** | Frontend API modulariseerimine + OpenAPI client | Suur `lib/api.ts` ja käsitsi hoitavad tüübid kasvatavad coupling'ut ja backend/frontend contract drift'i riski. | Feature-põhised API moodulid ning backend OpenAPI-st genereeritud TypeScript tüübid/client. | [#8](https://github.com/jarmo164/ForestIQ/issues/8) |
| **P2** | Validation, Keycloak ja production scheduling | Ebaühtlane request validation, Keycloak N+1/token overhead ja in-memory Quartz tekitavad production-riske. | Ühtlane Bean Validation/ProblemDetail; Keycloak token cache ja kindel client-role mapping; dokumenteeritud persistent/distributed scheduling; security scans ja fail-fast prod config. | [#9](https://github.com/jarmo164/ForestIQ/issues/9) |
| **P3** | Dokumentatsiooni ajakohasus | Mõned arhitektuuri/roadmap dokumendid kirjeldavad komponente endiselt tulevase tööna, kuigi need on juba koodis olemas. | Märgi igas roadmapis selgelt `implemented / partial / planned`; hoia arhitektuuridokument koodiga kooskõlas. | käsitleda jooksvalt vastavate issue'de PR-ides |

## Soovitatud teostusjärjekord

### Etapp 1 — turvalisus ja andmekorrektsus

1. **#1 CALLER authorization** — tee enne järgmiste CRM funktsioonide lisamist.
2. **#4 My Work correctness** — paranda workdesk'i otsene funktsionaalne viga ja pagination.
3. **#5 Optimistic locking** — väldi vaikseid lost-update olukordi.

Selle etapi järel peaks olema usaldusväärne alus piloodi kasutajaõigustele ja igapäevase CRM töö andmetele.

### Etapp 2 — regressioonikaitse

4. **#2 Backend coverage/security tests** — esmalt kirjuta testid kriitilistele piiridele ja tõsta coverage gate'i sammhaaval.
5. **#3 Critical E2E** — tõesta võtmevood reaalselt käivitatud stacki vastu.

Pärast seda etappi on arhitektuuri refaktorid oluliselt turvalisemad, sest regressioonid leitakse automaatselt.

### Etapp 3 — arhitektuuri võla vähendamine

6. **#6 jOOQ codegen + repository split**.
7. **#7 backend service decomposition**.
8. **#8 frontend API/OpenAPI modularization**.

Neid tasub teha enne, kui rewrite'i lisatakse suur hulk uusi mooduleid, sest vastasel juhul kasvab sama struktuuriprobleem iga uue feature'iga.

### Etapp 4 — production hardening

9. **#9 validation / Keycloak / scheduler / scans**.
10. Uuenda operatsioonilise valmisoleku dokumente ja tee deployment/go-live kontrollid.

## Detailsemad tähelepanekud

### 1. Autoriseerimine peab olema resource-level, mitte ainult role-level

Controlleri `@PreAuthorize` kontroll ütleb, kas roll tohib endpointi üldiselt kasutada. See ei vasta küsimusele, kas konkreetne CALLER tohib konkreetset ownerit lugeda või muuta.

Soovitus:

- loo näiteks `OwnerAuthorizationService` või selge domain policy;
- kontrolli organisatsiooni kõrval assignment'i;
- ära lase `ChangeStatusRequest` kaudu assignment'i muuta rollidel, kellel assignment õigust ei ole;
- rakenda sama policy't kõikjal: detail, update, activity, parcel marking, following ja muud owneriga seotud käsud;
- kirjuta testid eeskätt keelatud kombinatsioonidele.

### 2. Testikvaliteet peab kasvama koos rewrite'iga

Praegune backend coverage gate on sisuliselt ainult testide olemasolu kontroll. Suurtes teenustes ja integratsioonides jääb regressioon muidu kergesti märkamata.

Soovitatud sammud näiteks:

- esimene värav 20–30%;
- seejärel 40–50%;
- sihttase 60–70% kogu backendile;
- turva-, workflow- ja permission koodil kõrgem sisuline kate, sõltumata üldisest protsendist.

Coverage protsent ei ole eesmärk omaette: kõige olulisemad on owner authorization, assignment, deal state transitions, registry/inheritance synchronization ja organization isolation testid.

### 3. E2E peab katma päris süsteemipiire

Üksnes frontend unit testid ja Spring testid ei tõesta, et Keycloak + API + PostgreSQL/PostGIS + frontend töötavad koos.

Minimaalne smoke/regression komplekt:

- kasutaja autentimine;
- role/organization claim töötab;
- owner search + detail;
- CALLER näeb oma tööjärjekorda;
- tegevuse või staatuse salvestus;
- CRM manager saab töö määrata;
- keelatud owner annab 403;
- vähemalt üks välisandmete/registry või deal workflow.

### 4. Controller ei peaks olema query layer

`MyWorkController` ja osa admin funktsioonidest ehitavad jOOQ päringuid otse controlleris. Controller peaks tegelema HTTP lepingu, valideerimise ja response mapping'uga; päringuloogika kuulub repository/query service'i ning use-case orchestration application service'i.

### 5. jOOQ väärtus tuleb välja code generation'iga

Dünaamiline `table(name(...))` ja `field(name(...))` on toimiv, kuid schema typo või rename selgub alles runtime'is. Kuna Flyway on juba olemas, tasub kasutada sama skeemi jOOQ classide genereerimiseks.

See annab:

- compile-time vead schema muutuste korral;
- tugevamad Java tüübid;
- lihtsama refaktori;
- vähem stringipõhist SQL metadata't.

### 6. Väldi järgmiste God Service'ite tekkimist

Refaktor peaks järgima ärikasutusi, mitte lihtsalt faili pikkust. Näiteks deal domeenis võivad eraldi vastutused olla case creation, transition, offer/negotiation ja closing; registry poolel orchestration, provider adapter ja freshness tracking.

Ära lisa abstraktsiooni ainult kihtide arvu pärast — uus komponent peab omama selget vastutust.

### 7. Frontend contract peab tulema backendist

Spring OpenAPI on juba tehnoloogiapakis olemas. Käsitsi dubleeritud TypeScript DTO-d muudavad API muutuse kahe repoosa käsitsi sünkroniseerimise probleemiks.

Soovitus:

- genereeri API schema/types CI või development scriptiga;
- hoia domain-specific frontend mapping eraldi generated clientist;
- ehitus peab katki minema, kui backend contract muutub ja frontend pole sellega kooskõlas.

## Definition of Done selle backlog'i jaoks

Arhitektuuri hardening võib lugeda esimeses faasis lõpetatuks, kui:

- CALLER ei pääse assignment-piirist välja;
- kriitilised authorization testid jooksevad CI-s;
- My Work kuvab korrektsed parcel count'id ja pagination'i;
- concurrent owner write ei põhjusta vaikset lost update'i;
- backend coverage gate on sisuline, mitte 5%/1%;
- kriitiline Playwright suite jookseb CI-s;
- SQL pole owner/admin controllerites;
- jOOQ põhiskeem kasutab generated schema klasse;
- suured backend teenused ja frontend API kiht on jaotatud hallatavateks vastutusteks;
- production scheduling, Keycloak ja validation strateegiad on selgelt dokumenteeritud ja testitud.

## Seos teiste dokumentidega

See fail on tehnilise parendusbacklog'i **koondindeks**, mitte olemasolevate detailauditite asendus. Täpsem operatsiooniline, integratsiooni- ja ärivoogude analüüs jääb `docs/` vastavatesse audit/roadmap dokumentidesse.

Parenduste tegemisel uuenda nii vastavat GitHub issue't kui ka detaildokumenti, kui arhitektuuriline otsus või süsteemi tegelik seis muutub.
