package com.stellarink.ai.corpus.controller;

import com.stellarink.ai.corpus.service.CorpusSyncService;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.corpus.CorpusSyncResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 语料投影的管理端点（ADMIN）。
 *
 * <p>同步本身是**只读上游 + 写自己表**的活儿，不消耗任何模型额度，
 * 所以它既允许定时跑，也允许管理员随时手动触发（排查「刚发的文章怎么还没进知识库」时就用它）。
 */
@RestController
@RequestMapping("/ai/admin/corpus")
@RequiredArgsConstructor
public class AiCorpusController {

    private final CorpusSyncService corpusSyncService;

    /** 立刻同步一次。返回各项计数；失败时 `failed=true` 且**没有做任何删除**。 */
    @PostMapping("/sync")
    public Response<CorpusSyncResultVO> sync() {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(corpusSyncService.sync());
    }

    /** 投影表当前行数（不触发同步）。 */
    @GetMapping("/count")
    public Response<Integer> count() {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(corpusSyncService.snapshotSize());
    }
}
