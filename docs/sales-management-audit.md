# Büroo juhi müügimeeskonna juhtimise audit

## Juhi otsustsükkel

Büroo juht ei vaja üksnes müügitoru kogusummat, vaid vastuseid neljale järjestikusele küsimusele: kas müügivihjed on omanikuga seotud, kas töö on õiglaselt jaotatud, kas iga müüja liigub võimalusi edasi ning millise omaniku või töötaja puhul peab juht täna sekkuma. ForestIQ-s tuleb need vastused tuletada ülesande määrangust, staatusemuudatustest, meeldetuletustest ja CRM-i ajakohasusest.

| Juhi küsimus | Tõendusandmed | Vajalik juhtimistegevus |
| --- | --- | --- |
| Kus on müügitulu potentsiaal? | Uued, kontakteeritud ja huvitatud omanikud; hindamis- ning lepingustaatus | Suunata tähelepanu järgmisele kitsaskohale müügitorus |
| Kas töö on tasakaalus? | Igale kasutajale määratud omanike arv ning avatud töökoormus | Jaotada töö ümber enne, kui reaktsiooniaeg kasvab |
| Kes vajab tuge? | Viimase seitsme päeva staatusemuudatused, viimane tegevus ja tähtaja ületused | Coaching, prioriteedi muutmine või töö ümbermääramine |
| Milline tehing on ohus? | Määramata uued vihjed, vananenud huvitatud/kontakteeritud omanikud ning hilinenud meeldetuletused | Avada omanik, määrata vastutaja või kokkuleppida järgmine kontakt |

## Rakendamise põhimõte

Juhi tööruum peab olema ainult `ORG_ADMIN` ja `CRM_MANAGER` rollidele. See kuvab organisatsioonipiiriga koondid, kuid ei nõua juhtidelt andmete eksportimist. Iga sekkumisrida suunab otse omaniku töökaardile või olemasolevasse massmääramise töövoogu; seega ühendatakse analüüs ja tegevus samal ekraanil.

## Rakendatud juhi tööruum

Juhi tööruum asub aadressil `/sales-management` ja on nähtav ainult `ORG_ADMIN` ning `CRM_MANAGER` rollidele. See koondab määramata vihjete, vananenud võimaluste, tähtaja ületuste ja viimase seitsme päeva tegevusmahu mõõdikud. Meeskonnatabel näitab igale aktiivsele kasutaja-subjektile määratud omanike hulka, aktiivsete võimaluste hulka, seitsme päeva staatusemuudatusi, tähtaja ületusi ja viimast tegevusaega.

Sekkumisjärjekord sisaldab ainult tegevust nõudvaid omanikke. **Määramata müügivihje** tähendab uut omanikku ilma vastutajata. **Vananenud müügivõimalus** tähendab kontakteeritud või huvitatud omanikku, kellel puudub seitsme päeva jooksul ükskõik milline `owner_activity` sündmus. Iga rida avab otseselt omaniku töökaardi; töö ümberjaotuse otsetee viib olemasolevale halduse töölaudade vaatele.

## Valideerimine

Pärast rakendamist läbivad backendi Gradle testid, kliendi lintimine, TypeScripti kontroll, Vitesti testid ja Next.js tootmisehitus. Täislahenduse PostGIS/Keycloak e2e kontroll tuleb käivitada Dockeriga arenduskeskkonnas või CI-s, sest Docker ei ole selles töötluskeskkonnas saadaval.
