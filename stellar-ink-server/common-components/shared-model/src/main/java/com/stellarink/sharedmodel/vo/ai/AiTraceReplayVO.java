package com.stellarink.sharedmodel.vo.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 按 traceId 回放的**合并视图**（E3-4）：一个 traceId 看完整条链路。
 *
 * <p>两份数据来源互补，缺一不可：
 * <ul>
 *   <li>{@code calls} 来自 Java 侧的调用账（{@code ai_call_log}）：**谁**在什么时候调的、
 *       用了多少 token、成功还是失败、失败分类是什么 —— 身份只有 Java 有；</li>
 *   <li>{@code events} 来自 Python 侧的进程内缓冲：**链路内部**发生了什么
 *       （检索命中几段、何时拒答、调了哪个模型、上游返回了什么状态码）。</li>
 * </ul>
 *
 * <p>{@code pythonAvailable=false} 时只显示 Java 侧那份 —— **不要把「Python 挂了」
 * 表现成「这条链路什么都没发生」**：账还在，只是链路细节暂时取不到。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTraceReplayVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String traceId;

    /** Java 侧调用账（按发生顺序） */
    private List<AiTraceCallVO> calls;

    /** Python 侧链路事件；取不到时为空列表 */
    private List<Map<String, Object>> events;

    /** Python 侧是否给出过回答（`found=false` 也算「答了，只是没有这条链路的记录」） */
    private Boolean pythonAvailable;

    /** Python 侧是否**有这条链路**的记录；缓冲淘汰或多副本时会是 false */
    private Boolean pythonFound;

    /** 给人看的提示（例如「Python 侧回放不可用」），前端原样显示 */
    private List<String> notes;
}
