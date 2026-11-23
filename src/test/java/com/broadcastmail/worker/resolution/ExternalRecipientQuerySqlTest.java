package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.campaign.filter.CampaignFilter;
import com.broadcastmail.common.campaign.filter.CampaignFilterSerializer;
import com.broadcastmail.common.campaign.filter.FilterOperator;
import com.broadcastmail.common.campaign.filter.FilterQuery;
import com.broadcastmail.common.campaign.filter.FilterSource;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.common.connection.ReaderConnectionFactory;
import com.broadcastmail.common.connection.RecipientSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ExternalRecipientQuerySqlTest {

    private final ExternalRecipientQueryService service = new ExternalRecipientQueryService();

    @Test
    void shouldSendToConfiguredEmailTableWithQualifiedFilterColumns() throws Exception {
        // Given — email source public.contacts, profile table public.profiles, both have created_at
        Connection connection = connection("contacts");
        FilterQuery filters = new CampaignFilterSerializer().serialize(List.of(
                filter("created_at", FilterSource.PROFILE_TABLE, "7")), "AND",
                RecipientSource.of(connection).filterAliases());

        // When
        String sql = capturedSql(connection, () -> service.resolve(connection, "pw", filters, 0, 100));

        // Then
        assertThat(sql)
                .contains("SELECT email.\"id\"::text AS \"id\", email.\"email\" AS \"email\"")
                .contains("FROM \"public\".\"contacts\" email LEFT JOIN \"public\".\"profiles\" linked ON linked.\"id\" = email.\"id\"")
                .contains("WHERE linked.\"created_at\" > NOW()")
                .contains("ORDER BY email.\"id\"")
                .doesNotContain("user_emails");
    }

    @Test
    void shouldReadProfileColumnsFromEmailTableWhenItIsAlsoTheProfileTable() throws Exception {
        // Given
        Connection connection = connection("profiles");
        FilterQuery filters = new CampaignFilterSerializer().serialize(List.of(
                filter("country", FilterSource.PROFILE_TABLE, "HR")), "AND",
                RecipientSource.of(connection).filterAliases());

        // When
        String sql = capturedSql(connection, () -> service.resolve(connection, "pw", filters, 0, 100));

        // Then
        assertThat(sql).doesNotContain("JOIN").contains("WHERE email.\"country\" = ?");
    }

    private interface Call { void run(); }

    private String capturedSql(Connection connection, Call call) throws Exception {
        ReaderConnectionFactory factory = mock(ReaderConnectionFactory.class);
        java.sql.Connection jdbc = mock(java.sql.Connection.class);
        PreparedStatement stmt = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(connection.readerConnectionFactory()).thenReturn(factory);
        when(factory.open(anyString(), anyInt())).thenReturn(jdbc);
        when(jdbc.prepareStatement(anyString())).thenReturn(stmt);
        when(stmt.executeQuery()).thenReturn(rs);
        call.run();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).prepareStatement(sql.capture());
        return sql.getValue();
    }

    private Connection connection(String emailTable) {
        Connection c = mock(Connection.class);
        when(c.getEmailColumn()).thenReturn("email");
        when(c.getEmailTableSchema()).thenReturn("public");
        when(c.getEmailTableName()).thenReturn(emailTable);
        when(c.getEmailTableIdColumn()).thenReturn("id");
        when(c.getUserTableSchema()).thenReturn("public");
        when(c.getUserTableName()).thenReturn("profiles");
        when(c.getUserIdColumn()).thenReturn("id");
        return c;
    }

    private CampaignFilter filter(String column, FilterSource source, String value) {
        FilterOperator op = "created_at".equals(column) ? FilterOperator.WITHIN : FilterOperator.EQ;
        return CampaignFilter.builder().columnName(column).operator(op).filterValue(value)
                .filterOrder(0).source(source).build();
    }
}
