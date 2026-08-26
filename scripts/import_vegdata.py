#!/usr/bin/env python3
"""Import NVDB/Vegkart CSV files into app/src/main/assets/vegdata.db.

NVDB exports typically use EUREF89 UTM zone 33 (EPSG:25833) in GEO.GEOMETRI.
This script converts coordinates to WGS84 lat/lon for GPS matching in the app.
"""

from __future__ import annotations

import csv
import math
import re
import sqlite3
import struct
import sys
import unicodedata
from pathlib import Path

ROOM_IDENTITY_HASH = "97938522629ed225cf246da686482065"
ROOM_DATABASE_VERSION = 2

ROOT = Path(__file__).resolve().parents[1]
CSV_DIR = ROOT / "scripts" / "csv"
OUTPUT_DB = ROOT / "app" / "src" / "main" / "assets" / "vegdata.db"

CREATE_VEGOBJEKT = (
    "CREATE TABLE IF NOT EXISTS `vegobjekt` (`id` INTEGER NOT NULL, `type` TEXT NOT NULL, "
    "`verdi` TEXT, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `minLat` REAL NOT NULL, "
    "`maxLat` REAL NOT NULL, `minLon` REAL NOT NULL, `maxLon` REAL NOT NULL, "
    "`retning` TEXT, `vegRetningGrader` REAL, `points` BLOB, PRIMARY KEY(`id`))"
)

CREATE_VEGOBJEKT_RTREE = (
    "CREATE VIRTUAL TABLE IF NOT EXISTS vegobjekt_rtree USING rtree("
    "id, minLat, maxLat, minLon, maxLon)"
)

CREATE_VEGOBJEKT_SEG = (
    "CREATE TABLE IF NOT EXISTS vegobjekt_seg ("
    "segId INTEGER PRIMARY KEY, objektId INTEGER NOT NULL)"
)

CREATE_VEGOBJEKT_SEG_RTREE = (
    "CREATE VIRTUAL TABLE IF NOT EXISTS vegobjekt_seg_rtree USING rtree("
    "segId, minLat, maxLat, minLon, maxLon)"
)

ID_COLUMNS = ("vegobjekt-id", "vegobjektid", "vegobjekt_id", "nvdbid", "id")
LAT_COLUMNS = ("lat", "latitude", "breddegrad", "bredde")
LON_COLUMNS = ("lon", "lng", "longitude", "lengdegrad", "lengde")
WKT_COLUMNS = (
    "geo.geometri",
    "lok.geometri",
    "geometri, punkt",
    "geometri, linje",
    "geometri, flate",
    "wkt",
    "geometri",
    "geometry",
)
FART_COLUMNS = ("fartsgrense", "fart")
WILDLIFE_COLUMNS = ("dyreart", "egs.art", "art")
BOM_PRICE_COLUMNS = (
    "takst liten bil",
    "takstlitenbil",
    "egs.takst liten bil",
)
RETNING_COLUMNS = ("lok.retning",)
FERJE_NAME_COLUMNS = ("egs.navn", "navn")
JERNBANE_TYPE_COLUMNS = ("egs.type",)
ATK_CONTROL_TYPE_COLUMNS = ("type trafikkontroll", "typetrafikkontroll")
FERJE_STATUS_COLUMNS = ("egs.driftsstatus", "driftsstatus")
SKILTNUMMER_COLUMNS = ("egs.skiltnummer", "skiltnummer")

SKILT_TYPES = (
    "STOPP",
    "FARLIG_SVING",
    "SMALERE_VEG",
    "TUNNEL",
    "SLUTT_FORKJOERSVEI",
    "VIKEPLIKT",
    "FARLIG_VEGKRYSS",
)

csv.field_size_limit(min(sys.maxsize, 50_000_000))


def normalize_key(name: str) -> str:
    folded = unicodedata.normalize("NFKD", name.strip().lower())
    folded = "".join(char for char in folded if not unicodedata.combining(char))
    return (
        folded.replace("ø", "o")
        .replace("æ", "ae")
        .replace("å", "a")
        .replace(" ", "")
        .replace("(", "")
        .replace(")", "")
        .replace("/", "")
    )


def detect_type(filename: str) -> str | None:
    normalized = normalize_key(filename)
    if "kommune" in normalized:
        return None
    if "influens" in normalized:
        return "STREKNINGS_ATK"
    if "skiltplate" in normalized:
        return detect_skiltplate_type(normalized)
    if any(token in normalized for token in ("jernbane", "planovergang")):
        return "JERNBANE"
    if "ferje" in normalized:
        return "FERJEKAI"
    if any(token in normalized for token in ("vilt", "elg", "hjort")):
        return "VILTFARE"
    if any(token in normalized for token in ("foto", "atk", "fotoboks")):
        return "FOTOBOKS"
    if "bom" in normalized:
        return "BOM"
    if "forkjor" in normalized:
        return "FORKJOERSVEI"
    if "fart" in normalized:
        return "FART"
    return None


