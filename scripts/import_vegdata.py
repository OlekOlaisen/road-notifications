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
ANSIKTSSIDE_COLUMNS = ("ansiktsside", "rettet mot")
FERJE_NAME_COLUMNS = ("egs.navn", "navn")
KOMMUNE_NAME_COLUMNS = ("kommunenavn",)
KOMMUNE_NUMBER_COLUMNS = ("kommunenummer",)
KOMMUNE_FLATE_COLUMNS = (
    "geo.geometri",
    "geometri, flate",
    "lok.geometri",
)
JERNBANE_TYPE_COLUMNS = ("egs.type",)
ATK_CONTROL_TYPE_COLUMNS = ("type trafikkontroll", "typetrafikkontroll")
FORELDER_COLUMNS = ("rel.forelder", "forelder")
PARENT_775_RE = re.compile(r"775\s*:\s*(\d+)", re.IGNORECASE)
FERJE_STATUS_COLUMNS = ("egs.driftsstatus", "driftsstatus")
SKILTNUMMER_COLUMNS = ("egs.skiltnummer", "skiltnummer")

SKILT_TYPES = (
    "STOPP",
    "FARLIG_SVING",
    "SMALERE_VEG",
    "TUNNEL",
    "SLUTT_FORKJOERSVEI",
    "SLUTT_FART",
    "VIKEPLIKT",
    "FARLIG_VEGKRYSS",
)

SLUTT_FART_SPEED_RE = re.compile(
    r"(?:^|[^0-9])(110|100|90|80|70|60|50|40|30|20)slutt",
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
        return "KOMMUNE"
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
    if "fartsgrenseslutt" in normalized or SLUTT_FART_SPEED_RE.search(normalized):
        return "SLUTT_FART"
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
    if "fartsgrenseslutt" in normalized_filename or SLUTT_FART_SPEED_RE.search(
        normalized_filename,
    ):
        return "SLUTT_FART"
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


METERS_PER_DEGREE_LATITUDE = 111_320.0
POINT_RETNING_SNAP_METERS = 40.0
RETNING_GRID_CELL_DEGREES = 0.002
ROAD_ALIGNED_STRETCH_TYPES = ("FART", "FORKJOERSVEI", "STREKNINGS_ATK", "VILTFARE")
CROSSING_STRETCH_TYPES = ("BOM", "JERNBANE", "FERJEKAI")
STRETCH_TYPES = ROAD_ALIGNED_STRETCH_TYPES + CROSSING_STRETCH_TYPES
POINT_RETNING_TYPES = (
    "STOPP",
    "VIKEPLIKT",
    "FARLIG_SVING",
    "FARLIG_VEGKRYSS",
    "SMALERE_VEG",
    "TUNNEL",
    "SLUTT_FORKJOERSVEI",
    "SLUTT_FART",
    "FOTOBOKS",
    "STREKNINGS_ATK",
    "BOM",
    "VILTFARE",
    "JERNBANE",
    "FERJEKAI",
)


def distance_meters(
    from_latitude: float,
    from_longitude: float,
    to_latitude: float,
    to_longitude: float,
) -> float:
    mean_latitude = math.radians((from_latitude + to_latitude) / 2.0)
    northing = (to_latitude - from_latitude) * METERS_PER_DEGREE_LATITUDE
    easting = (
        (to_longitude - from_longitude)
        * METERS_PER_DEGREE_LATITUDE
        * math.cos(mean_latitude)
    )
    return math.hypot(easting, northing)


def wgs_polyline_span_meters(points: list[tuple[float, float]]) -> float:
    if len(points) < 2:
        return 0.0
    total = 0.0
    for index in range(len(points) - 1):
        total += distance_meters(
            points[index][0],
            points[index][1],
            points[index + 1][0],
            points[index + 1][1],
        )
    return total


def geometry_dict_from_wgs(
    points: list[tuple[float, float]],
    veg_retning_grader: float | None,
) -> dict[str, float | None]:
    latitudes = [point[0] for point in points]
    longitudes = [point[1] for point in points]
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
        "veg_retning_grader": veg_retning_grader,
        "points": points,
    }


