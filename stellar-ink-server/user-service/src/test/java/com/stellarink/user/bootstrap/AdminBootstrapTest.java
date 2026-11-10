package com.stellarink.user.bootstrap;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AdminBootstrapTest {
    @Test
    void rejectsPasswordsThatCannotPassLoginValidation() {
        assertThatThrownBy(() -> AdminBootstrap.createAdmin(null, "new_admin", "a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AdminBootstrap.createAdmin(null, "new_admin", "密".repeat(25)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createsOneAdminAndRetiresOnlyUnchangedDemoCredentials() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL")) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE `user` (username VARCHAR(50) UNIQUE,password VARCHAR(100),"
                        + "nickname VARCHAR(50),role VARCHAR(20),auth_version BIGINT,daily_goal INT,created_at TIMESTAMP)");
            }
            AdminBootstrap.createAdmin(connection, "new_admin", "a-long-private-password-123");
            assertThatThrownBy(() -> AdminBootstrap.createAdmin(connection, "second_admin", "another-long-password-123"))
                    .isInstanceOf(IllegalStateException.class);
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT password FROM `user`")) {
                result.next();
                assertThat(new BCryptPasswordEncoder().matches("a-long-private-password-123", result.getString(1))).isTrue();
            }
            try (var statement = connection.prepareStatement("INSERT INTO `user` (username,password,role,auth_version) VALUES (?,?,?,0)")) {
                statement.setString(1, "demo");
                statement.setString(2, AdminBootstrap.DEMO_HASH);
                statement.setString(3, "ADMIN");
                statement.executeUpdate();
            }
            assertThat(AdminBootstrap.retireDemoAccounts(connection)).isEqualTo(1);
            assertThat(AdminBootstrap.retireDemoAccounts(connection)).isZero();
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT role,auth_version FROM `user` WHERE username='demo'")) {
                result.next();
                assertThat(result.getString(1)).isEqualTo("READER");
                assertThat(result.getLong(2)).isEqualTo(1);
            }
        }
    }
}