def detect_skiltplate_type(normalized_filename: str) -> str | None:
    if "stopp" in normalized_filename:
        return "STOPP"
    if "vikeplikt" in normalized_filename:
        return "VIKEPLIKT"
    if "farligvegkryss" in normalized_filename:
        return "FARLIG_VEGKRYSS"
    if "forkjorsvegslutt" in normalized_filename or (
        "slutt" in normalized_filename and "forkjor" in normalized_filename
    ):
        return "SLUTT_FORKJOERSVEI"
    if any(
        token in normalized_filename
        for token in ("100.1", "100.2", "102.1", "102.2")
    ):
        return "FARLIG_SVING"
    if any(token in normalized_filename for token in ("106.1", "106.2", "106.3")):
        return "SMALERE_VEG"
    if "208" in normalized_filename:
        return "SLUTT_FORKJOERSVEI"
    if "206" in normalized_filename:
        return "FORKJOERSVEI"
    if "124" in normalized_filename:
        return "FARLIG_VEGKRYSS"
    if "122" in normalized_filename:
        return "TUNNEL"
    return None


def skiltnummer_code(raw: str) -> str | None:
    text = raw.strip()
    if not text:
        return None
    code = text.split("-", 1)[0].strip()
    return code or None


def find_column(columns: list[str], candidates: tuple[str, ...]) -> str | None:
    normalized_pairs = [(normalize_key(column), column) for column in columns]
    for candidate in candidates:
        candidate_key = normalize_key(candidate)
        for normalized_name, original in normalized_pairs:
            if normalized_name == candidate_key:
                return original
            if normalized_name.endswith("." + candidate_key):
                return original
            if len(candidate_key) >= 4 and candidate_key in normalized_name:
                return original
    for candidate in sorted(candidates, key=len, reverse=True):
        candidate_key = normalize_key(candidate)
        if len(candidate_key) < 4:
            padded = f".{candidate_key}."
            for normalized_name, original in normalized_pairs:
                if padded in f".{normalized_name}.":
                    return original
            continue
        for normalized_name, original in normalized_pairs:
            if candidate_key in normalized_name:
                return original
    return None


def utm33_to_wgs84(easting: float, northing: float) -> tuple[float, float]:
    k0 = 0.9996
    equatorial_radius = 6_378_137.0
    eccentricity = 0.081819190842622
    eccentricity_prime_squared = 0.00673949674228
    x_offset = easting - 500_000.0
    y_offset = northing
    longitude_origin_degrees = 15.0

    meridian_arc = y_offset / k0
    mu = meridian_arc / (
        equatorial_radius
        * (1 - eccentricity**2 / 4 - 3 * eccentricity**4 / 64 - 5 * eccentricity**6 / 256)
    )
    e1 = (1 - math.sqrt(1 - eccentricity**2)) / (1 + math.sqrt(1 - eccentricity**2))
    footprint_latitude = (
        mu
        + (3 * e1 / 2 - 27 * e1**3 / 32) * math.sin(2 * mu)
        + (21 * e1**2 / 16 - 55 * e1**4 / 32) * math.sin(4 * mu)
        + (151 * e1**3 / 96) * math.sin(6 * mu)
        + (1097 * e1**4 / 512) * math.sin(8 * mu)
    )

    sin_footprint = math.sin(footprint_latitude)
    cos_footprint = math.cos(footprint_latitude)
    tan_footprint = math.tan(footprint_latitude)
    n1 = equatorial_radius / math.sqrt(1 - (eccentricity * sin_footprint) ** 2)
    t1 = tan_footprint**2
    c1 = eccentricity_prime_squared * cos_footprint**2
    r1 = (
        equatorial_radius
        * (1 - eccentricity**2)
        / ((1 - (eccentricity * sin_footprint) ** 2) ** 1.5)
    )
    d = x_offset / (n1 * k0)

    latitude_radians = footprint_latitude - (n1 * tan_footprint / r1) * (
        d**2 / 2
        - (5 + 3 * t1 + 10 * c1 - 4 * c1**2 - 9 * eccentricity_prime_squared) * d**4 / 24
        + (61 + 90 * t1 + 298 * c1 + 45 * t1**2 - 252 * eccentricity_prime_squared - 3 * c1**2)
        * d**6
        / 720
    )
    longitude_radians = math.radians(longitude_origin_degrees) + (
        d
        - (1 + 2 * t1 + c1) * d**3 / 6
        + (5 - 2 * c1 + 28 * t1 - 3 * c1**2 + 8 * eccentricity_prime_squared + 24 * t1**2)
        * d**5
        / 120
    ) / cos_footprint
    return math.degrees(latitude_radians), math.degrees(longitude_radians)


