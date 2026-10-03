# Tehingujuhtumi töövoog ForestIQ-s

## Eesmärk

Tehingujuhtum seob ühe kliendi, konkreetsed hinnatavad kinnistud ja ühe reaalse müügiotsuse. See väldib olukorda, kus kliendi huvi, hindaja analüüs ja hilisem pakkumine elavad eri märkmetes või inimeste mälus.

## Üks canonical tee hindamisse

ForestIQ toetab ainult üht teed kliendi huvist hindamiseni:

`INTERESTED` → **Kvalifitseeri huvi** → `deal_case` + valitud kinnistud → `EVALUATION_REQUESTED` → hindaja claim/määramine → `EVALUATING` → hindamisotsus → `VALUED`.

Vana `SalesOutcome.SEND_TO_EVALUATION` on eemaldatud. Samuti ei ole enam avalikku `/owners/evaluation-queue` järjekorda; hindamise canonical queue on `/deals/evaluation-queue`. Avalik owner status API ei luba `EVALUATION` ega `WAITS_FOR_EVALUATION` olekusse otse minna, sest selline üleminek jätaks owneri ilma deal’i, valitud kinnistute, kvalifitseerimisbriefi, SLA ja hindamisülesandeta.

`OwnerService` võib workflow-siseselt muuta owneri staatuse `EVALUATION`-iks ainult pärast seda, kui DealService on samas tehingus deal/evaluation konteksti loonud. Seega owneri CRM staatus on tehingutöövoo peegeldus, mitte eraldi hindamisjärjekorra allikas.

## Helistaja töövoog

Helistaja avab müügipuldil järgmise kontakti ning pärast sisukat kõnet valib **Kvalifitseeri huvi**. Süsteem kuvab kõnebriefi: kliendi viimase tegevuse, järgmise meeldetuletuse, kinnistud, pindalad ja kinnistuandmete viimase värskuse.

Helistaja valib müügiobjekti, hinnatavad kinnistud ning lisab ainult otsuse jaoks vajaliku teabe: otsustaja, soovitud müügiaeg, võimalik hinnasoov ja kõne kokkuvõte. Kui märgitud on „Saada kohe hindaja tööjärjekorda”, teeb ForestIQ ühe tehinguna järgmist.

| Automaatne tegevus | Tulemus |
| --- | --- |
| Luuakse tehingujuhtum | Juhtum seob kliendi, müügivastutaja, kinnistud ja kvalifitseerimisinfo. |
| Omanik liigub hindamise olekusse | Tavaline CRM-i ülevaade ja hindaja töölaud näitavad järjepidevat töövoogu. |
| Luuakse hindamisülesanne | Hindaja saab üleandmispaketi ja kolme tööpäeva SLA tähtaja. |
| Lisatakse muutmatu ajaloosündmus | Juhtumi tekkimine ning hindamise tellimine on hiljem tõendatavad. |

## Hindaja töövoog

Evaluaatori töölaud kuvab aktiivseid tehingujuhtumeid töö omandi järgi. Tavaline `EVALUATOR` näeb talle määratud ja veel määramata hindamisülesandeid. `CRM_MANAGER` ja `ORG_ADMIN` näevad kogu organisatsiooni hindamisjärjekorda ning võivad ülesandeid vajadusel ümber määrata.

Määramata ülesanne on etapis `EVALUATION_REQUESTED`. Hindaja valib **Võta töö endale**, mis seob `evaluator_subject` tema autentitud kasutaja subject-ID-ga ja viib juhtumi etappi `EVALUATING`. Hindaja saab enda ülesande hiljem vabastada; juht/admin võib ülesande määrata või vabastada organisatsiooni tasandil.

Hindamisotsuse saab esitada ainult ülesandele määratud hindaja. Määramata ülesande puhul tagastab API konflikti ja palub ülesande enne enda tööjärjekorda võtta. Teise hindaja ülesande muutmine on tavakasutajale keelatud. `CRM_MANAGER` ja `ORG_ADMIN` võivad teha volitatud override'i.

Hindaja saab esitada struktureeritud otsuse. Otsus sisaldab väärtuse alampiiri ja ülempiiri, soovituslikku ostuhinda, sisemist minimaalhinda, kliendile pakutavat hinda, pakkumise võimalikku kehtivust, eeldusi ja riske. Sisemine minimaalhind on tööandmeväli; see ei ole kliendile mõeldud pakkumine.

