# Müügimehe isikliku müügikeskkonna audit

## Põhimõte

Müügimees peab oma tööpäeva alustama ühe otsusega: **kellele ma nüüd helistan ja miks?** Kliendihaldus, järeltegevuse meelespidamine, prioriteedi arvutamine ja tulemi logimine ei tohi nõuda eraldi tabelite täitmist ega juhipoolset koordineerimist. ForestIQ peab olema isiklik müügipult, mitte passiivne CRM.

| Tööhetk | Süsteemi vastutus | Müügimehe tegevus |
| --- | --- | --- |
| Päeva alustamine | Arvutab hilinenud järeltegevused, tänased kontaktid, uued vihjed ja vananenud võimalused | Vajutab **Alusta järgmist kontakti** |
| Kliendiga suhtlemine | Näitab kontaktandmeid, kinnistu ärifakte, varasemaid märkmeid ja järgmise kontakti põhjust | Helistab või kirjutab kliendile |
| Kõne tulemus | Pakub ühe kliki tulemusi, mis loovad märkme, muudavad vajadusel staatust ja panevad järeltegevuse kalendrisse | Valib tegeliku tulemuse |
| Päeva lõpetamine | Jätab kõik pooleli olevad kontaktid automaatselt järgmisse prioriteetsesse tööjärjekorda | Ei tee eraldi haldustööd |

## Prioriseerimise äriloogika

Isiklik tööjärjekord peab töötlema ainult müügimehele määratud omanikke. Kõrgeim prioriteet on tähtaja ületanud järeltegevusel, sellele järgneb täna tähtaeguv kontakt, esimene kontakt ilma tegevuseta ning seejärel vähemalt seitse päeva seisnud aktiivne võimalus. Lepingus ja mittehuvitatud staatuses omanikud jäetakse aktiivsest tööjärjekorrast välja.

## Rakendatav disain

Uus isiklik müügipult koondab ühe ekraani peale järgmise kliendi, põhjendatud tegevusjärjekorra, otsekõne, omaniku töökaardi ja kiired tulemuse nupud. Tulemuse nupp loob standardse tegevuslogi ning vajadusel järelmeeldetuletuse; seega on CRM-i andmestik korrektne, kuid müügimees ei pea seda käsitsi haldama.

## Rakendatud müügipult

Isiklik müügikeskkond asub aadressil `/sales` ning on nähtav müügitegevuse rollidele. See kuvab ühe kõrgeima prioriteediga kliendi esimesena, seejärel järgmised kuni kaheksa tegevust. Müügimees saab oma klienti otsida nime, telefoni või koodi järgi ning avada vajadusel täieliku kliendikaardi või kaarditööruumi.

Tööjärjekord kasutab ainult sisselogitud kasutajale määratud omanikke. Järjekord eelistab hilinenud meeldetuletust, täna tähtaeguvat meeldetuletust, esimest tegevuseta kontakti, vähemalt seitse päeva ilma tegevuseta kontakteeritud või huvitatud klienti ning viimaks muud aktiivset järelkontakti.

Kõnetulemuse nupud on transaktsioonilised. **Ei vastanud** ja **Helista hiljem** lisavad standardse tegevuslogi ning loovad automaatselt meeldetuletuse järgmisele sobivale tööpäeva hommikule. **Huvitatud** muudab staatuse vajadusel huvitatuks ning loob hindamise järgmise sammu meeldetuletuse. **Hindamisse** ja **Ei huvita** logivad tulemi ning muudavad staatust. Kõik toimingud kontrollivad, et klient on määratud sisselogitud müügimehele, ja ei langeta juba edasijõudnud müügivõimalust järelkontakti tõttu varasemasse staatusesse.

## Valideerimine

Backendi Gradle testid, kliendi lintimine, TypeScripti kontroll, Vitesti testid ja Next.js tootmisehitus läbivad. Reaalse PostgreSQL/PostGIS-i päringu ning Keycloaki kasutajaseansi täielik e2e kontroll tuleb käivitada Dockeriga CI-s või arenduskeskkonnas, sest Docker ei ole selles töötluskeskkonnas saadaval.
