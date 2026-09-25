package com.stellarink.ai.config;

import com.stellarink.aiclient.signature.InternalRequestSigner;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 给所有发往 Python 的 Feign 请求加内部签名头（{@code X-AI-*}）。
 *
 * <p>为什么放在 ai-service 而不是客户端契约模块：签名要用**当前登录身份**与 traceId，
 * 那是服务内才有的上下文；契约模块只提供算法（{@link InternalRequestSigner}），
 * 不依赖 Sa-Token 与 MDC，两边职责不混。
 *
 * <p>拦截器在 Feign **编码完请求体之后**执行（`RequestTemplate.resolve` 的最后一环），
 * 所以这里能拿到最终的 body 字节并参与摘要计算 —— 这一点很关键：
 * 如果签名覆盖的 body 与实际发出的 body 不一致，Python 侧会以 401 拒绝，
 * 而现象看起来像「密钥不对」，排查方向会完全跑偏。
 *
 * <p>密钥缺失时**不降级为不签名**：那等于把「内网即可冒充」重新打开。
 * 这里让它抛 {@link InternalRequestSigner.SecretMissingException}，
 * 由全局异常处理器给出可操作的提示（配置问题，不是 500 迷雾）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalSignatureFeignInterceptor implements RequestInterceptor {

    private final InternalSecretProvider secretProvider;
    private final InternalCallerProvider callerProvider;

    @Override
    public void apply(RequestTemplate template) {
        InternalCallerProvider.Caller caller = callerProvider.current();
        InternalRequestSigner signer = secretProvider.signer();
        Map<String, String> headers = signer.signRequest(
                template.method(),
                signingPath(template),
                bodyOf(template),
                caller.userId(),
                caller.role(),
                caller.traceId());
        headers.forEach(template::header);
    }

    /**
     * 签名覆盖的路径：**不含 query**。
     *
     * <p>与 Python 侧验签口径一致（它取 ASGI 的 {@code scope.path}）。
     * 将来要让 query 参与签名，必须两侧同时升级 —— 见 {@code CanonicalRequest} 的说明。
     */
    static String signingPath(RequestTemplate template) {
        String path = template.path();
        int queryStart = path.indexOf('?');
        return queryStart >= 0 ? path.substring(0, queryStart) : path;
    }

    /** 请求体按 UTF-8 还原：Feign 用同一编码写 body，否则两侧摘要对不上。 */
    static String bodyOf(RequestTemplate template) {
        byte[] body = template.body();
        return body == null ? null : new String(body, StandardCharsets.UTF_8);
    }
}
