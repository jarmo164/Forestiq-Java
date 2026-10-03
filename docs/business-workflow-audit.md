# ForestIQ ärilise kasutusväärtuse ja kaarditöövoo audit

## Ärimudel ja kasutaja eesmärk

ForestIQ väärtus tekib siis, kui metsaomaniku või kinnistu tuvastamisest jõutakse võimalikult väheste tegevustega järgmise müügitegevuseni: kontakt, hindamine, pakkumine või leping. Kõrgeima mõjuga kasutajarollid on helistaja, hindaja ja CRM-haldur. Nende jaoks peab iga otsing või kaardivalik kohe vastama küsimustele: kelle kinnistu see on, kui suur see on, milline on müügistaatus, kelle töö see on ja milline on järgmine tegevus.

## Tuvastatud prioriteedid

| Prioriteet | Äriprobleem | Praegune kitsaskoht | Planeeritud parandus |
| --- | --- | --- | --- |
| Kõrge | Kinnistu → omanik → tegevus võtab liiga kaua | Kaardivaade näitab ainult polügoone; klikil puudub omanik, staatus ja töötegevus | Kiirinfo külgpaneel kaardil, otse omaniku profiili ja töövoo tegevusteni |
| Kõrge | Kaardilt ei saa kiiresti otsida potentsiaalset tehingut | Puudub otsing katastritunnuse, omaniku või telefoninumbri järgi | Globaalne kaart-CRM otsing, tulemusest kaardile fokuseerimine |
| Kõrge | Kaardi liigutamine teeb korduvaid päringuid | Vektorkihid saadetakse `no-store` päistega | Autentitud privaatne lühiajaline brauserivahemälu koos turvalise organisatsioonifiltriga |
| Kõrge | Kinnistute äriline prioriteet pole kaardil nähtav | MVT kiht ei sisalda omaniku staatust ega määrangut | Staatuse- ja määrangupõhine värvikodeerimine ning kiirfiltrid |
| Keskmine | Helistaja peab otsima järgmise tegevuse mitmest vaatest | Töölaud, omanik ja kaart on eraldatud | Kiirtegevused kaardipaneelilt: ava omanik, helista, määra staatus, lisa märkus |
| Keskmine | Haldur ei näe kaarditöövoo mõju | Ülevaade mõõdab andmemahtu, mitte tegevusjärjekorda | Müügitoru KPI-d ja prioriteetsed järgmised tegevused avalehel |

## Rakendamise põhimõtted

Parandused peavad säilitama organisatsioonipiiri igas serveripäringus. Kaardikiht jääb MVT-põhiseks, sest see on suurte polügoonihulkade jaoks sobiv; detailinfo päring toimub ainult valitud kinnistu või otsingutulemuse kohta. Nii välditakse suure georuumiandmehulga laadimist enne, kui kasutaja vajab konkreetset ärilist vastust.

## Rakendatud täiustused

| Täiustus | Kasutaja töövoo mõju |
| --- | --- |
| Kaardipõhine tööruum | Kasutaja otsib CRM-ist nime või registrikoodi, fokuseerib omaniku kinnistud kaardile ning valib polügooni ilma vaadete vahel liikumata. |
| Kinnistu kiirülevaade | Valitud polügoon avab omaniku, staatuse, vastutaja, pindala, metsamaa, asukoha ja telefoninumbri; töökaart ning kõne on ühe kliki kaugusel. |
| Kaardi prioriseerimine | Polügooni toon kujutab omaniku töövoo staatust ning märgitud kinnistud on selgemalt esile tõstetud. Kiirfiltrid toovad esile huvitatud, kontakteeritud, hindamisel või määratud töö. |
| Privaatne vektorkihi vahemälu | Brauser hoiab autentitud MVT-kihte lühiajaliselt privaatses vahemälus. Andmed ei ole jagatud vahemälus ning serveripäringud vähenevad korduvatel pan- ja zoom-tegevustel. |
| Müügitoru juhtpaneel | Avaleht näitab uusi vihjeid, aktiivseid võimalusi, hindamisjärjekorda, määratud tööd, lepinguid, kaardikinnistuid ja tähtaegade ületusi koos järgmiste tööde otseteedega. |

## Valideerimine

Backendi Gradle testid, kliendi lintimine, TypeScripti kontroll, Vitesti testid ja Next.js tootmisehitus läbivad pärast muudatusi. Docker ei ole selles töötluskeskkonnas saadaval, mistõttu reaalse Keycloaki/PostGIS-i koostalitluse brauseri-e2e tuleb käivitada juurutuse CI-s või Dockeriga arenduskeskkonnas.
