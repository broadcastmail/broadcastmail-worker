package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.campaign.filter.FilterQuery;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.worker.resolution.dto.RecipientRow;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class ExternalRecipientQueryService {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[a-zA-Z_]\\w*");
    private static final String SELECT_CLAUSE = "SELECT u.\"id\", u.\"email\"";
    private static final String SELECT_BY_IDS_SQL = "SELECT u.\"id\", u.\"email\" FROM auth.user_emails u WHERE u.id = ANY(?)";

    public List<RecipientRow> resolve(Connection connection, String rolePassword,
                                      FilterQuery filterQuery, int offset, int batchSize) {
        validateColumnName(connection.getUserIdColumn());

        String base = filterQuery.sql().isBlank()
                ? SELECT_CLAUSE + " FROM auth.user_emails u"
                : SELECT_CLAUSE + " FROM auth.user_emails u"
                    + " JOIN " + connection.getUserTableSchema() + "." + connection.getUserTableName() //NOSONAR — schema/table come from trusted Connection entity
                    + " p ON p.\"" + connection.getUserIdColumn() + "\" = u.id";

        String sql = (filterQuery.sql().isBlank() ? base : base + " " + filterQuery.sql()) //NOSONAR — filterQuery.sql() is produced by CampaignFilterSerializer, not user input
                + " LIMIT " + batchSize + " OFFSET " + offset;

        // TODO: CWE-316 — accept char[] and zero out after DriverManager.getConnection()
        try (java.sql.Connection conn = openConnection(connection, rolePassword);
             PreparedStatement stmt = conn.prepareStatement(sql)) { //NOSONAR — sql built from trusted FilterQuery and validated Connection fields only

            for (int i = 0; i < filterQuery.parameters().size(); i++) {
                stmt.setObject(i + 1, filterQuery.parameters().get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                return mapRows(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve recipients: " + e.getMessage(), e);
        }
    }

    public List<RecipientRow> resolveByIds(Connection connection, String rolePassword,
                                           List<String> externalUserIds) {
        if (externalUserIds.isEmpty()) return List.of();

        try (java.sql.Connection conn = openConnection(connection, rolePassword)) {
            Array sqlArray = conn.createArrayOf("text", externalUserIds.toArray(new String[0]));
            try (PreparedStatement stmt = conn.prepareStatement(SELECT_BY_IDS_SQL)) {
                stmt.setArray(1, sqlArray);
                try (ResultSet rs = stmt.executeQuery()) {
                    return mapRows(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to resolve recipients by IDs: " + e.getMessage(), e);
        }
    }

    private List<RecipientRow> mapRows(ResultSet rs) throws SQLException {
        List<RecipientRow> rows = new ArrayList<>();
        while (rs.next()) {
            rows.add(new RecipientRow(rs.getString("id"), rs.getString("email")));
        }
        return rows;
    }

    private java.sql.Connection openConnection(Connection connection, String rolePassword) throws SQLException {
        String jdbcUrl = "jdbc:postgresql://db." + connection.getProjectRef() + ".supabase.co:6543/postgres?prepareThreshold=0";
        return DriverManager.getConnection(jdbcUrl, "broadcastmail_reader", rolePassword);
    }

    private void validateColumnName(String name) {
        if (!IDENTIFIER_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid column name: " + name);
        }
    }
}
