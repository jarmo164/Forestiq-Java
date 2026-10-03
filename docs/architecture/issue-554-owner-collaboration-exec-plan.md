# #554 — Owneri state-, assignment- ja timeline-koostööpiiri teostusplaan

**Staatus:** Lõpetatud ja valideeritud
**Issue:** [#554](https://github.com/jarmo164/ForestIQ/issues/554)
**Haru:** `issue-554-owner-collaboration` (base: `issue-553-identity-port`)

## Eesmärk

Owneri moodul on ainus moodul, mis muudab `owner` ja `owner_activity` püsiseisundit. Deal, workflow ja management kasutavad typed `OwnerCollaborationPort`-i, mitte owneri HTTP DTO-sid, `OwnerAuthorizationService`-it, JPA entity't või owneri persistence-adaptereid. Port säilitab olemasoleva organisatsioonipiiri, CALLER-i assignment-piiri, rollipõhise manager-bypass'i ning sama lokaalse transaktsiooni auditijälje.

## Lähteolukord ja ulatus

Moodulianalüüs tuvastas järgmised write-pathid. Deali `DealCommandRepository` ning `DealCommercialService` lisavad otse `owner_activity` SYSTEM kirjeid, samal ajal kui `DealService` ja `DealCommercialService` ehitavad `OwnerDtos.ChangeStatusRequest`-i ning kutsuvad otse `OwnerService`-it. Workflow lisab kontakte ja reminder lifecycle'i otse `owner_activity` tabelisse. Managementi `SegmentAssignmentService` lukustab ja muudab otse `owner.assigned_to_subject` välja ning kirjutab seejärel otse activity tabelisse. Kõik need rajad tuleb viia owneri mooduli käsu- ja access-piiri taha.

Fookuses ei ole selle tööga kõigi owneri SQL-põhiste **read model**-päringute ümberkirjutamine. Deal evaluation queue, segmentide analüütika ja workflow ajaloo lugemine säilitavad oma SQL-põhise read-path'i, kuid fookusmoodulitesse ei jää owneri/entity/HTTP-adapteri Java-sõltuvust ega ühtegi `owner` või `owner_activity` DML käsku.

## Disain

Lisada `ee.forestiq.owner.collaboration.OwnerCollaborationPort`, mille tüübid kuuluvad owneri moodulile. Port hõlmab nelja selget koostöövastutust: owneri read/mutate access-check, deali semantilised lifecycle transitionid, workflow struktureeritud timeline käsud ning managementi bulk assignment käsu. Iga käsk kasutab autentitud `OrganizationContext`-i ja aktorit owneri adapteris; välismoodul ei anna kaasa organisatsiooni ID-d ega vaba kujuga auth-konteksti.

Deal kasutab `requireReadable`, `requireMutable` ja `assertDealCreationAllowed` access-käske ning `DealLifecycleTransition` enumit. Sellega ei leki `OwnerStatus` ega `ChangeStatusRequest` deali API-sse. Owneri adapter kasutab olemasolevat owneri staatuse muutmise semantikat: tenantipõhine state-definition kontroll, DECEASED kaitse, optimistic lock, `STATUS_CHANGED` timeline, kasutajastatistika ja `OwnerStatusChangedEvent` jäävad samaks.

Workflow kasutab kahte typed timeline käsku. `WorkflowContactActivity` sisaldab vaid V44 kontaktiajaloo jaoks vajalikke välju ning tagastab püsiva activity UUID. `WorkflowReminderLifecycleActivity` kirjeldab reminderist tulenevat timeline-sündmust. Owneri repository kirjutab struktureeritud väljad ja töötab samas Springi transaktsioonis; see ei kasuta uut outboxi ega `REQUIRES_NEW` transaktsiooni.

Management annab owneri porti eelnevalt valideeritud subject-id ja põhjenduse. Owneri adapter teeb manager-autoriseerimise, tenant-scoped canonical locking'u, assignment update'i koos owneri versioni tõstmisega ja `ASSIGNED` activity kirjutusega. Management säilitab oma `management_assignment_run`, `management_assignment_item` ja segmendi sündmuste auditid ning tõlgib owneri typed tulemuse olemasolevasse HTTP vastusesse.

## Teostusetapid

- [x] Kaardistada deali, workflow ja managementi owneri write-pathid.
- [x] Lisada owneri typed collaboration-port, adapter ja activity persistence meetodid.
- [x] Refaktoreerida owneri state transitioni ühine sisemine teenus ning säilitada endpointi leping.
- [x] Migreerida deal, workflow ja management collaboration-porti.
- [x] Lisada ArchUnit guardrail ning unit- ja integratsioonitaseme käitumistestid.
- [x] Käivitada backendi kontrollid, vaadata diff üle, commitida, pushida ja sulgeda #554.

## Säilitatavad invariantid

| Invariant | Säilitamise viis |
|---|---|
| Organisatsiooni eraldatus | Port võtab organisatsiooni alati `OrganizationContext`-ist ja otsib owneri `(organization_id, id)` järgi. |
| CALLER-i assignment-piir | Port kasutab sama `OwnerAuthorizationService` poliitikat; EVALUATOR/VIEWER ei laienda CALLER-i õigust. |
| Deali atomicity | Port rakendub olemasolevas `@Transactional` kontekstis, et deal event/reminder ja owneri audit kas kõik commitivad või kõik rollbackivad. |
| Olekumuutuse kõrvalmõjud | Owneri state transition säilitab dynamic definition kontrolli, DECEASED kaitse, lock versioni, status activity, statistikakirje ja domain-eventi. |
| V44 workflow activity kuju | Kontaktile ja reminder lifecycle'ile jäävad `source_type`, channel/outcome/reason ning deal/reminder viited; kontakt tagastab activity UUID. |
| Management massmääramine | Kuni 1 000 ownerit, duplicate kontroll, aktiivse identity-kasutaja sobivus, muutmata assignmenti `UNCHANGED` tulemus ja managementi audit jäävad samaks. |
| Jõudlus ja lukud | Massmääramine lukustab kõik ownerid ühe tenant-scoped `SELECT … FOR UPDATE` päringuga deterministlikus primary-key järjekorras; tulemused tagastatakse algses requesti järjekorras. |

## Kontrollid

Teostus on lõpetatud: deal, workflow ja management ei kirjuta otse `owner` ega `owner_activity` tabelit ega impordi owneri HTTP DTO-sid; ArchUnit lubab ainult `owner.collaboration` API; ning `./gradlew test --no-daemon` ja `./gradlew integrationTest --no-daemon` läbisid 2026-09-24.
