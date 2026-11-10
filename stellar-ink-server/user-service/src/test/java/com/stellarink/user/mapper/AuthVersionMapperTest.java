package com.stellarink.user.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.stellarink.user.pojo.User;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthVersionMapperTest {
    @Test
    void securityChangesAreAtomicAndStaleProfileCannotUndoThem() throws Exception {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:auth_version;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE `user` (id BIGINT PRIMARY KEY,username VARCHAR(50),password VARCHAR(100),"
                    + "nickname VARCHAR(50),signature VARCHAR(200),avatar_text VARCHAR(10),avatar_url VARCHAR(255),"
                    + "daily_goal INT,role VARCHAR(20),auth_version BIGINT DEFAULT 0 NOT NULL,"
                    + "role_applied_at TIMESTAMP,role_apply_note VARCHAR(200),created_at TIMESTAMP)");
            statement.execute("INSERT INTO `user` (id,username,password,role,auth_version,role_apply_note) "
                    + "VALUES (1,'author','old-hash','AUTHOR',0,'申请')");
        }
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("unittest", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(UserMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
            var mapper = session.getMapper(UserMapper.class);
            User stale = mapper.selectById(1L);
            assertThat(mapper.changeRoleAndInvalidate(1L, "READER")).isEqualTo(1);
            assertThat(mapper.changePasswordAndInvalidate(1L, "old-hash", "new-hash")).isEqualTo(1);
            assertThat(mapper.changePasswordAndInvalidate(1L, "old-hash", "racing-hash")).isZero();
            stale.setNickname("新笔名");
            // 旧实体不仅含旧版本，还含旧口令和旧角色；通用更新必须不能回写它们。
            mapper.updateById(stale);
            User actual = mapper.selectById(1L);
            assertThat(actual.getAuthVersion()).isEqualTo(2L);
            assertThat(actual.getPassword()).isEqualTo("new-hash");
            assertThat(actual.getRole()).isEqualTo("READER");
            assertThat(actual.getNickname()).isEqualTo("新笔名");
        }
    }
}
