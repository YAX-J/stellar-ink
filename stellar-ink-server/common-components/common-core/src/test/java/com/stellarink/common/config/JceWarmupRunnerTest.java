package com.stellarink.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCE 预热的回归测试。
 *
 * <p>守住的是「清单与实现不许漂移」：{@link JceWarmupRunner#ALGORITHMS} 是承诺要预热的算法，
 * {@link JceWarmupRunner#warmUp()} 返回的是**真的跑通了**的算法。两者必须相等 ——
 * 删掉某一行预热代码、或加一个其实跑不通的算法，测试立刻红。
 *
 * <p>为什么值得一条测试：这块失败的表现是「重启后第一个鉴权请求卡 5 秒」，
 * 只在**可执行 fat jar** 里出现（嵌套 jar 的签名校验被放大），
 * 在 IDE / 单元测试的扁平 classpath 下几乎看不出来 —— 也就是光靠本地跑服务根本发现不了。
 */
class JceWarmupRunnerTest {

    @Test
    @DisplayName("清单里声明的算法都必须真的能预热成功（少一个就等于那个算法第一次用还卡 5 秒）")
    void everyDeclaredAlgorithmIsActuallyWarmedUp() {
        List<String> warmed = JceWarmupRunner.warmUp();

        assertEquals(JceWarmupRunner.ALGORITHMS, warmed,
                "预热结果与 ALGORITHMS 不一致：要么漏跑，要么某个算法在这个 JDK 上不可用");
        // 最关键的一项：Sa-Token → Hutool 的签/验才是那 5 秒的正主（fat jar 里 2.5MB 的大 jar）。
        // 只调 javax.crypto 实测只要 0~12ms，等于没预热 —— 所以这一条必须钉住。
        assertTrue(warmed.contains(JceWarmupRunner.SA_TOKEN_JWT),
                "Sa-Token JWT 签 + 验必须预热成功（Hutool 的 jar 才是那 5 秒）");
        // 正向钉住真正会用到的那一个：内部签名与 body 摘要
        assertTrue(warmed.contains(JceWarmupRunner.HMAC_SHA256), "HMAC-SHA256 必须预热（内部签名）");
        assertTrue(warmed.contains(JceWarmupRunner.AES_GCM), "AES/GCM 必须预热（模型 API Key 加解密）");
        assertTrue(warmed.contains(JceWarmupRunner.SHA_256), "SHA-256 必须预热（令牌摘要）");
    }

    @Test
    @DisplayName("重复预热是幂等的：JceSecurity 已缓存校验结果，第二次不该报错也不该重复付费")
    void warmUpIsIdempotent() {
        JceWarmupRunner.warmUp();
        assertEquals(JceWarmupRunner.ALGORITHMS, JceWarmupRunner.warmUp());
    }

    @Test
    @DisplayName("作为 ApplicationRunner 可正常调用（args 为 null 也不报错）")
    void runsAsApplicationRunner() {
        assertDoesNotThrow(() -> new JceWarmupRunner().run(null));
    }
}
