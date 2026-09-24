package com.stellarink.aiclient.signature;

import com.stellarink.sharedmodel.enums.Role;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 跨语言端到端冒烟：Java 生成签名 → Python 服务验签。
 *
 * <p>用法（先起 Python 服务并设置同一个密钥）：
 * <pre>
 * # 终端 1
 * cd stellar-ink-ai
 * $env:AI_INTERNAL_SECRET = "stellar-ink-internal-secret-for-test-only-0001"
 * uv run uvicorn app.main:app --host 127.0.0.1 --port 8200
 *
 * # 终端 2
 * cd stellar-ink-server
 * mvn -q -pl stellar-ink-ai-client test-compile
 * mvn -q -pl stellar-ink-ai-client exec:java -Dexec.classpathScope=test \
 *   -Dexec.mainClass=com.stellarink.aiclient.signature.InternalAuthSmoke
 * </pre>
 *
 * <p>为什么保留它：两侧单测各自只证明「自己那半边对」，而签名不一致的典型表现是
 * 「本地全绿、联调永远 401」。这个冒烟脚本用**真实的 HTTP 往返**把两侧接起来，
 * 是单元测试替代不了的一步。
 */
public final class InternalAuthSmoke {

    private static final String BASE_URL = System.getenv().getOrDefault("AI_BASE_URL", "http://127.0.0.1:8200");
    private static final String SECRET_ENV = "AI_INTERNAL_SECRET";
    private static final String PATH = "/internal/whoami";

    private InternalAuthSmoke() {
    }

    public static void main(String[] args) throws Exception {
        String secret = System.getenv(SECRET_ENV);
        if (secret == null || secret.isBlank()) {
            System.err.println("请先设置 " + SECRET_ENV + "（与 Python 侧一致）再运行本冒烟");
            System.exit(2);
        }
        InternalRequestSigner signer = InternalRequestSigner.fromSecret(secret);

        // 1) 探活：应为 200，且不需要签名
        System.out.println("health  -> " + get("/health", null));

        // 2) 无签名访问受保护路径：应为 401
        System.out.println("unsigned-> " + get(PATH, null));

        // 3) 带签名：应为 200，且响应里的 userId/role 与签名时一致
        var headers = signer.signRequest("GET", PATH, "", 42L, Role.AUTHOR, "java-smoke-trace");
        System.out.println("signed  -> " + get(PATH, headers));

        // 4) 改了身份头：签名不再匹配，应为 401（身份参与签名的回归）
        var tampered = new java.util.LinkedHashMap<>(headers);
        tampered.put("X-AI-User-Id", "1");
        tampered.put("X-AI-Role", "ADMIN");
        System.out.println("tampered-> " + get(PATH, tampered));
    }

    private static String get(String path, java.util.Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (value != null && !value.isEmpty()) {
                    builder.header(name, value);
                }
            });
        }
        HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                // 本机可能配了代理环境变量：冒烟要直连 loopback，避免被代理劫持成 502
                .proxy(java.net.ProxySelector.of(null))
                .build()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + " " + response.body();
    }
}
