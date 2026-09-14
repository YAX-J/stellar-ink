package com.stellarink.user.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 对象存储（腾讯云 COS）配置。
 *
 * <p>安全：密钥<b>刻意不作为配置项</b>（不绑 {bucket}/{@code secret-id} 之类的配置键），
 * 只允许从环境变量 {@code COS_SECRET_ID} / {@code COS_SECRET_KEY} 读取，与
 * {@code MYSQL_PASSWORD}、{@code SA_TOKEN_JWT_SECRET} 同口径 —— 这样即便有人往 Nacos
 * 或 yml 里写了密钥也不会生效，避免密钥被顺手提交进仓库。
 * 生产建议用 CAM 子账号密钥并限定到单个桶（最小权限），详见 docs/architecture/avatar-minio.md。
 */
@Data
@Component
@ConfigurationProperties(prefix = "stellar.ink.storage")
public class StorageProperties {

    /**
     * 存储类型：{@code local}（默认，本地磁盘）或 {@code cos}（腾讯云对象存储）。
     * <p>这个开关就是「换存储」和「回滚」的唯一入口。
     */
    private String type = "local";

    private Cos cos = new Cos();

    @Data
    public static class Cos {

        /** 存储桶全名，<b>必须带 APPID 后缀</b>，如 stellar-ink-avatars-1459736092 */
        private String bucket;

        /** 地域，如 ap-shanghai（不是「上海」） */
        private String region;

        /** CAM 子账号 SecretId：只从环境变量注入，配置文件里的同名项会被这里覆盖 */
        private String secretId;

        /** CAM 子账号 SecretKey：只从环境变量注入 */
        private String secretKey;

        public String getSecretId() {
            return secretId != null ? secretId : System.getenv("COS_SECRET_ID");
        }

        public String getSecretKey() {
            return secretKey != null ? secretKey : System.getenv("COS_SECRET_KEY");
        }

        /**
         * 对外访问基址：自定义 CDN 域名（推荐，如 https://cdn.example.com）或 COS 默认域名。
         * <p>留空则按 {@code https://{bucket}.cos.{region}.myqcloud.com} 推导。
         * <p>⚠️ 这里必须是**浏览器能访问到**的地址：写成内网端点或 localhost 会导致图片全站 404。
         */
        private String publicBase = "";

        /** 对象键前缀 */
        private String keyPrefix = "avatars/";

        /** 连接/读取超时（毫秒） */
        private int connectionTimeoutMs = 5000;

        private int socketTimeoutMs = 10000;

        /** 是否在启动时检查桶是否存在（只告警不阻塞启动） */
        private boolean checkBucketOnStartup = true;
    }
}
