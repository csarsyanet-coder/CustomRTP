package dev.arsyadev.proxycoinshttp;

import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.Optional;

public final class ProxyDatabase {
    private final Path databasePath;
    private final Logger logger;
    private Connection connection;

    public ProxyDatabase(Path databasePath, Logger logger) {
        this.databasePath = databasePath;
        this.logger = logger;
    }

   public synchronized void initialize() {
    try {
        Class.forName("org.sqlite.JDBC");

        String jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        this.connection = DriverManager.getConnection(jdbcUrl);

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, last_name TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, balance INTEGER NOT NULL DEFAULT 0)");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS processed_transactions (trx_id TEXT PRIMARY KEY, processed_at INTEGER NOT NULL)");
            statement.executeUpdate("PRAGMA journal_mode=WAL");
        }
    } catch (ClassNotFoundException exception) {
        throw new IllegalStateException("Driver SQLite tidak ditemukan", exception);
    } catch (SQLException exception) {
        throw new IllegalStateException("Gagal inisialisasi SQLite", exception);
    }
}

    public synchronized void upsertPlayer(String uuid, String username) {
        long now = Instant.now().getEpochSecond();
        try (PreparedStatement st = connection.prepareStatement("INSERT INTO players(uuid,last_name,first_seen,last_seen,balance) VALUES(?,?,?,?,0) ON CONFLICT(uuid) DO UPDATE SET last_name=excluded.last_name,last_seen=excluded.last_seen")) {
            st.setString(1, uuid); st.setString(2, username); st.setLong(3, now); st.setLong(4, now); st.executeUpdate();
        } catch (SQLException e) {
            logger.warn("[ProxyCoinsHttpCore] Gagal simpan player {}: {}", username, e.getMessage());
        }
    }

    public synchronized long getBalance(String uuid, String usernameIfMissing) {
        try (PreparedStatement st = connection.prepareStatement("SELECT balance FROM players WHERE uuid=?")) {
            st.setString(1, uuid);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getLong("balance");
            }
        } catch (SQLException e) {
            logger.warn("[ProxyCoinsHttpCore] Gagal ambil balance {}: {}", uuid, e.getMessage());
        }
        if (usernameIfMissing != null && !usernameIfMissing.isBlank()) upsertPlayer(uuid, usernameIfMissing);
        return 0L;
    }

    public synchronized long addBalance(String uuid, String username, long delta) {
        upsertPlayer(uuid, username);
        long newBalance = Math.max(0L, getBalance(uuid, username) + delta);
        setBalance(uuid, username, newBalance);
        return newBalance;
    }

    public synchronized void setBalance(String uuid, String username, long amount) {
        upsertPlayer(uuid, username);
        try (PreparedStatement st = connection.prepareStatement("UPDATE players SET balance=?, last_name=?, last_seen=? WHERE uuid=?")) {
            st.setLong(1, Math.max(0L, amount));
            st.setString(2, username);
            st.setLong(3, Instant.now().getEpochSecond());
            st.setString(4, uuid);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Gagal set balance", e);
        }
    }

    public synchronized boolean isProcessed(String trxId) {
        try (PreparedStatement st = connection.prepareStatement("SELECT 1 FROM processed_transactions WHERE trx_id=?")) {
            st.setString(1, trxId);
            try (ResultSet rs = st.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            logger.warn("[ProxyCoinsHttpCore] Gagal cek trx {}: {}", trxId, e.getMessage());
            return false;
        }
    }

    public synchronized void markProcessed(String trxId) {
        try (PreparedStatement st = connection.prepareStatement("INSERT OR IGNORE INTO processed_transactions(trx_id,processed_at) VALUES(?,?)")) {
            st.setString(1, trxId); st.setLong(2, Instant.now().getEpochSecond()); st.executeUpdate();
        } catch (SQLException e) {
            logger.warn("[ProxyCoinsHttpCore] Gagal tandai trx {}: {}", trxId, e.getMessage());
        }
    }

    public synchronized void close() {
        if (connection != null) try { connection.close(); } catch (SQLException ignored) {}
    }
}
