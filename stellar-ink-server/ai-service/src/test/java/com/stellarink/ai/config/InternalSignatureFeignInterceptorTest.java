package com.stellarink.ai.config;

import com.stellarink.aiclient.constant.AiInternalHeaders;
import com.stellarink.aiclient.signature.CanonicalRequest;
import com.stellarink.aiclient.signature.InternalRequestSigner;
import com.stellarink.sharedmodel.enums.Role;
import feign.Request;
import feign.RequestTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签名拦截器测试：**用验证端的算法反算一遍**，而不是只看「头存在」。
 *
 * <p>为什么强调这一点：只断言「有 X-AI-Signature」完全挡不住拼接错误 ——
 * 路径带了 query、body 用了平台默认编码、身份没进标准串，这些都会让签名看起来正常
 * 而 Python 侧一律 401。这里从被拦截器改写后的 {@link RequestTemplate} 里把
 * method/path/body/身份重新拼成标准串，用同一把密钥算一遍，必须与头里的签名逐字符相等。
 */
class InternalSignatureFeignInterceptorTest {

    private static final String SECRET = "test-secret-please-change-me-0123456789";
    private static final long USER_ID = 7L;

    /** 固定密钥与固定身份的接缝：生产实现从环境变量与 Sa-Token 取。 */
    private final InternalSecretProvider secretProvider = new InternalSecretProvider() {
        @Override
        public InternalRequestSigner signer() {
            return InternalRequestSigner.fromSecret(SECRET);
        }
    };

    private final InternalCallerProvider callerProvider =
            () -> new InternalCallerProvider.Caller(USER_ID, Role.ADMIN, "trace-42");

    private final InternalSignatureFeignInterceptor interceptor =
            new InternalSignatureFeignInterceptor(secretProvider, callerProvider);

    private static RequestTemplate template(String method, String path, String body) {
        RequestTemplate template = new RequestTemplate();
        template.method(method);
        template.uri(path);
        if (body != null) {
            // 用 String 重载：Feign 内部按 UTF-8 编码，与拦截器里的还原方式成对
            template.body(body);
        }
        return template;
    }

    private static String header(RequestTemplate template, String name) {
        Collection<String> values = template.headers().get(name);
        assertNotNull(values, "缺少内部头：" + name);
        assertEquals(1, values.size(), name + " 不该有多个值");
        return values.iterator().next();
    }

    @Test
    @DisplayName("签名与验证端算法逐字符一致（含身份字段）")
    void signatureMatchesVerifierSideAlgorithm() {
        RequestTemplate template = template("POST", "/eval/run", "{\"dataset\":\"golden_v1\"}");

        interceptor.apply(template);

        String timestamp = header(template, AiInternalHeaders.TIMESTAMP);
        String nonce = header(template, AiInternalHeaders.NONCE);
        String canonical = CanonicalRequest.of(
                "POST",
                "/eval/run",
                Long.parseLong(timestamp),
                nonce,
                CanonicalRequest.sha256Hex("{\"dataset\":\"golden_v1\"}"),
                USER_ID,
                Role.ADMIN.name());
        assertEquals(
                InternalRequestSigner.fromSecret(SECRET).sign(canonical),
                header(template, AiInternalHeaders.SIGNATURE));
    }

