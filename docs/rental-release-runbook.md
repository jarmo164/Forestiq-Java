# ForestIQ kliendiorganisatsiooni käivitusjuhend

**Eesmärk.** See juhend määrab kontrollitud tee, mille järgi ForestIQ antakse kasutusse uuele kliendiorganisatsioonile. Rakendus on organisatsioonipõhine: kasutaja JWT `organization_id` väide määrab kõigi andmepäringute organisatsioonipiiri. Seetõttu tuleb identiteet, organisatsioon ja andmete esmane laadimine kinnitada enne esimeste tööandmete sisestamist.[1]

> **Oluline.** `compose.prod.yaml` on tootmisteenuste käivitusleping, mitte arenduskeskkond. PostgreSQL/PostGIS, Keycloak ja S3-ühilduv objektisalvestus peavad olema teenusepakkuja hallatud ning kõik saladused tulevad saladuste haldurist, mitte Gitist ega lokaalsest `.env`-failist.[2]

## Käivituse otsuseväravad

| Värav | Kohustuslik tõend | Vastutaja | Otsus, kui kontroll ei läbi |
| --- | --- | --- | --- |
| Keskkond | `./scripts/verify-production-env.sh` läbib saladuste haldurist süstitud muutujatega. | DevOps | Juurutust ei alustata. |
| Kvaliteet | API testid ja katteanalüüs, kasutajaliidese lintimine, tüübi- ja üksustestid ning tootmisehitus on rohelised. | Tehniline omanik | Muudatus parandatakse või eemaldatakse. |
| Identiteet | Üks iga rolli kasutaja saab sisse logida; tokenis on õige `organization_id`; kasutaja näeb ainult oma organisatsiooni andmeid. | Kliendi administraator | Kasutajaid ei kutsuta enne Keycloak'i rolli- ja väiteparandust. |
| Andmed | Esmalaadimine on tehtud testkoopia abil, vead on dokumenteeritud ning tootmisorganisatsiooni UUID on kinnitatud. | Andmeomanik | Pärisandmeid ei impordita. |
| Töösuitsutest | Omanik, kinnistu, kaart, müügitegevus, leping ja dokument töötavad loodud kasutajaga. | QA ja kliendi võtmekasutaja | Käivitamine jääb piloodi staatusesse. |
| Tugi | Teada on tugikanal, vastutaja, varunduse taastamisproov ning sündmuste jälgimise sihtkoht. | Teenuse omanik | Täismahus kasutuselevõtt ei ole lubatud. |

## Soovitatud kliendiorganisatsiooni käivitusrada

Alustage ühe organisatsiooni ja 2–5 kontrollitud kasutajaga. Esimene klient ei peaks kasutama automaatsünkroniseerimist enne, kui kasutusõigused, andmekvaliteet ja operatiivne jälgimine on kinnitatud. Rakenduse valikulised registri-, pärimis-, MetsIS- ja Weasel-tööd on vaikimisi välja lülitatud; iga aktiivse tausttöö jaoks kontrollib käivituseelne skript vajalikke pakkuja seadistusi.[3]

| Etapp | Tegevus | Valmiskriteerium |
| --- | --- | --- |
| 1. Leping ja keskkond | Loo kliendile eraldi organisatsioon, domeen, Keycloak'i kasutajarühm ning eraldatud S3 andmekorv või prefiks vastavalt teenuse andmete eraldamise poliitikale. | Tootmiskeskkond ei kasuta arenduse realm'i, kohalikke URL-e ega demosaladusi. |
| 2. Saladused | Sisesta nõutud keskkonnamuutujad saladuste haldurisse ning käivita preflight ilma väärtusi logimata. | Skript väljastab `ForestIQ production environment preflight passed.` |
| 3. Juurutus | Ehita ja käivita `compose.prod.yaml` või samaväärne hallatud juurutus. Avalikud pordid paiguta TLS-i lõpetava pöördpuhverserveri taha. | API readiness-proov ja `/login` tervisekontroll on rohelised. |
| 4. Identiteet | Loo kliendi administraator, müüja, hindaja ja vaatleja ning kontrolli iga rolli minimaalset õigust. | Vale organisatsiooni või liiga laia rolliga kasutaja ei pääse kliendi tööandmetele. |
| 5. Andmed | Tee esmalaadimine proovikoopiaga, korda tootmisandmetega ning kontrolli omanike, kinnistute ja seoste arveid. | Tulemuste aruanne, vigased read ja tagasipööramise otsus on talletatud. |
| 6. Piloot | Käivita iga päev jälgitav 2–5 kasutaja piloot ning kinnita põhitöövood. | Võtmekasutaja kinnitab, et töö ei sõltu kõrvalisest tabelist. |
| 7. Täiskäivitamine | Ava ülejäänud kasutajad alles pärast piloodi mõõdikute, varunduse taastamisproovi ja tugikanali kinnitamist. | Kliendil on nimega teenuseomanik ja toimiv eskalatsioonitee. |

## Kiirkäsk enne juurutust

Järgmine käsk eeldab, et käitav keskkond on juba saanud muutujad saladuste haldurist. Skript ei kuva salasõnu, vaid ainult puuduvaid või vääras vormis muutujaid.

```bash
chmod +x scripts/verify-production-env.sh
./scripts/verify-production-env.sh
```

Pärast edukat kontrolli valideerib Compose ise kõik `:?` tähistusega kohustuslikud muutujad enne konteinerite käivitamist.[2] Tootmisjuurutuse ajal ei tohi kasutada `compose.yaml` arendusvirna ega arenduse Keycloak'i sätteid.

## Väljalaske otsus

ForestIQ on **kliendile väljarentimiseks valmis**, kui kõik tabelis toodud väravad on rohelised, `main` haru CI on kohustusliku kaitsega ning kliendi esimene tööpäev on läbitud piiratud pilotis. Kui mõni värav on lahtine, võib lahendust kasutada ainult kontrollitud tehnilise piloodina; seda ei tohi kirjeldada täielikult tootmisvalmina.

## Viited

[1]: ../backend/src/main/java/ee/forestiq/security/OrganizationContext.java "JWT organisatsioonikontekst"
[2]: ../compose.prod.yaml "Tootmise Compose'i teenuse- ja seadistuseleping"
[3]: ../scripts/verify-production-env.sh "Tootmise käivituseelne kontroll"
