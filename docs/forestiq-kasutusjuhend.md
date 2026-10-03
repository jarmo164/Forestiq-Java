# ForestIQ kasutusjuhend

**Dokumendi versioon:** 1.0  
**Rakenduse alus:** ForestIQ 2.0, `main` haru  
**Sihtrühm:** organisatsiooni administraatorid, CRM-juhid, müügihelistajad, hindajad ja vaatlejad  
**Autor:** Manus AI

## 1. Eesmärk ja kasutusulatus

ForestIQ on organisatsioonipõhine metsaomanike CRM ning georuumiline töölaud. Rakendus koondab omanike kontaktandmed, kinnistud, kaartide ja registrite andmed, müügitegevuse, hindamisjuhtumid, lepingud, pärimisjuhtumid ning andmeallikate sünkroonimise ühte tööruumi. Iga sisselogitud kasutaja töötab ainult aktiivse organisatsiooni andmetega; organisatsiooni ei valita vormiväljal, vaid see tuletatakse kasutaja sisselogimisteabest.[1]

> **Oluline kasutuspõhimõte:** ForestIQ on tööotsuste ja tööjärjekorra süsteem. Registri- ja pärimisandmed toetavad tööd, kuid ei asenda omandiõiguse, pärimisõiguse ega lepingu õiguslikku kontrolli pädevast allikast.[2]

Rakendus eeldab toetatud kaasaegset veebibrauserit ja HTTPS-ühendust. Sisselogimine toimub organisatsiooni identiteediteenuse kaudu. Kui kasutajal puudub õige organisatsiooni väide või roll, ei näe ta CRM-i andmeid; administraator peab sellisel juhul kontrollima kasutaja seadistust.[1]

## 2. Rollid ja õigused

ForestIQ kasutab viis põhirolli. Roll määrab, milliseid andmeid kasutaja näeb ja milliseid tegevusi ta teha saab. Kõik õigused on alati piiratud aktiivse organisatsiooniga.[3]

| Roll | Tüüpiline kasutaja | Peamised õigused | Oluline piirang |
| --- | --- | --- | --- |
| `ORG_ADMIN` | Organisatsiooni administraator | Kasutajate, rollide, ettevõtteprofiilide, lepingumallide ja integratsioonide haldus; kogu CRM-i ülevaade | Vastutab kasutajate ja andmeallikate seadistuse eest. |
| `CRM_MANAGER` | Müügijuht või büroojuht | Kõigi organisatsiooni omanike, määrangute, müügitöö ja töölaudade haldamine | Ei tohi anda ligipääsu teise organisatsiooni andmetele. |
| `CALLER` | Müügihelistaja | Ainult endale määratud omanike otsimine, vaatamine, muutmine ja müügitöö | Ei saa muuta määranguid ega näha määramata või teistele määratud omanike andmeid. |
| `EVALUATOR` | Hindaja | Hindamisjärjekord, hindamisotsused, kinnistute ja kaardi töövood | Ei halda üldist CRM-i ega kasutajamääranguid. |
| `VIEWER` | Vaatleja või analüütik | Organisatsiooni andmete lugemine | Ei saa muuta CRM-i, lepinguid ega töövooge. |

Kui kasutajal on korraga `CALLER` ja mõni laiem lugemisroll, jääb omaniku- ja kinnistuandmete puhul kehtima helistaja määrangupiir. Ainult `ORG_ADMIN` ja `CRM_MANAGER` saavad töötada kogu organisatsiooni ulatuses.[3]

## 3. Esimene sisselogimine

Pärast kasutajakonto loomist saadab administraator kasutajale sisselogimisjuhise ja ajutise salasõna. Kasutaja avab sisselogimislehe, tuvastab end identiteediteenuses ning jõuab tagasi ForestIQ töölauale. Esimese tööpäeva alguses tuleb kontrollida, et ekraani navigatsioonis on näha kasutaja tööks vajalikud vaated ning omanikke, tehinguid ja kaarti avades kuvatakse ainult oma organisatsiooni andmed.

