/**
 * Integration tests against local Supabase instance.
 * Prerequisites:
 *   1. Run: supabase start
 *   2. Run setup SQL in Studio (http://127.0.0.1:54323):
 *      - CREATE ROLE broadcastmail_reader NOINHERIT LOGIN PASSWORD 'testpassword123';
 *      - GRANT USAGE ON SCHEMA public TO broadcastmail_reader;
 *      - GRANT USAGE ON SCHEMA _broadcastmail TO broadcastmail_reader;
 *      - GRANT SELECT ON ALL TABLES IN SCHEMA public TO broadcastmail_reader;
 *      - CREATE SCHEMA _broadcastmail; CREATE VIEW _broadcastmail.user_emails AS SELECT id, email FROM auth.users;
 *      - GRANT SELECT ON _broadcastmail.user_emails TO broadcastmail_reader;
 *      - INSERT 3 test users into auth.users and public.profiles (2 free, 1 pro)
 */

package com.broadcastmail.worker.resolution;

import com.broadcastmail.common.campaign.filter.FilterQuery;
import com.broadcastmail.common.connection.Connection;
import com.broadcastmail.worker.IntegrationTest;
import com.broadcastmail.worker.TestContainersConfiguration;
import com.broadcastmail.worker.resolution.dto.RecipientRow;
import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.Socket;
import java.util.List;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
@Import(TestContainersConfiguration.class)
@Tag("local")
class ExternalRecipientQueryServiceTest extends IntegrationTest {

    @Autowired
    private ExternalRecipientQueryService service;

    @Value("${test.supabase.role-password}")
    private String rolePassword;

    private Connection connection;

    @BeforeEach
    void setUp() {
        assumeTrue(isSupabaseReachable(), "Local Supabase not running — skipping");
        connection = Connection.builder()
                .projectRef("127.0.0.1:54322")
                .userIdColumn("id")
                .emailColumn("email")
                .userTableName("profiles")
                .userTableSchema("public")
                .build();
    }

    private boolean isSupabaseReachable() {
        try (Socket s = new Socket("127.0.0.1", 54322)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void shouldReturnAllUsersWhenNoFiltersApplied() {
        // Given
        FilterQuery filterQuery = new FilterQuery("", List.of());

        // When
        List<RecipientRow> result = service.resolve(connection, rolePassword, filterQuery, 0, 100);

        // Then
        assertThat(result)
                .isNotEmpty()
                .allSatisfy(row -> {
                    AssertionsForClassTypes.assertThat(row.userId()).isNotBlank();
                    AssertionsForClassTypes.assertThat(row.email()).isNotBlank();
                });
    }

    @Test
    void shouldReturnOnlyMatchingUsersWhenFiltersApplied() {
        // Given
        FilterQuery filterQuery = new FilterQuery("WHERE \"plan\" = ?", List.of("free"));

        // When
        List<RecipientRow> result = service.resolve(connection, rolePassword, filterQuery, 0, 100);

        // Then
        assertThat(result).isNotEmpty();
        assertThat(result).hasSizeLessThan(3);
    }

    @Test
    void shouldReturnEmptyListWhenNoUsersMatch() {
        // Given
        FilterQuery filterQuery = new FilterQuery("WHERE \"plan\" = ?", List.of("enterprise"));

        // When
        List<RecipientRow> result = service.resolve(connection, rolePassword, filterQuery, 0, 100);

        // Then
        assertThat(result).isEmpty();
    }
}