def geometry_from_row(
    row: dict[str, str],
    columns: list[str],
) -> dict[str, float | None] | None:
    """Plate position from a POINT; compass heading from any LINESTRING.

    NVDB skilt often have GEO.GEOMETRI as the plate (point) and sometimes
    LOK.GEOMETRI as a short road line. Using only the point left
    vegRetningGrader empty, so MED/MOT could not be applied.
    """
    wgs_polylines: list[list[tuple[float, float]]] = []
    projected_polylines: list[list[tuple[float, float]]] = []

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
                latitude = None
                longitude = None
            else:
                if looks_like_utm(longitude, latitude):
                    latitude, longitude = utm33_to_wgs84(longitude, latitude)
                wgs_polylines.append([(latitude, longitude)])

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
        wgs_points = convert_projected_points(projected_points)
        if not wgs_points:
            continue
        wgs_polylines.append(wgs_points)
        projected_polylines.append(projected_points)

    if not wgs_polylines:
        return None

    point_polylines = [points for points in wgs_polylines if len(points) == 1]
    line_polylines = [points for points in wgs_polylines if len(points) >= 2]
    if point_polylines:
        position_points = point_polylines[0]
    elif line_polylines:
        position_points = max(line_polylines, key=wgs_polyline_span_meters)
    else:
        position_points = wgs_polylines[0]

    veg_retning = None
    if projected_polylines:
        longest_projected = max(
            projected_polylines,
            key=lambda points: 0.0
            if len(points) < 2
            else math.hypot(
                points[-1][0] - points[0][0],
                points[-1][1] - points[0][1],
            ),
        )
        veg_retning = veg_retning_from_projected_points(longest_projected)

    stretch_points = position_points
    if line_polylines:
        stretch_points = max(line_polylines, key=wgs_polyline_span_meters)

    geometry = geometry_dict_from_wgs(stretch_points, veg_retning)
    geometry["start_lat"] = position_points[0][0]
    geometry["start_lon"] = position_points[0][1]
    geometry["end_lat"] = position_points[-1][0]
    geometry["end_lon"] = position_points[-1][1]
    geometry["centroid_lat"] = sum(point[0] for point in position_points) / len(
        position_points,
    )
    geometry["centroid_lon"] = sum(point[1] for point in position_points) / len(
        position_points,
    )
    return geometry


def alert_coordinates(
    objekt_type: str,
    retning: str | None,
    geometry: dict[str, float | None],
) -> tuple[float, float]:
    """
    Forkjørsvei and fartsgrense stretches can be kilometers long. Alert at the
    entrance for the relevant travel direction instead of the geometric midpoint.
    """
    if objekt_type in {
        "FORKJOERSVEI",
        "SLUTT_FORKJOERSVEI",
        "FART",
        "STREKNINGS_ATK",
        "VILTFARE",
        "BOM",
        "JERNBANE",
        "FERJEKAI",
    }:
        if (retning or "").upper() == "MOT":
            return float(geometry["end_lat"]), float(geometry["end_lon"])
        return float(geometry["start_lat"]), float(geometry["start_lon"])
    return float(geometry["centroid_lat"]), float(geometry["centroid_lon"])


def parse_wkt_rings(wkt: str) -> list[list[tuple[float, float]]]:
    rings: list[list[tuple[float, float]]] = []
    open_indexes: list[int] = []
    for index, character in enumerate(wkt):
        if character == "(":
            open_indexes.append(index)
        elif character == ")" and open_indexes:
            start_index = open_indexes.pop()
            content = wkt[start_index + 1 : index]
            if "(" in content:
                continue
            points = parse_wkt_projected_points(f"({content})")
            if len(points) >= 3:
                rings.append(points)
    return rings


def ring_bbox_area(points: list[tuple[float, float]]) -> float:
    eastings = [point[0] for point in points]
    northings = [point[1] for point in points]
    return (max(eastings) - min(eastings)) * (max(northings) - min(northings))


def convert_projected_points(
    points: list[tuple[float, float]],
) -> list[tuple[float, float]]:
    converted: list[tuple[float, float]] = []
    for x_value, y_value in points:
        if looks_like_utm(x_value, y_value):
            latitude, longitude = utm33_to_wgs84(x_value, y_value)
        else:
            longitude, latitude = x_value, y_value
        converted.append((latitude, longitude))
    return converted


