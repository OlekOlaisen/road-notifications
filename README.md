# Vegassistent (road-notifications)

Offline Norwegian road-object alerts for Android phones and **Android Auto**.

While you drive, the app watches GPS against a local SQLite database of NVDB road objects and posts heads-up notifications for nearby signs and hazards — including on the car screen when Android Auto is connected.

## Screenshots

Heads-up alerts on Android Auto:

### Speed limit

![Speed limit 50 on Android Auto](docs/screenshots/android-auto-fartsgrense-50.jpg)

### Wildlife

![Wildlife (moose) on Android Auto](docs/screenshots/android-auto-viltfare-elg.jpg)

### Toll station

![Toll station on Android Auto](docs/screenshots/android-auto-bomstasjon.jpg)

### Stop sign

![Stop sign on Android Auto](docs/screenshots/android-auto-stopp.jpg)

## Features

- **Offline alerts** — no network required while driving; data ships in the APK (`vegdata.db`)
- **Android Auto heads-up** — MessagingStyle + Car App Library so alerts can appear over Maps
- **Travel-path matching** — prefers objects ahead on your path, not every nearby side street
- **Toggle categories** — enable/disable alert types (speed limits, priority road, speed cameras, wildlife, and more)
- **Test tab** — fire sample notifications without driving
- **Official sign artwork** — Norwegian trafikkskilt-style icons in notifications

### Alert types

| Type | Examples |
| --- | --- |
| Speed limits | 30–110 km/h |
| Priority road | Forkjørsvei / end of forkjørsvei |
| Speed cameras | Fotoboks (ATK) |
| Tolls | Bomstasjon (with price when available) |
| Wildlife | Elg, hjort, rein, rådyr |
| Rail crossings | Planovergang |
| Ferry | Ferjekai |
| Signs | Stop, sharp curves, road narrowing, tunnel |

## How it works

1. **Tracking** starts when you open the app (foreground location service).
2. Each GPS update queries nearby objects in Room/SQLite.
3. Candidates are filtered by distance, heading, and (when available) NVDB travel direction (`MED`/`MOT`).
4. Matching objects become phone + Auto notifications; preferences from the **Varsler** tab are respected.

Data is imported from NVDB / Vegkart CSV exports into `app/src/main/assets/vegdata.db`. See [`scripts/README.md`](scripts/README.md) for import details.

## Download

Pre-built release APKs are published on [GitHub Releases](https://github.com/OlekOlaisen/road-notifications/releases).

1. Open the latest release and download `app-release.apk`.
2. On your phone, allow **Install unknown apps** for your browser or file manager.
3. Open the APK to install. For updates, install over the existing app (same signing key).

For **Android Auto**, also enable developer settings and allow unknown sources for sideloaded apps (see below).

## Requirements

- Android **8.0+** (API 26)
- Location permission (fine + background recommended)
- Notification permission (Android 13+)
- For Auto: developer mode + **Unknown sources** for sideloaded apps

## Build

### Release (for distribution)

1. Copy `keystore.properties.example` to `keystore.properties` and set your keystore passwords.
2. Create a release keystore (once):

```bash
keytool -genkey -v -keystore vegassistent-release.keystore -alias vegassistent -keyalg RSA -keysize 2048 -validity 10000
```

3. Build and upload to GitHub Releases:

```bash
./gradlew :app:assembleRelease
gh release create v1.0.0 app/build/outputs/apk/release/app-release.apk --title "Vegassistent 1.0" --notes "First public release"
```

Output: `app/build/outputs/apk/release/app-release.apk`

> **Important:** Back up `vegassistent-release.keystore` and `keystore.properties`. You need the same key for all future updates. These files are gitignored and never committed.

### Debug (local testing)

```bash
./gradlew :app:assembleDebug
```

Install the debug APK on a device or emulator. After replacing `vegdata.db`, reinstall the app or clear app data so the new asset is used.

JDK **17** is required.

## Android Auto (local install)

This project is intended for **local / sideloaded** use:

1. Enable Android Auto developer settings on the phone.
2. Allow unknown sources for Android Auto.
3. Connect to the car (or Desktop Head Unit) and grant location/notification permissions.

Alerts use the messaging/car notification path so they can show as heads-up while navigating.

## Project layout

```
app/                 Android app (Compose UI, tracking service, Auto)
scripts/             NVDB CSV → SQLite import and sign SVG conversion
app/src/main/assets/ vegdata.db (bundled offline database)
```

## Data import

Place NVDB CSV files in `scripts/csv/`, then:

```bash
# Full rebuild of vegdata.db
python scripts/import_vegdata.py

# Update only selected types
python scripts/import_vegdata.py --only BOM,FORKJOERSVEI
```

Uses the Python standard library only. Filename patterns and columns are documented in [`scripts/README.md`](scripts/README.md).

> **Note:** `vegdata.db` is large (~80 MB). GitHub accepts it but warns above the 50 MB soft recommendation; Git LFS is optional if you prefer.

## Privacy

- Location is used **on-device** for matching road objects.
- No analytics or cloud sync is required for core alerting.

## License

No license file is included yet. Add one if you intend others to reuse the code or data packaging.
