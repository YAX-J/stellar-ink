package com.stellarink.user.bootstrap;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

/** 运维命令：不启动 HTTP、不注册 Nacos，密码从标准输入读取，不进入命令行参数。 */
public final class AdminBootstrap {
    public static final String DEMO_HASH = "$2b$10$/Y2gZHVQbuUI8VDF96YrDONvBeVPz4H44LX2bTrrTTGeRaf1nZN4a";

    private AdminBootstrap() { }

    public static void main(String[] args) throws Exception {
        String db = required("MYSQL_DB");
        if (!db.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("数据库名不合法");
        String url = "jdbc:mysql://" + System.getenv().getOrDefault("MYSQL_HOST", "mysql") + ":"
                + System.getenv().getOrDefault("MYSQL_PORT", "3306") + "/" + db
                + "?characterEncoding=utf8&serverTimezone=Asia/Shanghai&connectTimeout=3000&socketTimeout=15000";
        try (Connection connection = DriverManager.getConnection(url, required("MYSQL_USER"), required("MYSQL_PASSWORD"))) {
            try (var lock = connection.prepareStatement("SELECT GET_LOCK('stellar-ink-admin-bootstrap', 10)")) {
                try (var result = lock.executeQuery()) {
                    if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("初始化锁不可用");
                }
            }
            // 连接关闭自动释放命名锁；仅明确指定时停用仍使用公开种子口令的账号。
            if (args.length == 1 && "--retire-demo-accounts".equals(args[0])) {
                System.out.println("已停用公开口令账号数量：" + retireDemoAccounts(connection));
                return;
            }
            if (args.length != 0) throw new IllegalArgumentException("不支持的初始化参数");
            var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            createAdmin(connection, input.readLine(), input.readLine());
            System.out.println("管理员已创建；密码不回显。请登录后妥善保管凭据。");
        }
    }

    static void createAdmin(Connection connection, String username, String password) throws Exception {
        if (username == null || !username.matches("[A-Za-z0-9_]{3,50}")) {
            throw new IllegalArgumentException("登录名需为 3–50 位字母、数字或下划线");
        }
        if (password == null || password.length() < 16 || password.length() > 64
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("密码需 16–64 字符且 UTF-8 不超过 BCrypt 的 72 字节限制");
        }
        connection.setAutoCommit(false);
        try {
            try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM `user` WHERE role='ADMIN'")) {
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (result.getLong(1) != 0) throw new IllegalStateException("已有管理员，拒绝重复初始化或覆盖");
                }
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO `user` (username,password,nickname,role,auth_version,daily_goal,created_at) "
                            + "VALUES (?,?,?,'ADMIN',0,500,CURRENT_TIMESTAMP)")) {
                statement.setString(1, username);
                statement.setString(2, new BCryptPasswordEncoder().encode(password));
                statement.setString(3, username);
                statement.executeUpdate();
            }
            connection.commit();
        } catch (Exception ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static int retireDemoAccounts(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement(
                "UPDATE `user` SET password=?,role='READER',auth_version=auth_version+1 WHERE password=?")) {
            statement.setString(1, new BCryptPasswordEncoder().encode(UUID.randomUUID().toString()));
            statement.setString(2, DEMO_HASH);
            return statement.executeUpdate();
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少环境变量：" + key);
        return value;
    }
}
