"""Fetch OSRM driving geometries used as GPS replay fixtures.

Recorded tracks (not fetched here):
  oslo_ostby_ulstrud.txt — trip-log-9, Østbyveien–Ulsrud–Skullerud–Enebakkveien
"""

import json
import pathlib
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "app" / "src" / "test" / "resources" / "gps"

ROUTES = (
    (
        "oslo_ring2.txt",
        "10.7185,59.9293;10.776,59.926",
        "Oslo Ring 2 / Kirkeveien (east of Majorstuen to Carl Berners plass)",
        50,
    ),
    (
        "e6_jessheim_grua.txt",
        "10.708,60.185;10.6984,60.2151;10.6870,60.2451;10.655,60.268",
        "E6 Harestua to Grua (section ATK)",
        80,
    ),
    (
        "rv7_hol_curves.txt",
        "8.4998,60.3344;8.3977,60.4425",
        "Rv7 Hol (winding FARLIG_SVING corridor)",
        60,
    ),
)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for filename, coords, name, speed in ROUTES:
        url = (
            "https://router.project-osrm.org/route/v1/driving/"
            f"{coords}?overview=full&geometries=geojson"
        )
        with urllib.request.urlopen(url, timeout=30) as response:
            payload = json.loads(response.read().decode("utf-8"))
        route = payload["routes"][0]
        lines = [
            f"# name: {name}",
            f"# fallbackSpeedKmh: {speed}",
            f"# osrmDistanceMeters: {route['distance']}",
            f"# osrmDurationSeconds: {route['duration']}",
        ]
        for longitude, latitude in route["geometry"]["coordinates"]:
            lines.append(f"{latitude:.6f},{longitude:.6f}")
        path = OUT_DIR / filename
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(
            filename,
            "points",
            len(route["geometry"]["coordinates"]),
            "distance",
            route["distance"],
        )


if __name__ == "__main__":
    main()
