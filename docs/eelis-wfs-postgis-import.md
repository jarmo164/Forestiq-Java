# EELIS WFS → ForestIQ PostGIS import

## Eesmärk

`scripts/sync_eelis_wfs_to_postgis.py` laadib ühe EELIS-e avaliku WFS 2.0 andmekihi ForestIQ **generatsioonipõhisesse PostGIS-i hoidlasse**. Andmed ei lähe otse tenant-põhistesse äritabelitesse ega kirjuta üle eelmist avaldatud versiooni. Täielikult allalaaditud, kontrollitud ja avaldatud generatsioon muutub nähtavaks ainult `geospatial_source_current` kaudu.

Skript kasutab EELIS-e kanonilist WFS-punkti:

```text
https://gsavalik.envir.ee/geoserver/eelis/wfs
```

EELIS sisaldab WFS 2.0 leheküljeparameetreid (`count` ja `startIndex`) ning kuulutab `ImplementsResultPaging=TRUE`. Teenus kuulutab samas `PagingIsTransactionSafe=FALSE`. Importer kasutab unikaalset `sys_id` võtmevälja, salvestab iga lehe kursori samas PostgreSQL tehingus ning võrdleb EELIS-e `numberMatched` väärtust enne ja pärast allalaadimist.[1]

> **Oluline piirang:** võrdne `numberMatched` ei tõenda muutumatut provider-poolset hetktõmmist. EELIS ei paku selles WFS-is transaktsiooniturvalist lehekülge, provider-versiooni ega watermark-filtrit. Seega on see **best-effort** täisimport: see kaitseb katkestuse, skeemi-, CRS-, geomeetria-, objektide arvu- ja duplikaatvõtmevea eest, kuid ei saa avastada samal objektide arvul allika sisemist muutust. Täisimport nõuab selle piirangu selgesõnalist kinnitamist `--best-effort-snapshot` parameetriga.

## Andmemudel ja turbeomadused

Flyway migratsioon `V60__eelis_wfs_generation_resume.sql` lisab üldisele `geospatial_generation` mudelile WFS-spetsiifilise checkpoint'i. Skript kasutab järgmisi tabeleid.

| Tabel | Roll importimisel |
|---|---|
| `geospatial_source` | Üks üle-eestiline EELIS andmeallikas kihi kohta. Skript registreerib uue allika automaatselt, kuid keeldub olemasolevat `source_key` väärtust teise kihi vastu ümber sidumast. |
| `geospatial_source_run` | Ühe käivituse olek ja ridade mõõdikud. |
| `geospatial_generation` | Staging-, valideeritud, avaldatud või ebaõnnestunud andmeversioon ning `next_start_index` checkpoint. |
| `geospatial_feature` | Ühe generatsiooni GeoJSON omadused, providerivõti ja EPSG:3301 PostGIS geomeetria. |
| `geospatial_source_current` | Atomaarne pointer viimasele edukale generatsioonile. |
| `geospatial_generation_event` | Auditjälg genereerimise, valideerimise, avaldamise ja tõrgete kohta. |

Skript kasutab enne muudatusi `pg_try_advisory_lock(hashtext(source_key))`. Seega ei saa kaks sama skripti protsessi sama EELIS lähteallikat samaaegselt importida. Lisaks lubab andmebaasi osaline unikaalne indeks ainult ühe `STAGING` generatsiooni ühe `source_key` kohta. Avaldamise ajal lukustab skript `geospatial_source` rea `FOR UPDATE` abil; see on ForestIQ ühine serialiseerimispiir Java tööprotsesside ja eraldi importeri vahel.

| Kaitse | Käitumine |
|---|---|
| Katkestus või teenuse timeout | Jooks märgitakse võimalusel `PARTIAL`; juba commititud lehti ei kustutata ja järgmine käivitus jätkab `next_start_index` väärtusest. Kui PostgreSQL ühendus ise katkes, on checkpoint taastumise alus ning jooksu oleku kirjutamine võib ebaõnnestuda. |
| Muutunud kihtide skeem | EELIS `DescribeFeatureType` skeemi SHA-256 sõrmejälg peab jätkamisel kattuma. Vastasel korral skript peatub. |
| Muutunud objektide arv | Algne, iga lehe ning lõplik `numberMatched` peab olema sama. Vastasel juhul jääb staging avaldamata. |
| Mitteunikaalne või puuduv `sys_id` | Providerivõti peab igal real olemas olema. Andmebaasi generatsioonisisene unikaalsusindeks peatab duplikaadi, märgib generatsiooni `FAILED` ja hoiab eelmise versiooni aktiivsena. |
| Vigane või vales CRS-is geomeetria | Iga leht peab deklareerima EPSG:3301. Enne avaldamist kontrollitakse, et kõigil ridadel on kehtiv, mitte-tühi, 2D EPSG:3301 geomeetria. |
| Tühi asendus | Null-realine kiht ei asenda vaikimisi eelmist avaldatud generatsiooni. Vajalik on `--allow-empty --empty-reason '<põhjus>'`. |
| Poolik import | Lugejad näevad endiselt eelmist `PUBLISHED` generatsiooni. |

