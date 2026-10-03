# #553 — Keycloak kasutajakataloogi identity-porti eraldamise teostusplaan

**Staatus:** Teostatud  
**Issue:** [#553](https://github.com/jarmo164/ForestIQ/issues/553)  
**Har u:** `issue-553-identity-port`

## Eesmärk

Eraldada Keycloaki HTTP-, teenusetokeni-, rolli- ja lühiajalise kataloogicache'i detailid ühe identity-adapteri taha. Rakendusmoodulid kasutavad typed kasutajakataloogi ja assignability-porti ning ei sõltu enam `AdminController` HTTP DTO-dest ega Keycloaki adapteriklassist. Avalike admin-APIde JSON-kuju jääb samaks.

## Disainiotsused

1. Lisada `ee.forestiq.identity` paketti provider-neutraalsed `OrganizationUser`, `OrganizationRole`, `OrganizationIdentityDirectory` ja `OrganizationIdentityAdministration` tüübid.
2. Viia Keycloak adapter ning selle properties samasse identity-paketti. Adapter on ainus koht, mis sõltub `RestClient`-ist, Keycloaki URL-idest, tokenist ja töötlemata Keycloaki representatsioonidest.
3. Säilitada olemasolev 30-sekundiline organisatsiooni kasutajakataloogi cache ja täpselt üks portikutse ühe tarbijaotsuse kohta. Porti lisamine ei lisa request-threadile uut Keycloaki võrgukutset.
4. Hoida iga use-case'i sobivate rollide poliitika tarbijas: workflow sisaldab ka `EVALUATOR`-it, contract/quality/segment ning omaniku määramine ei sisalda.
5. Hoida admini HTTP record'id controlleri servas ning teha explicit mapping identity tüüpide ja wire-kuju vahel.
6. Migreerida kõik olemasolevad query/eligibility tarbijad porti, et teenuse nimi või adapteri paketitee ei jääks uue arhitektuuri vaikimisi sõltuvuseks.
7. Lisada ArchUnit reegel, mis keelab workflow-, contract- ja management-moodulitel sõltuda Keycloaki adapterist või admin HTTP adapteri tüüpide kaudu.

## Etapid

- [x] Kaardistada adapter ja kõik tarbijad.
- [x] Luua typed identity-port ning viia Keycloak adapter selle taha.
- [x] Migreerida admin-, workflow-, contract-, management- ja ülejäänud tarbijad.
- [x] Lisada ArchUnit ning identity-porti iseloomustavad testid.
- [x] Käivitada Java 21 kontrollid, vaadata diff üle, commitida ja pushida.

## Kontrollitavad säilitatavad omadused

- Organisatsiooni kasutajate filtreerimine tugineb `organization_id` atribuudile ning aktiivsele kasutajale.
- Tuntud rollid on `ORG_ADMIN`, `CRM_MANAGER`, `EVALUATOR`, `CALLER`, `VIEWER`; tundmatu roll jääb tagasi lükatuks.
- Kasutajate ja rollide sortimine, 100-realise paging'u ning 20 000 kasutaja ohutuspiiri käitumine säilib.
- Assignability tõrjub puuduva, mitteaktiivse, teise organisatsiooni või sobimatu rolliga subjekti sama `BAD_REQUEST` põhjusega.
- Frontendile nähtavad admini kasutaja DTO väljad `id`, `username`, `name` ja `roles` ei muutu.
