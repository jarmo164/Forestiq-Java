# Legacy funktsionaalsuse võrdlusaudit

## Lähtekoht

Võrdlus tehti Legacy `main` Angulari teekondade, komponentide ja kasutusvoogude ning rewrite-haru Next.js-i, Spring Booti ja testide vastu. Audit käsitleb Legacy töövooge funktsionaalse baasjoonena; ümberkirjutuse sihtarhitektuuri teadlikud erinevused ei õigusta kasutajaliideses eksitavat või mittetoimivat tegevust.

## Kinnitatud leiud

| Valdkond | Legacy kasutusvoog | Rewrite seis enne parandusi | Paranduse prioriteet |
| --- | --- | --- | --- |
| Evaluaatori töölaud | `/workdesk/evaluator` loetles hindamist ootavad omanikud ning avas profiili | Vastav leht, navigatsioon ja otstarbeline API puuduvad; andmemudelis on `EVALUATION` ja `WAITS_FOR_EVALUATION` staatused | Kõrge |
| Meeldetuletuste juhtpaneel ja statistikad | `/reminders-dashboard` ning kasutajastatistika võimaldasid halduril jälgida töövoogu | Backendis on kasutajastatistika endpoint ja kliendikonks, kuid kasutajaliidese vaade ning navigeerimine puuduvad | Kõrge |
| Kasutajaprofiil | `/me` võimaldas kasutajal hallata enda konto/parooli | Rewrite’is puudub profiilileht ja menüülink, kuigi Keycloak pakub konto haldamise voogu | Kõrge |
| Kasutajahaldus | Administraator haldas kasutajaid ja rolle | Rewrite’i loomise, rollimuudatuse ja kustutamise endpointid tagastavad ajutise väärtuse või on tühjad; kasutajaliides jätab mulje, et tegevus õnnestus | Kriitiline |
| Kliendi kvaliteedivärav | Legacy pakkus käivitatavat kliendi buildi | Rewrite lint ebaõnnestub 12 kasutamata impordi/muutuja hoiatuse tõttu | Kõrge |
| Backend’i kvaliteedivärav | Server peab kompileeruma ja testid läbima | Rakenduse testid läbivad Java 21 JDK-ga; testkeskkonnas puudus algselt JDK kompilaator, mitte projektiviga | Kontrollitud |

## Säilinud või juba rakendatud töövood

Omanike otsing, omaniku detail, staatuse muutmine, kinnistute detailid, kaardivaade, helistaja töölaud (`/my-work`), meeldetuletused, sisemine sõnumivahetus, kontaktiraamat, lepingud ning administraatori massmääramine on rewrite-harus olemas. Järgmised muudatused keskenduvad tõendatud lünkade sulgemisele ja mittetoimivate tegevuste kõrvaldamisele.

## Kontrollkriteeriumid

Parandused loetakse lõpetatuks ainult siis, kui kliendi lint, TypeScripti kontroll, üksustestid ja tootmisehitus läbivad; backendi testid läbivad; ning uued vaated on rollipõhiselt navigeeritavad ja kasutavad tegelikke API-kutseid.