## Eeldused

1. Käivita ForestIQ Flyway migratsioonid vähemalt versioonini **V60**. Ära käivita V60 SQL-i käsitsi tootmisandmebaasis; see tuleb rakendada tavapärase ForestIQ deploy/Flyway protsessiga.
2. Kasuta Python 3.11 või uuemat ning paigalda skripti andmebaasidraiver eraldatud virtuaalkeskkonda.
3. Sea ainult serverikeskkonnas PostgreSQL-i ühendus. Ära lisa parooli Git-i ega logidesse.

```bash
cd ForestIQ
python3 -m venv venv
. venv/bin/activate
python3 -m pip install --upgrade pip
python3 -m pip install -r scripts/requirements-eelis-wfs.txt
```

Lokaalse Compose andmebaasi jaoks saab URL-i koostada `.env` muutujatest. Tootmises kasuta sama saladuste halduse mehhanismi, mida kasutab ForestIQ API.

```bash
export FORESTIQ_DATABASE_URL='postgresql://forestiq:<parool>@127.0.0.1:5432/forestiq'
```

## Enne importi: kuivkäik

Kuivkäik teeb ainult EELIS-e `DescribeFeatureType` ja `resultType=hits` päringud. See ei ühendu PostgreSQL-iga.

```bash
python3 scripts/sync_eelis_wfs_to_postgis.py \
  --layer eelis:natura_elupaik \
  --dry-run
```

Oodatav JSON sisaldab vähemalt `sourceKey`, `geometryField`, `schemaFingerprint`, `sourceSrid` ja `providerNumberMatched`. Standardne `sourceKey` tuletatakse kihi nimest; näiteks `eelis:natura_elupaik` muutub väärtuseks `EELIS_NATURA_ELUPAIK`.

Kõik EELIS-e kihid ei pea sobima ForestIQ-i tootevajadusega. Vali enne importi konkreetne kasutusjuht. Näiteks võivad metsa- ja looduskeskkonna analüüsi jaoks olla asjakohased `eelis:natura_elupaik`, `eelis:sood`, `eelis:kuusekooreyrask_eelis` või `eelis:kr_vep`. Kihi vajadus ja lugejavaade tuleb enne tootmisesse toomist eraldi heaks kiita.

## Täielik import

Allolev käsk impordib ühe kihi 10 000 objektiga lehtedes. See väärtus on teadlikult väiksem kui EELIS-e capabilities-dokumendi `CountDefault=100000`, sest väiksem leht piirab mälu, transaktsiooni ja taastamisakna mahtu.[1]

```bash
FORESTIQ_DATABASE_URL='postgresql://forestiq:<parool>@127.0.0.1:5432/forestiq' \
python3 scripts/sync_eelis_wfs_to_postgis.py \
  --layer eelis:natura_elupaik \
  --source-key EELIS_NATURA_ELUPAIK \
  --display-name 'EELIS — Natura elupaigad' \
  --sort-field sys_id \
  --page-size 10000 \
  --max-pages 10000 \
  --best-effort-snapshot
```

Skript küsib EELIS-elt GeoJSON-i väärtusega `outputFormat=application/json`, mitte `application/geo+json`. EELIS capabilities-dokument loetleb mõlemad, kuid kontrollitud server tagastas `application/geo+json` päringule HTTP 400. GeoJSON geomeetria saadetakse EELIS-elt ja salvestatakse PostGIS-i EPSG:3301 kujuväljana; see vastab ForestIQ kanonilisele Eesti CRS-i lepingule.[2]

## Taastamine ja stagingu teadlik kõrvaldamine

