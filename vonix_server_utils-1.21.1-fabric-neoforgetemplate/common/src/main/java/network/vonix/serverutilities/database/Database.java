package network.vonix.serverutilities.database;

import net.minecraft.server.MinecraftServer;
import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.database.VsuLegacyMigration;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight SQLite wrapper backed by a single persistent connection.
 * All public methods must be called from the VonixSU-DB executor thread only —
 * never from the main tick thread directly.
 *
 * On first start the database recovers retained VSU data.db snapshots
 * (homes and persisted /back|/backdeath rows) into the current VSU database.
 * VonixCore-to-VSU is not the current migration path and is not invoked.
 *
 * DB file: config/vonix_server_utilities/data.db
 */
public final class Database {

    private static final Path DB_PATH =
            Paths.get("config", "vonix_server_utilities", "data.db");

    private Connection connection;

    /**
     * Initialise the database. Table creation runs synchronously so that the
     * connection is immediately usable; VSU-to-VSU retained recovery is submitted
     * to the DB executor so it does not delay server startup.
     */
    public void init(MinecraftServer server) {
        try {
            Files.createDirectories(DB_PATH.getParent());
            // Explicitly load the SQLite JDBC driver class.
            // On Forge/NeoForge, DriverManager uses the bootstrap class loader which
            // cannot see drivers loaded by FML's mod class loader, so auto-discovery
            // via ServiceLoader silently fails. Class.forName() forces registration.
            // NOTE: We use `org.sqlite.JDBC.class.getName()` rather than a string
            // literal so shadow's `relocate` rewrites the bytecode reference. After
            // relocation the actual class name at runtime is
            // network.vonix.serverutilities.shadow.sqlite.JDBC — string literals
            // would NOT be rewritten and the driver would fail to load.
            Class.forName(org.sqlite.JDBC.class.getName());
            connection = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH.toAbsolutePath());
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
            }
            createTables();
            VonixServerUtilities.LOGGER.info("[VonixSU] Database ready at {}", DB_PATH.toAbsolutePath());
        } catch (Exception e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] Database initialisation failed", e);
            return;
        }

        // Migration runs off the main thread; all later DB tasks queue behind it.
        VonixServerUtilities.dbAsync(() -> attemptMigration(server));
    }

    // ── Schema ────────────────────────────────────────────────────────────────

    private void createTables() throws SQLException {
        try (Statement s = connection.createStatement()) {
            // Homes
            s.execute("""
                CREATE TABLE IF NOT EXISTS vsu_homes (
                    uuid  TEXT NOT NULL,
                    name  TEXT NOT NULL,
                    world TEXT NOT NULL,
                    x     REAL NOT NULL,
                    y     REAL NOT NULL,
                    z     REAL NOT NULL,
                    yaw   REAL NOT NULL,
                    pitch REAL NOT NULL,
                    PRIMARY KEY (uuid, name)
                )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_vsu_homes_uuid ON vsu_homes (uuid)");

            // Warps
            s.execute("""
                CREATE TABLE IF NOT EXISTS vsu_warps (
                    name       TEXT PRIMARY KEY,
                    world      TEXT NOT NULL,
                    x          REAL NOT NULL,
                    y          REAL NOT NULL,
                    z          REAL NOT NULL,
                    yaw        REAL NOT NULL,
                    pitch      REAL NOT NULL,
                    created_by TEXT,
                    created_at INTEGER NOT NULL DEFAULT 0
                )""");

            // Kit cooldowns
            s.execute("""
                CREATE TABLE IF NOT EXISTS vsu_kit_cooldowns (
                    uuid      TEXT NOT NULL,
                    kit_name  TEXT NOT NULL,
                    last_used INTEGER NOT NULL,
                    PRIMARY KEY (uuid, kit_name)
                )""");

            // Back-location store (kind='tp' or 'death')
            s.execute("""
                CREATE TABLE IF NOT EXISTS vsu_back_locations (
                    uuid       TEXT NOT NULL,
                    world      TEXT NOT NULL,
                    x          REAL NOT NULL,
                    y          REAL NOT NULL,
                    z          REAL NOT NULL,
                    yaw        REAL NOT NULL,
                    pitch      REAL NOT NULL,
                    kind       TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (uuid, kind)
                )""");

            // Nicknames
            s.execute("""
                CREATE TABLE IF NOT EXISTS vsu_nicknames (
                    uuid       TEXT PRIMARY KEY,
                    nickname   TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )""");

            // Ignore list
            s.execute("""
                CREATE TABLE IF NOT EXISTS vsu_ignore_list (
                    owner_uuid  TEXT NOT NULL,
                    target_uuid TEXT NOT NULL,
                    created_at  INTEGER NOT NULL,
                    PRIMARY KEY (owner_uuid, target_uuid)
                )""");

            // One-time migration tracking
            s.execute("""
                CREATE TABLE IF NOT EXISTS vsu_migration (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""");
        }
    }

    // ── VSU-to-VSU retained database recovery ─────────────────────────────────

    private void attemptMigration(MinecraftServer server) {
        attemptVsuRecovery();
    }

    private void attemptVsuRecovery() {
        for (Path source : findLegacyVsuDatabases()) {
            try {
                String fingerprint = VsuLegacyMigration.fingerprint(source);
                String markerKey = "vsu_vsu_recovery:" + fingerprint;
                if (isMigrationComplete(markerKey)) {
                    VonixServerUtilities.LOGGER.info("[VonixSU] VSU recovery source already imported: {}", source.getFileName());
                    continue;
                }
                VsuLegacyMigration.ImportResult result =
                        VsuLegacyMigration.importInto(connection, source, fingerprint);
                if (!result.recognized()) {
                    VonixServerUtilities.LOGGER.warn(
                            "[VonixSU] Ignoring legacy candidate without VSU tables: {}", source);
                    continue;
                }
                markMigration(markerKey,
                        "complete;homes=" + result.homesInserted()
                                + ";back_locations=" + result.backLocationsInserted());
                VonixServerUtilities.LOGGER.info(
                        "[VonixSU] Recovered {} homes and {} back locations from {}.",
                        result.homesInserted(), result.backLocationsInserted(), source);
            } catch (Exception e) {
                VonixServerUtilities.LOGGER.warn(
                        "[VonixSU] VSU recovery failed for {}; source left untouched and marker not set: {}",
                        source, e.getMessage());
            }
        }
    }

    private List<Path> findLegacyVsuDatabases() {
        List<Path> candidates = new ArrayList<>();
        Path current = DB_PATH.toAbsolutePath().normalize();
        Path parent = current.getParent();
        if (parent == null) return candidates;

        if (Files.isDirectory(parent)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, "data.db*")) {
                for (Path path : stream) {
                    String name = path.getFileName().toString();
                    if (isRetainedVsuBackupName(name)) addLegacyCandidate(candidates, current, path);
                }
            } catch (IOException e) {
                VonixServerUtilities.LOGGER.warn("[VonixSU] Could not scan VSU database directory: {}", e.getMessage());
            }
        }

        Path configRoot = parent.getParent() == null ? parent : parent.getParent();
        for (String directory : new String[]{
                "vonix_server_utilities-legacy",
                "vonix_server_utilities_backup",
                "vonix_server_utilities.old"}) {
            addLegacyCandidate(candidates, current, configRoot.resolve(directory).resolve("data.db"));
        }
        for (String file : new String[]{
                "vonix_server_utilities.db",
                "vonix_server_utilities.db.bak",
                "vonix_server_utilities.db.old"}) {
            addLegacyCandidate(candidates, current, configRoot.resolve(file));
        }

        candidates.sort((left, right) -> left.toString().compareTo(right.toString()));
        return candidates;
    }

    private boolean isRetainedVsuBackupName(String name) {
        return name.equals("data.db.bak")
                || name.equals("data.db.old")
                || name.equals("data.db.backup")
                || name.startsWith("data.db.bak-")
                || name.startsWith("data.db.old-")
                || name.startsWith("data.db.backup-");
    }

    private void addLegacyCandidate(List<Path> candidates, Path current, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (normalized.equals(current)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)
                || candidates.contains(normalized)) return;
        candidates.add(normalized);
    }

    private boolean isMigrationComplete(String key) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT value FROM vsu_migration WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "complete".equals(rs.getString(1).split(";", 2)[0]);
            }
        }
    }

    private void markMigration(String key, String value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO vsu_migration(key,value) VALUES(?,?)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }


    // ── Back-location persistence ─────────────────────────────────────────────

    public record BackLocation(String world, double x, double y, double z,
                               float yaw, float pitch, String kind, long updatedAt) {}

    /** Upsert a back-location for the given player. kind = "tp" or "death". */
    public void setBackLocation(java.util.UUID uuid, String world,
                                 double x, double y, double z, float yaw, float pitch,
                                 String kind) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO vsu_back_locations VALUES(?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, world);
            ps.setDouble(3, x);
            ps.setDouble(4, y);
            ps.setDouble(5, z);
            ps.setFloat (6, yaw);
            ps.setFloat (7, pitch);
            ps.setString(8, kind);
            ps.setLong  (9, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] setBackLocation failed", e);
        }
    }

    public BackLocation getBackLocation(java.util.UUID uuid, String kind) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT world,x,y,z,yaw,pitch,updated_at FROM vsu_back_locations WHERE uuid=? AND kind=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kind);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return new BackLocation(
                    rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                    rs.getFloat(5), rs.getFloat(6), kind, rs.getLong(7));
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] getBackLocation failed", e);
        }
        return null;
    }

    public void deleteBackLocation(java.util.UUID uuid, String kind) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM vsu_back_locations WHERE uuid=? AND kind=?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kind);
            ps.executeUpdate();
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] deleteBackLocation failed", e);
        }
    }

    /** All persisted back-locations (used to rehydrate the in-memory cache on startup). */
    public List<Object[]> getAllBackLocations() {
        List<Object[]> out = new ArrayList<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT uuid,world,x,y,z,yaw,pitch,kind,updated_at FROM vsu_back_locations")) {
            while (rs.next()) {
                out.add(new Object[]{
                        rs.getString(1), rs.getString(2),
                        rs.getDouble(3), rs.getDouble(4), rs.getDouble(5),
                        rs.getFloat(6), rs.getFloat(7),
                        rs.getString(8), rs.getLong(9)});
            }
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] getAllBackLocations failed", e);
        }
        return out;
    }

    // ── Nickname persistence ──────────────────────────────────────────────────

    public void setNickname(java.util.UUID uuid, String nickname) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR REPLACE INTO vsu_nicknames VALUES(?,?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, nickname);
            ps.setLong  (3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] setNickname failed", e);
        }
    }

    public String getNickname(java.util.UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT nickname FROM vsu_nicknames WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getString(1);
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] getNickname failed", e);
        }
        return null;
    }

    public void deleteNickname(java.util.UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM vsu_nicknames WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] deleteNickname failed", e);
        }
    }

    /** Returns all (uuid, nickname) pairs. */
    public List<String[]> getAllNicknames() {
        List<String[]> out = new ArrayList<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT uuid,nickname FROM vsu_nicknames")) {
            while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] getAllNicknames failed", e);
        }
        return out;
    }

    // ── Ignore-list persistence ───────────────────────────────────────────────

    public void addIgnore(java.util.UUID owner, java.util.UUID target) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO vsu_ignore_list VALUES(?,?,?)")) {
            ps.setString(1, owner.toString());
            ps.setString(2, target.toString());
            ps.setLong  (3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] addIgnore failed", e);
        }
    }

    public void removeIgnore(java.util.UUID owner, java.util.UUID target) {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM vsu_ignore_list WHERE owner_uuid=? AND target_uuid=?")) {
            ps.setString(1, owner.toString());
            ps.setString(2, target.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] removeIgnore failed", e);
        }
    }

    public List<String[]> getAllIgnores() {
        List<String[]> out = new ArrayList<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT owner_uuid,target_uuid FROM vsu_ignore_list")) {
            while (rs.next()) out.add(new String[]{rs.getString(1), rs.getString(2)});
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] getAllIgnores failed", e);
        }
        return out;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns the persistent connection. Must only be accessed from the DB executor thread. */
    public Connection getConnection() { return connection; }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                VonixServerUtilities.LOGGER.info("[VonixSU] Database connection closed.");
            }
        } catch (SQLException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] Error closing database", e);
        }
    }
}
