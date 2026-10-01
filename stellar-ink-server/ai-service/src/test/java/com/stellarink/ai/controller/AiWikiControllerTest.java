package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.ai.service.AiWikiService;
import com.stellarink.common.advice.GlobalExceptionHandler;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.ai.AiWikiBuildVO;
import com.stellarink.sharedmodel.vo.ai.AiWikiClaimVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wiki 出口的两道门槛**刻意不同**，所以这个类主要验门槛，而不是验抽取：
 *
 * <ul>
 *   <li>构建是 ADMIN：它是批量模型调用，直接花钱，而且是「重写知识库」这种影响全站内容的动作；</li>
 *   <li>读取是公开：读者要能自己核对主张与原文片段 —— 要登录才能看证据的话，Wiki 就成了「信我」。</li>
 * </ul>
 *
 * <p>另验一条与 Agent 同源的口径：**预算只能收紧**（`bounded` 取 min），
 * 否则每个请求都能自行决定「这次花多少钱」。
 */
@WebMvcTest(controllers = AiWikiController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("unittest")
class AiWikiControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

    private static AiWikiBuildVO buildResult() {
        return AiWikiBuildVO.builder()
                .posts(1)
                .proposed(4)
                .kept(3)
                .inserted(2)
                .updated(1)
                .skipped(0)
                .dropped(Map.of("quoteNotFound", 1))
                .usageModel("deepseek-flash")
                .latencyMs(3120L)
                .notes(List.of("落库：新增 2 条、更新 1 条、未变动 0 条。"))
                .build();
    }

    private static void stubAsAdmin(MockedStatic<AuthHelper> auth) {
        auth.when(AuthHelper::currentRole).thenReturn(Role.ADMIN);
        auth.when(() -> AuthHelper.requireAtLeast(Role.ADMIN)).thenAnswer(invocation -> null);
    }

    @Test
    @DisplayName("构建：未登录 401，且不触发任何抽取")
    void buildRequiresLogin() throws Exception {
        mockMvc.perform(post("/ai/admin/wiki/build").contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(wikiService, never()).build(any());
    }

    @Test
    @DisplayName("构建：读者 403（它花钱、还重写全站知识条目）")
    void buildRequiresAdmin() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
            auth.when(() -> AuthHelper.requireAtLeast(Role.ADMIN))
                    .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "权限不足"));

            mockMvc.perform(post("/ai/admin/wiki/build").contentType("application/json").content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }

        verify(wikiService, never()).build(any());
    }

    @Test
    @DisplayName("构建：两边的账都回给调用方（抽取统计 + 落库统计）")
    void buildReturnsBothAccounts() throws Exception {
        when(wikiService.build(any())).thenReturn(buildResult());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAdmin(auth);

            mockMvc.perform(post("/ai/admin/wiki/build")
                            .contentType("application/json").content("{\"maxPosts\":2}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.proposed").value(4))
                    .andExpect(jsonPath("$.data.kept").value(3))
                    .andExpect(jsonPath("$.data.inserted").value(2))
                    .andExpect(jsonPath("$.data.updated").value(1))
                    .andExpect(jsonPath("$.data.dropped.quoteNotFound").value(1))
                    .andExpect(jsonPath("$.data.notes[0]").value(
                            org.hamcrest.Matchers.containsString("落库")));
        }
    }

    @Test
    @DisplayName("构建：预算只能收紧（传 99 会被夹到服务端默认 5）")
    void buildClampsBudget() throws Exception {
        when(wikiService.build(any())).thenReturn(buildResult());

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAdmin(auth);

            mockMvc.perform(post("/ai/admin/wiki/build")
                            .contentType("application/json").content("{\"maxPosts\":99}"))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<com.stellarink.aiclient.dto.AiWikiClaimsRequestDTO> captor =
                ArgumentCaptor.forClass(com.stellarink.aiclient.dto.AiWikiClaimsRequestDTO.class);
        verify(wikiService).build(captor.capture());
        assertEquals(AiWikiController.DEFAULT_MAX_POSTS, captor.getValue().getMaxPosts(),
                "客户端不能自行决定这次花多少钱");
        assertEquals(AiWikiController.DEFAULT_MAX_CLAIMS_PER_CHUNK,
                captor.getValue().getMaxClaimsPerChunk());
    }

    @Test
    @DisplayName("读取：**公开**（与文章本身的可见性一致），且证据一起回")
    void readerSideIsPublic() throws Exception {
        when(wikiService.claimsOfPost(7L)).thenReturn(List.of(AiWikiClaimVO.builder()
                .id(1L).postId(7L).chunkIndex(0).postVersion("v1").contentHash("hash0")
                .text("每天写五百字可以累积成十八万字").quote("每天写五百字，一年就是十八万字")
                .confidence(0.9).build()));

        mockMvc.perform(get("/ai/wiki/posts/{postId}/claims", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].text").value("每天写五百字可以累积成十八万字"))
                .andExpect(jsonPath("$.data[0].quote").value("每天写五百字，一年就是十八万字"))
                .andExpect(jsonPath("$.data[0].postVersion").value("v1"));
    }

    @Test
    @DisplayName("读取：条数接口也是公开的（读者侧据此决定要不要显示入口）")
    void countIsPublic() throws Exception {
        when(wikiService.countOfPost(7L)).thenReturn(3L);

        mockMvc.perform(get("/ai/wiki/claims/count").param("postId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(3));
    }
}