| Kontroll | Oodatav tulemus | Kui tulemus erineb |
| --- | --- | --- |
| Kasutaja saab sisse logida | Avaneb ForestIQ avaleht või töölaud. | Kontrollida kasutajanime, ajutist salasõna ja identiteediteenuse kutset. |
| Õige menüü on nähtav | Rollile vastavad vaated on avatud. | Paluda administraatoril kontrollida rolle. |
| Omaniku avamine töötab | Avaneb omaniku detail või näidatakse õiguspärast „puudub ligipääs” vastust. | Kontrollida kasutaja organisatsiooni väidet ja määrangut. |
| Helistaja näeb oma järjekorda | Müügipult näitab ainult talle määratud tööd. | Kontrollida omaniku `assigned_to_subject` määrangut. |

## 4. Igapäevane töö müügihelistajana

Müügihelistaja alustab tööpäeva lehel **Müük**. Töölaud paigutab ettepoole tähtaja ületanud järeltegevused, täna tähtaeguvad kontaktid, esimese kontaktita omanikud ja pikalt seisnud aktiivsed võimalused. Järjekord sisaldab ainult sisselogitud kasutajale määratud omanikke.[4]

Avage järgmine kontakt ning vaadake enne helistamist kontaktandmeid, viimaseid märkmeid, kinnistuid ja eelmise tegevuse tulemust. Pärast kõnet valige võimalikult täpne tulemus. Nupud **Ei vastanud** ja **Helista hiljem** lisavad tegevuslogi ning loovad uue meeldetuletuse. Tulemus **Huvitatud** muudab vajadusel staatust ja valmistab ette järgmise sammu; **Ei huvita** lõpetab aktiivse müügisuuna. Süsteem ei vii juba edasijõudnud võimalust hilise järelkontakti tõttu tagasi varasemasse olekusse.[4]

> **Hea tava:** sisestage kõne tulemus kohe pärast kontakti. See tagab, et järgmisel kasutajal või järgmisel tööpäeval on nähtav üheselt mõistetav tegevusajalugu ja järgmine tähtaeg.

## 5. Omanike CRM

Leht **Omanikud** on peamine koht kontaktide otsimiseks, profiili avamiseks ja omaniku tööajaloo haldamiseks. Otsingus võib kasutada nime, telefoni, e-posti või isiku-/registrikoodi. Omaniku detailvaade koondab kontaktid, oleku, kinnistud, märkmed, jälgimised, tegevused ning sellega seotud töövood.[1]

| Tegevus | Millal kasutada | Tulemus |
| --- | --- | --- |
| Otsi omanikku | Enne uue kontakti loomist või helistamist | Vähendab topeltkirjete riski ja avab olemasoleva ajaloo. |
| Muuda kontaktandmeid | Kui omanik kinnitab uue telefoni, e-posti või aadressi | Profiil ajakohastub organisatsiooni CRM-is. |
| Lisa märkus / tegevus | Iga sisuka kõne, kohtumise või otsuse järel | Ajajoonel säilib tegevuse sisu ja autor. |
| Muuda olekut | Kui omaniku müügiseis muutub | Tööjärjekorrad ja analüütika kasutavad uut seisundit. |
| Määra vastutaja | Kui töö tuleb üle anda teisele müüjale | Uus vastutaja näeb omaniku tööd oma järjekorras. |
| Ava kinnistud või kaart | Kui kontakt vajab ruumi- või registriandmete kontrolli | Näidatakse omanikuga seotud aktiivseid kinnistuid. |

Helistaja saab muuta ainult talle määratud omaniku andmeid. Määrangu muutmine on administraatori või CRM-juhi tegevus.[3]

## 6. Omanike CSV- ja Excel-import

`ORG_ADMIN` ja `CRM_MANAGER` saavad omanike vaates importida CSV- või XLSX-faili. Import on teadlikult kolmeastmeline: **inspect**, **preview** ja **commit**. Andmeid ei kirjutata andmebaasi enne, kui kasutaja on veeruseose, vigade ning tulemuse üle kontrollinud.[5]

