#!/usr/bin/env python3
"""Unit tests for NVDB import heading / lok.retning helpers."""

from __future__ import annotations

import sqlite3
import unittest

import import_vegdata as nvdb


class GeometryHeadingTest(unittest.TestCase):
    def test_point_plus_linestring_uses_line_heading_and_point_position(self) -> None:
        row = {
            "GEO.GEOMETRI": "POINT (262000 6645000)",
            "LOK.GEOMETRI": "LINESTRING (262000 6645000, 262000 6645080)",
        }
        columns = ["GEO.GEOMETRI", "LOK.GEOMETRI"]
        geometry = nvdb.geometry_from_row(row, columns)
        self.assertIsNotNone(geometry)
        assert geometry is not None
        self.assertAlmostEqual(geometry["veg_retning_grader"], 0.0, delta=2.0)
        point_lat = nvdb.convert_projected_points([(262000.0, 6645000.0)])[0][0]
        self.assertAlmostEqual(float(geometry["centroid_lat"]), point_lat, delta=1e-6)

    def test_point_only_has_no_heading(self) -> None:
        row = {"GEO.GEOMETRI": "POINT (262000 6645000)"}
        geometry = nvdb.geometry_from_row(row, ["GEO.GEOMETRI"])
        self.assertIsNotNone(geometry)
        assert geometry is not None
        self.assertIsNone(geometry["veg_retning_grader"])


class StretchSnapHeadingTest(unittest.TestCase):
    def test_nearest_segment_heading_is_northbound(self) -> None:
        start = (60.0, 10.0)
        end = (60.001, 10.0)
        grid = {
            nvdb.retning_grid_cell(start[0], start[1]): [(start, end)],
        }
        heading = nvdb.nearest_stretch_heading(60.0004, 10.0, grid)
        self.assertIsNotNone(heading)
        assert heading is not None
        self.assertLess(min(heading, 360.0 - heading), 5.0)

    def test_fill_sets_heading_on_point_sign(self) -> None:
        connection = sqlite3.connect(":memory:")
        connection.execute(nvdb.CREATE_VEGOBJEKT)
        fart_points = [
            (60.0, 10.0),
            (60.001, 10.0),
        ]
        blob = nvdb.pack_stretch_points(
            "FART",
            {"points": fart_points},
        )
        connection.execute(
            """
            INSERT INTO vegobjekt
            (id, type, verdi, lat, lon, minLat, maxLat, minLon, maxLon,
             retning, vegRetningGrader, points)
            VALUES (1, 'FART', '80', 60.0, 10.0, 60.0, 60.001, 10.0, 10.0,
                    'MED', 0, ?)
            """,
            (blob,),
        )
        connection.execute(
            """
            INSERT INTO vegobjekt
            (id, type, verdi, lat, lon, minLat, maxLat, minLon, maxLon,
             retning, vegRetningGrader, points)
            VALUES (2, 'FARLIG_VEGKRYSS', '124', 60.0005, 10.0,
                    60.0005, 60.0005, 10.0, 10.0, 'MED', NULL, NULL)
            """
        )
        filled = nvdb.fill_point_veg_retning_from_stretches(connection)
        self.assertEqual(filled, 1)
        heading = connection.execute(
            "SELECT vegRetningGrader FROM vegobjekt WHERE id = 2"
        ).fetchone()[0]
        self.assertIsNotNone(heading)
        self.assertLess(min(float(heading), 360.0 - float(heading)), 5.0)


class EntranceStretchTest(unittest.TestCase):
    def test_wildlife_is_packed_as_a_stretch(self) -> None:
        blob = nvdb.pack_stretch_points(
            "VILTFARE",
            {"points": [(60.0, 10.0), (60.001, 10.0)]},
        )
        self.assertIsNotNone(blob)
        self.assertGreaterEqual(len(nvdb.unpack_stretch_points(blob)), 2)

    def test_crossing_types_are_packed_as_stretches(self) -> None:
        for objekt_type in ("BOM", "JERNBANE", "FERJEKAI"):
            blob = nvdb.pack_stretch_points(
                objekt_type,
                {"points": [(60.0, 10.0), (60.0, 10.002)]},
            )
            self.assertIsNotNone(blob, objekt_type)

    def test_entrance_types_alert_at_med_start_and_mot_end(self) -> None:
        geometry = {
            "start_lat": 60.0,
            "start_lon": 10.0,
            "end_lat": 60.01,
            "end_lon": 10.0,
            "centroid_lat": 60.005,
            "centroid_lon": 10.0,
        }
        for objekt_type in (
            "VILTFARE",
            "BOM",
            "JERNBANE",
            "FERJEKAI",
        ):
            med_lat, med_lon = nvdb.alert_coordinates(objekt_type, "MED", geometry)
            mot_lat, mot_lon = nvdb.alert_coordinates(objekt_type, "MOT", geometry)
            self.assertEqual((med_lat, med_lon), (60.0, 10.0), objekt_type)
            self.assertEqual((mot_lat, mot_lon), (60.01, 10.0), objekt_type)


class SectionControlCameraTest(unittest.TestCase):
    def test_parent_775_ids_parse_single_and_multiple(self) -> None:
        columns = ["REL.FORELDER", "REL.BARN"]
        self.assertEqual(
            nvdb.parent_775_ids({"REL.FORELDER": "775:375060046"}, columns),
            {375060046},
        )
        self.assertEqual(
            nvdb.parent_775_ids(
                {"REL.FORELDER": "775:111, 775:222"},
                columns,
            ),
            {111, 222},
        )
        self.assertEqual(
            nvdb.parent_775_ids(
                {"REL.FORELDER": "", "REL.BARN": "297:367330165"},
                columns,
            ),
            set(),
        )

    def test_section_control_camera_follows_streknings_atk_parent(self) -> None:
        columns = ["REL.FORELDER"]
        section_row = {"REL.FORELDER": "775:375060046"}
        point_atk_row = {"REL.FORELDER": "775:999"}
        streknings_ids = {375060046}
        self.assertTrue(
            nvdb.is_section_control_camera(section_row, columns, streknings_ids),
        )
        self.assertFalse(
            nvdb.is_section_control_camera(point_atk_row, columns, streknings_ids),
        )
        self.assertFalse(
            nvdb.is_section_control_camera(section_row, columns, set()),
        )

    def test_section_camera_is_not_packed_as_a_stretch(self) -> None:
        blob = nvdb.pack_stretch_points(
            "STREKNINGS_ATK",
            {"points": [(60.0, 10.0)]},
        )
        self.assertIsNone(blob)


class SluttFartRetningTest(unittest.TestCase):
    def test_ansiktsside_overrides_lok_retning_for_slutt_fart(self) -> None:
        columns = ["LOK.RETNING", "EGS.ANSIKTSSIDE, RETTET MOT.1894"]
        facing_oncoming = {
            "LOK.RETNING": "MED",
            "EGS.ANSIKTSSIDE, RETTET MOT.1894": "Trafikk mot metreringsretning",
        }
        facing_along = {
            "LOK.RETNING": "MED",
            "EGS.ANSIKTSSIDE, RETTET MOT.1894": "Trafikk i metreringsretning",
        }
        self.assertEqual(
            nvdb.retning_from_row(facing_oncoming, columns, "SLUTT_FART"),
            "MOT",
        )
        self.assertEqual(
            nvdb.retning_from_row(facing_along, columns, "SLUTT_FART"),
            "MED",
        )
        self.assertEqual(
            nvdb.retning_from_row(facing_oncoming, columns, "STOPP"),
            "MED",
        )


if __name__ == "__main__":
    unittest.main()
