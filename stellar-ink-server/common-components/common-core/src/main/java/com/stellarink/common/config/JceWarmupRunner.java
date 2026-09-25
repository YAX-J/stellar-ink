package com.stellarink.common.config;

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
 * JCE 预热：**把「第一个用加密算法的请求要卡 5 秒」这笔开销挪到启动期**。
 *
 * <p><b>症状</b>：服务重启后，第一个**需要验签或加密**的请求要 5~6 秒，之后的同类请求只要几十毫秒。
 * 它跟「空闲」无关，也跟数据库无关 —— 实测当天同一个 JVM 里相隔 34 分钟出现的两次 5 秒卡顿，
 * 都恰好是各自重启后的第一个鉴权请求；而**公开接口（不解析 JWT）一直很快**：
 * 重启后 {@code GET /ai/health} 22ms，紧接着第一个需要验签的 {@code GET /ai/admin/models} 5379ms，
 * 再往后全是 12~51ms。两个并发的鉴权请求还会**在同一毫秒**一起解开 —— 那是同一把锁的特征。
 *
 * <p><b>原因</b>（线程 dump 直接抓到，栈底到栈顶）：
 * <pre>
 *   javax.crypto.Mac.getInstance
 *     javax.crypto.JceSecurity.getVerificationResult / ProviderVerifier.verify
 *       javax.crypto.JarVerifier.verifyJars → verifySingleJar
 *         org.springframework.boot.loader.zip.ZipContent.getEntry   ← 在逐条读可执行 fat jar
 *           sun.nio.ch.FileDispatcherImpl.pread0
 * </pre>
 * 第一次用到某个算法时，JVM 要**校验该算法调用方所在 jar 的签名信息**（JCE 的老规矩）。
 * 我们跑的是 Spring Boot 可执行 fat jar，{@code JarVerifier} 只能通过嵌套 jar 的中央目录
 * **一条一条随机读**（{@code pread}），于是这个本来微不足道的校验被放大成数秒。
 * 校验结果由 {@code JceSecurity} 缓存（按 provider），所以只疼第一次。
 *
 * <p><b>为什么用「预热」而不是「关掉校验」</b>：JCE 的 provider 校验没有官方开关，
 * 改 fat jar 结构（拆包部署）代价远大于收益；而预热只是把这几秒从「用户点下去」挪到
 * 「服务启动」，代价是启动日志里多一行耗时。{@link ApplicationRunner} 在 Tomcat 开始接受
 * 请求**之后**才执行，所以健康检查、Nacos 注册都不会被它拖住；
 * 万一有请求恰好在这几秒里打进来，它只是和预热一起等同一把锁（总量不变，不会更慢）。
 *
 * <p>⚠️ 预热的算法清单必须覆盖真实用到的路径，删掉某一项 = 那个算法又回到「第一次用卡 5 秒」：
 * <ul>
 *   <li>{@code Sa-Token-JWT}：**这一项才是那 5 秒的正主** —— Sa-Token 的 JWT 签发与校验走
 *       Hutool（{@code cn.hutool.crypto.SecureUtil.createMac}，线程 dump 里抓到的就是它），
 *       而 Hutool 是 fat jar 里一个 2.5MB 的大 jar，逐条校验才那么慢。
 *       ⚠️ 只从我们自己的类里调 {@code javax.crypto} **没用**（实测 0~12ms）：校验的是
 *       「调用方所在的那个 jar」，我们自己那个 jar 只有几十个类，便宜得很。
 *       所以这一项必须真的走一遍 Sa-Token 的签 + 验；</li>
 *   <li>{@code HmacSHA256}：ai-service → Python 的内部签名（我们自己的调用路径）；</li>
 *   <li>{@code AES/GCM/NoPadding}：{@code AesGcmCipher} 加解密模型 API Key；</li>
 *   <li>{@code SHA-256}：令牌摘要（撤销列表）与内部签名里的 body 摘要。</li>
 * </ul>
 * 因此 {@link #ALGORITHMS} 既是清单也是单测的断言对象（见 {@code JceWarmupRunnerTest}）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "stellar.ink.crypto.warmup.enabled", havingValue = "true", matchIfMissing = true)
public class JceWarmupRunner implements ApplicationRunner {

    /** Sa-Token JWT 的签 + 验（Hutool 实现）——真正吃掉那 5 秒的一项 */
    public static final String SA_TOKEN_JWT = "Sa-Token-JWT(create+parse)";
    /** HMAC-SHA256：内部签名（我们自己的调用路径） */
    public static final String HMAC_SHA256 = "HmacSHA256";
    /** AES-GCM：模型 API Key 的加解密 */
    public static final String AES_GCM = "AES/GCM/NoPadding";
    /** SHA-256：令牌摘要与 body 摘要 */
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
    /** GCM 的 IV 长度（12 字节，与 {@code AesGcmCipher} 一致） */
    private static final int GCM_IV_LENGTH = 12;
    /** GCM 认证标签长度（位） */
    private static final int GCM_TAG_BITS = 128;
    /** 预热用的临时密钥长度：AES-256 / HMAC 都够 */
    private static final int KEY_BYTES = 32;

    @Override
    public void run(ApplicationArguments args) {
        long startedAt = System.currentTimeMillis();
        List<String> warmed = warmUp();
        log.info("JCE 预热完成：{} 耗时 {}ms（不做这一步，这几秒会落在重启后第一个需要验签/加密的请求上）",
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
            // 预热失败不影响业务（真用到时该报的错照报），所以只记日志
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
