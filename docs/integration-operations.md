# Integratsioonide käitamine

ForestIQ välisandmete tööd kasutavad ühist käituskihti. Automaatne ajastus ja administraatori käsikäivitus läbivad sama `IntegrationJobHandler`-i, PostgreSQL advisory lock'i ning jooksulogi. Sama organisatsiooni sama töö kohta saab korraga olla ainult üks `QUEUED` või `RUNNING` jooks.

## Administraatori vaade

`ORG_ADMIN` leiab lehelt **Haldus → Andmeallikad ja sünkroonimine** kõik registreeritud tööd. Iga rea juures kuvatakse andmeallikas, eesmärk, ajastus, seadistuse olek, viimane tulemus, algus- ja lõpuaeg ning jooksu mõõdikud. **Käivita** loob asünkroonse käsijooksu; vaade värskendab olekut automaatselt.

| Töö | Allikas | Vaikimisi ajastus |
| --- | --- | --- |
| `OWNER_CADASTRE_RELATIONS` | MetsIS / Forestek | seadistatud fikseeritud intervall |
| `CADASTRE_PUBLIC_DATA` | Maa- ja Ruumiamet WFS | registri värskendusintervall |
| `FOREST_NOTIFICATIONS` | Metsaregister WFS | registri värskendusintervall |
| `FOREST_PLAN` | Metsaregister WFS | registri värskendusintervall |
| `WEASEL_OWNERSHIP_TRANSITIONS` | Weasel | seadistatud fikseeritud intervall |
| `INHERITANCE_NOTICES` | Ametlikud Teadaanded | iga 30 minuti järel |

## Metsaregistri WFS mahukad kihid

Metsaregistri tööd ei laadi mitmemiljonilisi `teatis` ega `eraldis` kihte tervikuna alla. Iga katastriüksuse päring sisaldab rakenduse koostatud CQL-filtrit `katastri_nr='<tunnus>'`, mille väärtus valideeritakse enne välisvõrku saatmist. WFS 2.0 vastused tuuakse `sys_id` järgi stabiilses järjekorras ning `count` ja `startIndex` abil lehekülgedena.

Vaikimisi on lehekülje suurus 10 000 kirjet. `METSAREGISTER_WFS_PAGE_SIZE` ei saa rakenduses ületada teenuse ühe päringu 1 000 000 rea piiri ka siis, kui keskkonnamuutujas määratakse suurem väärtus. `METSAREGISTER_WFS_MAX_PAGES_PER_QUERY` peatab ootamatult piiramatu päringu; ebaõnnestunud jooks jääb Admini jooksulukku nähtavaks ega kirjuta poolikut katastri hetktõmmist edukaks.

Soovituslik tootmisseadistus on alustada 10 000 kirjest lehekülje kohta. Suurendada tasub ainult pärast vastuse suuruse, GeoServeri latentsuse ja rakenduse mälu kasutuse mõõtmist. `METSAREGISTER_WFS_SORT_FIELD` peab jääma lähtekihi unikaalseks ja stabiilseks väljaks (`sys_id`), sest muidu võib paralleelselt muutuvas kihis `startIndex` lehekülgimine kirjeid vahele jätta või korrata.

## Jooksu olekud ja tõrkekäitumine

Jooks liigub olekust `QUEUED` olekusse `RUNNING` ning lõpeb `SUCCEEDED`, `PARTIAL`, `FAILED` või `SKIPPED` olekus. Jooksulogi säilitab normaliseeritud mõõdikud ja lühikese veateate, mitte välisallika toorvastust ega autentimisandmeid. Tõrge ei märgi tööd õnnestunuks ning ei muuda eelmise eduka jooksu andmeid kehtetuks.

Ajastatud töö võib olla välja lülitatud, kuid seadistatud integratsiooni saab vajaduse korral käsitsi käivitada. `MISCONFIGURED` tööd ei saa käivitada. Käsikäivituse API ja jooksude ajalugu on piiratud `ORG_ADMIN` rollile:

```text
GET  /api/v1/admin/integrations
POST /api/v1/admin/integrations/{jobKey}/runs
GET  /api/v1/admin/integrations/{jobKey}/runs
GET  /api/v1/admin/integrations/runs/{runId}
```

Pärimisteadete käsijooksule võib anda `from` ja `to` parameetrid kujul `YYYY-MM-DD`; vahemik ei tohi ületada `INHERITANCE_SOURCE_MAX_BACKFILL_DAYS` väärtust ega ulatuda tulevikku.
