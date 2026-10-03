# MetsIS omaniku–katastri portfelli sünkroniseerimine

## Eesmärk

ForestIQ kasutab MetsIS/forestek vahendus-API-t selleks, et töö algaks **juba rikastatud omaniku–katastri portfellist**, mitte helistaja käsitsi sisestusest. MetsIS/forestek jääb omaniku ning aktiivsete omandisuhete kanoniliseks välisallikaks. ForestIQ hoiab ainult tööks vajaliku projektsiooni, aktiivse seose staatust ja andmetest algavaid töösignaale.

> MetsIS portfell on töövaliku lähteandmestik, mitte juriidilise omandiõiguse lõplik tõend. Lepingu- ja hoolsuskontrolli etapis tuleb omandiõigus kontrollida pädevast allikast.

## Andmevoog

| Etapp | Käitumine |
| --- | --- |
| 1. Portfelli väljavõte | ForestIQ loeb serverist MetsIS API omaniku, omaniku tüübi ja aktiivsete katastriüksuste loendi lehekülgede kaupa. |
| 2. Minimaalne projektsioon | Puuduv omanik luuakse organisatsiooni CRM-i ilma kontakti- või müügiandmeid oletamata. Puuduv katastriüksus luuakse tehnilise portfelliobjektina ning seda rikastavad hiljem Maa-ameti ja metsaregistri adapterid. |
| 3. Kaasomand | Tabel `owner_parcel_relation` seob ühe kinnistu mitme omanikuga. Kinnistu geomeetriat või metsanduse andmeid ei dubleerita kaasomaniku kohta. |
| 4. Muutusetuvastus | Esmakordselt nähtud aktiivne omanik–kinnistu seos loob `PORTFOLIO_OWNERSHIP_ADDED` töösignaali. Seos märgitakse suletuks ainult pärast kogu lehekülgedeülese snapshoti edukat kontrolli ja lavastamist. |
| 5. Juhi töövalik | Haldur valib filtriga **MetsIS uus omandisuhe** määramata kontaktid, lisab vajadusel metsaala ja teatise filtrid ning määrab töö helistajale. |
| 6. Helistaja töö | Helistaja saab oma müügipuldile valmis tööjärjekorra ning ei pea ise omaniku–katastri seost eri registritest kokku panema. |

## Kanonilised allikad ja andmete minimeerimine

| Andmekiht | Tõeallikas | ForestIQ-s säilitatav operatsiooniline osa |
| --- | --- | --- |
| Omaniku–katastri aktiivne seos | MetsIS/forestek | Aktiivne või suletud seoseprojektis, allika viide ja viimase nägemise aeg. |
| Kinnistu geomeetria, pindala ja aadress | Maa-amet / Maa- ja Ruumiamet | ForestIQ tööprojektsioon koos sünkroniseerimise ajalooga. |
| Metsateatised ja metsaeraldised | Metsaregistri ametlik WFS | ForestIQ tööprojektsioon ja andmesignaalid. |
| Helistaja töö, staatus ja märkmed | ForestIQ | ForestIQ on kanoniline töövooallikas. |

ForestIQ ei salvesta MetsIS API täielikke vastusedokumente ega päringute toorlogi. Välisest vastusest kasutatakse töötlemise ajal ainult omaniku koodi, nime, tüüpi ja aktiivsete katastritunnuste loendit; püsivalt säilib normaliseeritud seos ning sünkroniseerimise kontrollpunkt.

## Turvaline seadistamine

```bash
METSIS_PORTFOLIO_URI=https://<metsis-or-forestek-host>/api/owners-test
METSIS_PORTFOLIO_AUTHORIZATION=<upstream-authorization-value>
METSIS_PORTFOLIO_ORGANIZATION_ID=<forestiq-organization-uuid>
METSIS_PORTFOLIO_AUTO_SYNC_ENABLED=true
METSIS_PORTFOLIO_AUTO_SYNC_DELAY_MS=86400000
METSIS_PORTFOLIO_PAGE_SIZE=1000
METSIS_PORTFOLIO_MAX_PAGES_PER_RUN=5000
```

Autorisatsioon ning URL kuuluvad ainult serveripoole saladuste haldurisse. `METSIS_PORTFOLIO_ORGANIZATION_ID` piirab sünkroniseerimise ühe ForestIQ organisatsiooniga. Automaatne sünkroniseerimine on teadlikult vaikimisi väljas ning see tuleb lubada alles pärast esmakordset käsitsi sünkroniseerimist, tulemuste kontrolli ning välisallika kasutusõiguse kinnitamist.

Legacy Forestek `/api/owners-test` endpoint määrab lehekülje suuruse ise ega arvesta ForestIQ `METSIS_PORTFOLIO_PAGE_SIZE` väärtust. Seetõttu on `METSIS_PORTFOLIO_MAX_PAGES_PER_RUN` ainult katkise või lõputu pageri turvapiir, mitte tavapärase snapshoti mahu piiramiseks mõeldud batch-size. Vaikimisi 5000 lehekülge jätab suurele tootmis-snapshotile piisava varu, kuid täielikkuse kontroll jääb alles: ForestIQ rakendab seosemuudatused alles pärast seda, kui provider tagastab snapshoti lõppu tähistava tühja lehe.

## Operatsioonireeglid

Juhi tööpäev algab lehelt **Haldus → Andmeallikad ja sünkroonimine**, kus MetsIS portfelli rida näitab viimast õnnestumist, töödeldud ridu, lisandunud aktiivseid seoseid, suletud seoseid ja viimast tõrget. Haldur võib käivitada käsitsi sünkroniseerimise; töötajate igapäevatöö ei vaja seda nuppu.

Sünkroniseerimine on idempotentne. Sama aktiivne seos ei tekita teist töösignaali ning suletud seos ei kustuta ForestIQ tegevus- ega müügiajalugu. Puudulik lehekülgede jada, ootamatu tühi vastus või allika tõrge jätab eelmise eduka seostekomplekti aktiivseks. Allika nimi ja omanikutüüp säilivad eraldi lähteväljades ega kirjuta üle ForestIQ kontaktandmeid, CRM-i nime, staatust ega töömäärangut.
