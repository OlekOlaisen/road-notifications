package no.roadnotifications.importer;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.ev.BooleanEncodedValue;
import com.graphhopper.routing.ev.VehicleAccess;
import com.graphhopper.util.DistanceCalcEarth;
import com.graphhopper.util.FetchMode;
import com.graphhopper.util.GHUtility;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds an offline SQLite road graph from an OSM PBF via GraphHopper.
 *
 * GraphHopper 11 cannot run on Android (Java 25 + Janino). This tool therefore
 * extracts car-accessible edges on the desktop, and the phone snaps GPS to that
 * graph without embedding GraphHopper.
 */
public final class OsmGraphImporter {
    private static final int FORMAT_VERSION = 1;
    private static final double MIN_EDGE_METERS = 4.0;
    private static final double SIMPLIFY_SPACING_METERS = 15.0;
    private static final DistanceCalcEarth DISTANCE_CALC = new DistanceCalcEarth();

    private OsmGraphImporter() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println(
                "Usage: OsmGraphImporter <norway.osm.pbf> <roadgraph.db> <graphhopper-cache-dir>"
            );
            System.exit(1);
        }
        File osmFile = new File(args[0]);
        File outputFile = new File(args[1]);
        File cacheDir = new File(args[2]);
        if (!osmFile.isFile()) {
            throw new IllegalArgumentException("OSM-filen finnes ikke: " + osmFile.getAbsolutePath());
        }
        File outputParent = outputFile.getParentFile();
        if (outputParent != null) {
            outputParent.mkdirs();
        }
        cacheDir.mkdirs();
        if (outputFile.exists() && !outputFile.delete()) {
            throw new IllegalStateException("Klarte ikke å slette gammel " + outputFile);
        }

        System.out.println("Leser " + osmFile.getAbsolutePath());
        GraphHopper hopper = createHopper(osmFile, cacheDir);
        hopper.importOrLoad();
        try {
            writeSqlite(hopper, osmFile, outputFile);
        } finally {
            hopper.close();
        }
        System.out.println("Skrev " + outputFile.getAbsolutePath() + " (" + outputFile.length() + " byte)");
    }

    private static GraphHopper createHopper(File osmFile, File cacheDir) {
        GraphHopper hopper = new GraphHopper();
        hopper.setOSMFile(osmFile.getAbsolutePath());
        hopper.setGraphHopperLocation(cacheDir.getAbsolutePath());
        hopper.setEncodedValuesString(
            "car_access, car_average_speed, road_class, road_environment, max_speed, road_access"
        );
        hopper.setProfiles(
            new Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json"))
        );
        return hopper;
    }

    private static void writeSqlite(GraphHopper hopper, File osmFile, File outputFile)
        throws Exception {
        BooleanEncodedValue carAccess = hopper.getEncodingManager()
            .getBooleanEncodedValue(VehicleAccess.key("car"));
        String jdbcUrl = "jdbc:sqlite:" + outputFile.getAbsolutePath().replace('\\', '/');
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            try (Statement pragma = connection.createStatement()) {
                pragma.execute("PRAGMA journal_mode = MEMORY");
                pragma.execute("PRAGMA synchronous = OFF");
            }
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                    "CREATE TABLE road_graph_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)"
                );
                statement.execute(
                    "CREATE TABLE road_edge ("
                        + "id INTEGER PRIMARY KEY, "
                        + "minLat REAL NOT NULL, "
                        + "maxLat REAL NOT NULL, "
                        + "minLon REAL NOT NULL, "
                        + "maxLon REAL NOT NULL, "
                        + "fwd INTEGER NOT NULL, "
                        + "bwd INTEGER NOT NULL, "
                        + "name TEXT, "
                        + "points BLOB NOT NULL)"
                );
                statement.execute(
                    "CREATE VIRTUAL TABLE road_edge_rtree USING rtree("
                        + "id, minLat, maxLat, minLon, maxLon)"
                );
            }
            try (PreparedStatement meta = connection.prepareStatement(
                    "INSERT INTO road_graph_meta(key, value) VALUES(?, ?)"
                )) {
                insertMeta(meta, "format", Integer.toString(FORMAT_VERSION));
                insertMeta(meta, "source", osmFile.getName());
            }
            String insertSql =
                "INSERT INTO road_edge(id, minLat, maxLat, minLon, maxLon, fwd, bwd, name, points) "
                    + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)";
            String rtreeSql =
                "INSERT INTO road_edge_rtree(id, minLat, maxLat, minLon, maxLon) VALUES(?, ?, ?, ?, ?)";
            int inserted = 0;
            int skipped = 0;
            try (
                PreparedStatement insert = connection.prepareStatement(insertSql);
                PreparedStatement rtree = connection.prepareStatement(rtreeSql)
            ) {
                var edges = hopper.getBaseGraph().getAllEdges();
                while (edges.next()) {
                    boolean forwardAccess = edges.get(carAccess);
                    boolean backwardAccess = edges.getReverse(carAccess);
                    if (!forwardAccess && !backwardAccess) {
                        skipped += 1;
                        continue;
                    }
                    List<GHPoint> simplified = simplifyGeometry(
                        edges.fetchWayGeometry(FetchMode.ALL)
                    );
                    if (simplified.size() < 2) {
                        skipped += 1;
                        continue;
                    }
                    double minLat = simplified.get(0).lat;
                    double maxLat = minLat;
                    double minLon = simplified.get(0).lon;
                    double maxLon = minLon;
                    for (GHPoint point : simplified) {
                        minLat = Math.min(minLat, point.lat);
                        maxLat = Math.max(maxLat, point.lat);
                        minLon = Math.min(minLon, point.lon);
                        maxLon = Math.max(maxLon, point.lon);
                    }
                    int edgeId = edges.getEdge();
                    insert.setInt(1, edgeId);
                    insert.setDouble(2, minLat);
                    insert.setDouble(3, maxLat);
                    insert.setDouble(4, minLon);
                    insert.setDouble(5, maxLon);
                    insert.setInt(6, forwardAccess ? 1 : 0);
                    insert.setInt(7, backwardAccess ? 1 : 0);
                    String name = edges.getName();
                    if (name == null || name.isBlank()) {
                        insert.setNull(8, java.sql.Types.VARCHAR);
                    } else {
                        insert.setString(8, name.trim());
                    }
                    insert.setBytes(9, packPoints(simplified));
                    insert.addBatch();
                    rtree.setInt(1, edgeId);
                    rtree.setDouble(2, minLat);
                    rtree.setDouble(3, maxLat);
                    rtree.setDouble(4, minLon);
                    rtree.setDouble(5, maxLon);
                    rtree.addBatch();
                    inserted += 1;
                    if (inserted % 20_000 == 0) {
                        insert.executeBatch();
                        rtree.executeBatch();
                        connection.commit();
                        System.out.println(
                            "Eksportert " + inserted + " bilveier, hoppet over " + skipped
                        );
                    }
                }
                insert.executeBatch();
                rtree.executeBatch();
            }
            connection.commit();
            System.out.println("Ferdig: " + inserted + " kanter, " + skipped + " hoppet over");
        }
    }

    private static void insertMeta(PreparedStatement statement, String key, String value)
        throws Exception {
        statement.setString(1, key);
        statement.setString(2, value);
        statement.executeUpdate();
    }

    private static List<GHPoint> simplifyGeometry(PointList geometry) {
        List<GHPoint> simplified = new ArrayList<>();
        if (geometry == null || geometry.size() < 2) {
            return simplified;
        }
        GHPoint previous = new GHPoint(geometry.getLat(0), geometry.getLon(0));
        simplified.add(previous);
        for (int index = 1; index < geometry.size() - 1; index += 1) {
            GHPoint candidate = new GHPoint(geometry.getLat(index), geometry.getLon(index));
            double spacing = DISTANCE_CALC.calcDist(
                previous.lat,
                previous.lon,
                candidate.lat,
                candidate.lon
            );
            if (spacing >= SIMPLIFY_SPACING_METERS) {
                simplified.add(candidate);
                previous = candidate;
            }
        }
        GHPoint last = new GHPoint(
            geometry.getLat(geometry.size() - 1),
            geometry.getLon(geometry.size() - 1)
        );
        if (!simplified.isEmpty()) {
            GHPoint currentLast = simplified.get(simplified.size() - 1);
            if (currentLast.lat != last.lat || currentLast.lon != last.lon) {
                simplified.add(last);
            }
        }
        if (simplified.size() < 2) {
            return simplified;
        }
        double lengthMeters = 0.0;
        for (int index = 1; index < simplified.size(); index += 1) {
            GHPoint from = simplified.get(index - 1);
            GHPoint to = simplified.get(index);
            lengthMeters += DISTANCE_CALC.calcDist(from.lat, from.lon, to.lat, to.lon);
        }
        if (lengthMeters < MIN_EDGE_METERS) {
            return List.of();
        }
        return simplified;
    }

    private static byte[] packPoints(List<GHPoint> points) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + (points.size() * 8))
            .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(points.size());
        for (GHPoint point : points) {
            buffer.putInt((int) Math.round(point.lat * 1_000_000.0));
            buffer.putInt((int) Math.round(point.lon * 1_000_000.0));
        }
        return buffer.array();
    }
}
