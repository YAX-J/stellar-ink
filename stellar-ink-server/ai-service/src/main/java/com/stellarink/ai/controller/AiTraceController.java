package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.AiTraceDTO;
import com.stellarink.ai.service.AiUsageService;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.ai.AiTraceCallVO;
import com.stellarink.sharedmodel.vo.ai.AiTraceReplayVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 按 traceId 回放链路（E3-4 后半）：{@code GET /ai/admin/trace/{traceId}}。
 *
 * <p>把两份互补的数据合到一处：
 * <ul>
 *   <li>Java 侧调用账（{@code ai_call_log}）：**谁**调的、多少 token、成功失败、失败分类；</li>
 *   <li>Python 侧链路事件（{@code /internal/trace/{id}}）：检索命中几段、调了哪个模型、上游状态码。</li>
 * </ul>
 *
 * <p><b>Python 取不到时仍然返回 Java 那一半</b>（并在 {@code notes} 里说明原因）：
 * 一次下游故障不该把「本来就有的账」也藏起来 —— 那会让人以为「这次调用根本没发生」。
 * 这正是本项目反复踩过的那类坑：一个失败把另一个成功的事实盖掉。
 *
 * <p>为什么是 ADMIN：账里有「谁在什么时候用了多少」——运营与个人信息；
 * 而且排障是运维动作。网关按 {@code /ai/admin/} 前缀先拦一道，这里再复核一次。
 */
@Slf4j
@RestController
@RequestMapping("/ai/admin/trace")
@RequiredArgsConstructor
@Tag(name = "AI 链路回放", description = "ADMIN：按 traceId 看调用账 + 编排侧的检索/工具/模型事件")
public class AiTraceController {

    /**
     * traceId 的形状白名单：32 位十六进制（Java 侧 UUID 去横线、Python 侧同）。
     *
     * <p>刻意**收紧**而不是「非空即可」：它会进 Redis 键、SQL 的 where 与 Feign 的 URL 路径 ——
     * 放任任意字符串等于把路径拼接与查询注入的口子留在最外层。
     * 但要允许短一点的调试值（≤64 的字母数字），避免把手工造的 traceId 一刀切掉。
     */
    private static final Pattern TRACE_ID = Pattern.compile("[A-Za-z0-9]{8,64}");

    private final AiUsageService usageService;

    private final PythonAiClient pythonAiClient;

    @GetMapping("/{traceId}")
    @Operation(
            summary = "按 traceId 回放链路",
            description = "返回 Java 侧调用账 + Python 侧检索/工具/模型事件；Python 不可用时只回前半并给出说明")
    public Response<AiTraceReplayVO> replay(@PathVariable("traceId") String traceId) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        if (traceId == null || !TRACE_ID.matcher(traceId).matches()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "traceId 形状不对（应为 8-64 位字母数字）。");
        }

        List<AiTraceCallVO> calls = usageService.traceCalls(traceId);
        List<Map<String, Object>> events = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean pythonAvailable = false;
        boolean pythonFound = false;

        try {
            AiTraceDTO python = pythonAiClient.traceReplay(traceId);
            pythonAvailable = true;
            pythonFound = python != null && Boolean.TRUE.equals(python.getFound());
            if (pythonFound && python.getEvents() != null) {
                events.addAll(python.getEvents());
            } else {
                // 「为什么没有」有两种可能，说不清就都摆出来，让排障的人自己判断
                notes.add("Python 侧没有这条 traceId 的记录：它的链路缓冲是有界的，"
                        + "也可能是这条调用落在别的实例上。");
            }
        } catch (RuntimeException error) {
            // Python 不可用**不吞**成空：账还在，把原因写清楚
            log.warn("链路回放：Python 侧取不到事件，只回 Java 侧调用账：traceId={} error={}",
                    traceId, error.toString());
            notes.add("Python 侧回放不可用（" + error.getClass().getSimpleName()
                    + "）：下面是 Java 侧的调用账，链路细节暂时取不到。");
        }

        AiTraceReplayVO replay = AiTraceReplayVO.builder()
                .traceId(traceId)
                .calls(calls)
                .events(events)
                .pythonAvailable(pythonAvailable)
                .pythonFound(pythonFound)
                .notes(notes)
                .build();
        log.info("链路回放：traceId={} calls={} events={} pythonAvailable={}",
                traceId, calls.size(), events.size(), pythonAvailable);
        return Response.success(replay);
    }
}
