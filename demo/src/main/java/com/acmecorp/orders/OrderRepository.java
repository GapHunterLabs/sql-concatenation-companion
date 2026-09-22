package com.acmecorp.orders;

import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.SQLException;

public class OrderRepository {

    private static final String TABLE_ORDERS = "orders";
    private static final String KEY_STATUS = "status";

    // Real risk: unparameterized concatenation, executed -- SHOULD be flagged.
    void findVulnerable(Statement stmt, String userId) throws SQLException {
        stmt.executeQuery("SELECT * FROM orders WHERE user_id = " + userId);
    }

    // Constants first, the variable last -- SHOULD be flagged, naming 'status'.
    void findByStatus(Statement stmt, String status) throws SQLException {
        stmt.executeQuery("SELECT * FROM " + TABLE_ORDERS + " WHERE " + KEY_STATUS + " = '" + status + "'");
    }

    // Only constants and a bound '?' -- should NOT be flagged.
    void findByStatusSafe(Connection conn, String status) throws SQLException {
        PreparedStatement ps = conn.prepareStatement("SELECT * FROM " + TABLE_ORDERS + " WHERE " + KEY_STATUS + " = ?");
        ps.setString(1, status);
        ps.executeQuery();
    }

    // An int can't carry SQL -- should NOT be flagged.
    void findById(Statement stmt, int orderId) throws SQLException {
        stmt.executeQuery("SELECT * FROM orders WHERE id = " + orderId);
    }

    // Two constant literals concatenated -- pure formatting, should NOT be flagged.
    String buildBaseQuery() {
        return "SELECT * FROM " + "orders";
    }

    // Already parameterized correctly -- should NOT be flagged.
    void findSafe(PreparedStatement stmt) throws SQLException {
        stmt.executeQuery();
    }
}
