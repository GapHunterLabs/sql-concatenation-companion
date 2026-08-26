package com.acmecorp.orders;

import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.Connection;
import java.sql.SQLException;

public class OrderRepository {

    // Real risk: unparameterized concatenation, executed -- SHOULD be flagged.
    void findVulnerable(Statement stmt, String userId) throws SQLException {
        stmt.executeQuery("SELECT * FROM orders WHERE user_id = " + userId);
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
