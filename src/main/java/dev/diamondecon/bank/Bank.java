package dev.diamondecon.bank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Diamond-backed accounts. Every balance change goes through a SQL transaction
 * and is written to the ledger, so balances can always be explained.
 */
public final class Bank {

    public enum TxType { DEPOSIT, WITHDRAW, PAY, SHOP_PURCHASE }

    public record Account(UUID uuid, String name, long balance) {}

    public record Txn(long createdAt, TxType type, UUID from, String fromName, UUID to, String toName,
                      long amount, String item, int itemAmount) {}

    public record Stats(long deposited, long withdrawn, long sent, long received,
                        long shopIncome, long shopSpent, int count) {}

    @FunctionalInterface
    private interface Work {
        boolean run() throws SQLException;
    }

    private final Connection c;

    public Bank(Connection connection) {
        this.c = connection;
    }

    public void ensureAccount(UUID id, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO accounts(uuid, name) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name")) {
            ps.setString(1, id.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    public long balance(UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM accounts WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public Optional<Account> findByName(String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, name, balance FROM accounts WHERE name = ? COLLATE NOCASE LIMIT 1")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(account(rs)) : Optional.empty();
            }
        }
    }

    public List<Account> top(int limit) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, name, balance FROM accounts WHERE balance > 0 ORDER BY balance DESC LIMIT ?")) {
            ps.setInt(1, limit);
            List<Account> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(account(rs));
            }
            return out;
        }
    }

    public void deposit(UUID id, long amount) throws SQLException {
        atomically(() -> {
            credit(id, amount);
            log(TxType.DEPOSIT, null, id, amount, null, 0, null);
            return true;
        });
    }

    /** @return false if the balance is too low */
    public boolean withdraw(UUID id, long amount) throws SQLException {
        return atomically(() -> {
            if (!debit(id, amount)) return false;
            log(TxType.WITHDRAW, id, null, amount, null, 0, null);
            return true;
        });
    }

    /** @return false if the sender's balance is too low */
    public boolean transfer(UUID from, UUID to, long amount, TxType type,
                            String item, int itemAmount, Long shopId) throws SQLException {
        return atomically(() -> {
            if (!debit(from, amount)) return false;
            credit(to, amount);
            log(type, from, to, amount, item, itemAmount, shopId);
            return true;
        });
    }

    public List<Txn> history(UUID id, int limit, int offset) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT t.created_at, t.type, t.from_uuid, f.name, t.to_uuid, r.name, t.amount, t.item, t.item_amount
                FROM transactions t
                LEFT JOIN accounts f ON f.uuid = t.from_uuid
                LEFT JOIN accounts r ON r.uuid = t.to_uuid
                WHERE t.from_uuid = ?1 OR t.to_uuid = ?1
                ORDER BY t.id DESC LIMIT ?2 OFFSET ?3""")) {
            ps.setString(1, id.toString());
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            List<Txn> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Txn(rs.getLong(1), TxType.valueOf(rs.getString(2)),
                            uuid(rs.getString(3)), rs.getString(4), uuid(rs.getString(5)), rs.getString(6),
                            rs.getLong(7), rs.getString(8), rs.getInt(9)));
                }
            }
            return out;
        }
    }

    public Stats stats(UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT
                  COALESCE(SUM(CASE WHEN type = 'DEPOSIT'       AND to_uuid   = ?1 THEN amount END), 0),
                  COALESCE(SUM(CASE WHEN type = 'WITHDRAW'      AND from_uuid = ?1 THEN amount END), 0),
                  COALESCE(SUM(CASE WHEN type = 'PAY'           AND from_uuid = ?1 THEN amount END), 0),
                  COALESCE(SUM(CASE WHEN type = 'PAY'           AND to_uuid   = ?1 THEN amount END), 0),
                  COALESCE(SUM(CASE WHEN type = 'SHOP_PURCHASE' AND to_uuid   = ?1 THEN amount END), 0),
                  COALESCE(SUM(CASE WHEN type = 'SHOP_PURCHASE' AND from_uuid = ?1 THEN amount END), 0),
                  COUNT(*)
                FROM transactions WHERE from_uuid = ?1 OR to_uuid = ?1""")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Stats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getInt(7));
            }
        }
    }

    private boolean debit(UUID id, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE accounts SET balance = balance - ?1 WHERE uuid = ?2 AND balance >= ?1")) {
            ps.setLong(1, amount);
            ps.setString(2, id.toString());
            return ps.executeUpdate() == 1;
        }
    }

    private void credit(UUID id, long amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE accounts SET balance = balance + ? WHERE uuid = ?")) {
            ps.setLong(1, amount);
            ps.setString(2, id.toString());
            if (ps.executeUpdate() != 1) throw new SQLException("No account for " + id);
        }
    }

    private void log(TxType type, UUID from, UUID to, long amount, String item, int itemAmount, Long shopId)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO transactions(created_at, type, from_uuid, to_uuid, amount, item, item_amount, shop_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, type.name());
            ps.setString(3, from == null ? null : from.toString());
            ps.setString(4, to == null ? null : to.toString());
            ps.setLong(5, amount);
            ps.setString(6, item);
            if (item == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, itemAmount);
            if (shopId == null) ps.setNull(8, Types.INTEGER); else ps.setLong(8, shopId);
            ps.executeUpdate();
        }
    }

    private boolean atomically(Work work) throws SQLException {
        c.setAutoCommit(false);
        try {
            boolean ok = work.run();
            if (ok) c.commit(); else c.rollback();
            return ok;
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private static Account account(ResultSet rs) throws SQLException {
        return new Account(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3));
    }

    private static UUID uuid(String s) {
        return s == null ? null : UUID.fromString(s);
    }
}
