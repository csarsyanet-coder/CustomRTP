package dev.arsyadev.proxycoinshttp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

public final class ProxyDatabase {

    private final Path databasePath;
    private Connection connection;

    public ProxyDatabase(Path databasePath) {
        this.databasePath = databasePath;
    }

    public synchronized void initialize() {
        try {
            Files.createDirectories(databasePath.getParent());
            Class.forName("org.sqlite.JDBC");

            String jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
            this.connection = DriverManager.getConnection(jdbcUrl);

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS players (
                        uuid TEXT PRIMARY KEY,
                        last_name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                        first_seen INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL,
                        balance INTEGER NOT NULL DEFAULT 0
                    )
                    """);

                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS processed_transactions (
                        trx_id TEXT PRIMARY KEY,
                        processed_at INTEGER NOT NULL
                    )
                    """);

                statement.executeUpdate("PRAGMA journal_mode=WAL");
            }
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Driver SQLite tidak ditemukan", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Gagal inisialisasi SQLite", exception);
        }
    }

    public synchronized void close() {
        if (connection == null) {
            return;
        }

        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }

    public synchronized void upsertPlayer(UUID uuid, String username) {
        long now = System.currentTimeMillis();

        try (PreparedStatement select = connection.prepareStatement(
                "SELECT uuid FROM players WHERE uuid = ?")) {
            select.setString(1, uuid.toString());

            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) {
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE players SET last_name = ?, last_seen = ? WHERE uuid = ?")) {
                        update.setString(1, username);
                        update.setLong(2, now);
                        update.setString(3, uuid.toString());
                        update.executeUpdate();
                    }
                } else {
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO players (uuid, last_name, first_seen, last_seen, balance) VALUES (?, ?, ?, ?, 0)")) {
                        insert.setString(1, uuid.toString());
                        insert.setString(2, username);
                        insert.setLong(3, now);
                        insert.setLong(4, now);
                        insert.executeUpdate();
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Gagal upsert player", exception);
        }
    }

    public synchronized boolean playerExistsByName(String username) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM players WHERE last_name = ? COLLATE NOCASE LIMIT 1")) {
            statement.setString(1, username);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Gagal cek player", exception);
        }
    }

    public synchronized long getBalanceByName(String username) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance FROM players WHERE last_name = ? COLLATE NOCASE LIMIT 1")) {
            statement.setString(1, username);

            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("balance");
                }
                return -1L;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Gagal ambil balance by name", exception);
        }
    }

    public synchronized long getBalanceByUuid(UUID uuid) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT balance FROM players WHERE uuid = ? LIMIT 1")) {
            statement.setString(1, uuid.toString());

            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("balance");
                }
                return -1L;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Gagal ambil balance by uuid", exception);
        }
    }

    public synchronized long addBalanceByName(String username, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount tidak boleh minus");
        }

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE players SET balance = balance + ? WHERE last_name = ? COLLATE NOCASE")) {
            update.setLong(1, amount);
            update.setString(2, username);
            int rows = update.executeUpdate();

            if (rows == 0) {
                return -1L;
            }

            return getBalanceByName(username);
        } catch (SQLException exception) {
            throw new IllegalStateException("Gagal tambah balance", exception);
        }
    }

    public synchronized long takeBalanceByName(String username, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount tidak boleh minus");
        }

        long current = getBalanceByName(username);
        if (current < 0) {
            return -1L;
        }

        long next = Math.max(0L, current - amount);

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE players SET balance = ? WHERE last_name = ? COLLATE NOCASE")) {
            update.setLong(1, next);
            update.setString(2, username);
            update.executeUpdate();
            return next;
        } catch (SQLException exception) {
            throw new IllegalStateException("Gagal kurangi balance", exception);
        }
    }

    public synchronized long setBalanceByName(String username, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Amount tidak boleh minus");
        }

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE players SET balance = ? WHERE last_name = ? COLLATE NOCASE")) {
            update.setLong(1, amount);
            update.setString(2, username);
            int rows = update.executeUpdate();

            if (rows == 0) {
                return -1L;
            }

            return amount;
        } catch (SQLException exception) {
            throw new IllegalStateException("Gagal set balance", exception);
        }
    }

    public synchronized boolean isProcessed(String trxId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM processed_transactions WHERE trx_id = ? LIMIT 1")) {
            statement.setString(1, trxId);

            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Gagal cek transaksi", exception);
        }
    }

    public synchronized void markProcessed(String trxId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO processed_transactions (trx_id, processed_at) VALUES (?, ?)")) {
            statement.setString(1, trxId);
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Gagal mark transaksi", exception);
        }
    }
}
