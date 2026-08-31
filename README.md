# Vegassistent

Varsel om skilt og faremomenter mens du kjører, på telefonen og i **Android Auto**. Alt skjer på enheten — du trenger ikke nett underveis.

Appen følger GPS mot et kart over norske vegobjekter (NVDB) og viser heads-up når noe ligger foran deg på vegen du kjører.

## Skjermbilder

Heads-up i Android Auto:

### Fartsgrense

![Fartsgrense 50 i Android Auto](docs/screenshots/android-auto-fartsgrense-50.jpg)

### Viltfare

![Viltfare (elg) i Android Auto](docs/screenshots/android-auto-viltfare-elg.jpg)

### Bomstasjon

![Bomstasjon i Android Auto](docs/screenshots/android-auto-bomstasjon.jpg)

### Stopp

![Stoppskilt i Android Auto](docs/screenshots/android-auto-stopp.jpg)

## Hva appen varsler om

Du kan slå typer av og på under **Varsler**.

| Type | Eksempler |
| --- | --- |
| Fartsgrense | 30–110 km/t |
| Forkjørsvei | Start og slutt |
| Fotoboks | Punkt og strekningsmåling |
| Bom | Bomstasjon, med pris når det finnes |
| Vilt | Elg, hjort, rein, rådyr |
| Jernbane | Planovergang |
| Ferje | Ferjekai |
| Skilt | Stopp, krappe svinger, innsnevring, tunnel |

Varslene følger vegen du er på, ikke sidegater ved siden av. I tunneler og under bruer holder appen seg på siste kjente veg i stedet for å hoppe med GPS.

## Last ned og installer

Last ned `app-release.apk` fra [siste utgivelse](https://github.com/OlekOlaisen/road-notifications/releases). Filen er ca. **1,4 GB** fordi kart og skilt for hele landet ligger i appen.

1. Tillat **Installere ukjente apper** for nettleseren eller filbehandleren.
2. Åpne APK-filen og installer.
3. Gi tillatelse til **posisjon** og **varsler**.

Første oppstart kopierer kartdata til telefonen — ha flere GB ledig plass. Oppdateringer: installer den nye APK-en over den du har.

Krever Android 8.0 eller nyere.

## Android Auto

Appen er sideloadet, så Android Auto må tillate ukjente kilder:

1. Åpne Android Auto-innstillingene på telefonen.
2. Slå på utviklerinnstillinger (trykk gjentatte ganger på versjonsnummeret).
3. Tillat ukjente kilder.
4. Koble til bilen og gi posisjon- og varslingstilgang.

Varslene kan da vises som heads-up over kartet mens du navigerer.

## Personvern

Posisjon brukes bare på telefonen for å treffe skilt og veg. Appen trenger ikke konto, analyse eller sky for å varsle.
