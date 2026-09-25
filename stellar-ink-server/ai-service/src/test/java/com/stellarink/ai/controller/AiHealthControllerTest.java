package com.stellarink.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.ai.client.PythonHealthProbe;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.common.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /ai/health} 的契约测试。
 *
 * <p>用 {@code @WebMvcTest} 而不是整个上下文：探活是纯 Web 行为，
 * 起全上下文会把数据源、Nacos 一起拉起来（测试要求连 MySQL 是没必要的负担）。
 * 数据源与加密的行为由 {@code AiProviderConfigServiceImplTest} 单独覆盖，
 * 这里把配置服务替换成 Mock，避免它去要 Mapper。
 */
@WebMvcTest(controllers = AiHealthController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class AiHealthControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 公开接口允许出现的字段白名单：多一个字段就要在这里显式确认它不是泄露 */
    private static final Set<String> ALLOWED_DATA_FIELDS = Set.of(
            "service", "version", "env", "available", "reason", "downstreamAvailable", "checkedAt");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PythonHealthProbe pythonHealthProbe;

    /**
     * 评测控制器也在这个切片里被装配（启动类显式声明了 {@code @ComponentScan}，
     * 切片会扫描全部组件），它依赖 Feign 的 Python 客户端 —— 切片里没有 Feign 自动配置，
     * 必须 mock 掉，否则整个切片上下文起不来。
     */
    @MockBean
    private com.stellarink.aiclient.client.PythonAiClient pythonAiClient;

    /** 配置服务的实现在切片测试里没有 Mapper 可用，替换成 Mock（它本身由专门的单测覆盖）。 */
    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    @Test
    @DisplayName("公开可访问：不带 token 也能探测")
    void healthIsPublic() throws Exception {
        when(pythonHealthProbe.probe()).thenReturn(PythonHealthProbe.ProbeResult.unavailable("test"));

        mockMvc.perform(get("/ai/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.service").value("ai-service"));
    }

    @Test
    @DisplayName("下游未接线时如实上报 available=false，并给出对用户可读的原因")
    void reportsDownstreamNotWiredInM0() throws Exception {
        when(pythonHealthProbe.probe()).thenReturn(
                PythonHealthProbe.ProbeResult.unavailable("内部细节：http://127.0.0.1:8200 连不上"));

        MvcResult result = mockMvc.perform(get("/ai/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.downstreamAvailable").value(false))
                .andReturn();

        JsonNode data = readData(result);
        assertNotNull(data.get("reason"));
        // 公开响应里只给结论；地址与真实原因留在服务端日志
        assertEquals("下游 AI 编排服务未就绪", data.get("reason").asText());
    }

    @Test
    @DisplayName("下游可用时 available=true 且不再给 reason")
    void reportsAvailableWhenDownstreamIsUp() throws Exception {
        when(pythonHealthProbe.probe()).thenReturn(
                PythonHealthProbe.ProbeResult.available("stellar-ink-ai", "0.1.0"));

        mockMvc.perform(get("/ai/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.reason").doesNotExist());
    }

    @Test
    @DisplayName("不泄露配置：响应里不得出现内网地址、端口、密钥或配置键名")
    void doesNotLeakConfiguration() throws Exception {
        when(pythonHealthProbe.probe()).thenReturn(
                PythonHealthProbe.ProbeResult.unavailable("内部细节：http://127.0.0.1:8200 连不上"));

        String body = bodyOf(mockMvc.perform(get("/ai/health")).andReturn());

        for (String forbidden : new String[]{"8200", "127.0.0.1", "pythonBaseUrl", "python-base-url",
                "secret", "Secret", "jwt", "token", "nacos", "Nacos", "password"}) {
            assertFalse(body.contains(forbidden), "公开接口泄露了敏感内容：" + forbidden);
        }
    }

    @Test
    @DisplayName("响应字段受白名单约束：新增字段必须显式评审（防止顺手带出配置）")
    void dataFieldsAreWhitelisted() throws Exception {
        when(pythonHealthProbe.probe()).thenReturn(PythonHealthProbe.ProbeResult.unavailable("test"));

        JsonNode data = readData(mockMvc.perform(get("/ai/health")).andReturn());

        Set<String> fields = new java.util.HashSet<>();
        data.fieldNames().forEachRemaining(fields::add);

        assertEquals(ALLOWED_DATA_FIELDS, fields, "data 字段与白名单不一致，请确认新字段不泄露配置");
    }

    @Test
    @DisplayName("未知 AI 路径返回「路径不存在」：未实现的接口不能看起来像可用")
    void unknownAiPathIsNotFound() throws Exception {
        // 注意：公共 common-core 的 GlobalExceptionHandler 把 NoResourceFoundException 映射成
        // HTTP 200 + body.code=404（与仓库其它服务一致）。此处按实际行为断言，
        // 不去改公共处理器 —— 那会影响 user-service / content-service 的既有契约。
        mockMvc.perform(get("/ai/not-implemented-yet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("已实现路径用错方法：405 而不是 500（新增路由会改变旧断言的语义）")
    void wrongMethodOnKnownPathIsMethodNotAllowed() throws Exception {
        // 这条曾经用 GET /ai/qa/stream 当作「未知路径」的代表 —— 直到 D2s 真的把它实现出来。
        // 路由一落地，同一句断言的含义就从「不存在」变成「方法不对」，而它当时报的是 500。
        // 因此这里把两件事**分开**断言：不存在的路径给 404，存在但方法不对给 405。
        mockMvc.perform(get("/ai/qa/stream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405));
    }

    /** 必须按 UTF-8 解码：MockMvc 默认用 ISO-8859-1，中文会变问号，断言会莫名其妙地失败。 */
    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static JsonNode readData(MvcResult result) throws Exception {
        return MAPPER.readTree(bodyOf(result)).get("data");
    }
}
