# Weaseli omandiüleminekute töövoog

## Eesmärk

ForestIQ kasutab Weaseli **katastri omandiüleminekute** feedi andmetest algava töövoo signaalina. See ei ole inimeste füüsilise asukoha jälgimine ega muuda automaatselt ForestIQ juriidilist omanikuandmestikku. Sündmus tähendab, et Weasel tuvastas katastriüksusel muutuse omanike identifikaatorite kogumis.

> Weaseli sündmus on müügi- või andmekontrolli töö **signaal**, mitte omandiõiguse lõplik tõend. Omandiõigus tuleb lepingu- või hoolsuskontrolli etapis kontrollida pädevast allikast.

## Andmevoog

| Etapp | Käitumine |
| --- | --- |
| 1. Weaseli lähteandmed | Weasel tuvastab vana ja uue omanike kogumi erinevuse katastriüksuse kaupa ning avaldab sündmuse oma autentitud API-st. |
| 2. ForestIQ sünkroniseerimine | ForestIQ tõmbab piiratud ajavahemiku, lehekülgede ja kattuvusaknaga sündmused ainult konfigureeritud organisatsiooni tokeniga. |
| 3. Kohalik sobitamine | Sündmus imporditakse ainult siis, kui katastritunnus ja ForestIQ kohalik omaniku kood on Weaseli vana või uue omanike kogumiga täpselt kooskõlas. |
| 4. Audit | Süsteem salvestab ainult Weaseli sündmuse ID, kohaliku kinnistu viite, katastritunnuse, aja, muutuse suuna ning omanike arvu enne/pärast. Dubleeritud sündmus ei loo uut rida. |
| 5. Töösignaal | Uus sobitatud sündmus tekitab `OWNERSHIP_TRANSITION` andmesignaali ning süsteemse tegevusajaloo kirje. |
| 6. Juhi tööjaotus | Haldur filtreerib **Weaseli omandimuutuse** järgi ainult veel määramata kontakte ja annab valitud töö helistajale. |
| 7. Kliendikaart | Juht, CRM-haldur ja hindaja näevad omandiüleminekute ajajoont; kasutajaliides kuvab ainult katastriüksuse, aja, muutuse suuna ning omanike arvu enne/pärast. |

## Turve ja andmete minimeerimine

Weasel on sündmuse ja omanike detailandmete kanoniline lähteallikas. ForestIQ **ei talleta** Weaseli vana/uue omaniku identifikaatorite loendeid ega API toorvastust; detailide vajadusel tuleb neid lugeda Weaselist vastavalt lähteallika õigustele. Weaseli API peab olema kasutatav ainult serveritevahelise OAuth Bearer-tokeniga. Token, Weaseli API URL ja `WEASEL_ORGANIZATION_ID` on ainult serveripoole keskkonnamuutujad; neid ei tohi anda brauserile ega lisada `NEXT_PUBLIC_*` muutujate hulka.

`WEASEL_ORGANIZATION_ID` on kohustuslik. ForestIQ lubab sünkroniseerida ainult selle ühe organisatsiooni portfelli, mis on seotud Weaseli tokeni organisatsiooniga. Seega ei saa sama teenusemandaadi kaudu kogemata töödelda teise ForestIQ rentniku andmeid.

| Roll | Õigus |
| --- | --- |
| `ORG_ADMIN`, `CRM_MANAGER` | Käivitab käsitsi sünkroniseerimise, näeb ajajoont ja saab luua tööjaotuse. |
| `EVALUATOR` | Näeb omaniku töökaardil andmeminimeeritud ajajoont. |
| `CALLER`, `VIEWER` | Ei näe Weaseli toorandmeid ega käivita sünkroniseerimist. |

## Seadistamine

```bash
WEASEL_API_BASE_URL=https://weasel.example.ee/api/v1
WEASEL_API_ACCESS_TOKEN=<least-privilege-oauth-bearer-token>
WEASEL_ORGANIZATION_ID=<forestiq-organization-uuid>
WEASEL_AUTO_REFRESH_ENABLED=true
WEASEL_AUTO_REFRESH_DELAY_MS=21600000
WEASEL_PAGE_SIZE=100
WEASEL_MAX_PAGES_PER_RUN=10
WEASEL_OVERLAP_HOURS=6
WEASEL_INITIAL_LOOKBACK_DAYS=14
```

Automaatne sünkroniseerimine on vaikimisi väljas. See tuleb sisse lülitada alles pärast dokumenteeritud andmejagamise alust, Weaseli organisatsiooni funktsioonilipu aktiveerimist ning vähimate õigustega tokeni väljastamist.

## Operatsioonireeglid

Sünkroniseerija kasutab kuue tunni kattuvusakent, et hilinenud sündmused ei läheks kaotsi. Andmebaasi unikaalsus `(organization_id, weasel_transition_id)` tagab, et sama sündmus ei tekita uut tegevust ega uut töösignaali. Viga salvestatakse organisatsiooni sünkroniseerimise kontrollpunkti ja järgmine töö taastab sama ajavahemiku kattuvusakna abil. Migratsioon puhastab ka juba salvestatud Weaseli tooridentifikaatorid ning toorvastused.

Juht peab kasutama omandimuutuse signaali esmase kontrolli- või kontaktiülesandena. See ei asenda nõusoleku, kontakteerimisõiguse ega omandiõiguse kontrolli poliitikat.
