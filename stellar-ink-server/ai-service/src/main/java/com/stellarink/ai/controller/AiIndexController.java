package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.IndexJobDTO;
import com.stellarink.aiclient.dto.IndexRebuildRequestDTO;
import com.stellarink.aiclient.enums.IndexTaskKind;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.response.Response;
import lombok.RequiredArgsConstructor;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 索引重建入口（ADMIN）：把面板的一次点击转发给 Python 的 `/admin/index/rebuild`。
 *
 * <p>这是 M4 的遗留缺口：契约（本 DTO、{@code AiContractPaths.INDEX_REBUILD}、Feign 方法、
 * roadmap 文档）早就齐了，但两侧都没有实现 —— 于是「重建索引」这件事从来没有真正发生过。
 *
 * <p>本服务在这条链路上**只做三件事**（AGENTS §5：Java 侧不得出现任何 AI 算法）：
 * 校验 ADMIN、原样转发、把结果透出。真正的切块/嵌入/写库全在 Python。
 *
 * <p>⚠️ 两个必须知道的现实：
 * <ul>
 *   <li><b>它是同步的</b>：Python 跑完才返回（我们没有任务队列）。语料是几十篇时只花一次嵌入调用，
 *       但语料涨大后这个请求会变长 —— 那时该做的是任务队列，而不是在这里加超时。</li>
 *   <li><b>返回的 job 查不到历史</b>：契约里还有 {@code GET /admin/jobs/{id}}，本阶段没实现，
 *       所以别拿这个 jobId 去查进度（查不到不是「任务丢了」）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/ai/admin/index")
@RequiredArgsConstructor
public class AiIndexController {

    private final PythonAiClient pythonAiClient;

    /**
     * 重建向量索引。请求体可省略（等价于全量重建）。
     *
     * <p>不省略时可以带 {@code kind=post_rebuild} + {@code postId}（单篇重建）与 {@code reason}
     * （写进 Python 日志，便于回溯「谁在什么时候重建的」）。
     */
    @PostMapping("/rebuild")
    public Response<IndexJobDTO> rebuild(@RequestBody(required = false) IndexRebuildRequestDTO request) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        IndexRebuildRequestDTO payload = request == null ? new IndexRebuildRequestDTO() : request;
        if (payload.getKind() == null) {
            payload.setKind(IndexTaskKind.FULL_REBUILD);
        }
        return Response.success(pythonAiClient.rebuildIndex(payload));
    }

    /**
     * 对账式增量索引：只重嵌变了的、删掉语料里已经没有的。
     *
     * <p>与全量重建的区别：它按「段落哈希」比对，**没变的文章一次都不嵌** ——
     * 所以它是「发布/删除之后自动生效」那条链路的执行体（定时任务也调它）。
     */
    @PostMapping("/reconcile")
    public Response<Map<String, Object>> reconcile() {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(pythonAiClient.reconcileIndex());
    }
}