def kommune_geometry_from_row(
    row: dict[str, str],
    columns: list[str],
) -> dict[str, float | list[list[tuple[float, float]]] | None] | None:
    rings_wgs: list[list[tuple[float, float]]] = []
    for wkt_name in KOMMUNE_FLATE_COLUMNS:
        column = find_column(columns, (wkt_name,))
        if column is None:
            continue
        projected_rings = parse_wkt_rings(str(row.get(column, "")))
        if not projected_rings:
            continue
        simplified_rings = sorted(
            projected_rings,
            key=ring_bbox_area,
            reverse=True,
        )[:32]
        for projected_ring in simplified_rings:
            sampled = resample_projected_points(
                projected_ring,
                spacing_meters=120.0,
                max_points=256,
            )
            wgs_ring = convert_projected_points(sampled)
            if len(wgs_ring) >= 3:
                rings_wgs.append(wgs_ring)
        if rings_wgs:
            break
    if not rings_wgs:
        return None
    latitudes = [latitude for ring in rings_wgs for latitude, _ in ring]
    longitudes = [longitude for ring in rings_wgs for _, longitude in ring]
    largest_ring = max(rings_wgs, key=len)
    centroid_lat = sum(latitude for latitude, _ in largest_ring) / len(largest_ring)
    centroid_lon = sum(longitude for _, longitude in largest_ring) / len(largest_ring)
    return {
        "start_lat": largest_ring[0][0],
        "start_lon": largest_ring[0][1],
        "end_lat": largest_ring[-1][0],
        "end_lon": largest_ring[-1][1],
        "centroid_lat": centroid_lat,
        "centroid_lon": centroid_lon,
        "min_lat": min(latitudes),
        "max_lat": max(latitudes),
        "min_lon": min(longitudes),
        "max_lon": max(longitudes),
        "veg_retning_grader": None,
        "points": largest_ring,
        "rings": rings_wgs,
    }


def pack_polygon_rings(geometry: dict[str, float | list | None]) -> bytes | None:
    raw_rings = geometry.get("rings")
    if not isinstance(raw_rings, list):
        return None
    rings = [
        ring
        for ring in raw_rings
        if isinstance(ring, list) and len(ring) >= 3
    ]
    if not rings:
        return None
    packed = struct.pack("<I", len(rings))
    for ring in rings:
        packed += struct.pack("<I", len(ring))
        for latitude, longitude in ring:
            packed += struct.pack(
                "<ii",
                int(round(float(latitude) * 1_000_000.0)),
                int(round(float(longitude) * 1_000_000.0)),
            )
    return packed


def pack_stretch_points(
    objekt_type: str,
    geometry: dict[str, float | None],
) -> bytes | None:
    if objekt_type not in STRETCH_TYPES:
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


def parent_775_ids(row: dict[str, str], columns: list[str]) -> set[int]:
    column = find_column(columns, FORELDER_COLUMNS)
    if column is None:
        return set()
    raw = str(row.get(column, ""))
    return {int(match.group(1)) for match in PARENT_775_RE.finditer(raw)}


def is_section_control_camera(
    row: dict[str, str],
    columns: list[str],
    streknings_atk_ids: set[int],
) -> bool:
    if not streknings_atk_ids:
        return False
    return bool(parent_775_ids(row, columns) & streknings_atk_ids)


def nvdb_id_from_row_id(row_id: int) -> int:
    if row_id & FORKJOERSVEI_EXTRA_ROW_FLAG == 0:
        return row_id
    nvdb_id_mask = (1 << FORKJOERSVEI_NVDB_ID_BITS) - 1
    return (row_id >> FORKJOERSVEI_OCCURRENCE_BITS) & nvdb_id_mask


def streknings_atk_nvdb_ids(connection: sqlite3.Connection) -> set[int]:
    rows = connection.execute(
        "SELECT id FROM vegobjekt WHERE type = 'STREKNINGS_ATK'",
    ).fetchall()
    return {nvdb_id_from_row_id(int(row[0])) for row in rows}


def retning_from_ansiktsside(
    row: dict[str, str],
    columns: list[str],
) -> str | None:
    column = find_column(columns, ANSIKTSSIDE_COLUMNS)
    if column is None:
        return None
    raw = normalize_key(str(row.get(column, "")))
    if not raw:
        return None
    if "motmetrering" in raw:
        return "MOT"
    if "imetrering" in raw:
        return "MED"
    return None


