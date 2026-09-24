package com.stellarink.aiclient.signature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.aiclient.constant.AiInternalHeaders;
import com.stellarink.sharedmodel.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内部签名的契约测试，基准是 {@code stellar-ink-ai/tests/fixtures/signature_vector.json}。
 *
 * <p>Python 侧（A2-3 的验签中间件）读同一份向量做反向验证：同一标准串必须算出同一签名。
 * 任何一侧改了分隔符、大小写或摘要编码方式，两侧测试会同时失败 ——
 * 这类不一致如果留到联调，表现是「永远 401」，而原因极难定位。
 */
class InternalRequestSignerTest {

    /** 从当前目录向上找仓库根：Surefire 在模块目录跑，exec:java 在 reactor 根跑，两者都要能用 */
    private static final Path FIXTURE = locateFixture();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path locateFixture() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("stellar-ink-ai/tests/fixtures/signature_vector.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("找不到签名向量文件，起点：" + Path.of("").toAbsolutePath());
    }

    private static JsonNode vector() throws IOException {
        return MAPPER.readTree(FIXTURE.toFile());
    }

    @Test
    @DisplayName("向量文件可达且已生成（占位符说明没跑过生成脚本）")
    void fixtureIsGenerated() throws IOException {
        assertTrue(Files.exists(FIXTURE), "找不到向量文件：" + FIXTURE.toAbsolutePath());
        for (JsonNode testCase : vector().get("cases")) {
            assertFalse(testCase.get("signature").asText().contains("COMPUTED"),
                    "向量还没生成，请先跑 stellar-ink-ai/scripts/gen_signature_vector.py");
        }
    }

    @Test
    @DisplayName("标准串与签名逐字节等于固定向量（跨语言一致性的核心断言）")
    void matchesFrozenVectors() throws IOException {
        InternalRequestSigner signer = InternalRequestSigner.fromSecret(vector().get("secret").asText());

        for (JsonNode testCase : vector().get("cases")) {
            String name = testCase.get("name").asText();
            JsonNode identity = testCase.get("identity");
            String bodySha256 = CanonicalRequest.sha256Hex(testCase.get("body").asText());
            assertEquals(testCase.get("bodySha256").asText(), bodySha256, name + " 的 body 摘要不一致");

            String canonical = CanonicalRequest.of(
                    testCase.get("method").asText(),
                    testCase.get("path").asText(),
                    testCase.get("timestampMs").asLong(),
                    testCase.get("nonce").asText(),
                    bodySha256,
                    identity.get("userId").asLong(),
                    identity.get("role").asText());
            assertEquals(testCase.get("canonical").asText(), canonical, name + " 的标准串不一致");
            assertEquals(testCase.get("signature").asText(), signer.sign(canonical), name + " 的签名不一致");
        }
    }

    @Test
    @DisplayName("空 body 用空串的摘要，而不是空字符串（否则无 body 与空 body 会撞同一个签名）")
    void emptyBodyUsesDigestOfEmptyString() throws IOException {
        assertEquals(vector().get("bodySha256Empty").asText(), CanonicalRequest.sha256Hex(""));
        assertEquals(vector().get("bodySha256Empty").asText(), CanonicalRequest.sha256Hex((String) null));
        assertEquals(vector().get("bodySha256Empty").asText(), CanonicalRequest.sha256Hex(new byte[0]));
    }

    @Test
    @DisplayName("请求头齐全且签名可复现：py 侧只靠这些头就能验签")
    void signRequestProducesAllHeaders() throws IOException {
        String secret = vector().get("secret").asText();
        JsonNode testCase = vector().get("cases").get(0);
        InternalRequestSigner signer = InternalRequestSigner.fromSecret(secret);

        Map<String, String> headers = signer.signRequest(
                testCase.get("method").asText(),
                testCase.get("path").asText(),
                testCase.get("body").asText(),
                42L,
                Role.AUTHOR,
                "trace-abc",
                testCase.get("timestampMs").asLong(),
                testCase.get("nonce").asText());

        assertEquals(testCase.get("signature").asText(), headers.get(AiInternalHeaders.SIGNATURE));
        assertEquals("42", headers.get(AiInternalHeaders.USER_ID));
        assertEquals("AUTHOR", headers.get(AiInternalHeaders.ROLE));
        assertEquals(String.valueOf(testCase.get("timestampMs").asLong()),
                headers.get(AiInternalHeaders.TIMESTAMP));
        assertEquals(testCase.get("nonce").asText(), headers.get(AiInternalHeaders.NONCE));
        assertEquals("trace-abc", headers.get(AiInternalHeaders.TRACE_ID));
        // 头名拼错会导致 Python 侧验签失败且极难发现，所以逐一核对常量
        assertEquals(6, headers.size());
    }

