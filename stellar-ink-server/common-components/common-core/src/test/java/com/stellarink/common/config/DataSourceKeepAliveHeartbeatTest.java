package com.stellarink.common.config;

import com.alibaba.druid.pool.DruidDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JDBC 保活心跳的回归测试。
 *
 * <p>最要害的一条是「**同时借出 N 条不同的连接**」：Druid 的借用是 LIFO
 * （取最近归还的那条），若心跳一条一条借还，就会反复热同一条 ——
 * 池里其余连接照旧闲到被 NAT 丢掉，业务借到它们时卡 15 秒
 * （这正是实测到的现象：MySQL 侧一部分连接每十秒被 ping、另一部分闲置上千秒）。
 */
class DataSourceKeepAliveHeartbeatTest {

    /** 只改「每次热几条」，其余走真实实现 */
    private static DataSourceKeepAliveHeartbeat heartbeatOf(DataSource dataSource, int perBeat) {
        return new DataSourceKeepAliveHeartbeat(dataSource, Duration.ofSeconds(30)) {
            @Override
            int connectionsPerBeat() {
                return perBeat;
            }
        };
    }

    @Test
    @DisplayName("一次心跳同时热 N 条连接，各发一条 SELECT 1，并全部归还")
    void touchesEveryPooledConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        List<Connection> connections = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            ResultSet resultSet = mock(ResultSet.class);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            connections.add(connection);
        }
        when(dataSource.getConnection()).thenReturn(
                connections.get(0), connections.get(1), connections.get(2));

        heartbeatOf(dataSource, 3).beat();

        // 同时借出三条不同的连接（不是借一条还一条）
        verify(dataSource, times(3)).getConnection();
        for (Connection connection : connections) {
            verify(connection).createStatement();
            verify(connection).close();
        }
    }

    @Test
    @DisplayName("Druid 池：每次热 initial-size 条（稳态下池里就这么多）")
    void usesDruidInitialSize() {
        DruidDataSource druid = mock(DruidDataSource.class);
        when(druid.getInitialSize()).thenReturn(5);

        assertEquals(5, new DataSourceKeepAliveHeartbeat(druid, Duration.ofSeconds(30)).connectionsPerBeat());
    }

    @Test
    @DisplayName("非 Druid 数据源兜底只热一条")
    void fallsBackToOneConnection() {
        assertEquals(1, new DataSourceKeepAliveHeartbeat(
                mock(DataSource.class), Duration.ofSeconds(30)).connectionsPerBeat());
    }

    @Test
    @DisplayName("拿不到连接只记日志，不抛（坏连接交给 Druid 淘汰）")
    void swallowsFailure() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("Communications link failure"));

        DataSourceKeepAliveHeartbeat heartbeat = new DataSourceKeepAliveHeartbeat(dataSource, Duration.ofSeconds(30));
        assertDoesNotThrow(heartbeat::beat);
        assertDoesNotThrow(heartbeat::beat);
    }

    @Test
    @DisplayName("生命周期：守护线程 start/stop，start 幂等")
    void lifecycle() {
        DataSourceKeepAliveHeartbeat heartbeat =
                new DataSourceKeepAliveHeartbeat(mock(DataSource.class), Duration.ofMillis(50));
        assertFalse(heartbeat.isRunning());
        heartbeat.start();
        assertTrue(heartbeat.isRunning());
        heartbeat.start();
        assertTrue(heartbeat.isRunning());
        heartbeat.stop();
        assertFalse(heartbeat.isRunning());
    }
}
