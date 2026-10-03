# Andmetest algav töövoog

ForestIQ töö algab välisandmete sünkroniseerimisest, mitte helistaja käsitsi sisestusest. Kinnistu- ja omanikuandmed on esmalt organisatsiooni andmeruumis, seejärel valib juht andmesignaalide järgi helistajatele töö ning alles pärast seda jõuab kontakt müügipulti.

## Andmevoog

| Etapp | Automaatne tegevus | Tulemus |
| --- | --- | --- |
| Ametliku allika värskendus | Maa-ameti andmed, metsateatised ja metsakorralduse eraldised värskendatakse olemasolevate kinnistute kohta. | Kinnistu põhiandmed ja metsaandmed on enne tööjaotust olemas. |
| Muutusetuvastus | Süsteem võrdleb värskenduse eelset ja uut metsaregistri seisu. | Uus metsateatis või uus/muutunud eraldis loob `data_change_signal` kirje. |
| Pärimisteate kontroll | Kinnitatud pärimisteade märgib omaniku surnuks, avab pärimisjuhtumi ja loob `DECEASED_OWNER` signaali. | Tavapärane müügitöö peatub ning pärimise käsitlemine on juhile nähtav. |
| Juhi töövalik | Haldur filtreerib määramata omanikke maakonna, min. metsaala, telefoni, uue teatise, muutunud eraldise või mis tahes värske andmesignaali järgi. | Valmis andmepõhine kontaktide grupp. |
| Tööjaotus | Juht valib tulemused ja määrab need helistajale. | Omanikud saavad helistaja vastutajaks ja staatusse `ASSIGNED`; signaali esimene määramisaeg salvestatakse. |
| Müügitöö | Helistaja näeb ainult talle määratud kontakte müügipuldil. | Andmed on juba kokku toodud ning helistaja keskendub kliendikontaktile. |

## Käitamise eeltingimused

Registri aegumisepõhine automaatvärskendus on turvaliselt vaikimisi välja lülitatud. Pärast ametlike WFS-teenuste kasutustingimuste ja päringumahu kinnitamist tuleb tootmiskeskkonnas seadistada:

```bash
REGISTRY_AUTO_REFRESH_ENABLED=true
REGISTRY_AUTO_REFRESH_FIXED_DELAY_MS=21600000
REGISTRY_AUTO_REFRESH_BATCH_SIZE=100
REGISTRY_AUTO_REFRESH_CADASTRE_MAX_AGE_HOURS=168
REGISTRY_AUTO_REFRESH_FOREST_MAX_AGE_HOURS=24
```

See töötab olemasoleva organisatsiooni kinnistuportfelli peal. Uue organisatsiooni või uue omanike/kinnistute universumi esmasel laadimisel tuleb kasutada kontrollitud importi või eraldi lepingulist andmeallikat; WFS-i üleriigiline massskaneerimine ei ole vaikimisi õige ega turvaline käitusviis.

## Juhi tööpõhimõte

Juht ei alusta enam nimekirjast „kellele helistada”. Ta alustab küsimusest „milline ametlik andmemuutus loob täna kõige väärtuslikuma kontaktivõimaluse?”. Halduri töölaud järjestab tulemused viimase avatud andmesignaali järgi ning vaikimisi jätab välja juba helistajale määratud omanikud.