def retning_from_row(
    row: dict[str, str],
    columns: list[str],
    objekt_type: str | None = None,
) -> str | None:
    if objekt_type == "SLUTT_FART":
        face = retning_from_ansiktsside(row, columns)
        if face is not None:
            return face
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
    if objekt_type == "KOMMUNE":
        name_column = find_column(columns, KOMMUNE_NAME_COLUMNS)
        if name_column:
            raw = str(row.get(name_column, "")).strip()
            if raw:
                return raw
        number_column = find_column(columns, KOMMUNE_NUMBER_COLUMNS)
        if number_column:
            raw = str(row.get(number_column, "")).strip()
            if raw:
                return raw
        return None
    if objekt_type == "JERNBANE":
        column = find_column(columns, JERNBANE_TYPE_COLUMNS)
        if column:
            raw = str(row.get(column, "")).strip()
            return raw or None
        return None
    if objekt_type == "SLUTT_FART":
        column = find_column(columns, SKILTNUMMER_COLUMNS)
        if column:
            raw = str(row.get(column, "")).strip()
            parsed = slutt_fart_verdi_from_code(skiltnummer_code(raw))
            if parsed:
                return parsed
        return slutt_fart_verdi_from_filename(filename)
    if objekt_type in SKILT_TYPES:
        column = find_column(columns, SKILTNUMMER_COLUMNS)
        if column:
            raw = str(row.get(column, "")).strip()
            code = skiltnummer_code(raw)
            if code:
                return code
        return skiltnummer_from_filename(filename)
    return None


def slutt_fart_verdi_from_code(code: str | None) -> str | None:
    if not code:
        return None
    compact = code.replace(" ", "").replace(",", ".")
    if compact == "368" or compact.startswith("368."):
        return "368"
    match = re.fullmatch(r"364[._](\d{2,3})", compact)
    if match:
        return match.group(1)
    return None


