# CSV-import til vegdata.db

Skriptet leser NVDB/Vegkart-CSV fra `scripts/csv/` og skriver `app/src/main/assets/vegdata.db`.

## Kjør

Full import (bygger databasen på nytt):

```bash
python scripts/import_vegdata.py
```

Kun én type (beholder resten av databasen), f.eks. bomstasjoner:

```bash
python scripts/import_vegdata.py --only BOM
```

Skriptet bruker kun Python standardbibliotek (`csv`, `sqlite3`).

## Geometri

NVDB-CSV fra Vegkart/eksport bruker typisk **EUREF89 UTM sone 33** i `GEO.GEOMETRI` / `LOK.GEOMETRI`. Skriptet konverterer til WGS84 lat/lon.

## Forventede filnavn

Filtype styres av filnavnet (store/små bokstaver og æ/ø/å er OK):

| Innhold i filnavn | Type i Room |
| --- | --- |
| `fart`, `fartsgrense` | `FART` |
| `foto`, `atk`, `fotoboks` | `FOTOBOKS` (ATK-punkt). Strekningskameraer (`REL.FORELDER` → Streknings-ATK) importeres som `STREKNINGS_ATK` |
| `influens` | `STREKNINGS_ATK` (kun rader merket Streknings-ATK) |
| `bom`, `bomstasjon` | `BOM` (takst liten bil i `verdi`) |
| `forkjor`, `forkjør` | `FORKJOERSVEI` |
| `vilt`, `elg`, `hjort` | `VILTFARE` |
| `jernbane`, `planovergang` | `JERNBANE` (kun «I plan*») |
| `ferje` | `FERJEKAI` (hopper over nedlagt) |
| `skiltplate` + `stopp` | `STOPP` |
| `skiltplate` + `100.*`/`102.*` | `FARLIG_SVING` |
| `skiltplate` + `106.*` | `SMALERE_VEG` |
| `skiltplate` + `122` | `TUNNEL` |
| `skiltplate` + `124` / `farligvegkryss` | `FARLIG_VEGKRYSS` |
| `skiltplate` + `vikeplikt` | `VIKEPLIKT` |
| `skiltplate` + `208` / `forkjørsvegslutt` | `SLUTT_FORKJOERSVEI` |
| `skiltplate` + `fartsgrenseslutt` | `SLUTT_FART` (skilt 368, verdi `368`) |
| `skiltplate` + `20slutt`…`70slutt` | `SLUTT_FART` (skilt 364.XX, verdi er farten) |
| `kommune` | `KOMMUNE` (flate-geometri, varsel ved ny kommune) |

ATK-influensstrekning importeres som `STREKNINGS_ATK` når `EGS.TYPE TRAFIKKONTROLL` er Streknings-ATK; vanlige punkt-ATK-soner i samme fil hoppes over. ATK-punkt (162) som hører til en slik strekning (`REL.FORELDER` = `775:<id>`) importeres som `STREKNINGS_ATK` ved kameraet, ikke som `FOTOBOKS` (350 m). Appen varsler strekningsmåling på skiltet.

Viltfare (NVDB 72) importeres som **strekning**: polylinjen pakkes, og appen varsler ved innkjøring i `LOK.RETNING` (`MED`/`MOT`). Uten ny import treffer appen bare midtpunktet og varsler nesten aldri.

Bom, jernbanekryssing og ferjekai pakkes også som strekning. Varselpunktet er innkjøringen (`MED` = start, `MOT` = slutt), og matching bruker nærmeste punkt på linjen — ikke centroid. Heading for `MED`/`MOT` hentes fra veinettet, fordi selve bommen/skinnene ofte ligger tvers på veien.

Eksempler: `Fartsgrense_105-eksport OSLO.csv`, `ATK-punkt_162-eksport ATK-PUNKT.csv`, `Jernbanekryssing_100-eksport JERNBANE.csv`.

## Kolonner som gjenkjennes

- **Id:** `OBJ.VEGOBJEKT-ID` (og liknende)
- **Geometri:** `GEO.GEOMETRI`, `LOK.GEOMETRI` (WKT POINT/LINESTRING, gjerne med Z)
- **Retning:** `LOK.RETNING` (`MED`/`MOT`) brukes på alle NVDB-objekt som har feltet: fareskilt (100/102/106/122/124), vikeplikt/stopp/208, ATK, bom, viltfare, jernbane, ferje, fartsgrense og forkjørsvei. `vegRetningGrader` kommer fra LINESTRING, eller (for punkt-skilt) fra nærmeste fartsgrense-/forkjørsvei-segment. Mangler retning, varsles begge veier.

Fyll heading på en eksisterende database uten å lese CSV på nytt:

```bash
python scripts/import_vegdata.py --fill-retning
```
- **Fart:** `EGS.FARTSGRENSE (KM/H).*`
- **Vilt:** `EGS.ART.*` (`Elg`, `Hjort`, `Rein`, `Rådyr`)
- **Ferje / streknings-ATK:** `EGS.NAVN.*`
- **Kommune:** `EGS.KOMMUNENAVN.*` (flate i `GEO.GEOMETRI` / `EGS.GEOMETRI, FLATE.*`)
- **Jernbane:** `EGS.TYPE.*`

## Room-hash

`ROOM_IDENTITY_HASH` i skriptet må matche `identityHash` i `app/schemas/no.roadnotifications.data.VegDatabase/2.json` etter compile. Oppdater konstanten hvis `VegObjektEntity` endres.

## Veinett (OSM / GraphHopper)

`roadgraph.db` er det offline veinettet appen snapper GPS mot.

1. Last ned [norway-latest.osm.pbf](https://download.geofabrik.de/europe/norway.html) til `scripts/csv/`.
2. Kjør:

```bash
./gradlew :importer:run
```

Skriver `app/src/main/assets/roadgraph.db`. Første kjøring av hele Norge tar gjerne 10–30 minutter og bruker flere GB RAM. GraphHopper-cachen i `scripts/graphhopper-cache/` gjør senere eksport raskere.