1. Valige fail ja käivitage **Inspect**. Rakendus loeb päised ning väikese näidise ja pakub veergude seost.
2. Seostage ainult toetatud väljad: nimi, isiku- või registrikood, omaniku tüüp, e-post, telefon, aadress ja lisainfo.
3. Käivitage **Preview**. Kontrollige `READY`, `DUPLICATE` ja `INVALID` ridade arvu ning laadige vajadusel veaaruanne alla.
4. Kui tulemus on õige, kinnitage **Commit** sama failiga. Server kontrollib faili uuesti ning lisab ainult valmis read.
5. Avage impordiajalugu ja talletage vigaste või topeltridade CSV-aruanne järeltegevuseks.

| Piirang | Väärtus |
| --- | --- |
| Toetatud failid | CSV ja XLSX |
| Maksimaalne faili suurus | 10 MB |
| Maksimaalne mitte-tühi andmerida | 10 000 |
| Maksimaalne veergude arv | 100 |
| Organisatsiooni määramine | Ainult sisselogitud kasutaja organisatsioonist; fail ei saa seda muuta |

Valesti kaardistatud, kuid juba kinnitatud importi ei tohi automaatselt kustutada. Enne parandust tuleb veenduda, et imporditud omanikega ei ole tekkinud kinnistuid, tehinguid, lepinguid, meeldetuletusi ega muid järgnevaid tööandmeid.[5]

## 7. Kinnistud, kaart ja registriandmed

Kaardivaade kasutab kinnistute PostGIS geomeetriat ja kuvab organisatsiooniandmeid MapLibre'i kaardil. Omaniku detailist või kaardilt saab avada kinnistu kokkuvõtte, katastriandmed ning metsaregistri teabe. Helistaja näeb ainult talle määratud omanike kinnistuid; ka vektorkiht rakendab sama piirangut.[3]

Kinnistu andmete juures kasutatakse vajaduse korral käsitsi värskendamist. **Maa-amet** värskendab katastri piiri, aadressi ja põhiandmeid. Metsaregistri toimingud toovad metsateatiste ning metsaeraldiste/projektsioonide andmeid. Värskenduse tulemus või tõrge jääb sünkroonlogisse, mis võimaldab administraatoril hiljem kontrollida andmete päritolu.[1]

Andmete värskus ei ole automaatselt garantii, et välisallikas on õiguslikult lõplik. Enne olulist tehinguotsust tuleb kontrollida ajatemplit, allikat ja organisatsiooni töökorralduse kohast ametlikku registrit.

## 8. Tehingujuhtum ja hindamine

Tehingujuhtum seob ühe omaniku, valitud kinnistud ja müügiotsuse. Hindamisse ei liigutata omanikku lihtsalt olekumuutusega; alati luuakse tehingujuhtum koos kinnistute, kvalifitseerimisinfo ja hindamisülesandega.[6]

### 8.1 Helistaja või müüja tegevus

Avage omaniku või müügitöö lauast **Kvalifitseeri huvi**. Valige müügiobjekt, hinnatavad kinnistud, otsustaja, soovitud müügiaeg, võimalik hinnasoov ja kõne kokkuvõte. Kui soovite hindamist, märkige juhtum hindamisjärjekorda. Süsteem loob tehingujuhtumi, muudab omaniku hindamise olekusse, lisab ajaloo ning määrab hindamisülesande tähtaja.[6]

Pärast hindaja otsust avaneb müügivastutajale väärtustatud juhtum. Vaadake läbi soovituslik ostuhind, pakkumise hind, eeldused ja riskid. Kui pakkumiseks on valmisolek, valige **Alusta läbirääkimist**. Seda võib teha ainult juhtumi müügivastutaja.[6]

### 8.2 Hindaja tegevus

Hindaja kasutab lehte **Hindamistöölaud**. Määramata ülesanne on olekus `EVALUATION_REQUESTED`; vajutage **Võta töö endale**, et võtta ülesanne töötlusse. See viib juhtumi olekusse `EVALUATING` ning seob selle teie kasutajaga.[6]

Täitke hinnangu alampiir ja ülempiir, soovituslik ostuhind, sisemine minimaalhind, kliendile pakutav hind, pakkumise kehtivus, eeldused ning riskid. Kui infot jääb puudu, tagastage töö müügile koos põhjendusega. Esitatud otsus viib juhtumi olekusse `VALUED` ja loob müügivastutajale järeltegevuse.[6]

