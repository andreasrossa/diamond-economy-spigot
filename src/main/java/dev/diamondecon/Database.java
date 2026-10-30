package dev.diamondecon;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Single SQLite connection, used only from the main server thread.
 * The schema is meant to be read directly by the future dashboard.
 */
public final class Database implements AutoCloseable {

    private final Connection connection;

    public Database(File file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA busy_timeout=5000");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        uuid    TEXT PRIMARY KEY,
                        name    TEXT NOT NULL,
                        balance INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0)
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_accounts_name ON accounts(name COLLATE NOCASE)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at  INTEGER NOT NULL,
                        type        TEXT NOT NULL,
                        from_uuid   TEXT,
                        to_uuid     TEXT,
                        amount      INTEGER NOT NULL CHECK (amount > 0),
                        item        TEXT,
                        item_amount INTEGER,
                        shop_id     INTEGER
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_from ON transactions(from_uuid, created_at)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tx_to ON transactions(to_uuid, created_at)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS shops (
                        id          INTEGER PRIMARY KEY AUTOINCREMENT,
                        owner_uuid  TEXT NOT NULL,
                        owner_name  TEXT NOT NULL,
                        world       TEXT NOT NULL,
                        x           INTEGER NOT NULL,
                        y           INTEGER NOT NULL,
                        z           INTEGER NOT NULL,
                        item        TEXT NOT NULL,
                        item_type   TEXT NOT NULL,
                        quantity    INTEGER NOT NULL CHECK (quantity > 0),
                        price       INTEGER NOT NULL CHECK (price > 0),
                        stock       INTEGER NOT NULL DEFAULT 0,
                        created_at  INTEGER NOT NULL,
                        UNIQUE (world, x, y, z)
                    )""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_shops_item ON shops(item_type)");
        }
    }

    public Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
