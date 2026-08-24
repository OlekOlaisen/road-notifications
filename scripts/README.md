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
| `foto`, `atk`, `fotoboks` | `FOTOBOKS` |
| `bom`, `bomstasjon` | `BOM` (takst liten bil i `verdi`) |
| `forkjor`, `forkjør` | `FORKJOERSVEI` |
| `vilt`, `elg`, `hjort` | `VILTFARE` |
| `jernbane`, `planovergang` | `JERNBANE` (kun «I plan*») |
| `ferje` | `FERJEKAI` (hopper over nedlagt) |
| `skiltplate` + `stopp` | `STOPP` |
| `skiltplate` + `100.*`/`102.*` | `FARLIG_SVING` |
| `skiltplate` + `106.*` | `SMALERE_VEG` |
| `skiltplate` + `122` | `TUNNEL` |
| `skiltplate` + `208` | `SLUTT_FORKJOERSVEI` |

`Kommune_*.csv` og `*influens*` hoppes over (kommuner er ikke vegobjekter; ATK-influensstrekning dekkes av ATK-punkt).

Eksempler: `Fartsgrense_105-eksport OSLO.csv`, `ATK-punkt_162-eksport ATK-PUNKT.csv`, `Jernbanekryssing_100-eksport JERNBANE.csv`.

## Kolonner som gjenkjennes

- **Id:** `OBJ.VEGOBJEKT-ID` (og liknende)
- **Geometri:** `GEO.GEOMETRI`, `LOK.GEOMETRI` (WKT POINT/LINESTRING, gjerne med Z)
- **Retning:** `LOK.RETNING` (`MED`/`MOT`); `vegRetningGrader` fra LINESTRING (MET-retning)
- **Fart:** `EGS.FARTSGRENSE (KM/H).*`
- **Vilt:** `EGS.ART.*` (`Elg`, `Hjort`, `Rein`, `Rådyr`)
- **Ferje:** `EGS.NAVN.*`
- **Jernbane:** `EGS.TYPE.*`

## Room-hash

`ROOM_IDENTITY_HASH` i skriptet må matche `identityHash` i `app/schemas/no.roadnotifications.data.VegDatabase/1.json` etter compile. Oppdater konstanten hvis `VegObjektEntity` endres.