def slutt_fart_verdi_from_filename(filename: str) -> str | None:
    normalized = normalize_key(filename)
    if "fartsgrenseslutt" in normalized:
        return "368"
    match = SLUTT_FART_SPEED_RE.search(normalized)
    if match:
        return match.group(1)
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
    streknings_atk_ids: set[int] | None = None,
) -> int:
    objekt_type = detect_type(path.name)
    if objekt_type is None:
        print(f"Hopper over ukjent fil: {path.name}")
        return 0
    if forkjoersvei_occurrences is None:
        forkjoersvei_occurrences = {}
    if streknings_atk_ids is None:
        streknings_atk_ids = set()
    separator = detect_separator(path)
    inserted = 0
    skipped = 0
    reclassified_section_cameras = 0
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
            row_type = objekt_type
            if objekt_type == "STREKNINGS_ATK" and objekt_id is not None:
                streknings_atk_ids.add(objekt_id)
            if objekt_type == "FOTOBOKS" and is_section_control_camera(
                string_row,
                columns,
                streknings_atk_ids,
            ):
                row_type = "STREKNINGS_ATK"
                reclassified_section_cameras += 1
            if objekt_type == "KOMMUNE":
                geometry = kommune_geometry_from_row(string_row, columns)
            else:
                geometry = geometry_from_row(string_row, columns)
            if objekt_id is None or geometry is None:
                skipped += 1
                continue
            verdi = value_for_type(string_row, columns, row_type, path.name)
            retning = retning_from_row(string_row, columns, row_type)
            latitude, longitude = alert_coordinates(row_type, retning, geometry)
            if row_type == "KOMMUNE":
                points_blob = pack_polygon_rings(geometry)
            else:
                points_blob = pack_stretch_points(row_type, geometry)
            veg_retning = geometry["veg_retning_grader"]
            if row_type in CROSSING_STRETCH_TYPES:
                # Boom / rails / quay lines often run across the road.
                # Snap heading from the road network instead.
                veg_retning = None
            row_id = objekt_id
            if row_type in {
                "FORKJOERSVEI",
                "STREKNINGS_ATK",
                "VILTFARE",
                "BOM",
                "JERNBANE",
                "FERJEKAI",
            }:
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
                    row_type,
                    verdi,
                    latitude,
                    longitude,
                    geometry["min_lat"],
                    geometry["max_lat"],
                    geometry["min_lon"],
                    geometry["max_lon"],
                    retning,
                    veg_retning,
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
    if reclassified_section_cameras:
        print(
            f"{path.name}: {reclassified_section_cameras} ATK-punkt "
            "importert som STREKNINGS_ATK (strekningskamera).",
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


def retning_grid_cell(latitude: float, longitude: float) -> tuple[int, int]:
    return (
        int(math.floor(latitude / RETNING_GRID_CELL_DEGREES)),
        int(math.floor(longitude / RETNING_GRID_CELL_DEGREES)),
    )


def compass_bearing_wgs(
    from_latitude: float,
    from_longitude: float,
    to_latitude: float,
    to_longitude: float,
) -> float | None:
    northing = (to_latitude - from_latitude) * METERS_PER_DEGREE_LATITUDE
    easting = (
        (to_longitude - from_longitude)
        * METERS_PER_DEGREE_LATITUDE
        * math.cos(math.radians((from_latitude + to_latitude) / 2.0))
    )
    if abs(easting) < 0.5 and abs(northing) < 0.5:
        return None
    return math.degrees(math.atan2(easting, northing)) % 360.0


def distance_to_segment_meters(
    latitude: float,
    longitude: float,
    start: tuple[float, float],
    end: tuple[float, float],
) -> tuple[float, float] | None:
    heading = compass_bearing_wgs(start[0], start[1], end[0], end[1])
    if heading is None:
        return None
    segment_length = distance_meters(start[0], start[1], end[0], end[1])
    if segment_length < 0.5:
        return None
    from_start = distance_meters(start[0], start[1], latitude, longitude)
    start_to_point_bearing = compass_bearing_wgs(
        start[0],
        start[1],
        latitude,
        longitude,
    )
    if start_to_point_bearing is None:
        return 0.0, heading
    heading_delta = abs(((start_to_point_bearing - heading + 180.0) % 360.0) - 180.0)
    along_track = from_start * math.cos(math.radians(heading_delta))
    fraction = min(1.0, max(0.0, along_track / segment_length))
    snapped_latitude = start[0] + ((end[0] - start[0]) * fraction)
    snapped_longitude = start[1] + ((end[1] - start[1]) * fraction)
    return (
        distance_meters(latitude, longitude, snapped_latitude, snapped_longitude),
        heading,
    )


def build_stretch_heading_grid(
    connection: sqlite3.Connection,
) -> dict[tuple[int, int], list[tuple[tuple[float, float], tuple[float, float]]]]:
    grid: dict[
        tuple[int, int],
        list[tuple[tuple[float, float], tuple[float, float]]],
    ] = {}
    placeholders = ",".join("?" for _ in ROAD_ALIGNED_STRETCH_TYPES)
    rows = connection.execute(
        f"SELECT points FROM vegobjekt WHERE type IN ({placeholders}) AND points IS NOT NULL",
        ROAD_ALIGNED_STRETCH_TYPES,
    )
    for (blob,) in rows:
        points = unpack_stretch_points(blob)
        for index in range(len(points) - 1):
            start = points[index]
            end = points[index + 1]
            start_cell = retning_grid_cell(start[0], start[1])
            end_cell = retning_grid_cell(end[0], end[1])
            min_lat_cell = min(start_cell[0], end_cell[0])
            max_lat_cell = max(start_cell[0], end_cell[0])
            min_lon_cell = min(start_cell[1], end_cell[1])
            max_lon_cell = max(start_cell[1], end_cell[1])
            for lat_cell in range(min_lat_cell, max_lat_cell + 1):
                for lon_cell in range(min_lon_cell, max_lon_cell + 1):
                    grid.setdefault((lat_cell, lon_cell), []).append((start, end))
    return grid


def nearest_stretch_heading(
    latitude: float,
    longitude: float,
    grid: dict[
        tuple[int, int],
        list[tuple[tuple[float, float], tuple[float, float]]],
    ],
    max_distance_meters: float = POINT_RETNING_SNAP_METERS,
) -> float | None:
    origin_cell = retning_grid_cell(latitude, longitude)
    best_distance = max_distance_meters
    best_heading: float | None = None
    seen: set[tuple[float, float, float, float]] = set()
    for lat_delta in (-1, 0, 1):
        for lon_delta in (-1, 0, 1):
            cell = (origin_cell[0] + lat_delta, origin_cell[1] + lon_delta)
            for start, end in grid.get(cell, ()):
                key = (start[0], start[1], end[0], end[1])
                if key in seen:
                    continue
                seen.add(key)
                projected = distance_to_segment_meters(latitude, longitude, start, end)
                if projected is None:
                    continue
                distance, heading = projected
                if distance <= best_distance:
                    best_distance = distance
                    best_heading = heading
    return best_heading


def fill_point_veg_retning_from_stretches(connection: sqlite3.Connection) -> int:
    """Set vegRetningGrader on point signs that have lok.retning but no heading.

    Warning plates are points. MED/MOT is relative to road metrering, which
    speed-limit / priority-road polylines already follow.
    """
    placeholders = ",".join("?" for _ in POINT_RETNING_TYPES)
    missing = connection.execute(
        f"""
        SELECT id, lat, lon FROM vegobjekt
        WHERE type IN ({placeholders})
          AND retning IN ('MED', 'MOT')
          AND vegRetningGrader IS NULL
        """,
        POINT_RETNING_TYPES,
    ).fetchall()
    if not missing:
        print("Alle punkt-skilt med lok.retning har allerede vegRetningGrader.")
        return 0
    print(
        f"Fyller vegRetningGrader for {len(missing)} punkt-skilt fra "
        "fartsgrense-/forkjørsvei-strekninger...",
        flush=True,
    )
    grid = build_stretch_heading_grid(connection)
    updates: list[tuple[float, int]] = []
    filled = 0
    for objekt_id, latitude, longitude in missing:
        heading = nearest_stretch_heading(latitude, longitude, grid)
        if heading is None:
            continue
        updates.append((heading, objekt_id))
        filled += 1
        if len(updates) >= 2_000:
            connection.executemany(
                "UPDATE vegobjekt SET vegRetningGrader = ? WHERE id = ?",
                updates,
            )
            connection.commit()
            updates.clear()
            print(f"  {filled} skilt oppdatert...", flush=True)
    if updates:
        connection.executemany(
            "UPDATE vegobjekt SET vegRetningGrader = ? WHERE id = ?",
            updates,
        )
        connection.commit()
    missed = len(missing) - filled
    missed_text = ""
    if missed:
        missed_text = (
            f" ({missed} uten strekning innen {POINT_RETNING_SNAP_METERS:.0f} m)"
        )
    print(
        f"Satte vegRetningGrader på {filled} punkt-skilt{missed_text}.",
        flush=True,
    )
    return filled


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
    placeholders = ",".join("?" for _ in STRETCH_TYPES)
    rows = connection.execute(
        f"SELECT id, points FROM vegobjekt WHERE type IN ({placeholders}) AND points IS NOT NULL",
        STRETCH_TYPES,
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
    parser.add_argument(
        "--fill-retning",
        action="store_true",
        help="Fyll vegRetningGrader på punkt-skilt med lok.retning i eksisterende vegdata.db",
    )
    args = parser.parse_args(argv)
    if args.segments_only or args.fill_retning:
        if not OUTPUT_DB.exists():
            print(f"Fant ikke {OUTPUT_DB}. Kjør full import først.")
            return 1
        working_db = OUTPUT_DB.with_suffix(".import.db")
        shutil.copy2(OUTPUT_DB, working_db)
        connection = sqlite3.connect(working_db)
        try:
            if args.segments_only:
                rebuild_segment_rtree(connection)
            if args.fill_retning:
                fill_point_veg_retning_from_stretches(connection)
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
        csv_files = sorted(
            CSV_DIR.glob("*.csv"),
            key=lambda path: (
                0 if detect_type(path.name) == "STREKNINGS_ATK" else
                1 if detect_type(path.name) == "FOTOBOKS" else
                2,
                path.name,
            ),
        )
        if not csv_files:
            print(f"Ingen CSV-filer i {CSV_DIR}.")
        total = 0
        forkjoersvei_occurrences: dict[int, int] = {}
        streknings_atk_ids: set[int] = set()
        if only_types is not None:
            streknings_atk_ids.update(streknings_atk_nvdb_ids(connection))
        for csv_path in csv_files:
            detected = detect_type(csv_path.name)
            if only_types is not None and detected not in only_types:
                continue
            total += import_csv(
                csv_path,
                connection,
                forkjoersvei_occurrences,
                streknings_atk_ids,
            )
        rebuild_rtree(connection)
        stretch_types = set(STRETCH_TYPES)
        if only_types is None:
            rebuild_segment_rtree(connection)
        elif stretch_types & only_types:
            append_stretch_index(connection, stretch_types & only_types)
        else:
            print(
                "Hopper over strekning-indeks "
                "(ingen strekningstype-endring)."
            )
        fill_point_veg_retning_from_stretches(connection)
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