    @Test
    @DisplayName("任一要素变化都会改变签名（方法/路径/时间戳/nonce/body/身份/密钥）")
    void anyChangeChangesSignature() throws IOException {
        InternalRequestSigner signer = InternalRequestSigner.fromSecret(vector().get("secret").asText());
        String nonce = "0f1e2d3c4b5a69788796a5b4c3d2e1f0";
        long timestamp = 1790256000000L;
        String bodyDigest = CanonicalRequest.sha256Hex("{\"a\":1}");

        String base = signer.sign(CanonicalRequest.of("POST", "/qa/stream", timestamp, nonce, bodyDigest, 42L, "AUTHOR"));

        assertNotEquals(base, signer.sign(CanonicalRequest.of("PUT", "/qa/stream", timestamp, nonce, bodyDigest, 42L, "AUTHOR")));
        assertNotEquals(base, signer.sign(CanonicalRequest.of("POST", "/writing/suggest", timestamp, nonce, bodyDigest, 42L, "AUTHOR")));
        assertNotEquals(base, signer.sign(CanonicalRequest.of("POST", "/qa/stream", timestamp + 1, nonce, bodyDigest, 42L, "AUTHOR")));
        assertNotEquals(base, signer.sign(CanonicalRequest.of("POST", "/qa/stream", timestamp, "another-nonce", bodyDigest, 42L, "AUTHOR")));
        assertNotEquals(base, signer.sign(CanonicalRequest.of("POST", "/qa/stream", timestamp, nonce,
                CanonicalRequest.sha256Hex("{\"a\":2}"), 42L, "AUTHOR")));

        // 身份也在签名里：改 user_id 或 role 必须让签名变化，否则内网可冒充 ADMIN
        assertNotEquals(base, signer.sign(CanonicalRequest.of("POST", "/qa/stream", timestamp, nonce, bodyDigest, 1L, "AUTHOR")));
        assertNotEquals(base, signer.sign(CanonicalRequest.of("POST", "/qa/stream", timestamp, nonce, bodyDigest, 42L, "ADMIN")));

        InternalRequestSigner otherKey = InternalRequestSigner.fromSecret(
                "another-internal-secret-for-test-only-000002");
        assertNotEquals(base, otherKey.sign(CanonicalRequest.of("POST", "/qa/stream", timestamp, nonce, bodyDigest, 42L, "AUTHOR")));
    }

    @Test
    @DisplayName("方法大小写不影响签名：GET/get 必须得到同一个结果")
    void methodCaseIsNormalized() throws IOException {
        InternalRequestSigner signer = InternalRequestSigner.fromSecret(vector().get("secret").asText());
        String digest = CanonicalRequest.sha256Hex("");

        assertEquals(
                signer.sign(CanonicalRequest.of("GET", "/health", 1L, "n", digest, 7L, "READER")),
                signer.sign(CanonicalRequest.of("get", "/health", 1L, "n", digest, 7L, "reader")));
    }

    @Test
    @DisplayName("标准串字段校验：防止把 query、换行或非法身份拼进来造成歧义")
    void canonicalRejectsAmbiguousInput() {
        String digest = CanonicalRequest.sha256Hex("");

        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.of("GET", "/health?x=1", 1L, "n", digest, 7L, "READER"));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.of("GET", "health", 1L, "n", digest, 7L, "READER"));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.of("GET", "/health", 1L, "bad\nnonce", digest, 7L, "READER"));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.of("GET", "/health", 0L, "n", digest, 7L, "READER"));
        // 身份非法：负数 id、白名单外的角色都不该被签出一个「看起来合法」的请求
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.of("GET", "/health", 1L, "n", digest, 0L, "READER"));
        assertThrows(IllegalArgumentException.class,
                () -> CanonicalRequest.of("GET", "/health", 1L, "n", digest, 7L, "SUPER"));
    }

    @Test
    @DisplayName("nonce 每次不同（32 位十六进制）：重放防线不能被自己破坏")
    void nonceIsRandomPerRequest() {
        String first = CanonicalRequest.newNonce();
        String second = CanonicalRequest.newNonce();

        assertNotEquals(first, second);
        assertEquals(32, first.length());
        assertTrue(first.matches("[0-9a-f]{32}"));
    }

    @Test
    @DisplayName("密钥缺失或过短都归类为「配置问题」，并说明合格要求")
    void secretProblemsAreActionable() {
        assertThrows(InternalRequestSigner.SecretMissingException.class,
                () -> InternalRequestSigner.fromSecret(null));
        assertThrows(InternalRequestSigner.SecretMissingException.class,
                () -> InternalRequestSigner.fromSecret("   "));

        // 缺失时提示怎么生成密钥；过短时提示长度要求 —— 两种都要可操作
        var missing = assertThrows(InternalRequestSigner.SecretMissingException.class,
                () -> InternalRequestSigner.fromSecret(""));
        assertTrue(missing.getMessage().contains("openssl"),
                "缺失密钥时要告诉人怎么生成：" + missing.getMessage());

        var tooShort = assertThrows(InternalRequestSigner.SecretMissingException.class,
                () -> InternalRequestSigner.fromSecret("too-short"));
        assertTrue(tooShort.getMessage().contains(String.valueOf(InternalRequestSigner.MIN_SECRET_LENGTH)),
                "过短时要说明长度要求：" + tooShort.getMessage());
    }

    @Test
    @DisplayName("未携带业务身份时拒绝签名：不允许发一个「没有身份」的内部请求")
    void refusesSigningWithoutUserId() {
        InternalRequestSigner signer = InternalRequestSigner.fromSecret(
                "stellar-ink-internal-secret-for-test-only-0001");

        assertThrows(IllegalArgumentException.class,
                () -> signer.signRequest("GET", "/health", "", null, Role.READER, "trace"));
    }
}
