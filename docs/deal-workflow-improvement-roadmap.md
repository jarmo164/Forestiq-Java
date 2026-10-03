# Kontaktist tehinguni töövoo täiustamise teekaart

## Järeldus

ForestIQ-l on olemas töövoo põhiosad: isiklik müügipult, kinnistuandmete kaart, registri värskendamine, hindamisjärjekord, meeldetuletused, lepingud ja juhi sekkumisvaade. Suurim järgmine võit ei tule veel ühest eraldi töölauast, vaid sellest, et need osad moodustavad ühe **juhtumi**: kindel kontakt, konkreetsed kinnistud, registreeritud huvi, hindamisülesanne, hinnastamisotsus, pakkumine ja läbirääkimiste ajalugu.

## Praegune ahel ja peamised lüngad

| Etapp | Olemasolev tugevus | Puuduv töövõime |
| --- | --- | --- |
| Kliendi valik ja registrikontroll | Müügipult prioriseerib kontakte; kaart ja kinnistu täisvärskendus on olemas. | Puudub enne kõnet koostatav ühele ekraanile koondatud kliendi- ja kinnistubrief; helistaja peab ise otsustama, milliseid registreid avada ja mis on kõne eesmärk. |
| Kõne tulemus ja huvi | Ühe-kliki kõnetulemused ning järelmeeldetuletused toimivad. | Huvi ei muutu struktureeritud müügivõimaluseks: puuduvad müügiese, hinnavahemik, müügivalmidus, tähtaeg, konkurentsiolukord ning kõne käigus saadud kvalifikatsioonivastus. |
| Hindajale üleandmine | Hindamist ootavate omanike järjekord on olemas. | Puudub hindamisülesanne koos kokkulepitud kinnistute, kliendi eesmärgi, registriandmete värskuse, eelmise kontakti ning teenustaseme tähtajaga. |
| Hindamine ja pakkumise kujundamine | Kinnistuandmed ja hindamisväljad eksisteerivad. | Puudub versioonitud hindamisotsus: valitud kinnistud, metsa- ja maaosa, eeldused, väärtusvahemik, põhjendus, sisemine minimaalhind ning kliendile lubatav pakkumine. |
| Läbirääkimine | Tegevuslogi, meeldetuletus ja staatuste muutmine on olemas. | Puudub pakkumise elutsükkel, vastupakkumised, järgmine samm, otsustaja, aegumispäev ja selge põhjus, miks võimalus võideti või kaotati. |
| Leping | PDF-lepingu genereerimine on olemas. | Leping täidetakse käsitsi ega ole seotud valitud müügivõimaluse, hindamisotsuse, kinnistute ja kokkulepitud hinnaga. |

## Soovitatud sihtmudel: tehingujuhtum

Iga huvitunud kliendi puhul tekib üks või mitu **tehingujuhtumit**. Juhtum ei asenda omanikku ega kinnistut; see seob need konkreetse müügiotsusega. Juhtumil on vastutaja, etapp, lubatud järgmine tegevus ja kaup ehk valitud kinnistud või nende osad.

> Helistaja peab lõpetama kõne tulemusega, mitte andmesisestusega. Süsteem peab looma tulemuse põhjal järgmise töö, õiged vastutajad ja selge tähtaja.

| Tehingu etapp | Süsteemi automaatne tegevus | Inimese otsus |
| --- | --- | --- |
| Kvalifitseerimine | Koostab kõnebriefi, kontrollib registriandmete värskust ja märgib puuduvad väljad. | Kas klient on päriselt müüja, mida ta soovib müüa ning mis ajaraamiga? |
| Hindamise tellimine | Loob hindajale ülesande, lisab kinnistud, kõne kokkuvõtte, huvitaseme ja SLA tähtaja. | Millised kinnistud ja väärtuskomponendid kuuluvad pakkumisse? |
| Hindamisotsus | Kuvab andmete vanuse, kitsendused, metsaregistri signaalid, hindamise eeldused ja soovitusvahemiku. | Sisemine minimaalhind ja kliendile väljastatav pakkumine. |
| Pakkumine | Genereerib pakkumise versiooni ning loob automaatse järelkontakti. | Pakkumise summa, kehtivus ja eritingimused. |
| Läbirääkimine | Hoiab pakkumised, vastupakkumised, põhjendused ja kokkulepitud järgmise sammu samas ajajoones. | Kas võtta vastu, teha vastupakkumine või lõpetada? |
| Lepingu sõlmimine | Eeltäidab lepingu valitud tehingujuhtumi, osapoolte, kinnistute ja kinnitatud hinnaga. | Lõplik kontroll ja allkirjastamine. |

