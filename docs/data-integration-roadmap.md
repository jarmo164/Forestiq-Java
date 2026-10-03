# ForestIQ andmekihi ja liidestuste teekaart

**Autor:** Manus AI  
**Kuupäev:** 21. august 2026  
**Eesmärk:** muuta ForestIQ kinnistu leidmise, esmase hindamise, juriidilise hoolsuskontrolli ja tehingu järelvalve jaoks andmeteadlikuks süsteemiks, ilma et müügimehe töövoog muutuks halduskoormaks.

## Juhtkokkuvõte

ForestIQ-l on olemas tugev baas: katastri geomeetria, metsaregistri teatised ja eraldised, samuti RIK-i lepinguline adapter. Järgmine suurim väärtuse kasv ei tule veel ühe üldise kaardikihi lisamisest, vaid **tehinguotsuse jaoks selgelt eristatud riskisignaalidest**. Süsteem peab suutma ühe kinnistu kohta vastata neljale küsimusele: kas selle metsa majanduslik potentsiaal on usaldusväärne, millised avalik-õiguslikud piirangud vähendavad seda potentsiaali, kas tehingupartner saab tehingut sõlmida ning millal on töö tegemine või väljavedu riskantne.

Soovitan jagada andmeallikad kolme klassi. **P0** on avalikud või juba lepinguga kaetavad teenused, mis takistavad otsest tehinguriski ja tuleb võtta järgmiseks tootmisetapiks. **P1** parandab oluliselt müügikiirust, planeerimist või kvaliteeti, kuid ei tohi blokeerida pakkumise tegemist. **P2** on analüütiline või kommertsandmestik, mis tuleb võtta kasutusele ainult siis, kui andmekvaliteet, litsents ja tegelik ärikasu on tõestatud.

> **Oluline põhimõte:** avalik ruumiandmestik on otsusetugi, mitte omandi ega raieõiguse lõplik tõend. Lõplik hoolsuskontroll peab viitama õiguslikult pädevale allikale ning säilitama päringu aja ja vastuse versiooni.

## 1. Andmelüngad ja prioriteedid

