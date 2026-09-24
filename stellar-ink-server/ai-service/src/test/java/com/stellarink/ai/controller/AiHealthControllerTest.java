package com.stellarink.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /ai/health} 的契约测试：跑**完整的 Spring 上下文**（含 security/配置/公共组件），
 * 但禁用 Nacos 注册与配置拉取（见 {@code application-test.yml}），因此不依赖任何外部组件。
 *
 * <p>M0 阶段下游是 Fake 探活，所以这里断言的是「如实上报未就绪」而不是「一切正常」——
 * 假装健康比暴露未接线更危险。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiHealthControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 公开接口允许出现的字段白名单：多一个字段就要在这里显式确认它不是泄露 */
    private static final Set<String> ALLOWED_DATA_FIELDS = Set.of(
            "service", "version", "env", "available", "reason", "downstreamAvailable", "checkedAt");

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("公开可访问：不带 token 也能探测")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/ai/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.service").value("ai-service"));
    }

    @Test
    @DisplayName("M0 如实上报：下游未接线时 available=false，且给出对用户可读的原因")
    void reportsDownstreamNotWiredInM0() throws Exception {
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
    @DisplayName("不泄露配置：响应里不得出现内网地址、端口、密钥或配置键名")
    void doesNotLeakConfiguration() throws Exception {
        MvcResult result = mockMvc.perform(get("/ai/health")).andReturn();
        String body = bodyOf(result);

        for (String forbidden : new String[]{"8200", "127.0.0.1", "pythonBaseUrl", "python-base-url",
                "secret", "Secret", "jwt", "token", "nacos", "Nacos", "password"}) {
            assertFalse(body.contains(forbidden), "公开接口泄露了敏感内容：" + forbidden);
        }
    }

    @Test
    @DisplayName("响应字段受白名单约束：新增字段必须显式评审（防止顺手带出配置）")
    void dataFieldsAreWhitelisted() throws Exception {
        JsonNode data = readData(mockMvc.perform(get("/ai/health")).andReturn());

        Set<String> fields = new java.util.HashSet<>();
        data.fieldNames().forEachRemaining(fields::add);

        assertEquals(ALLOWED_DATA_FIELDS, fields, "data 字段与白名单不一致，请确认新字段不泄露配置");
    }

    @Test
    @DisplayName("未知 AI 路径返回「路径不存在」：M0 只有 /ai/health，其余接口属 M1 之后")
    void unknownAiPathIsNotFound() throws Exception {
        // 注意：公共 common-core 的 GlobalExceptionHandler 把 NoResourceFoundException 映射成
        // HTTP 200 + body.code=404（与仓库其它服务一致）。此处按实际行为断言，
        // 不去改公共处理器 —— 那会影响 user-service / content-service 的既有契约。
        mockMvc.perform(get("/ai/qa/stream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    /** 必须按 UTF-8 解码：MockMvc 默认用 ISO-8859-1，中文会变问号，断言会莫名其妙地失败。 */
    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static JsonNode readData(MvcResult result) throws Exception {
        return MAPPER.readTree(bodyOf(result)).get("data");
    }
}