Kui hindajal on vaja täiendavaid andmeid, valib ta **Küsi müügilt täiendust** ja kirjeldab puuduva info. Juhtum liigub tagasi kvalifitseeritud etappi ning müügivastutaja tööjärjekorda lisatakse automaatne järeltegevus. Esitatud hindamisotsuse korral liigub juhtum staatusele `VALUED`, müüjale tekib kontaktimise ülesanne ja ajalukku lisandub otsuse versioon.

### Hindamise SLA ja tööpäevad

Hindamisülesande tähtaeg arvutatakse kolme **tööpäeva**, mitte kolme kalendripäeva võrra. Arvutus toimub `BusinessCalendar` teenuses ajavööndiga `Europe/Tallinn`. Laupäev ja pühapäev ei lähe tööpäevadena arvesse; näiteks reedel loodud ülesande kolmas tööpäev on kolmapäev.

Praegune poliitika ei välista veel Eesti riigipühi. See piirang on teadlikult dokumenteeritud ning pühade kalender tuleb tulevikus lisada samasse `BusinessCalendar` teenusesse, et kogu rakendus kasutaks ühtset SLA-loogikat.

## Andmekaitse ja piirid

Kõik tehingujuhtumi, hinnastamisotsuse ning kinnistute päringud on organisatsioonipõhised. Helistaja saab algatada ainult talle määratud kliendi tehingujuhtumi. Hindamisülesande omand on seotud autentitud subject-ID-ga, mitte kasutajanime kuvatekstiga. Hindaja otsus ei asenda õiguslikku kontrolli ega tehinguhinna lõplikku heakskiitu. Lepingute sidumine tehingujuhtumiga on järgmine arenduslaiendus.

## Väärtustatud juhtum ja läbirääkimine

Esitatud hindamisotsus muudab omaniku CRM-i staatuseks `VALUED` ning jätab tehingujuhtumi etappi `VALUED`. Müügivastutajale tekib järgmiseks tööpäevaks automaatne tegevus „Võta kliendiga ühendust ja esita pakkumine”. Kliendikaardil kuvatakse hindamisotsuse versioon, soovituslik ostuhind ning sisemised riskimärkused.

Müügivastutaja saab pärast hindamisotsuse läbivaatamist vajutada **Alusta läbirääkimist**. Ainult tehingu müügivastutaja võib seda teha. Süsteem muudab tehingujuhtumi etappi `NEGOTIATION`, omaniku staatuseks `NEGOTIATION`, lisab ajalukku sündmuse ja seab järgmise sammu pakkumise esitamiseks ning kokkulepitud edasise kontakti jaoks.

| CRM-i staatus | Tehingujuhtumi etapp | Tähendus |
| --- | --- | --- |
| `INTERESTED` | `QUALIFIED` | Helistaja on kliendi huvi kvalifitseerinud, kuid hindamisotsus puudub. |
| `EVALUATION` | `EVALUATION_REQUESTED` | Hindamisülesanne ootab hindajat või määramist. |
| `EVALUATION` | `EVALUATING` | Hindamisülesanne kuulub konkreetsele hindajale. |
| `VALUED` | `VALUED` | Hindamisotsus on olemas ning müügivastutaja peab pakkumisega ühendust võtma. |
| `NEGOTIATION` | `NEGOTIATION` | Klient ja müügivastutaja arutavad pakkumist või vastupakkumist. |

## Samaaegsed muudatused ja HTTP 409

Deal state transitionid kasutavad expected-state update'i: andmebaasi muudatus õnnestub ainult siis, kui `deal_case.stage` on endiselt sama, mida request enne üleminekut luges. Kui teine kasutaja või protsess jõuab sama juhtumit vahepeal muuta, ei kirjutata uuemat state'i üle.

Evaluation revisioni number leitakse parent `deal_case` rea lukustamise järel. `deal_evaluation` tabeli `UNIQUE (deal_case_id, revision)` constraint on täiendav andmebaasitaseme kaitse revisionide kokkupõrke vastu.

Konflikti korral tagastab API **HTTP 409 Conflict** sõnumiga, et tehingujuhtumit muudeti samal ajal ning klient peab andmed värskendama ja tegevust kordama. Konfliktne transaction rollback'itakse, seega ei jää poolikut evaluation revisioni, reminderit ega audit event'i.