    @Test
    @DisplayName("身份头与签名一起发出：内网改头即验签失败")
    void identityHeadersAreSentWithTheSignature() {
        RequestTemplate template = template("GET", "/eval/datasets", null);

        interceptor.apply(template);

        assertEquals(Long.toString(USER_ID), header(template, AiInternalHeaders.USER_ID));
        assertEquals(Role.ADMIN.name(), header(template, AiInternalHeaders.ROLE));
        assertEquals("trace-42", header(template, AiInternalHeaders.TRACE_ID));
        // 无 body 的 GET 也要有摘要（空串的 SHA-256），否则两侧对「无 body」的理解会分叉
        assertTrue(header(template, AiInternalHeaders.SIGNATURE).matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("路径里的 query 不参与签名：与 Python 取 scope.path 的口径一致")
    void queryIsExcludedFromTheSignedPath() {
        RequestTemplate template = template("GET", "/eval/datasets?debug=1", null);

        interceptor.apply(template);

        String canonical = CanonicalRequest.of(
                "GET",
                "/eval/datasets",
                Long.parseLong(header(template, AiInternalHeaders.TIMESTAMP)),
                header(template, AiInternalHeaders.NONCE),
                CanonicalRequest.EMPTY_BODY_SHA256,
                USER_ID,
                Role.ADMIN.name());
        assertEquals(
                InternalRequestSigner.fromSecret(SECRET).sign(canonical),
                header(template, AiInternalHeaders.SIGNATURE));
    }

    @Test
    @DisplayName("中文请求体按 UTF-8 计算摘要（平台默认编码不同会让两侧对不上）")
    void bodyDigestUsesUtf8() {
        String body = "{\"question\":\"星笺为什么把文章比作星辰？\"}";
        RequestTemplate template = template("POST", "/eval/run", body);

        interceptor.apply(template);

        String canonical = CanonicalRequest.of(
                "POST",
                "/eval/run",
                Long.parseLong(header(template, AiInternalHeaders.TIMESTAMP)),
                header(template, AiInternalHeaders.NONCE),
                CanonicalRequest.sha256Hex(body.getBytes(StandardCharsets.UTF_8)),
                USER_ID,
                Role.ADMIN.name());
        assertEquals(
                InternalRequestSigner.fromSecret(SECRET).sign(canonical),
                header(template, AiInternalHeaders.SIGNATURE));
    }

    @Test
    @DisplayName("密钥缺失：拒绝签名而不是发一个未签名请求")
    void missingSecretFailsLoudly() {
        InternalSecretProvider withoutSecret = new InternalSecretProvider() {
            @Override
            public InternalRequestSigner signer() {
                throw new InternalRequestSigner.SecretMissingException(
                        "未配置 " + InternalRequestSigner.SECRET_ENV);
            }
        };
        InternalSignatureFeignInterceptor failing =
                new InternalSignatureFeignInterceptor(withoutSecret, callerProvider);

        assertThrows(
                InternalRequestSigner.SecretMissingException.class,
                () -> failing.apply(template("GET", "/eval/datasets", null)));
    }

    @Test
    @DisplayName("没有登录身份：同样拒绝（默认一个身份就是提权漏洞）")
    void missingIdentityFailsLoudly() {
        InternalCallerProvider withoutIdentity = () -> {
            throw new IllegalStateException("内部调用缺少登录身份");
        };
        InternalSignatureFeignInterceptor failing =
                new InternalSignatureFeignInterceptor(secretProvider, withoutIdentity);

        assertThrows(
                IllegalStateException.class,
                () -> failing.apply(template("GET", "/eval/datasets", null)));
    }

    @Test
    @DisplayName("同一请求两次签名：nonce 不同，签名也不同（防重放的前提）")
    void nonceMakesEachSignatureUnique() {
        RequestTemplate first = template("GET", "/eval/datasets", null);
        RequestTemplate second = template("GET", "/eval/datasets", null);

        interceptor.apply(first);
        interceptor.apply(second);

        assertTrue(!header(first, AiInternalHeaders.NONCE).equals(header(second, AiInternalHeaders.NONCE)));
        assertTrue(!header(first, AiInternalHeaders.SIGNATURE)
                .equals(header(second, AiInternalHeaders.SIGNATURE)));
    }

    @Test
    @DisplayName("拦截器不会覆盖调用方显式设置的其他头")
    void keepsExistingHeaders() {
        RequestTemplate template = template("GET", "/eval/datasets", null);
        template.header("X-Trace-Id", "kept");

        interceptor.apply(template);

        Map<String, Collection<String>> headers = template.headers();
        assertTrue(headers.containsKey("X-Trace-Id"));
    }

    @Test
    @DisplayName("模板构造：body 为 null 时按空串摘要，不是『缺字段』")
    void nullBodyBecomesEmptyDigest() {
        RequestTemplate template = new RequestTemplate();
        template.method(Request.HttpMethod.GET);
        template.uri("/eval/datasets");

        interceptor.apply(template);

        String canonical = CanonicalRequest.of(
                "GET",
                "/eval/datasets",
                Long.parseLong(header(template, AiInternalHeaders.TIMESTAMP)),
                header(template, AiInternalHeaders.NONCE),
                CanonicalRequest.EMPTY_BODY_SHA256,
                USER_ID,
                Role.ADMIN.name());
        assertEquals(
                InternalRequestSigner.fromSecret(SECRET).sign(canonical),
                header(template, AiInternalHeaders.SIGNATURE));
    }
}
