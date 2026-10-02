package com.stellarink.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.ai.config.TestMasterKeyConfig;
import com.stellarink.ai.mapper.AiProviderConfigMapper;
import com.stellarink.ai.pojo.AiProviderConfig;
import com.stellarink.ai.service.AiStyleProfileService;
import com.stellarink.ai.service.AiMemoryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.sharedmodel.dto.ai.AiProviderSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.vo.ai.AiProviderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模型配置面板接口的权限与脱敏回归（跑**完整上下文 + H2 内存库**）。
 *
 * <p>为什么不用 {@code @WebMvcTest} 切片：模型配置要真落库，而且权限链路依赖
 * Sa-Token 的 JWT 模式 —— 切片里那条链路要额外拼装上下文与插件，非常脆。
 * 用 H2 起全上下文既更接近真实，又顺带验证了「{@code @Mapper} 扫描生效」「加密列能存能取」。
 *
 * <p>只需额外导入测试主密钥（生产只从环境变量读）。鉴权方面：没有 token 时
 * {@code AuthHelper} 会把 Sa-Token 的 JWT 异常翻译成 401，因此这里断言的是
 * 「未登录一律 401、绝不 500」这条底线；配置功能本身的正确性由服务层单测覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("unittest")
@Import(TestMasterKeyConfig.class)
class AiProviderAdminControllerTest {

    @MockBean
    private AiStyleProfileService styleProfileService;

    @MockBean
    private AiMemoryService memoryService;

    private static final String PLAINTEXT_KEY = "sk-live-abcdefghijklmnop-9f3a";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AiProviderConfigMapper mapper;

    @Autowired
    private AiProviderConfigService service;