Ajutise võrgu-, EELIS-e või PostgreSQL-i tõrke järel käivita täpselt sama käsk uuesti. Skript leiab sama `source_key` aktiivse staging-generatsiooni ja jätkab selle järgmiselt commititud `next_start_index`-ilt.

```bash
python3 scripts/sync_eelis_wfs_to_postgis.py \
  --layer eelis:natura_elupaik \
  --source-key EELIS_NATURA_ELUPAIK \
  --best-effort-snapshot
```

Kui EELIS-e skeem või `numberMatched` muutus ja importer peatub tahtlikult, vaata muudatus üle. Pärast otsust, et vana poolik generatsioon tuleb hüljata, alusta uut importi ainult selgesõnalise `--discard-staging` parameetriga:

```bash
python3 scripts/sync_eelis_wfs_to_postgis.py \
  --layer eelis:natura_elupaik \
  --source-key EELIS_NATURA_ELUPAIK \
  --discard-staging \
  --best-effort-snapshot
```

See ei kustuta eelmist `PUBLISHED` generatsiooni. See märgib ainult praeguse pooliku `STAGING` generatsiooni ebaõnnestunuks ning loob uue versiooni.

## Valideerimine pärast edukat jooksu

Skript kirjutab lõpus JSON-i staatuse, genereerimise UUID, rea- ja lehearvu. Seejärel kontrolli avaldatud generatsiooni PostGIS-is.

```sql
SELECT
  s.source_key,
  s.dataset_key,
  g.id AS generation_id,
  g.status,
  g.row_count,
  g.provider_number_matched,
  g.pages_processed,
  g.validation_summary,
  c.published_at
FROM geospatial_source s
JOIN geospatial_source_current c ON c.source_key = s.source_key
JOIN geospatial_generation g ON g.id = c.generation_id
WHERE s.source_key = 'EELIS_NATURA_ELUPAIK';
```

Geomeetria kontrolliks:

```sql
SELECT
  count(*) AS rows_total,
  count(*) FILTER (WHERE NOT ST_IsValid(geom)) AS invalid_geometry,
  count(*) FILTER (WHERE ST_SRID(geom) <> 3301) AS wrong_srid
FROM geospatial_feature
WHERE source_key = 'EELIS_NATURA_ELUPAIK'
  AND generation_id = (
    SELECT generation_id
    FROM geospatial_source_current
    WHERE source_key = 'EELIS_NATURA_ELUPAIK'
  );
```

## Käitamispiirid

See on **kontrollitud käsitsi või eraldi tööprotsessis käivitatav bulk-importer**. See ei lisa automaatset ajastust ega API endpointi. Enne regulaarset tootmisvärskendust tuleb otsustada, millised EELIS-e kihid on ForestIQ-is heaks kiidetud, kui sageli neid vaja on, ning kuidas import käivitatakse API tööprotsessist eraldi. Riiklike ruumiandmete import peab jääma interaktiivsest API liiklusest isoleeritud tööks.[3]

## Testid

```bash
python3 -m unittest scripts/test_sync_eelis_wfs_to_postgis.py -v
python3 scripts/sync_eelis_wfs_to_postgis.py --layer eelis:natura_elupaik --dry-run
```

Esimene käsk kontrollib offline skeemi-, lehe-, CRS- ja argumentide valideerimisloogikat. Teine kontrollib EELIS-e elavat capabilities/skeemi ning objektiarvu, kuid ei kirjuta andmebaasi.

Enne muudatuse ühendamist valideeriti migratsioonid `V51`, `V59` ja `V60` puhtas ajutises PostgreSQL/PostGIS andmebaasis ning imporditi elav `eelis:yrg` kiht kolme 1 000-objektilise lehega. Tulemus sisaldas 2 190 provider- ja kohaliku rea, 2 190 kehtivat EPSG:3301 geomeetriat ning avaldatud generatsiooni. Samas testis prooviti olemasolevat `source_key` väärtust teise EELIS kihiga ümber siduda; importer keeldus ja säilitas algse avaldatud generatsiooni.

## Viited

[1]: https://gsavalik.envir.ee/geoserver/eelis/wfs?service=WFS&request=GetCapabilities&version=2.0.0 "EELIS avaliku OGC WFS teenuse GetCapabilities dokument"
[2]: https://docs.geoserver.org/main/en/user/services/wfs/basics/ "GeoServer WFS basics"
[3]: https://github.com/jarmo164/ForestIQ/blob/main/docs/geospatial/ADR-001-target-geospatial-architecture.md "ForestIQ target geospatial architecture"