| Prioriteet | Andmekiht ja soovitatud teenus | Otsus, mida see parandab | Liidestusviis | Miks nüüd | Peamine piirang |
| --- | --- | --- | --- | --- | --- |
| **P0** | Kitsenduste, kaitsealade ja EELIS-e ruumiandmed | Näitab, kas kinnistul on raiet, ligipääsu või kasutust piirav kaitse- või kitsendusrežiim. | OGC WFS/WMS; vajadusel Keskkonnaandmete JSON API. | Vähendab kõige kulukamat valepositiivset ostuvõimalust: metsa, mille realiseeritav väärtus on piiratud. Keskkonnaportaal avaldab EELIS-e ja keskkonnaandmete teenuseid ning juhib tähelepanu filtrite/pagineerimise vajadusele. [1] | Tundlike liikide täpseid asukohti ei tohi kuvada ega kasutada ilma selge õigusliku aluseta. EELIS API on aktiivses arengus. |
| **P0** | Kinnistusraamatu lepinguline hoolsuskontroll | Kontrollib omanikku, esindusõigust, hüpoteeke, servituute, kasutusõigusi ja märkusi enne pakkumise kinnitamist või lepingu allkirjastamist. | Lepinguline teenus/portaal; enne API arendust kinnitada RIK-i tehniline kasutusviis. | E-kinnistusraamat sisaldab nimetatud koormatisi ja pakub ettevõtetele igapäevase suure mahu jaoks lepingulist kasutust. [2] | Vajab lepingut, õigustatud kasutuse määratlust, rollipõhist ligipääsu, päringuauditit ja säilitustähtaegade poliitikat. |
| **P0** | Metsaregistri raieajaloo- ja teatise-signaalid | Eraldab kehtivad, arhiivsed ning riskiga seotud metsateatised ja hoiatab, kui majanduslik hinnang põhineb muutunud metsal. | Olemasoleva ametliku WFS-adapteri laiendus `teatis_arhiiv`, `mke` ja vajaduse järgi teised dokumenteeritud kihid. | ForestIQ kasutab juba ametlikku metsaregistri WFS-i; järgmine samm on otsuse jaoks selge ajaline signaal, mitte ainult toorandmete loend. [3] | Ei tohi eeldada, et teatis üksi tõendab tegelikku raiet; säilita andmeallikas, päringu aeg ja usaldustase. |
| **P0** | Kinnisvaratehingute turutrend | Kalibreerib piirkondliku pakkumishinna mudelit ja märgib liiga kõrge/madala turuhinnangu. | Agregeeritud statistika import või kasutaja avatud päring; ära eelda detail-API olemasolu. | Maa- ja Ruumiameti tehingustatistika sisaldab andmeid alates 1996. aastast, kuid üksiktehingute väärtused on piiratud ja kuvatakse vaid vähemalt viie tehingu korral. [4] | Ei sobi üksikkinnistu automaatse turuväärtuse ainsa alusena; andmete versioon ja päringu kuupäev tuleb salvestada. |
| **P1** | ADS / In-ADS aadressi normaliseerimine | Vähendab duplikaate, parandab kliendi-, kinnistu- ja lepingudokumentide aadressi kvaliteeti ning otsitavust. | In-ADS veebikomponent või dokumenteeritud aadressiotsingu integratsioon. | In-ADS on ADS-i ametlik otsinguliides, mis tagastab normaliseeritud aadressi ja metaandmed ning on integreeritav veebisüsteemi. [5] | Ära kirjuta üle kasutaja sisestatud posti- või kontaktiaadressi ilma kinnituse ja allika eristamiseta. |
| **P1** | Äriregistri ettevõtte kontroll | Kontrollib juriidilisest isikust omaniku staatust, registrikoodi ja esindusõigust ning rikastab lepingueelset töövoogu. | Lepinguline XML/REST andmeteenus või igapäevased hulgifailid. | RIK nõuab lepingut, soovitab hulgialglaadimiseks igapäevaselt uuenevaid faile ning seab päringutele mahu- ja paralleelsuspiirangud. [6] | Vaja on järjekorda, vahemälu, rate-limit’i ja ainult ärilise eesmärgi jaoks vajalike väljade töötlemist. |
| **P1** | Ilma-, hüdro- ja hoiatussignaalid | Juhib välitööde, hindamiste ja metsamaterjali väljaveo ajastust; hoiatab tormi-, tugeva saju-, külma- või üleujutusriskist. | Ilmateenistuse XML ning Keskkonnaandmete JSON/PostgREST API. | Vaatlusandmed uuenevad 10 minuti või tunni järel; prognoosi- ja hoiatusvood on masinloetavad. [7] [8] | Kasuta ruumilist lähima jaama/ala reeglit ning filtreeri API päringuid; ära tõlgenda piirkondlikku signaali üksikkinnistu mõõtmisena. |
| **P1** | Tee-, ligipääsu- ja reljeefiandmed | Hinnang väljaveotee pikkusele, hooajalisele teostatavusele ja operatsioonikulule. | Avalik tee- ja reljeefikiht; arvutus PostGIS-is, vajadusel litsentsitud marsruutimispartner. | Mõjutab otseselt metsa realiseerimisväärtust, kuid ei takista esmase pakkumise tegemist. | Vajab enne tootmist teenuse omaniku ja täpse teeandmekihi võimekuse valideerimist; ära kasuta kinnitamata WFS-i URL-i. |
| **P2** | Sentinel/Copernicus metsamuutuse seire | Märgib lageraie, tormi- või lumekahju kandidaadid ning tõstab kontrolli prioriteeti. | Copernicus Data Space STAC/OGC/REST, OAuth2. | Võimalik konkurentsieelis, kuid nõuab pilvisuse, geomeetria ja valepositiivsete tulemuste kvaliteedimudelit. [9] | Satelliidisignaal ei ole hindamisotsus; vajalik inimese kinnitus või teine allikas. |
| **P2** | Ajakohane puiduhinna- ja logistikavoog | Täpsustab sortimendipõhist tulumudelit. | Partneri/litsentsitud API või kontrollitud failivoog. | Avalik Erametsakeskuse hinnainfo on ajalooline ja lõpeb 2022. aasta andmetega, mistõttu seda ei tohi käsitleda reaalaja hinnasignaalina. [10] | Vajalik kaubanduslik andmeleping, andmekvaliteedi test ja hindade päritolu/kuupäeva kuvamine. |

