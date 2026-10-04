package com.stellarink.ai.controller;

import com.stellarink.ai.config.InternalSignatureFeignInterceptor;
import com.stellarink.ai.corpus.controller.AiCorpusController;
import com.stellarink.ai.corpus.service.CorpusSyncService;
import com.stellarink.ai.service.AiMemoryService;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.AiRetrievalAuditService;
import com.stellarink.ai.service.AiStyleProfileService;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.ai.service.AiWikiService;
import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.common.advice.GlobalExceptionHandler;
import com.stellarink.common.redis.RedisUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 语料同步端点的门槛。
 *
 * <p>为什么只测「未登录被拒」：这两个端点一个会改自己的表（sync）、一个会读行数（count），
 * 都属于 ADMIN 面。切片里没有登录上下文，所以能验的正是最要紧的那半条 ——
 * **没登录就必须被拒**（`AuthHelper.requireAtLeast` 抛 NotLoginException，
 * 由全局处理器翻成 `HTTP 200 + code=401`；这正是本仓库「鉴权失败形态不统一」的那条口径）。
 *
 * <p>⚠️ 刻意不写「已登录 ADMIN 时返回计数」的用例：切片里没有真的 Sa-Token 会话，
 * 要造一个就得伪造登录态 —— 那样测的其实是伪造本身，不如把计数透出交给
 * `CorpusSyncServiceImplTest`（服务层）与真实往返验。
 */
@WebMvcTest(controllers = AiCorpusController.class)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("unittest")
class AiCorpusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CorpusSyncService corpusSyncService;

    /**
     * 启动类的 @ComponentScan 会把本模块**所有**组件装进切片，所以每个切片都要把外部依赖 mock 掉
     * （这份清单与 AiEvalControllerTest 保持一致）。少一个就会看到
     * 「No qualifying bean of type FeignClientFactory」这类与测试意图毫无关系的报错。
     */
    @MockBean
    private PythonAiClient pythonAiClient;

    @MockBean
    private AiRetrievalAuditService auditService;

    @MockBean
    private AiStyleProfileService styleProfileService;

    @MockBean
    private AiMemoryService memoryService;

    @MockBean
    private AiUsageService usageService;

    @MockBean
    private RedisUtils redisUtils;

    @MockBean
    private AiProviderConfigService aiProviderConfigService;

    @MockBean
    private AiModelLibraryService modelLibraryService;

    @MockBean
    private AiWikiService wikiService;

    /** 启动类的 @ComponentScan 会把签名拦截器也装进切片，mock 掉以免它去要内部密钥。 */
    @MockBean
    private InternalSignatureFeignInterceptor internalSignatureFeignInterceptor;

    @Test
    @DisplayName("未登录不得触发同步（写操作，必须过 ADMIN 门槛）")
    void syncRequiresLogin() throws Exception {
        mockMvc.perform(post("/ai/admin/corpus/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("未登录不得读投影表行数")
    void countRequiresLogin() throws Exception {
        mockMvc.perform(get("/ai/admin/corpus/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401));
    }
}
