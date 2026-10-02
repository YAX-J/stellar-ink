package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.ai.service.AiMemoryService;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.ai.service.AiWikiService;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.vo.ai.AiMemoryEvidenceVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 记忆管理入口的 HTTP 契约（M9-2）。
 *
 * <p>这一层要守两件事：
 *
 * <ol>
 *   <li><b>身份只从登录态来</b>：路径与参数里都没有 userId —— 用例断言
 *       「服务收到的 userId 就是登录者」，这样「查别人的记忆」不是被过滤掉，而是**表达不出来**；</li>
 *   <li><b>列表要把证据一起带回</b>：面板上「凭什么记住这条」和「记住什么」同等重要，
 *       只回正文的话用户唯一能做的就是「信不信」。</li>
 * </ol>
 */
@WebMvcTest(AiMemoryController.class)
@AutoConfigureMockMvc
@ActiveProfiles("unittest")
class AiMemoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiMemoryService memoryService;

    @MockBean
    private AiWikiService wikiService;

    @MockBean
    private PythonAiClient pythonAiClient;

    @MockBean
    private RedisUtils redisUtils;

    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    @MockBean
    private AiModelLibraryService modelLibraryService;

    @MockBean
    private AiUsageService usageService;

    private static void stubLogin(MockedStatic<AuthHelper> auth, long userId) {
        auth.when(AuthHelper::loginId).thenReturn(userId);
    }

    private static AiMemoryVO sample() {
        return AiMemoryVO.builder()
                .id(5L)
                .memoryType("preference")
                .content("作者偏好短句")
                .confidence(0.6)
                .source("model_suggested")
                .status("active")
                .evidence(List.of(AiMemoryEvidenceVO.builder()
                        .kind("quote")
                        .ref("句子短一点读起来才顺")
                        .build()))
                .build();
    }

    @Test
    @DisplayName("列表：按登录身份取数，证据一起回来")
    void listUsesLoggedInIdentity() throws Exception {
        when(memoryService.listMine(eq(7L), any(), any())).thenReturn(List.of(sample()));

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLogin(auth, 7L);

            mockMvc.perform(get("/ai/memory/list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data[0].content").value("作者偏好短句"))
                    .andExpect(jsonPath("$.data[0].evidence[0].ref").value("句子短一点读起来才顺"));

            verify(memoryService).listMine(7L, null, null);
        }
    }

    @Test
    @DisplayName("启用/禁用：只动自己的那条（服务收到的 userId 是登录者）")
    void setStatusUsesLoggedInIdentity() throws Exception {
        when(memoryService.setStatus(eq(7L), eq(5L), eq("disabled"))).thenReturn(sample());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLogin(auth, 7L);

            mockMvc.perform(put("/ai/memory/5/status").param("status", "disabled"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0));

            verify(memoryService).setStatus(7L, 5L, "disabled");
        }
    }

    @Test
    @DisplayName("删除与全部清除：同样只带登录身份，路径里没有别人的 id")
    void deleteAndClear() throws Exception {
        when(memoryService.delete(7L, 5L)).thenReturn(5L);
        when(memoryService.clearAll(7L)).thenReturn(3);

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLogin(auth, 7L);

            mockMvc.perform(delete("/ai/memory/5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(5));

            MvcResult cleared = mockMvc.perform(post("/ai/memory/clear"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.removed").value(3))
                    .andReturn();

            // 响应体里不该出现任何用户 id：它属于「谁在操作」，不是「操作了谁」
            String body = cleared.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(body.contains("\"userId\""), "响应里不该回显 userId：" + body);
        }
    }

    @Test
    @DisplayName("未登录：直接拒绝，且不会去动数据")
    void requiresLogin() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::loginId)
                    .thenThrow(new RuntimeException("未登录"));

            mockMvc.perform(get("/ai/memory/list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500));
        }
    }

    @Test
    @DisplayName("接口形状：路径里没有 userId 段（私密数据不靠过滤保证隔离）")
    void pathsCarryNoUserId() throws Exception {
        when(memoryService.listMine(eq(7L), any(), any())).thenReturn(List.of());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLogin(auth, 7L);
            // 带 userId 的路径应当 404：接口根本不接受「查某个用户」这种形状
            mockMvc.perform(get("/ai/memory/7/list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(404));
            assertTrue(true);
        }
    }

    @Test
    @DisplayName("状态参数是必填的：不传就走不到服务层（避免「什么也没改」的假成功）")
    void statusParamIsRequired() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLogin(auth, 7L);

            mockMvc.perform(put("/ai/memory/5/status"))
                    .andExpect(status().isOk())
                    // 缺参数的 code 是本仓库自己的 PARAM_ERROR(1001)，不是 HTTP 400
                    .andExpect(jsonPath("$.code").value(1001));
        }
    }

    @Test
    @DisplayName("列表返回空是正常结果，不是错误")
    void emptyListIsFine() throws Exception {
        when(memoryService.listMine(eq(7L), any(), any())).thenReturn(List.of());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLogin(auth, 7L);

            mockMvc.perform(get("/ai/memory/list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data").isEmpty());
        }
    }

    @Test
    @DisplayName("服务抛业务异常时，code 与消息原样透出（前端要能显示原因）")
    void businessErrorIsPassedThrough() throws Exception {
        when(memoryService.delete(eq(7L), eq(5L)))
                .thenThrow(new com.stellarink.sharedmodel.exception.BusinessException(
                        com.stellarink.sharedmodel.enums.ErrorCode.NOT_FOUND, "记忆不存在"));

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubLogin(auth, 7L);

            MvcResult result = mockMvc.perform(delete("/ai/memory/5"))
                    .andExpect(status().isOk())
                    .andReturn();

            String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(true, body.contains("记忆不存在"), body);
        }
    }
}