    @AfterEach
    void cleanUp() {
        mapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AiProviderConfig>lambdaQuery());
    }

    private static String saveBody(String model, String apiKey) {
        return """
                {
                  "role": "chat",
                  "displayName": "DeepSeek Chat",
                  "provider": "openai_compatible",
                  "baseUrl": "https://api.deepseek.com/v1",
                  "model": "%s",
                  "apiKey": "%s"
                }
                """.formatted(model, apiKey);
    }

    private void seedChatConfig() {
        AiProviderSaveDTO dto = new AiProviderSaveDTO();
        dto.setRole(AiModelRole.CHAT);
        dto.setDisplayName("DeepSeek Chat");
        dto.setProvider("openai_compatible");
        dto.setBaseUrl("https://api.deepseek.com/v1");
        dto.setModel("deepseek-chat");
        dto.setApiKey(PLAINTEXT_KEY);
        service.save(dto, 1L);
    }

    @Test
    @DisplayName("未登录：列表、保存、删除、自检、运行时配置统一 401（不是 500，也不是悄悄放行）")
    void allEndpointsRequireLogin() throws Exception {
        // 没有 token：AuthHelper 把 SaJwtException 翻译成 401，而不是让它冒泡成 500
        mockMvc.perform(get("/ai/admin/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/ai/admin/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("deepseek-chat", PLAINTEXT_KEY)))
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(delete("/ai/admin/providers/chat"))
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(post("/ai/admin/providers/chat/check"))
                .andExpect(jsonPath("$.code").value(401));

        // 模型库与「给角色选模型」同样只对 ADMIN 开放：它们也能读到端点与掩码
        mockMvc.perform(get("/ai/admin/models"))
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(put("/ai/admin/providers/chat/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":1}"))
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(delete("/ai/admin/models/1"))
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/ai/admin/providers/runtime"))
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("越权响应里不能回显请求体中的明文 Key")
    void forbiddenResponseDoesNotEchoKey() throws Exception {
        MvcResult result = mockMvc.perform(post("/ai/admin/providers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("deepseek-chat", PLAINTEXT_KEY)))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(PLAINTEXT_KEY), "拒绝响应不该回显请求体里的密钥");
    }

    @Test
    @DisplayName("落库是密文：直接读表也拿不到明文（主密钥只在环境变量）")
    void storedRowKeepsCiphertextOnly() {
        seedChatConfig();

        AiProviderConfig row = mapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AiProviderConfig>lambdaQuery());
        String cipher = new String(row.getApiKeyCipher(), StandardCharsets.UTF_8);

        assertTrue(cipher.startsWith("v1:"), "落库内容不是密文格式：" + cipher);
        assertFalse(cipher.contains(PLAINTEXT_KEY));
        assertEquals("sk-…9f3a", row.getApiKeyMask());
        assertFalse(row.getApiKeyMask().contains("abcdefghijklmnop"));
    }

    @Test
    @DisplayName("服务层视图只带掩码：序列化后的 JSON 里既没有明文也没有密文")
    void viewCarriesOnlyMask() {
        seedChatConfig();

        AiProviderVO vo = service.list().get(0);
        JsonNode node = objectMapper.valueToTree(vo);
        String json = node.toString();

        assertTrue(json.contains("sk-…9f3a"));
        assertFalse(json.contains(PLAINTEXT_KEY));
        assertFalse(json.contains("v1:"), "响应里不该出现密文本身");
        assertFalse(json.contains("abcdefghijklmnop"));
    }

    @Test
    @DisplayName("自检接口：结果只含结论字段，不带 baseUrl 与密钥")
    void checkResultHidesEndpoint() throws Exception {
        seedChatConfig();

        // 未登录会被拦在 403，这里直接调服务层取结论结构（接口形状另有断言）
        var result = service.checkConnectivity(AiModelRole.CHAT);
        String rendered = objectMapper.writeValueAsString(result);

        assertFalse(rendered.contains("api.deepseek.com"));
        assertFalse(rendered.contains(PLAINTEXT_KEY));
        assertTrue(rendered.contains("tcp_only"));
    }

    @Test
    @DisplayName("留空 Key 更新模型名：密文与掩码保持原样，不要求重填密钥")
    void updateWithoutKeyKeepsCipher() {
        seedChatConfig();
        byte[] before = mapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AiProviderConfig>lambdaQuery()).getApiKeyCipher().clone();

        AiProviderSaveDTO dto = new AiProviderSaveDTO();
        dto.setRole(AiModelRole.CHAT);
        dto.setDisplayName("DeepSeek Reasoner");
        dto.setProvider("openai_compatible");
        dto.setBaseUrl("https://api.deepseek.com/v1");
        dto.setModel("deepseek-reasoner");
        dto.setApiKey("");
        service.save(dto, 2L);

        AiProviderConfig after = mapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AiProviderConfig>lambdaQuery());
        assertEquals("deepseek-reasoner", after.getModel());
        assertTrue(java.util.Arrays.equals(before, after.getApiKeyCipher()), "留空 Key 不该改动已存密文");
        assertEquals("sk-…9f3a", after.getApiKeyMask());
    }

    @Test
    @DisplayName("未知角色不能假成功：解析不出角色时返回 null，由接口层报参数错误")
    void unknownRoleIsRejected() {
        assertNull(AiModelRole.parse("nope"));
        assertNull(AiModelRole.parse(""));
        assertEquals(AiModelRole.CHAT, AiModelRole.parse(" CHAT "));
    }

    @Test
    @DisplayName("列表响应的字段形状固定：前端面板按 role 建立索引，JSON 键必须是驼峰")
    void listResponseShapeIsStableForThePanel() {
        seedChatConfig();

        JsonNode data = objectMapper.valueToTree(service.list());

        assertTrue(data.isArray() && data.size() == 1);
        JsonNode first = data.get(0);
        // 面板用 role 当索引键；apiKeyMask/apiKeyConfigured 决定按钮与提示
        assertTrue(first.has("role"));
        assertTrue(first.has("displayName"));
        assertTrue(first.has("baseUrl"));
        assertTrue(first.has("model"));
        assertTrue(first.has("apiKeyMask"));
        assertTrue(first.has("apiKeyConfigured"));
        assertTrue(first.has("lastCheckStatus"));
        assertEquals("chat", first.get("role").asText(), "role 必须是面板可识别的小写键");
        assertFalse(first.has("apiKeyCipher"), "响应里不该出现密文字段");
    }
}