## P0: järgmine arenduslaine

### 1. Kõne-eelne kliendibrief

Müügipuldile tuleb lisada ühe ekraani **kõnebrief**. See näitab määratud kontakte, valitud kinnistuid, registriandmete viimast värskust, metsa- ja pindala kokkuvõtet, kitsenduse hoiatust, varasema kontakti kokkuvõtet, avatud meeldetuletust ning soovitatud kõne eesmärki. Süsteem ei pea kõnet ise tegema ega otsustama, kuid peab eemaldama enne kõnet registrite vahel liikumise.

### 2. Struktureeritud huvi ja tehingujuhtum

Nupp „Huvitatud” peab avama väga lühikese kvalifitseerimisvormi: müügiobjekt (mets, maa, mõlemad), valitud kinnistud, müügi ajaraam, kontaktisik/otsustaja, hinnasoov või hinnatundlikkus ning järgmine kokkulepitud samm. Salvestamine loob tehingujuhtumi ja hindamisülesande, mitte ainult uue omaniku staatuse.

### 3. Hindaja üleandmispakett ja SLA

Hindaja järjekord peab sisaldama mitte üksnes omaniku nime, vaid täielikku üleandmispaketti. Vajalikud väljad on valitud kinnistud, huvi põhjus, telefonikõne kokkuvõte, registriandmete ajatempli kontroll, andmeriskid, hinnatähtaeg ning tellija. Hindaja saab staatuse „vajab andmetäiendust” korral ülesande tagasi saata koos põhjusega; süsteem loob siis helistajale tegevuse.

### 4. Hindamisotsus ja pakkumisversioon

Hindaja peab salvestama struktureeritud tulemuse: hinnatav objekt, väärtusvahemik, soovituslik ostuhind, sisemine minimaalhind, peamised eeldused, riskid ning kehtivusaeg. Kliendile väljastatav pakkumine on sellest eraldi versioon, et sisemine hinnaarvamus ei leki müügisuhtlusse.

## P1: tehingu kiirust ja konversiooni parandavad täiendused

| Täiendus | Äriline põhjus | Reegel |
| --- | --- | --- |
| Pakkumise jälgija | Väldib olukorda, kus pakkumine saadetakse ja jääb seisma. | Igal aktiivsel pakkumisel on kehtivus, järgmine kontakt ja vastutaja. |
| Vastupakkumiste ajajoon | Annab juhile ja müüjale konteksti, miks hind muutus. | Iga summa, tingimus ja kliendi vastus on muutmatu sündmus. |
| Kaotuse põhjus | Näitab, kas probleem on hinnas, ajastuses, konkurentsis või andmekvaliteedis. | Kaotatud juhtum nõuab standardset põhjust; vaba märkus on täiendav. |
| Tehingu terviseskoor | Juhi ja müüja prioriteetide alus. | Punane, kui järgmine samm puudub, pakkumine aegub või hindamine ületab SLA. |
| Mobiilne põhitöö | Välihindaja ja helistaja saavad kohapeal sisestada vaid otsuse, foto ja märkme. | Keerukad haldusseaded jäävad töölauale. |

## P2: pärast andmete ja protsessi stabiliseerumist

Hinnasoovituse modelleerimine, automaatne kõnekokkuvõte, dokumentide e-allkirjastamise ühendus ning satelliidipõhised metsa muutuse signaalid võivad anda täiendava konkurentsieelise. Need ei tohiks aga eelneda struktureeritud tehingujuhtumile, kuna ilma ühtse juhtumita ei ole võimalik mõõta, milline signaal tegelikult konversiooni või marginaali parandas.

## Mõõdikud

Rakenduse väärtust tuleb hinnata töövoo, mitte lihtsalt sisselogimiste järgi. Esmased mõõdikud on kõne → kvalifitseeritud huvi konversioon, huvi → hindamisotsus aeg, hindamisotsus → pakkumine aeg, pakkumine → tehing konversioon, järgmise tegevuseta aktiivsete juhtumite osakaal ning pakkumise keskmine allahindlus võrreldes sisemise soovitusega.

## Rakendamisjärjekord

Esmalt tuleb luua tehingujuhtumi andmemudel ja kõnebrief, seejärel hindamisülesanne ning hindamisotsus/pakkumisversioon. Alles pärast seda tuleks siduda PDF-leping tehingujuhtumiga. Nii eemaldab iga etapp kohe käsitsi töö ja annab järgmise etapi jaoks usaldusväärsema sisendi.
