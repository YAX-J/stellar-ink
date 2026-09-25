package com.stellarink.ai.stream;

import com.stellarink.aiclient.dto.QaStreamRequestDTO;

/**
 * 从 Python 拉取 SSE 帧的通道。
 *
 * <p>为什么不走 Feign：Feign 的返回类型会**先把整个响应体读进内存**再交给调用方
 * （它的解码器都是「拿到完整 body」语义），对 SSE 来说那等于把流式退化成一次性 ——
 * 用户会一直转圈，直到模型把整段话说完才一次性看到答案，而我们为此付了流式的全部复杂度。
 * 因此这里单独开一条通道：{@code java.net.http.HttpClient}（JDK 自带，**不引新依赖**）
 * 的 {@code BodyHandlers.ofLines()} 天然按行给流。
 *
 * <p>返回 {@link Handle} 而不是 `Stream`：调用方必须显式 {@code close()}
 * ——「浏览器断开 → 取消下游」这条链路靠它落地，包一层是为了让「忘记关闭」在代码里看得见。
 */
public interface QaStreamClient {

    /**
     * 发起流式请求。
     *
     * @return 可迭代的帧句柄；**用完必须 close**（`try-with-resources`）
     * @throws com.stellarink.sharedmodel.exception.BusinessException 上游不可用（映射成 503，不返回空流）
     */
    Handle open(QaStreamRequestDTO request);

    /** 一次流式调用的句柄：既能迭代帧，也能取消整条下游连接。 */
    interface Handle extends Iterable<QaSseFrame>, AutoCloseable {

        /** 取消：关闭上游连接。**可能被调用多次**（正常关闭 + 断开时的取消），必须幂等。 */
        @Override
        void close();
    }
}
