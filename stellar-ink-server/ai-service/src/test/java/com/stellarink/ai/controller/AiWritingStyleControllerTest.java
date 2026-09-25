package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.WritingStyleProfileDTO;
import com.stellarink.aiclient.dto.WritingStyleRequestDTO;
import com.stellarink.aiclient.dto.WritingStyleResultDTO;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.common.advice.GlobalExceptionHandler;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写作画像出口的权限与「按登录身份取样」。
 *
 * <p>最要紧的一条：**作者 id 必须来自登录身份**，客户端传什么都没用 ——
 * 否则任何人都能拿别人的 id 去量写作习惯（画像不含原句，但「写了多少、爱用什么词」也是隐私）。
 */
@WebMvcTest(controllers = AiWritingController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("test")
class AiWritingStyleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PythonAiClient pythonAiClient;

    /** 切片会把同包组件一起装配：模型配置控制器要 Mapper，这里 mock 掉。 */
    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    private static WritingStyleResultDTO profileOf(boolean enough) {
        WritingStyleResultDTO result = new WritingStyleResultDTO();
        result.setAuthorId(3L);
        result.setEvidenceSufficient(enough);
        result.setNotes("口径：只统计已发布文章的正文");
        if (enough) {
            WritingStyleProfileDTO profile = new WritingStyleProfileDTO();
            profile.setSampleCount(12);
            profile.setCharCount(4021);
            profile.setMedianSentenceChars(22.5);
            profile.setCommonPhrases(List.of("所以我", "的时候"));
            profile.setTransitions(List.of("所以", "其实"));
            profile.setTopTags(List.of("随笔"));
            result.setProfile(profile);
        }
        return result;
    }

    private static void stubAsAuthor(MockedStatic<AuthHelper> auth) {
        auth.when(AuthHelper::currentRole).thenReturn(Role.AUTHOR);
        auth.when(AuthHelper::loginId).thenReturn(3L);
        auth.when(() -> AuthHelper.requireAtLeast(any())).thenAnswer(invocation -> null);
    }

    @Test
    @DisplayName("未登录：401，且不碰下游（画像要读作者的文章）")
    void requiresLogin() throws Exception {
        mockMvc.perform(post("/ai/writing/style")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));

        verify(pythonAiClient, never()).writingStyle(any());
    }

    @Test
    @DisplayName("读者：403（画像是创作辅助，读者没有草稿可辅助）")
    void requiresAuthorRole() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            auth.when(AuthHelper::currentRole).thenReturn(Role.READER);
            auth.when(AuthHelper::loginId).thenReturn(9L);
            auth.when(() -> AuthHelper.requireAtLeast(any())).thenThrow(
                    new com.stellarink.sharedmodel.exception.BusinessException(
                            com.stellarink.sharedmodel.enums.ErrorCode.FORBIDDEN, "权限不足"));

            mockMvc.perform(post("/ai/writing/style")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }

        verify(pythonAiClient, never()).writingStyle(any());
    }

    @Test
    @DisplayName("作者 id 取登录身份，忽略客户端传的任何 userId")
    void authorIdComesFromTheSession() throws Exception {
        when(pythonAiClient.writingStyle(any())).thenReturn(profileOf(true));

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAuthor(auth);

            mockMvc.perform(post("/ai/writing/style")
                            .contentType(MediaType.APPLICATION_JSON)
                            // 客户端多写的字段不该有任何影响：内部契约里根本没有它
                            .content("{\"maxSamples\": 10, \"authorId\": 999, \"userId\": 999}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.profile.sampleCount").value(12))
                    .andExpect(jsonPath("$.data.profile.medianSentenceChars").value(22.5));
        }

        ArgumentCaptor<WritingStyleRequestDTO> captor =
                ArgumentCaptor.forClass(WritingStyleRequestDTO.class);
        verify(pythonAiClient).writingStyle(captor.capture());
        assertEquals(3L, captor.getValue().getAuthorId(), "作者 id 必须是登录身份，不是请求体里的");
        assertEquals(10, captor.getValue().getMaxSamples());
    }

    @Test
    @DisplayName("样本不足：原样透传 evidenceSufficient=false 与可读原因")
    void insufficientEvidenceIsPassedThrough() throws Exception {
        when(pythonAiClient.writingStyle(any())).thenReturn(profileOf(false));

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAuthor(auth);

            mockMvc.perform(post("/ai/writing/style")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.evidenceSufficient").value(false))
                    .andExpect(jsonPath("$.data.profile").doesNotExist())
                    .andExpect(jsonPath("$.data.notes").isNotEmpty());
        }
    }

    @Test
    @DisplayName("maxSamples 越界：契约层拦下（不会走到 Python）")
    void maxSamplesIsBounded() throws Exception {
        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAuthor(auth);

            mockMvc.perform(post("/ai/writing/style")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"maxSamples\": 999}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(1001));
        }

        verify(pythonAiClient, never()).writingStyle(any());
    }

    @Test
    @DisplayName("maxSamples 缺省时为 null，由 Python 用契约默认值（20）")
    void maxSamplesMayBeAbsent() throws Exception {
        when(pythonAiClient.writingStyle(any())).thenReturn(profileOf(true));

        try (MockedStatic<AuthHelper> auth = mockStatic(AuthHelper.class)) {
            stubAsAuthor(auth);

            mockMvc.perform(post("/ai/writing/style")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());
        }

        ArgumentCaptor<WritingStyleRequestDTO> captor =
                ArgumentCaptor.forClass(WritingStyleRequestDTO.class);
        verify(pythonAiClient).writingStyle(captor.capture());
        assertNull(captor.getValue().getMaxSamples(), "缺省就不要编值，让默认口径只存在一处");
        assertEquals(20, captor.getValue().resolvedMaxSamples(), "内部兜底为契约默认值");
        assertFalse(profileOf(false).getProfile() != null, "样本不足时不该有画像");
        assertTrue(profileOf(true).getProfile().hasContent(), "有样本时 hasContent 应为真");
    }
}
