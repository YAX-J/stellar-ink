package com.stellarink.ai.controller;

import com.stellarink.ai.service.AiMemoryService;
import com.stellarink.ai.service.AiStyleProfileService;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.dto.ai.AiMemoryConfirmRequest;
import com.stellarink.sharedmodel.dto.ai.AiMemoryExtractRequest;
import com.stellarink.sharedmodel.vo.ai.AiMemoryConfirmVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryExtractVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryVO;
import com.stellarink.sharedmodel.vo.ai.AiStyleProfileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 作者记忆的管理入口（M9-2）。
 *
 * <p><b>身份只从登录态来</b>：没有「查某个用户的记忆」这种入参 ——
 * 路径里也不出现 userId。于是「用户 A 看不到用户 B 的记忆」不是一条过滤条件，
 * 而是**接口根本表达不出来**。
 *
 * <p><b>门槛是「登录」而不是某个角色</b>：记忆是用户自己的东西，
 * 读者也可以在问答里积累它，没必要只有作者能用。
 * ⚠️ 因此网关必须把 {@code /ai/memory/**} 在「GET 全放行」**之前**单独拦下（见网关配置）——
 * 否则列表接口会匿名可读，而这是私密数据。
 */
@Tag(name = "作者记忆", description = "M9：查看/启用/禁用/删除/全部清除自己的长期记忆")
@RestController
@RequestMapping("/ai/memory")
@RequiredArgsConstructor
public class AiMemoryController {

    private final AiMemoryService memoryService;

    /** 派生风格画像（M9-3b）：它是从记忆与文章推出来的，删除记忆时要一起清 */
    private final AiStyleProfileService styleProfileService;

    @GetMapping("/list")
    @Operation(summary = "列出我的记忆（可按状态与类型过滤）")
    public Response<List<AiMemoryVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type) {
        return Response.success(memoryService.listMine(AuthHelper.loginId(), status, type));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "启用或禁用一条记忆（删除请用 DELETE）")
    public Response<AiMemoryVO> setStatus(
            @PathVariable Long id, @RequestParam String status) {
        return Response.success(memoryService.setStatus(AuthHelper.loginId(), id, status));
    }

    @PostMapping("/extract")
    @Operation(summary = "从一段对话里抽记忆候选（落成待确认，不参与召回）")
    public Response<AiMemoryExtractVO> extract(@RequestBody AiMemoryExtractRequest request) {
        return Response.success(memoryService.extract(
                AuthHelper.loginId(), request.getConversation(), request.getMaxCandidates()));
    }

    @PostMapping("/confirm")
    @Operation(summary = "确认待确认的记忆（冲突保持待确认并原样报回）")
    public Response<AiMemoryConfirmVO> confirm(
            @RequestBody(required = false) AiMemoryConfirmRequest request) {
        List<Long> ids = request == null || request.getMemoryIds() == null
                ? List.of() : request.getMemoryIds();
        return Response.success(memoryService.confirm(AuthHelper.loginId(), ids));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除一条记忆（同时清证据与派生画像）")
    public Response<Long> delete(@PathVariable Long id) {
        return Response.success(memoryService.delete(AuthHelper.loginId(), id));
    }

    @PostMapping("/clear")
    @Operation(summary = "全部清除我的记忆（含证据与派生画像）")
    public Response<Map<String, Integer>> clear() {
        int removed = memoryService.clearAll(AuthHelper.loginId());
        return Response.success(Map.of("removed", removed));
    }

    @GetMapping("/style-profile")
    @Operation(
            summary = "取最新一版的派生风格画像",
            description = "没有时 data 为 null —— 「还没生成过」与「生成出来是空的」不是一回事")
    public Response<AiStyleProfileVO> styleProfile() {
        return Response.success(styleProfileService.latest(AuthHelper.loginId()));
    }

    @PostMapping("/style-profile/refresh")
    @Operation(
            summary = "按登录者的文章刷新风格画像（存成新版本）",
            description = "样本不足时返回参数错误并说明「再多写几篇」，而不是存一版空画像")
    public Response<AiStyleProfileVO> refreshStyleProfile() {
        Long userId = AuthHelper.loginId();
        // 统计的是**登录者自己**的文章：让他刷新别人的风格画像没有意义
        return Response.success(styleProfileService.refresh(userId, userId));
    }
}
