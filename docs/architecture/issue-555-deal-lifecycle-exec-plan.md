# #555 — Deali lifecycle-käskude ja töövaate piiri teostusplaan

**Staatus:** Teostatud ja valideeritud
**Issue:** [#555](https://github.com/jarmo164/ForestIQ/issues/555)
**Haru:** `issue-555-deal-lifecycle` (base: `issue-554-owner-collaboration`)

## Eesmärk

Deali mutatsioonid peavad läbima ühe rakenduskihi `DealLifecycleService` käsupinna. `DealWorkService` jääb aktiivsete deali tööde, health-reeglite ja loss-raporti lugemisprojektsiooniks. Mõlemad avalikud kaotuse endpointid kasutavad sama kanonilist käsku ning contract saab dealilt ainult autoriseeritud immutable WON-deali source-snapshot’i.

## Disain

`DealLifecycleService` koondab olemasolevate deali-, kommerts- ja töövaateteenuste käsud ühte typed application boundary’sse, säilitades controllerite URL-id, request- ja response-DTO-d. Deali loomine, hindamise määramine ja esitamine ning pakkumise käsud delegeerivad olemasolevatele domeeniteenustele. Järgmise tegevuse, loss outcome’i, follow-up reminderi, reminder-eventi ja loss-reason kataloogi käsud liiguvad `DealWorkService`-ist lifecycle-teenusesse.

Kanoniline loss-käsk valideerib tenantipõhise aktiivse põhjuse ja vajadusel tagasilükatud pakkumise, kirjutab `deal_loss_outcome`, `DEAL_LOST` sündmuse, `deal_case` terminalse seisundi ning optional follow-up reminderi ja selle CREATED-sündmuse samas transaktsioonis. Owneri süsteemne activity liigub `OwnerCollaborationPort`i kaudu samasse käsujadasse. Kommertsendpoint säilitab oma vastuse, kuid teisendab vana requesti kanonilise käsu sisendiks; töövaate endpoint tagastab edasi `LossOutcome`i.

Deal-moodulile lisatakse `WonDealContractSourcePort`. Selle adapter on ainus komponent, mis tunneb WON-deali, pakkumissündmuste JSON-kuju, omaniku ning kinnistute jOOQ projektsiooni. Adapter kasutab `OrganizationContext`i ja `OwnerCollaborationPort.requireReadable` kontrolli ning tagastab defensive immutable recordid. Contracti olemasolev source-teenus muutub porti kasutavaks mapperiks, nii et contract ei sõltu enam deal’i tabelitest ega eventide skeemist.

## Teostusetapid

- [x] Kaardistada deali lifecycle’i, töövaate ja contracti source’i praegused vastutused.
- [x] Kavandada typed lifecycle- ja immutable WON-source piirid.
- [x] Rakendada lifecycle teenus, read-only töövaade ja contracti source port.
- [x] Lisada testid, valideerida, commitida, pushida ja sulgeda #555.

## Säilitatavad invariantid

| Invariant | Säilitamise viis |
|---|---|
| Avalik API | Olemasolevad controlleri URL-id ning request/response DTO-d jäävad kehtima; controllerid muutuvad õhukesteks adapteriteks. |
| Organisatsiooni- ja owner-scope | Kõik käsud kasutavad `OrganizationContext`i ja `OwnerCollaborationPort`i; contracti source kontrollib `requireReadable` enne tundlike omaniku- või kinnistuandmete tagastamist. |
| Lossi atomaarus | `deal_case`, loss outcome, deal-event, follow-up reminder ja reminder-event ning owneri activity läbivad ühe transaktsiooni. |
| Terminalne immutability | WON, LOST ja CANCELLED tehingu järgmine tegevus või loss ei muutu. |
| Töövaate jõudlus | `DealWorkService` säilitab ühe scoped SQL-lugemisprojektsiooni ja ei kirjuta enam deali või reminderite tabeleid. |
| Contracti snapshot | Pordi recordid ning nende parcelite loend on defensive immutable; contract ei näe jOOQ `Record`it, JSONB-d ega eventitüübi stringe. |

## Kontrollid

Teostus loetakse lõpetatuks, kui `DealWorkService` ei sisalda enam mutatsioonikäske, mõlemad loss-endpointid kasutavad sama lifecycle-käsku, contract kasutab ainult `WonDealContractSourcePort`i ning unit- ja PostGIS integratsioonitestid läbivad.

| Kontroll | Tulemus |
|---|---|
| Lifecycle, contract-source, ArchUnit ja controlleri turbetestid | Läbisid. |
| Täielik backendi unit-testide kogum | `./gradlew test --no-daemon` läbis. |
| Täielik PostGIS integratsioonitestide kogum | `./gradlew integrationTest --no-daemon` läbis pärast lõplikke muudatusi. |
