package com.stellarink.user.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.http.HttpProtocol;
import com.qcloud.cos.region.Region;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 腾讯云 COS 客户端装配。
 *
 * <p>只在 {@code stellar.ink.storage.type=cos} 时生效 —— 本地开发默认 {@code local}，
 * 不会因为缺少 COS 依赖或密钥而启动失败。
 *
 * <p>客户端是线程安全的，全应用共用一个实例；配了 {@code destroyMethod="shutdown"}，
 * 由 Spring 在关闭时释放底层连接池。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "stellar.ink.storage.type", havingValue = "cos")
public class CosStorageConfig {

    private final StorageProperties properties;

    @Bean(destroyMethod = "shutdown")
    public COSClient cosClient() {
        StorageProperties.Cos cos = properties.getCos();
        if (!StringUtils.hasText(cos.getBucket()) || !StringUtils.hasText(cos.getRegion())) {
            throw new IllegalStateException(
                    "storage.type=cos 时必须配置 stellar.ink.storage.cos.bucket（含 APPID 后缀）与 region。");
        }
        /* 密钥只从环境变量注入；缺失时直接拒绝启动，避免带着空密钥运行到第一次上传才报错 */
        if (!StringUtils.hasText(cos.getSecretId()) || !StringUtils.hasText(cos.getSecretKey())) {
            throw new IllegalStateException(
                    "storage.type=cos 时必须通过环境变量 COS_SECRET_ID / COS_SECRET_KEY 提供 CAM 子账号密钥。");
        }
        COSCredentials credentials = new BasicCOSCredentials(cos.getSecretId(), cos.getSecretKey());
        ClientConfig clientConfig = new ClientConfig(new Region(cos.getRegion()));
        clientConfig.setHttpProtocol(HttpProtocol.https);
        clientConfig.setConnectionTimeout(cos.getConnectionTimeoutMs());
        clientConfig.setSocketTimeout(cos.getSocketTimeoutMs());
        log.info("COS 客户端已装配 bucket={} region={} publicBase={}",
                cos.getBucket(), cos.getRegion(), StringUtils.hasText(cos.getPublicBase())
                        ? cos.getPublicBase() : "(默认域名)");
        return new COSClient(credentials, clientConfig);
    }

    /**
     * 启动时做一次轻量连通性自检：<b>只告警不阻塞启动</b>。
     *
     * <p>探测方式刻意用 {@code getObjectMetadata} 读一个必然不存在的键，而不是 {@code doesBucketExist}：
     * 后者走的是 HeadBucket，腾讯云对部分 CAM 子账号会直接返回 403，看起来像「桶不存在」，
     * 极易误导排障。用取对象的方式可以拿到精确结论：
     * <ul>
     *   <li>{@code NoSuchKey} / 404 → 凭据有效、桶可达、读路径通（正常）；</li>
     *   <li>{@code AccessDenied} → 密钥无效或策略缺权限（需要处理）；</li>
     *   <li>{@code NoSuchBucket} → 桶名漏了 APPID 后缀或地域填错（需要处理）。</li>
     * </ul>
     * 用读而不是写来探测，是为了不在桶里留下任何垃圾对象。
     */
    @Bean
    public CosStartupChecker cosStartupChecker(COSClient cosClient, StorageProperties properties) {
        return new CosStartupChecker(cosClient, properties);
    }

    /** 独立成类是为了让 {@code @Bean} 方法体保持简单，也方便单测替换 */
    @Slf4j
    public static class CosStartupChecker {
        public CosStartupChecker(COSClient cosClient, StorageProperties properties) {
            StorageProperties.Cos cos = properties.getCos();
            if (!cos.isCheckBucketOnStartup()) {
                return;
            }
            String probeKey = cos.getKeyPrefix() + "__startup_probe__";
            try {
                cosClient.getObjectMetadata(cos.getBucket(), probeKey);
                /* 探测键居然存在：说明历史上真传过这个键，属无害情况，仅记录 */
                log.info("COS 存储桶可达（探测键意外存在）：{}", cos.getBucket());
            } catch (CosServiceException e) {
                String code = e.getErrorCode();
                if ("NoSuchKey".equals(code) || e.getStatusCode() == 404) {
                    log.info("COS 存储桶可用：{} region={}", cos.getBucket(), cos.getRegion());
                } else if ("NoSuchBucket".equals(code)) {
                    log.warn("COS 存储桶不存在：{}（检查桶名是否带 APPID 后缀、region 是否匹配）", cos.getBucket());
                } else if (e.getStatusCode() == 403) {
                    /* 注意：HeadObject 在「密钥不存在」和「策略缺权限」两种情况下都只回笼统的 403 Forbidden，
                       拿不到 InvalidAccessKeyId 那种精确错误码（只有 PutObject 才给）。
                       所以这里如实列出两种可能，不替 COS 下结论 —— 免得把「密钥抄错」误导成「策略没配」。 */
                    log.warn("COS 自检返回 403（头像上传可能失败）。两种常见原因："
                            + "① 密钥无效或已删除（从控制台列表复制的脱敏值 AKID**** 一定失败）；"
                            + "② 策略缺 PutObject/GetObject/DeleteObject 权限。"
                            + "bucket={} errorCode={} requestId={}", cos.getBucket(), code, e.getRequestId());
                } else {
                    log.warn("COS 启动自检返回异常：bucket={} errorCode={} message={}",
                            cos.getBucket(), code, e.getMessage());
                }
            } catch (CosClientException e) {
                log.warn("COS 启动自检失败（不影响启动，可能是网络不可达）：errorCode={} message={}",
                        e.getErrorCode(), e.getMessage());
            }
        }
    }
}
