package com.stellarink.ai.service;

import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code baseUrl} 安全策略（M12 的 SSRF 闸门）。
 *
 * <p>为什么这份用例重要：{@code baseUrl} 是<b>服务端</b>拿去发请求的地址。
 * 只让站长填时它只是配置；让读者/作者也能填之后，任何人都能让服务去打内网
 * （云元数据 169.254.169.254、Nacos、内网服务），而报错还会把响应内容带回来。
 *
 * <p>两档规则：站长（全局）允许内网（自建推理就在 127.0.0.1），用户只允许公网，
 * 而<b>链路本地两档都拒绝</b>。
 */
class ProviderUrlPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "https://api.deepseek.com/v1",
            "http://1.1.1.1:8000/v1",
    })
    @DisplayName("公网地址：两档都放行")
    void publicAddressesPassForEveryone(String url) {
        assertDoesNotThrow(() -> ProviderUrlPolicy.check(url, false));
        assertDoesNotThrow(() -> ProviderUrlPolicy.check(url, true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1:8000/v1",
            "http://localhost:11434/v1",
            "http://10.0.0.5:8000/v1",
            "http://192.168.1.20/v1",
            "http://172.16.0.9/v1",
            "http://ollama.internal/v1",
            "http://box.local/v1",
    })
    @DisplayName("内网地址：只有站长（全局配置）能填")
    void privateAddressesOnlyForTheAdmin(String url) {
        // 自建 vLLM / Ollama 就在本机或内网：禁掉等于把最正当的用法堵死
        assertDoesNotThrow(() -> ProviderUrlPolicy.check(url, true));
        assertThrows(BusinessException.class, () -> ProviderUrlPolicy.check(url, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/",
            "http://169.254.0.1/v1",
    })
    @DisplayName("链路本地：连站长也拒绝（那是云元数据网段，不是模型端点）")
    void linkLocalIsAlwaysRejected(String url) {
        assertThrows(BusinessException.class, () -> ProviderUrlPolicy.check(url, true));
        assertThrows(BusinessException.class, () -> ProviderUrlPolicy.check(url, false));
    }

    @Test
    @DisplayName("云元数据主机名也要拒")
    void metadataHostnameIsRejected() {
        assertThrows(BusinessException.class,
                () -> ProviderUrlPolicy.check("http://metadata.google.internal/v1", true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://api.example.com/v1", "//api.example.com/v1", ""})
    @DisplayName("协议必须是 http/https，且要有主机名")
    void schemeMustBeHttpOrHttps(String url) {
        assertThrows(BusinessException.class, () -> ProviderUrlPolicy.check(url, true));
    }

    @Test
    @DisplayName("URL 里带凭据要拒：那会被日志与审计记录下来")
    void credentialsInUrlAreRejected() {
        assertThrows(BusinessException.class,
                () -> ProviderUrlPolicy.check("https://user:pass@api.example.com/v1", true));
    }

    @Test
    @DisplayName("用户级只放开 chat/fast/reasoning（与 Python 的 USER_SCOPED_ROLES 一致）")
    void onlyGenerationRolesAreUserScoped() {
        assertTrue(ProviderUrlPolicy.isUserScoped(AiModelRole.CHAT));
        assertTrue(ProviderUrlPolicy.isUserScoped(AiModelRole.FAST));
        assertTrue(ProviderUrlPolicy.isUserScoped(AiModelRole.REASONING));
        // embedding/rerank 由站长统一配：向量索引只有一份，换模型检索得到的是错的结果
        assertFalse(ProviderUrlPolicy.isUserScoped(AiModelRole.EMBEDDING));
        assertFalse(ProviderUrlPolicy.isUserScoped(AiModelRole.RERANK));
    }
}
