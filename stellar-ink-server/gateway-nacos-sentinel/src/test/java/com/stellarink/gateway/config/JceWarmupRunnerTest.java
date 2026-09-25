package com.stellarink.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 网关版 JCE 预热的回归测试（与 common-core 的同名测试一一对应，见那份的类注释）。
 *
 * <p>网关尤其需要这一层：重启后第一个带 token 的请求必然踩到 5 秒的 JCE provider 校验，
 * 而它就是用户刷新页面发出的第一个请求。清单与实现不许漂移。
 */
class JceWarmupRunnerTest {

    @Test
    @DisplayName("清单里声明的算法都必须真的能预热成功（少一个就等于那个算法第一次用还卡 5 秒）")
    void everyDeclaredAlgorithmIsActuallyWarmedUp() {
        assertEquals(JceWarmupRunner.ALGORITHMS, JceWarmupRunner.warmUp(),
                "预热结果与 ALGORITHMS 不一致：要么漏跑，要么某个算法在这个 JDK 上不可用");
        // 网关每个带 token 的请求都要验签，而验签走 Sa-Token → Hutool（那 5 秒的正主）
        assertTrue(JceWarmupRunner.warmUp().contains(JceWarmupRunner.SA_TOKEN_JWT),
                "Sa-Token JWT 签 + 验必须预热成功：网关每个带 token 的请求都要走它");
    }

    @Test
    @DisplayName("重复预热是幂等的")
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
