package com.stellarink.ai.client;

/**
 * Python 服务探活：默认实现是 {@link HttpPythonHealthProbe}（真的去问一次 {@code GET /health}），
 * {@link FakePythonHealthProbe} 退居测试/演示。
 *
 * <p>为什么抽接口：{@code /ai/health} 的**响应契约**（有哪些字段、降级怎么写）
 * 不该等到 Python 链路打通才确定，否则前端与运维要跟着改两遍；
 * 顺带让切片测试能塞一个不发起网络调用的实现。
 */
public interface PythonHealthProbe {

    ProbeResult probe();

    /**
     * 探活结果。
     *
     * @param available   Python 是否可用（拿不到结果、或对面自报非 ok，一律 false）
     * @param service     Python 侧上报的服务名，未取到时为 null
     * @param version     Python 侧上报的版本，未取到时为 null
     * @param reason      不可用的**可读原因**（用于运维与日志），可用时为 null
     */
    record ProbeResult(boolean available, String service, String version, String reason) {

        public static ProbeResult available(String service, String version) {
            return new ProbeResult(true, service, version, null);
        }

        public static ProbeResult unavailable(String reason) {
            return new ProbeResult(false, null, null, reason);
        }
    }
}