| Juhtumi etapp | Tähendus | Peamine vastutaja |
| --- | --- | --- |
| `QUALIFIED` | Omaniku huvi on kogutud; hindamisotsus puudub | Müügivastutaja |
| `EVALUATION_REQUESTED` | Hindaja töö ootab võtmist või määramist | Hindaja / juht |
| `EVALUATING` | Hindamist teeb konkreetne hindaja | Määratud hindaja |
| `VALUED` | Hindamisotsus on esitatud | Müügivastutaja |
| `NEGOTIATION` | Pakkumise või vastupakkumise läbirääkimine | Müügivastutaja |

Kui süsteem teatab, et juhtumit muudeti samal ajal, värskendage vaade ja korrake tegevust pärast tegeliku etapi kontrollimist. ForestIQ kasutab samaaegsete üleminekute korral HTTP 409 konfliktikaitset, et vältida poolikuid hindamisotsuseid või topeltmuudatusi.[6]

## 9. Lepingud ja PDF-dokumendid

Lehel **Haldus → Ettevõtted ja lepingumallid** saavad administraatorid hallata ettevõtteprofiile, vaikimisi ostjat ja HTML-põhiseid lepingumalle. Mallis kasutatakse rakenduse pakutud asendusvälju, näiteks müüja, ettevõtte ja kinnistute ridade teavet.[1]

Lehel **Lepingud** valige ettevõte ja mall, sisestage nõutud müüja-, kinnistu- ja hinnainfo ning looge PDF. Dokument salvestatakse turvaliselt S3-ühilduvasse objektisalvestusse ning lepingute ajalugu jääb andmebaasi. Enne PDF-i lõplikku kasutamist peab vastutaja kontrollima ettevõtte andmeid, lepinguteksti, osapooli ning hinnastamist.[1]

## 10. Meeldetuletused, sõnumid ja telefoniraamat

**Meeldetuletused** aitavad hoida isiklikke järeltegevusi tähtajal. Lisage meeldetuletusele lühike tegevuskirjeldus, tähtaeg ja vajadusel seos omanikuga. Tähtaja ületanud ning samal päeval tähtaeguvad tegevused kuvatakse töölaudadel kõrgema prioriteediga.[4]

**Sõnumid** toetavad organisatsioonisisest tööalast suhtlust. Saaja tuleb valida organisatsiooni aktiivsete kasutajate hulgast; sõnumisse ei tohi lisada rohkem isikuandmeid, kui tööülesande lahendamiseks vaja. **Telefoniraamat** on kiire kontaktivaade, mida kasutatakse kolleegide ja tööga seotud kontaktide leidmiseks.

## 11. Pärimisregister

Pärimisjuhtum on tööjärjekorra ja kontaktitöö töövahend, mitte pärimisõiguse tõend. Lehel **Pärimisregister** saab filtreerida juhtumeid staatuse, tähtaja ja määratud vastutaja järgi, avada juhtumi ajajoont ning hallata pärijate tööandmeid.[2]

| Etapp | Kasutaja tegevus |
| --- | --- |
| Ametliku teate kontroll | Kontrollige olemasoleva omaniku kohta ametlikku teadet. Vaste puudumisel andmeid ei muudeta. |
| Juhtumi avamine | Teate vaste korral tekib omanikuga seotud pärimisjuhtum ja omanik võib liikuda `DECEASED` olekusse. |
| Pärijate täiendamine | Lisage nimi, seos, osa, kontakt ja kontaktistaatus ainult tööks vajaliku ulatuseni. |
| Kontaktitöö | Lisage märkmeid, muutke juurdluse/seose olekut ja määrake vastutaja. |
| Lõpetamine | Märkige `SETTLED`, `CLOSED` või `NOT_RELEVANT`, kui töö on põhjendatult lõppenud. |

Automaatne pärimisteadete kontroll on vaikimisi välja lülitatud. Selle lubamine on administraatori ja andmekaitse eest vastutava isiku otsus, mis eeldab allika kasutustingimuste ning säilitamisreeglite kontrolli.[2]

## 12. Administraatori töö: kasutajad, andmeallikad ja töökindlus