## 2. Soovitatav otsusmootor: hinnang, risk ja usaldus

ForestIQ ei peaks koondama kõiki välisvälju lõputuks kinnistukaardiks. Iga andmeallikas peab tootma ühe või mitu **selgitavat otsusesignaali**. Näiteks `RESTRICTION_REVIEW_REQUIRED`, `FOREST_DATA_STALE`, `OWNERSHIP_CHECK_REQUIRED`, `ACCESS_COST_ESTIMATE_LOW_CONFIDENCE` või `WEATHER_OPERATION_RISK`.

| Kiht | Andmete töötlemine | Kasutajale nähtav tulemus | Kas võib automaatselt blokeerida lepingu? |
| --- | --- | --- | --- |
| Maa-amet, metsaregister, EELIS | Geomeetriline ristumine, kehtivuse aeg, lähteallika versioon | „Kontrolli piiranguid” ja piirangu tüübi kokkuvõte; link allikale | **Ei**, välja arvatud kasutaja seadistatud nõuete korral. |
| Kinnistusraamat ja äriregister | Lepingu-/päringupõhine kontroll, vastuse audit, väljade minimeerimine | „Juriidiline kontroll puudub / vajab kinnitust / kontrollitud” | **Jah**, vähemalt lepingu kinnitamise töövoos. |
| Ilm ja logistika | Ruumi- ja ajapõhine hinnang, lühike TTL-vahemälu | „Välitöö sobib / hoiatus” või „väljaveorisk kõrgem” | **Ei**, peab jääma operatiivseks otsusetoe signaaliks. |
| Satelliit ja turumudel | Mudeli skoor + usaldustase + kuupäev | „Vajab pildilist või kohapealset kontrolli”, hinnavahemik | **Ei**, kuni mudeli täpsus ja protsess on valideeritud. |

## 3. Tehniline arhitektuur

> **Ära seo müügiekraani otse välise registriga.** Kõik välispäringud peavad läbima serveripoolse adapteri, mis lisab autentimise, aja- ja mahupiirangu, vahemälu, auditilogi, normaliseerimise ja kasutajaorganisatsiooni konteksti.

Soovitatud ühine adapterileping on järgmine.

```text
Frontend → ForestIQ API → integration_adapter
                         ├─ request validator
                         ├─ source-specific rate limiter / retry policy
                         ├─ response normalizer
                         ├─ provenance + freshness store
                         ├─ risk-signal evaluator
                         └─ audit / observability event
```

Iga adapter peaks tootma vähemalt järgmised väljad: `source`, `source_record_id`, `retrieved_at`, `effective_at` (kui allikas annab), `data_classification`, `raw_payload_reference`, `confidence`, `license_notice` ja `refresh_status`. Toorvastust ei tohi näidata kõikidele rollidele; see tuleb säilitada minimaalselt ning lahutada kasutajaliideses ärisignaalist.

Püsiv taustsünkroniseerimine tuleb aktiveerida ainult konteineris/CI-s või muus püsivas arvutuskeskkonnas. Intervall tuleb olla iga allika jaoks erinev: metsateatised ja ilmahoiatused on sündmuse- või päevapõhised, katastriandmed võivad olla aeglasema TTL-iga ning kinnistusraamatu kontroll tuleb siduda pakkumise või lepingu töövooga, mitte masspärida.

