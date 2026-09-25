package com.stellarink.gateway.config;

import lombok.extern.slf4j.Slf4j;
import cn.dev33.satoken.jwt.SaJwtUtil;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * JCE 预热（网关版）。
 *
 * <p>与 {@code com.stellarink.common.config.JceWarmupRunner} 是同一套逻辑的另一份实现：
 * 网关是 WebFlux，不能依赖带 servlet 的 common-core。<b>改动要同步两处</b>。
 * 完整的症状 / 线程 dump 证据 / 原理见那份的类注释，这里只记网关侧为什么要它：
 * 网关对**每个带 token 的请求**都要验签 JWT —— 也就是说重启后**第一个带 token 的请求**
 * 必然踩到那 5 秒的 JCE provider 校验，而它恰好是用户「刷新页面」发出的第一个请求，
 * 所以看起来像「刷新后加载也很慢」。
 *
 * <p>⚠️ 真正吃时间的是 {@link #SA_TOKEN_JWT} 那一项：Sa-Token 的签/验走 Hutool，
 * 而 Hutool 是 fat jar 里 2.5MB 的大 jar；只从我们自己的类调 {@code javax.crypto} 实测只要 0~12ms
 * （校验的是「调用方所在的那个 jar」），**起不到预热作用**。
 *
 * <p>本类在 Tomcat/Netty 已开始接受请求之后执行，健康检查与 Nacos 注册不受影响。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "stellar.ink.crypto.warmup.enabled", havingValue = "true", matchIfMissing = true)
public class JceWarmupRunner implements ApplicationRunner {

    /** Sa-Token JWT 的签 + 验（Hutool 实现）——真正吃掉那 5 秒的一项 */
    public static final String SA_TOKEN_JWT = "Sa-Token-JWT(create+parse)";
    /** HMAC-SHA256：内部签名等我们自己的调用路径 */
    public static final String HMAC_SHA256 = "HmacSHA256";
    /** AES-GCM：网关当前不直接用，但清单与 common-core 保持一致，避免两边漂移 */
    public static final String AES_GCM = "AES/GCM/NoPadding";
    /** SHA-256：令牌撤销列表的键摘要 */
    public static final String SHA_256 = "SHA-256";

    /** 必须预热的算法清单；改这里就要同步 {@link #warmUp()} 与单测。 */
    public static final List<String> ALGORITHMS = List.of(SA_TOKEN_JWT, HMAC_SHA256, AES_GCM, SHA_256);

    /** 预热用的临时 JWT 密钥（HS256 要求至少 32 字节），与任何真实密钥无关 */
    private static final String WARMUP_JWT_KEY = "stellar-ink-jce-warmup-key-0000000001";
    /** 与 {@code StpUtil} 默认登录类型一致，parseToken 需要它 */
    private static final String WARMUP_LOGIN_TYPE = "login";
    /** 预热 token 的设备类型（只用来凑齐 Sa-Token 的载荷结构，无业务含义） */
    private static final String WARMUP_DEVICE = "warmup";
    /** 预热 token 的有效期（秒）：够活到本轮预热解析完就行 */
    private static final long WARMUP_JWT_TTL_SECONDS = 60L;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    @Override
    public void run(ApplicationArguments args) {
        long startedAt = System.currentTimeMillis();
        List<String> warmed = warmUp();
        log.info("JCE 预热完成：{} 耗时 {}ms（不做这一步，这几秒会落在重启后第一个带 token 的请求上）",
                warmed, System.currentTimeMillis() - startedAt);
    }

    /**
     * 真跑一次运算（不是只 {@code getInstance}）：签名校验发生在第一次真正使用算法时。
     *
     * @return 成功预热的算法名，顺序同 {@link #ALGORITHMS}
     */
    public static List<String> warmUp() {
        List<String> warmed = new ArrayList<>(ALGORITHMS.size());
        byte[] key = new byte[KEY_BYTES];

        // ① 最贵的一项：走 Sa-Token → Hutool 的 JWT 签与验（fat jar 里的大 jar）
        try {
            // ⚠️ 必须用带 timeout 的重载：createToken(payloads, key) 不带有效期，
            //    解析时会被判「jwt 已过期」而抛 SaJwtException（踩过一次，单测直接抓住）
            String token = SaJwtUtil.createToken(WARMUP_LOGIN_TYPE, 0L, WARMUP_DEVICE, WARMUP_JWT_TTL_SECONDS,
                    null, WARMUP_JWT_KEY);
            SaJwtUtil.parseToken(token, WARMUP_LOGIN_TYPE, WARMUP_JWT_KEY, true);
            warmed.add(SA_TOKEN_JWT);
        } catch (Exception e) {
            log.warn("JCE 预热失败[{}]：{}", SA_TOKEN_JWT, e.toString());
        }

        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            mac.doFinal(new byte[0]);
            warmed.add(HMAC_SHA256);
        } catch (GeneralSecurityException e) {
            log.warn("JCE 预热失败[{}]：{}", HMAC_SHA256, e.toString());
        }

        try {
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, new byte[GCM_IV_LENGTH]));
            cipher.doFinal(new byte[0]);
            warmed.add(AES_GCM);
        } catch (GeneralSecurityException e) {
            log.warn("JCE 预热失败[{}]：{}", AES_GCM, e.toString());
        }

        try {
            MessageDigest.getInstance(SHA_256).digest(new byte[0]);
            warmed.add(SHA_256);
        } catch (NoSuchAlgorithmException e) {
            log.warn("JCE 预热失败[{}]：{}", SHA_256, e.toString());
        }

        return warmed;
    }
}