Administraator avab **Haldus**-vaate, et hallata kasutajaid ja rolle. Kasutajale tuleb määrata minimaalne tööks vajalik roll ning õiged organisatsiooniandmed. Enne uue töötaja täismahus kasutuselevõttu tehke alati sisselogimis- ja õiguste suitsutest.

Lehel **Haldus → Andmeallikad ja sünkroonimine** näeb `ORG_ADMIN` registreeritud integratsioone, nende seadistuse olekut, viimast käiku, mõõdikuid ning tõrgete kokkuvõtet. Nupp **Käivita** alustab asünkroonse käsijooksu; sama organisatsiooni sama töö kohta hoiab süsteem ära paralleelse `QUEUED` või `RUNNING` käigu.[7]

| Jooksuolek | Tähendus | Administraatori tegevus |
| --- | --- | --- |
| `QUEUED` | Töö on järjekorras | Oodake käivitust; ärge looge uut identset jooksu. |
| `RUNNING` | Töö töötleb andmeid | Jälgige kestust ja mõõdikuid. |
| `SUCCEEDED` | Töö lõpetas edukalt | Kontrollige vajadusel ajatempleid ja tulemusarve. |
| `PARTIAL` | Osa andmeid jäi töötlemata, töö on kontrollitult jätkatav | Uurige veateadet ning käivitage uuesti pärast põhjuse kõrvaldamist. |
| `FAILED` | Töö ebaõnnestus | Kontrollige seadistust, välisallikat ja logi; ärge eeldage, et andmed värskendusid. |
| `SKIPPED` | Töö jäeti teadlikult käivitamata | Kontrollige, kas töö on lubatud ja seadistatud. |

## 13. Turvaline ja korrektne kasutamine

ForestIQ sisaldab isikuandmeid, kinnistuandmeid, ärilisi hinnanguid ja võimalikke pärimisandmeid. Kasutaja peab töötama ainult oma konto alt, lukustama arvuti töölt lahkudes ning hoiduma tundliku info kopeerimisest volitamata kanalitesse. Dokumentide, hinnangute ja pärijate kontaktandmete allalaadimisel tuleb järgida organisatsiooni säilitus- ja andmekaitsekorda.

Kui märkate vale määrangut, teise organisatsiooni andmeid, ootamatut juurdepääsu või ekslikku importi, lõpetage mõjutatud toiming ning teavitage kohe organisatsiooni administraatorit. Ärge parandage tõrget massilise kustutamisega ega muutke ajaloolisi sündmusi; kasutage korrektseid töövooge ja säilitage auditi jälg.[3] [5]

## 14. Kiirabi ja tugi

| Olukord | Esmane tegevus |
| --- | --- |
| Kasutaja ei saa sisse logida | Kontrollida kasutajakontot, ajutist salasõna, rolli ja organisatsiooni väidet. |
| Omanik ei ole helistaja järjekorras | Kontrollida, kas omanik on sellele kasutajale määratud ning kas olek on aktiivne. |
| Hindaja ei saa otsust esitada | Kontrollida, kas ülesanne on enne endale võetud või talle määratud. |
| Import annab vigu | Laadida preview/commit veaaruanne, parandada lähtefail ja korrata importi algusest. |
| Integratsioon on `MISCONFIGURED` või `FAILED` | Kontrollida administraatorina seadistust, teenusekontot, allika ühendust ja jooksulogi. |
| Leping või PDF vajab parandust | Parandada ettevõtteprofiil või mall ning luua uus kontrollitud dokument; säilitada vana ajalugu. |

## Viited

[1]: ../README.md "ForestIQ 2.0 ülevaade, põhifunktsioonid ja lepingute töövoog"
[2]: ./inheritance-workflow.md "Pärimisjuhtumi töövoog ForestIQ-s"
[3]: ./access-control-matrix.md "ForestIQ õiguste maatriks"
[4]: ./salesperson-workspace-audit.md "Müügimehe isiklik müügikeskkond"
[5]: ./owner-imports.md "Owner onboarding imports"
[6]: ./deal-case-workflow.md "Tehingujuhtumi töövoog ForestIQ-s"
[7]: ./integration-operations.md "Integratsioonide käitamine"