## 4. 90-päevane teekaart

| Aeg | Eesmärk | Konkreetne tulemus | Edukriteerium |
| --- | --- | --- | --- |
| **0–30 päeva** | Tehinguriski nähtavaks tegemine | EELIS/kitsenduste adapter, metsateatise ajalisuse signaal, kinnistukaardi „andmete värskus ja allikad” paneel. | Igal pakkumisel on nähtav piirangukontrolli staatus, allika aeg ja vajadusel inimesele määratud kontrollülesanne. |
| **31–60 päeva** | Tehingu hoolsuskontrolli formaliseerimine | Kinnistusraamatu lepingu- ja juurdepääsumudel; äriregistri adapteri prototüüp; ADS normaliseerimine. | Lepingu töövoog ei liigu kinnitamiseni ilma seadistatud juriidilise kontrolli olekuta; ettevõtte omaniku andmed on tõendatult värsked. |
| **61–90 päeva** | Operatiivne ja hinnastamise otsusetugi | Ilma/hoiatuse tööülesanded, tee/reljeefi tõendusprojekt, KTA piirkonnatrendi mudel. | Müügimees näeb tööpäeva alguses vaid tegevust muutvaid hoiatusi; pakkumismudelis kuvatakse trendi allikas, aeg ja usaldustase. |
| **Pärast valideerimist** | Konkurentsieelis | Sentinel muutuse avastamine ja litsentsitud puiduhinna/veokulu andmevoog. | Enne laialdast kasutust on mõõdetud valepositiivsete määr, kasutaja kinnituse määr ja otsuse rahaline mõju. |

## 5. Juurutamise mittesoovitused

Esiteks ei tohi kasutada veebilehe HTML-i kraapimist ega dokumenteerimata portaali sisemisi API-sid, kui ametlik WFS, WMS, OpenAPI või lepinguline teenus on olemas. Teiseks ei tohi lisada omandi-, esindusõiguse- ega kontaktandmeid üldise automaatse taustvärskenduse alla; need päringud vajavad ärilist põhjust, õiguste kontrolli ja tugevat auditilogimist. Kolmandaks ei tohi satelliidi- või hinnamudelit esitada kui ametlikku raieõiguse või turuväärtuse otsust.

## References

[1]: https://keskkonnaportaal.ee/et/avaandmed/keskkonna-ja-ilma-valdkonna-andmeteenused "Keskkonnaportaal: keskkonna ja ilma valdkonna andmeteenused"
[2]: https://www.rik.ee/et/e-kinnistusraamat/e-kinnistusraamat "RIK: e-kinnistusraamat"
[3]: https://keskkonnaportaal.ee/et/avaandmed/geoserver "Keskkonnaportaal: GeoServer ja metsaregistri WFS"
[4]: https://maaruum.ee/maakataster-ja-maa-hindamine/kinnisvaratehingud/kinnisvaratehingute-statistika "Maa- ja Ruumiamet: kinnisvaratehingute statistika"
[5]: https://geoportaal.maaamet.ee/eng/services/in-ads-p660.html "Maa- ja Ruumiamet: In-ADS"
[6]: https://avaandmed.ariregister.rik.ee/en/open-data-api/introduction-api-services "RIK: e-Äriregistri avaandmete API teenused"
[7]: https://www.ilmateenistus.ee/teenused/ilmainfo/eesti-vaatlusandmed-xml/ "Ilmateenistus: Eesti vaatlusandmed XML"
[8]: https://www.ilmateenistus.ee/teenused/ilmainfo/eesti-prognoos-xml/ "Ilmateenistus: Eesti prognoos XML"
[9]: https://dataspace.copernicus.eu/ "Copernicus Data Space Ecosystem"
[10]: https://www.eramets.ee/uuringud-ja-statistika/hinnainfo/ "Erametsakeskus: puidu hinnainfo"
