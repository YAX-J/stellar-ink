package com.stellarink.aiclient.error;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.sharedmodel.enums.ErrorCode;
import feign.Response;
import feign.codec.ErrorDecoder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 把 Python 的错误体翻成**可直接展示**的业务异常。
 *
 * <p><b>为什么必须有这一层</b>（实测踩到）：Python 侧刻意把「没配模型」做成可读的 400
 * （「角色 embedding 尚未配置模型（请在 AI 实验室 → 模型配置里填写）」），
 * 但没有 ErrorDecoder 时，Feign 只会抛一个 {@code FeignException}，
 * 全局处理器把它当未知异常 → 用户看到的是 **{@code code=500「系统繁忙，请稍后重试」}**。
 * 于是「去面板配一个角色」这件三十秒的事，变成了「服务坏了，等运维」。
 *
 * <p>错误体形状（Python `app/api/v1/*` 与 `app/main.py` 统一给出）：
 * {@code {"code": "AI_BAD_REQUEST", "message": "…"}}。认识的才转换，不认识的
 * （网关 HTML、空体、FastAPI 的 {@code {"detail": …}}）交回 Feign 默认处理 ——
 * **上游原始报文绝不透给用户**（可能含内网地址与栈）。
 */
@Slf4j
public class PythonErrorDecoder implements ErrorDecoder {

    /** 可读消息的长度上限：正常的可操作提示几十个字，超过它多半不是给人看的 */
    private static final int MAX_MESSAGE_CHARS = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ErrorDecoder defaultDecoder = new ErrorDecoder.Default();

    @Override
    public Exception decode(String methodKey, Response response) {
        int status = response.status();
        // ⚠️ Feign 的 body 是**流**，只能读一次：所以在这里读出来再往下传。
        String body = bodyOf(response);
        String message = readableMessage(body);
        if (message == null) {
            // FastAPI 的参数校验失败是 {detail:[{loc,msg,…}]}，不是我们的 {code,message} 契约。
            // 不认它就会掉进下面的「契约外」分支 → FeignException → 全局处理器给
            // 「系统繁忙，请稍后重试」，而真正的原因（哪个字段不合法）就此消失 ——
            // 2026-10-05 那次 /ai/writing/style 的 500 就是这么被藏了两轮。
            message = validationMessage(body);
        }
        if (message == null) {
            // 不是我们的错误契约：保留默认行为（FeignException → 上层兜成 5xx），
            // 但把状态码记下来，否则这类问题在日志里毫无痕迹
            log.warn("Python 返回了契约外的错误：{} status={}", methodKey, status);
            return defaultDecoder.decode(methodKey, response);
        }

        ErrorCode errorCode = errorCodeOf(status);
        log.warn(
                "Python 调用失败：{} status={} code={} message={}",
                methodKey,
                status,
                errorCode.getCode(),
                message);
        return new PythonApiException(errorCode, status, message);
    }

    /**
     * 状态码 → 错误码。
     *
     * <p>两处刻意的映射：
     * <ul>
     *   <li><b>401/403 不映射成 {@code UNAUTHORIZED/FORBIDDEN}</b>：Python 的这两个码与我们自己的
     *       内部签名校验（{@code AI_INTERNAL_SECRET} 两侧不一致之类）无关，与用户会话也无关 ——
     *       它现在还有一个来源：**用户填给供应商的密钥被上游拒了**（拉取模型清单那条路径，
     *       见 {@code ProviderAuthError → 401}）。两种都映射成 {@code SERVICE_UNAVAILABLE}
     *       并把上游那句可操作提示原样交给用户；映射成 401 会让前端 {@code isAuthError()}
     *       判定为「登录失效」，把用户清出登录态 —— 那是完全错误的动作。</li>
     *   <li><b>429 映射成 {@code SERVICE_UNAVAILABLE}</b>：被上游限流时服务对我们就等于暂时不可用。
     *       仓库的 {@code ErrorCode} 没有「限流」档，而「稍后重试」这层意思由 Python 的消息
     *       （「模型服务限流，请稍后重试」）带给用户。</li>
     * </ul>
     */
    private static ErrorCode errorCodeOf(int status) {
        return switch (status) {
            // 400/422 都是「请求方（这里是运维/站长）能自己修」的问题：原样把消息交出去
            case 400, 422 -> ErrorCode.PARAM_ERROR;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 401, 403, 429 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> ErrorCode.SERVICE_UNAVAILABLE;
        };
    }

    /** 从错误体里取可展示的消息；取不到（非 JSON、没有 message 字段、空体）返回 null。 */
    private static String readableMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            // `message` 是 Python 的键；`msg` 容错兼容（同一个仓库的 Java 侧用 msg）
            JsonNode message = node.hasNonNull("message") ? node.get("message") : node.get("msg");
            if (message == null || !message.isTextual() || message.asText().isBlank()) {
                return null;
            }
            return truncate(message.asText().trim());
        } catch (IOException malformed) {
            return null;
        }
    }

    /**
     * FastAPI 的参数校验错误 → 一句人话。
     *
     * <p>形状是 {@code {"detail":[{"type":…,"loc":["body","maxSamples"],"msg":"Input should be a
     * valid integer",…}]}}。这里取第一条，拼成
     * {@code "请求参数不合法（maxSamples）：Input should be a valid integer"} ——
     * 定位到**具体字段**才是这条消息的全部价值；只说「参数错误」等于没说。
     *
     * <p>`detail` 也可能直接是字符串（自己 raise 的 HTTPException），同样认。
     */
    private static String validationMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode detail = MAPPER.readTree(body).get("detail");
            if (detail == null || detail.isNull()) {
                return null;
            }
            if (detail.isTextual()) {
                return truncate("请求参数不合法：" + detail.asText().trim());
            }
            if (!detail.isArray() || detail.isEmpty()) {
                return null;
            }
            JsonNode first = detail.get(0);
            String message = first.path("msg").asText("").trim();
            if (message.isBlank()) {
                return null;
            }
            String field = lastLocationPart(first.path("loc"));
            return truncate(field.isBlank()
                    ? "请求参数不合法：" + message
                    : "请求参数不合法（" + field + "）：" + message);
        } catch (IOException malformed) {
            return null;
        }
    }

    /** `loc` 形如 `["body","maxSamples"]`：取最后一段（字段名）作为可读定位。 */
    private static String lastLocationPart(JsonNode loc) {
        if (loc == null || !loc.isArray() || loc.isEmpty()) {
            return "";
        }
        JsonNode last = loc.get(loc.size() - 1);
        return last.isTextual() ? last.asText() : last.toString();
    }

    private static String bodyOf(Response response) {
        if (response.body() == null) {
            return null;
        }
        try (InputStream stream = response.body().asInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static String truncate(String message) {
        return message.length() <= MAX_MESSAGE_CHARS
                ? message
                : message.substring(0, MAX_MESSAGE_CHARS) + "…";
    }
}