def looks_like_utm(x_value: float, y_value: float) -> bool:
    return abs(x_value) > 180 or abs(y_value) > 90


def parse_wkt_projected_points(wkt: str) -> list[tuple[float, float]]:
    text = wkt.strip()
    if not text:
        return []
    open_index = text.find("(")
    close_index = text.rfind(")")
    if open_index < 0 or close_index < 0:
        return []
    body = text[open_index + 1 : close_index].replace("(", " ").replace(")", " ")
    points: list[tuple[float, float]] = []
    for part in body.split(","):
        tokens = part.strip().split()
        if len(tokens) < 2:
            continue
        try:
            easting = float(tokens[0].replace(",", "."))
            northing = float(tokens[1].replace(",", "."))
        except ValueError:
            continue
        points.append((easting, northing))
    return points


def sample_projected_points(points: list[tuple[float, float]]) -> list[tuple[float, float]]:
    if len(points) <= 80:
        return points
    step = max(1, len(points) // 80)
    sampled = points[::step]
    if sampled[-1] != points[-1]:
        sampled.append(points[-1])
    return sampled


def resample_projected_points(
    points: list[tuple[float, float]],
    spacing_meters: float = 20.0,
    max_points: int = 64,
) -> list[tuple[float, float]]:
    if len(points) < 2:
        return points
    sampled = [points[0]]
    for candidate in points[1:]:
        last = sampled[-1]
        distance = math.hypot(candidate[0] - last[0], candidate[1] - last[1])
        if distance >= spacing_meters:
            sampled.append(candidate)
    if sampled[-1] != points[-1]:
        sampled.append(points[-1])
    if len(sampled) <= max_points:
        return sampled
    step = (len(sampled) - 1) / (max_points - 1)
    reduced = [sampled[int(index * step)] for index in range(max_points - 1)]
    reduced.append(sampled[-1])
    return reduced


def compass_bearing_degrees(
    from_easting: float,
    from_northing: float,
    to_easting: float,
    to_northing: float,
) -> float | None:
    delta_easting = to_easting - from_easting
    delta_northing = to_northing - from_northing
    if abs(delta_easting) < 1e-6 and abs(delta_northing) < 1e-6:
        return None
    bearing = math.degrees(math.atan2(delta_easting, delta_northing)) % 360.0
    return bearing


def veg_retning_from_projected_points(
    points: list[tuple[float, float]],
) -> float | None:
    if len(points) < 2:
        return None
    start_easting, start_northing = points[0]
    end_easting, end_northing = points[-1]
    span = math.hypot(end_easting - start_easting, end_northing - start_northing)
    if span < 5.0:
        for index in range(1, len(points)):
            candidate = compass_bearing_degrees(
                start_easting,
                start_northing,
                points[index][0],
                points[index][1],
            )
            if candidate is not None:
                segment = math.hypot(
                    points[index][0] - start_easting,
                    points[index][1] - start_northing,
                )
                if segment >= 5.0:
                    return candidate
        return compass_bearing_degrees(
            start_easting,
            start_northing,
            end_easting,
            end_northing,
        )
    return compass_bearing_degrees(
        start_easting,
        start_northing,
        end_easting,
        end_northing,
    )


def geometry_from_row(
    row: dict[str, str],
    columns: list[str],
) -> dict[str, float | None] | None:
    lat_column = find_column(columns, LAT_COLUMNS)
    lon_column = find_column(columns, LON_COLUMNS)
    if lat_column and lon_column:
        lat_text = str(row.get(lat_column, "")).strip()
        lon_text = str(row.get(lon_column, "")).strip()
        if lat_text and lon_text:
            try:
                latitude = float(lat_text.replace(",", "."))
                longitude = float(lon_text.replace(",", "."))
            except ValueError:
                return None
            if looks_like_utm(longitude, latitude):
                latitude, longitude = utm33_to_wgs84(longitude, latitude)
            return {
                "start_lat": latitude,
                "start_lon": longitude,
                "end_lat": latitude,
                "end_lon": longitude,
                "centroid_lat": latitude,
                "centroid_lon": longitude,
                "min_lat": latitude,
                "max_lat": latitude,
                "min_lon": longitude,
                "max_lon": longitude,
                "veg_retning_grader": None,
                "points": [(latitude, longitude)],
            }

    for wkt_name in WKT_COLUMNS:
        column = find_column(columns, (wkt_name,))
        if column is None:
            continue
        projected_points = resample_projected_points(
            sample_projected_points(
                parse_wkt_projected_points(str(row.get(column, "")))
            )
        )
        if not projected_points:
            continue
        latitudes: list[float] = []
        longitudes: list[float] = []
        for x_value, y_value in projected_points:
            if looks_like_utm(x_value, y_value):
                latitude, longitude = utm33_to_wgs84(x_value, y_value)
            else:
                longitude, latitude = x_value, y_value
            latitudes.append(latitude)
            longitudes.append(longitude)
        veg_retning = veg_retning_from_projected_points(projected_points)
        return {
            "start_lat": latitudes[0],
            "start_lon": longitudes[0],
            "end_lat": latitudes[-1],
            "end_lon": longitudes[-1],
            "centroid_lat": sum(latitudes) / len(latitudes),
            "centroid_lon": sum(longitudes) / len(longitudes),
            "min_lat": min(latitudes),
            "max_lat": max(latitudes),
            "min_lon": min(longitudes),
            "max_lon": max(longitudes),
            "veg_retning_grader": veg_retning,
            "points": list(zip(latitudes, longitudes)),
        }
    return None


def alert_coordinates(
    objekt_type: str,
    retning: str | None,
    geometry: dict[str, float | None],
) -> tuple[float, float]:
    """
    Forkjørsvei and fartsgrense stretches can be kilometers long. Alert at the
    entrance for the relevant travel direction instead of the geometric midpoint.
    """
    if objekt_type in {"FORKJOERSVEI", "SLUTT_FORKJOERSVEI", "FART", "STREKNINGS_ATK"}:
        if (retning or "").upper() == "MOT":
            return float(geometry["end_lat"]), float(geometry["end_lon"])
        return float(geometry["start_lat"]), float(geometry["start_lon"])
    return float(geometry["centroid_lat"]), float(geometry["centroid_lon"])


def pack_stretch_points(
    objekt_type: str,
    geometry: dict[str, float | None],
) -> bytes | None:
    if objekt_type not in {"FART", "FORKJOERSVEI", "STREKNINGS_ATK"}:
        return None
    raw_points = geometry.get("points")
    if not isinstance(raw_points, list) or len(raw_points) < 2:
        return None
    packed = struct.pack("<I", len(raw_points))
    for latitude, longitude in raw_points:
        packed += struct.pack(
            "<ii",
            int(round(float(latitude) * 1_000_000.0)),
            int(round(float(longitude) * 1_000_000.0)),
        )
    return packed


def retning_from_row(row: dict[str, str], columns: list[str]) -> str | None:
    column = find_column(columns, RETNING_COLUMNS)
    if column is None:
        return None
    raw = str(row.get(column, "")).strip().upper()
    if raw in {"MED", "MOT"}:
        return raw
    return None


def should_keep_row(
    row: dict[str, str],
    columns: list[str],
    objekt_type: str,
) -> bool:
    if objekt_type == "JERNBANE":
        column = find_column(columns, JERNBANE_TYPE_COLUMNS)
        if column is None:
            return True
        raw = str(row.get(column, "")).strip().lower()
        return raw.startswith("i plan")
    if objekt_type == "FERJEKAI":
        column = find_column(columns, FERJE_STATUS_COLUMNS)
        if column is None:
            return True
        raw = str(row.get(column, "")).strip().lower()
        return raw != "nedlagt"
    if objekt_type == "STREKNINGS_ATK":
        column = find_column(columns, ATK_CONTROL_TYPE_COLUMNS)
        if column is None:
            return False
        raw = normalize_key(str(row.get(column, ""))).replace("-", "")
        return "strekningsatk" in raw
    return True


def normalize_wildlife_value(raw: str) -> str:
    upper = unicodedata.normalize("NFKD", raw.strip().upper())
    upper = "".join(char for char in upper if not unicodedata.combining(char))
    upper = upper.replace("Ø", "O").replace("Æ", "AE").replace("Å", "A")
    compact = upper.replace(" ", "")
    if "ELG" in compact:
        return "ELG"
    if "HJORT" in compact:
        return "HJORT"
    if "REIN" in compact:
        return "REIN"
    if "RADYR" in compact or "RADYR" in compact:
        return "RADYR"
    if compact.startswith("R") and "DYR" in compact:
        return "RADYR"
    return compact or "VILT"


def value_for_type(
    row: dict[str, str],
    columns: list[str],
    objekt_type: str,
    filename: str,
) -> str | None:
    if objekt_type == "FART":
        column = find_column(columns, FART_COLUMNS)
        if column:
            raw = str(row.get(column, "")).strip()
            return raw or None
        return None
    if objekt_type == "BOM":
        column = find_column(columns, BOM_PRICE_COLUMNS)
        if column:
            raw = str(row.get(column, "")).strip().replace(",", ".")
            if not raw:
                return None
            try:
                price = float(raw)
            except ValueError:
                return raw
            if price.is_integer():
                return str(int(price))
            return f"{price:g}"
        return None
    if objekt_type == "VILTFARE":
        column = find_column(columns, WILDLIFE_COLUMNS)
        if column:
            raw = str(row.get(column, "")).strip()
            if raw:
                return normalize_wildlife_value(raw)
        normalized_name = normalize_key(filename)
        if "elg" in normalized_name:
            return "ELG"
        if "hjort" in normalized_name:
            return "HJORT"
        return "VILT"
    if objekt_type == "FERJEKAI":
        column = find_column(columns, FERJE_NAME_COLUMNS)
        if column:
            raw = str(row.get(column, "")).strip()
            return raw or None
        return None
    if objekt_type == "STREKNINGS_ATK":
        column = find_column(columns, FERJE_NAME_COLUMNS)
        if column:
            raw = str(row.get(column, "")).strip()
            return raw or None
        return None
    if objekt_type == "JERNBANE":
        column = find_column(columns, JERNBANE_TYPE_COLUMNS)
        if column:
            raw = str(row.get(column, "")).strip()
            return raw or None
        return None
    if objekt_type in SKILT_TYPES:
        column = find_column(columns, SKILTNUMMER_COLUMNS)
        if column:
            raw = str(row.get(column, "")).strip()
            code = skiltnummer_code(raw)
            if code:
                return code
        return skiltnummer_from_filename(filename)
    return None


def skiltnummer_from_filename(filename: str) -> str | None:
    normalized = normalize_key(filename)
    for code in (
        "100.1",
        "100.2",
        "102.1",
        "102.2",
        "106.1",
        "106.2",
        "106.3",
        "122",
        "124",
        "202",
        "208",
        "206",
        "204",
    ):
        if code in normalized:
            return code
    if "stopp" in normalized:
        return "204"
    return None


def parse_id(row: dict[str, str], columns: list[str]) -> int | None:
    column = find_column(columns, ID_COLUMNS)
    if column is None:
        return None
    raw = str(row.get(column, "")).strip()
    if not raw:
        return None
    digits = re.sub(r"[^\d]", "", raw.split(".")[0])
    if not digits:
        return None
    return int(digits)


FORKJOERSVEI_EXTRA_ROW_FLAG = 1 << 62
FORKJOERSVEI_NVDB_ID_BITS = 46
FORKJOERSVEI_OCCURRENCE_BITS = 16


def forkjoersvei_row_id(nvdb_id: int, occurrence: int) -> int:
    """Keep every NVDB 596 linestring. Extra rows for the same id get a unique PK."""
    if occurrence <= 0:
        return nvdb_id
    max_nvdb_id = 1 << FORKJOERSVEI_NVDB_ID_BITS
    max_occurrence = 1 << FORKJOERSVEI_OCCURRENCE_BITS
    if nvdb_id >= max_nvdb_id or occurrence >= max_occurrence:
        raise ValueError(
            f"Kan ikke lage unik forkjørsvei-id for nvdb_id={nvdb_id} occurrence={occurrence}"
        )
    return (
        FORKJOERSVEI_EXTRA_ROW_FLAG
        | ((nvdb_id & (max_nvdb_id - 1)) << FORKJOERSVEI_OCCURRENCE_BITS)
        | occurrence
    )


def detect_separator(path: Path) -> str:
    sample = path.read_text(encoding="utf-8-sig", errors="replace")[:8192]
    try:
        dialect = csv.Sniffer().sniff(sample, delimiters=";,|\t")
        return dialect.delimiter
    except csv.Error:
        return ";" if sample.count(";") >= sample.count(",") else ","


def import_csv(
    path: Path,
    connection: sqlite3.Connection,
    forkjoersvei_occurrences: dict[int, int] | None = None,
) -> int:
    objekt_type = detect_type(path.name)
    if objekt_type is None:
        print(f"Hopper over ukjent fil: {path.name}")
        return 0
    if forkjoersvei_occurrences is None:
        forkjoersvei_occurrences = {}
    separator = detect_separator(path)
    inserted = 0
    skipped = 0
    with path.open(encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle, delimiter=separator)
        columns = [str(column) for column in (reader.fieldnames or [])]
        for row in reader:
            string_row = {
                str(key): "" if value is None else str(value)
                for key, value in row.items()
            }
            if not should_keep_row(string_row, columns, objekt_type):
                skipped += 1
                continue
            objekt_id = parse_id(string_row, columns)
            geometry = geometry_from_row(string_row, columns)
            if objekt_id is None or geometry is None:
                skipped += 1
                continue
            verdi = value_for_type(string_row, columns, objekt_type, path.name)
            retning = retning_from_row(string_row, columns)
            latitude, longitude = alert_coordinates(objekt_type, retning, geometry)
            points_blob = pack_stretch_points(objekt_type, geometry)
            row_id = objekt_id
            if objekt_type in {"FORKJOERSVEI", "STREKNINGS_ATK"}:
                occurrence = forkjoersvei_occurrences.get(objekt_id, 0)
                forkjoersvei_occurrences[objekt_id] = occurrence + 1
                row_id = forkjoersvei_row_id(objekt_id, occurrence)
            connection.execute(
                """
                INSERT OR REPLACE INTO vegobjekt
                (id, type, verdi, lat, lon, minLat, maxLat, minLon, maxLon, retning, vegRetningGrader, points)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    row_id,
                    objekt_type,
                    verdi,
                    latitude,
                    longitude,
                    geometry["min_lat"],
                    geometry["max_lat"],
                    geometry["min_lon"],
                    geometry["max_lon"],
                    retning,
                    geometry["veg_retning_grader"],
                    points_blob,
                ),
            )
            inserted += 1
            if inserted % 5_000 == 0:
                connection.commit()
                print(
                    f"{path.name}: {inserted} {objekt_type}-objekter importert, "
                    f"{skipped} rader hoppet over",
                    flush=True,
                )
    print(
        f"{path.name}: {inserted} {objekt_type}-objekter importert, "
        f"{skipped} rader hoppet over",
        flush=True,
    )
    return inserted


def create_database(connection: sqlite3.Connection) -> None:
    connection.execute(CREATE_VEGOBJEKT)
    connection.execute(CREATE_VEGOBJEKT_RTREE)
    connection.execute(CREATE_VEGOBJEKT_SEG)
    connection.execute(CREATE_VEGOBJEKT_SEG_RTREE)
    connection.execute(
        "CREATE INDEX IF NOT EXISTS `index_vegobjekt_lat_lon` ON `vegobjekt` (`lat`, `lon`)"
    )
    connection.execute(
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)"
    )
    connection.execute("DELETE FROM room_master_table")
    connection.execute(
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, ?)",
        (ROOM_IDENTITY_HASH,),
    )
    connection.execute(f"PRAGMA user_version = {ROOM_DATABASE_VERSION}")


def unpack_stretch_points(blob: bytes | None) -> list[tuple[float, float]]:
    if blob is None or len(blob) < 12:
        return []
    point_count = struct.unpack_from("<I", blob, 0)[0]
    if point_count < 2 or len(blob) < 4 + point_count * 8:
        return []
    points: list[tuple[float, float]] = []
    offset = 4
    for _ in range(point_count):
        latitude_e6, longitude_e6 = struct.unpack_from("<ii", blob, offset)
        points.append((latitude_e6 / 1_000_000.0, longitude_e6 / 1_000_000.0))
        offset += 8
    return points


def rebuild_rtree(connection: sqlite3.Connection) -> None:
    connection.execute("DROP TABLE IF EXISTS vegobjekt_rtree")
    connection.execute(CREATE_VEGOBJEKT_RTREE)
    connection.execute(
        """
        INSERT INTO vegobjekt_rtree(id, minLat, maxLat, minLon, maxLon)
        SELECT id, minLat, maxLat, minLon, maxLon FROM vegobjekt
        """
    )


def rebuild_segment_rtree(connection: sqlite3.Connection) -> None:
    """Index each polyline segment so mid-stretch lookup does not use huge bboxes."""
    connection.execute("DROP TABLE IF EXISTS vegobjekt_seg_rtree")
    connection.execute("DROP TABLE IF EXISTS vegobjekt_seg")
    connection.execute(CREATE_VEGOBJEKT_SEG)
    connection.execute(CREATE_VEGOBJEKT_SEG_RTREE)
    connection.execute(
        "CREATE INDEX IF NOT EXISTS index_vegobjekt_seg_objektId ON vegobjekt_seg(objektId)"
    )
    pad_degrees = 0.00025
    segment_id = 1
    segment_rows: list[tuple[int, int]] = []
    rtree_rows: list[tuple[int, float, float, float, float]] = []
    rows = connection.execute(
        "SELECT id, points FROM vegobjekt WHERE type IN ('FART', 'FORKJOERSVEI', 'STREKNINGS_ATK') AND points IS NOT NULL"
    )
    for objekt_id, blob in rows:
        points = unpack_stretch_points(blob)
        for index in range(len(points) - 1):
            start_latitude, start_longitude = points[index]
            end_latitude, end_longitude = points[index + 1]
            segment_rows.append((segment_id, objekt_id))
            rtree_rows.append(
                (
                    segment_id,
                    min(start_latitude, end_latitude) - pad_degrees,
                    max(start_latitude, end_latitude) + pad_degrees,
                    min(start_longitude, end_longitude) - pad_degrees,
                    max(start_longitude, end_longitude) + pad_degrees,
                )
            )
            segment_id += 1
            if len(segment_rows) >= 8_000:
                connection.executemany(
                    "INSERT INTO vegobjekt_seg(segId, objektId) VALUES (?, ?)",
                    segment_rows,
                )
                connection.executemany(
                    """
                    INSERT INTO vegobjekt_seg_rtree(segId, minLat, maxLat, minLon, maxLon)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    rtree_rows,
                )
                segment_rows.clear()
                rtree_rows.clear()
                connection.commit()
                print(f"Strekning-indeks: {segment_id - 1} segmenter", flush=True)
    if segment_rows:
        connection.executemany(
            "INSERT INTO vegobjekt_seg(segId, objektId) VALUES (?, ?)",
            segment_rows,
        )
        connection.executemany(
            """
            INSERT INTO vegobjekt_seg_rtree(segId, minLat, maxLat, minLon, maxLon)
            VALUES (?, ?, ?, ?, ?)
            """,
            rtree_rows,
        )
    print(f"Strekning-indeks ferdig: {segment_id - 1} segmenter", flush=True)


def append_stretch_index(connection: sqlite3.Connection, types: set[str]) -> None:
    """Add polyline segments for [types] without rebuilding FART/FORKJOERSVEI."""
    if not types:
        return
    connection.execute(CREATE_VEGOBJEKT_SEG)
    connection.execute(CREATE_VEGOBJEKT_SEG_RTREE)
    connection.execute(
        "CREATE INDEX IF NOT EXISTS index_vegobjekt_seg_objektId ON vegobjekt_seg(objektId)"
    )
    start_id_row = connection.execute(
        "SELECT COALESCE(MAX(segId), 0) FROM vegobjekt_seg"
    ).fetchone()
    segment_id = int(start_id_row[0] if start_id_row else 0) + 1
    pad_degrees = 0.00025
    segment_rows: list[tuple[int, int]] = []
    rtree_rows: list[tuple[int, float, float, float, float]] = []
    placeholders = ",".join("?" for _ in types)
    rows = connection.execute(
        f"SELECT id, points FROM vegobjekt WHERE type IN ({placeholders}) AND points IS NOT NULL",
        tuple(sorted(types)),
    )
    first_id = segment_id
    for objekt_id, blob in rows:
        points = unpack_stretch_points(blob)
        for index in range(len(points) - 1):
            start_latitude, start_longitude = points[index]
            end_latitude, end_longitude = points[index + 1]
            segment_rows.append((segment_id, objekt_id))
            rtree_rows.append(
                (
                    segment_id,
                    min(start_latitude, end_latitude) - pad_degrees,
                    max(start_latitude, end_latitude) + pad_degrees,
                    min(start_longitude, end_longitude) - pad_degrees,
                    max(start_longitude, end_longitude) + pad_degrees,
                )
            )
            segment_id += 1
            if len(segment_rows) >= 8_000:
                connection.executemany(
                    "INSERT INTO vegobjekt_seg(segId, objektId) VALUES (?, ?)",
                    segment_rows,
                )
                connection.executemany(
                    """
                    INSERT INTO vegobjekt_seg_rtree(segId, minLat, maxLat, minLon, maxLon)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    rtree_rows,
                )
                segment_rows.clear()
                rtree_rows.clear()
                connection.commit()
    if segment_rows:
        connection.executemany(
            "INSERT INTO vegobjekt_seg(segId, objektId) VALUES (?, ?)",
            segment_rows,
        )
        connection.executemany(
            """
            INSERT INTO vegobjekt_seg_rtree(segId, minLat, maxLat, minLon, maxLon)
            VALUES (?, ?, ?, ?, ?)
            """,
            rtree_rows,
        )
    print(
        f"Strekning-indeks utvidet: {segment_id - first_id} nye segmenter "
        f"({', '.join(sorted(types))})",
        flush=True,
    )


def delete_segments_for_types(connection: sqlite3.Connection, types: set[str]) -> None:
    placeholders = ",".join("?" for _ in types)
    objekt_ids = [
        row[0]
        for row in connection.execute(
            f"SELECT id FROM vegobjekt WHERE type IN ({placeholders})",
            tuple(sorted(types)),
        )
    ]
    if not objekt_ids:
        return
    table_exists = connection.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='vegobjekt_seg'"
    ).fetchone()
    if table_exists is None:
        return
    for offset in range(0, len(objekt_ids), 400):
        chunk = objekt_ids[offset : offset + 400]
        id_placeholders = ",".join("?" for _ in chunk)
        seg_ids = [
            row[0]
            for row in connection.execute(
                f"SELECT segId FROM vegobjekt_seg WHERE objektId IN ({id_placeholders})",
                chunk,
            )
        ]
        if not seg_ids:
            continue
        seg_placeholders = ",".join("?" for _ in seg_ids)
        connection.execute(
            f"DELETE FROM vegobjekt_seg WHERE segId IN ({seg_placeholders})",
            seg_ids,
        )
        connection.execute(
            f"DELETE FROM vegobjekt_seg_rtree WHERE segId IN ({seg_placeholders})",
            seg_ids,
        )


def main(argv: list[str] | None = None) -> int:
    import argparse
    import shutil
    import time

    parser = argparse.ArgumentParser(description="Importer NVDB-CSV til vegdata.db")
    parser.add_argument(
        "--only",
        metavar="TYPE",
        help="Oppdater kun angitte typer (kommaseparert), f.eks. STOPP,FARLIG_SVING",
    )
    parser.add_argument(
        "--segments-only",
        action="store_true",
        help="Bygg bare strekning-indeksen på eksisterende vegdata.db",
    )
    args = parser.parse_args(argv)
    if args.segments_only:
        if not OUTPUT_DB.exists():
            print(f"Fant ikke {OUTPUT_DB}. Kjør full import først.")
            return 1
        working_db = OUTPUT_DB.with_suffix(".import.db")
        shutil.copy2(OUTPUT_DB, working_db)
        connection = sqlite3.connect(working_db)
        try:
            rebuild_segment_rtree(connection)
            connection.commit()
        finally:
            connection.close()
        return replace_output_db(working_db)

    only_types: set[str] | None = None
    if args.only:
        only_types = {
            part.strip().upper()
            for part in args.only.split(",")
            if part.strip()
        }

    OUTPUT_DB.parent.mkdir(parents=True, exist_ok=True)
    working_db = OUTPUT_DB
    if only_types is None:
        working_db = OUTPUT_DB.with_suffix(".import.db")
        if working_db.exists():
            working_db.unlink()
        connection = sqlite3.connect(working_db)
        create_database(connection)
    else:
        if not OUTPUT_DB.exists():
            print(f"Fant ikke {OUTPUT_DB}. Kjør full import først, eller fjern --only.")
            return 1
        working_db = OUTPUT_DB.with_suffix(".import.db")
        shutil.copy2(OUTPUT_DB, working_db)
        connection = sqlite3.connect(working_db)
        placeholders = ",".join("?" for _ in only_types)
        delete_segments_for_types(connection, only_types)
        deleted = connection.execute(
            f"DELETE FROM vegobjekt WHERE type IN ({placeholders})",
            tuple(sorted(only_types)),
        ).rowcount
        print(f"Slettet {deleted} eksisterende rader for {', '.join(sorted(only_types))}.")

    try:
        csv_files = sorted(CSV_DIR.glob("*.csv"))
        if not csv_files:
            print(f"Ingen CSV-filer i {CSV_DIR}.")
        total = 0
        forkjoersvei_occurrences: dict[int, int] = {}
        for csv_path in csv_files:
            detected = detect_type(csv_path.name)
            if only_types is not None and detected not in only_types:
                continue
            total += import_csv(csv_path, connection, forkjoersvei_occurrences)
        rebuild_rtree(connection)
        stretch_types = {"FART", "FORKJOERSVEI", "STREKNINGS_ATK"}
        if only_types is None:
            rebuild_segment_rtree(connection)
        elif stretch_types & only_types:
            append_stretch_index(connection, stretch_types & only_types)
        else:
            print("Hopper over strekning-indeks (ingen FART/FORKJOERSVEI/STREKNINGS_ATK-endring).")
        connection.commit()
        print(f"Skrev {total} rader til {working_db}")
    finally:
        connection.close()

    if working_db != OUTPUT_DB:
        return replace_output_db(working_db)
    return 0


def replace_output_db(working_db: Path) -> int:
    import shutil
    import time

    replaced = False
    last_error: Exception | None = None
    for _attempt in range(8):
        try:
            if OUTPUT_DB.exists():
                OUTPUT_DB.unlink()
            working_db.replace(OUTPUT_DB)
            replaced = True
            break
        except OSError as error:
            last_error = error
            time.sleep(0.75)
    if not replaced:
        try:
            shutil.copy2(working_db, OUTPUT_DB)
            print(f"Kopierte til {OUTPUT_DB} (replace var låst).")
            working_db.unlink(missing_ok=True)
            replaced = True
        except OSError as copy_error:
            print(
                f"Klarte ikke å erstatte {OUTPUT_DB} (filen er trolig låst). "
                f"Ny database ligger i {working_db}. "
                f"Lukk appen/IDE-låsen og kjør på nytt, eller kopier manuelt. "
                f"Feil: {last_error or copy_error}"
            )
            return 1
    if replaced:
        print(f"Oppdatert {OUTPUT_DB}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
