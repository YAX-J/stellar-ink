package com.stellarink.ai.service;

import com.stellarink.sharedmodel.vo.ai.AiMemoryConfirmVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryExtractVO;
import com.stellarink.sharedmodel.vo.ai.AiMemoryVO;

import java.util.List;

/**
 * 作者记忆的落库与状态流转（M9-2）。
 *
 * <p><b>为什么「候选 / 规则」在 Python、而这里只管库</b>：抽取与「该不该记」的判断属于 AI 能力
 * （换模型、改提示词、调阈值都要动那里），而记忆的持久化、状态流转、用户隔离属于数据面。
 * 这条边界与 E4 的 Wiki 完全一致。
 *
 * <p><b>所有方法都带 {@code userId} 且不接收它作为请求参数</b>：身份由 Java 从 Sa-Token 取，
 * 前端传不了别人的 id —— 于是「用户 A 的记忆不会被用户 B 看到」由**取数范围**保证，
 * 而不是靠某处的过滤条件（过滤条件漏一处就是数据泄露）。
 */
public interface AiMemoryService {

    /**
     * 从一段对话里抽候选，并存成 <b>pending</b>（等人确认）。
     *
     * <p>为什么抽出来就落库、而不是只回给前端：候选要能被「稍后再看」，
     * 而 pending 状态**不参与召回** —— 存下来不等于记住了。
     */
    AiMemoryExtractVO extract(Long userId, String conversation, Integer maxCandidates);

    /**
     * 确认候选：把 pending 的记忆按「新增 / 重复 / 冲突」处理。
     *
     * <p>三条处置（判定由 Python 的规则给出，Java 只执行）：
     *
     * <ul>
     *   <li><b>新增</b> → 转 active（用户确认过的，可信度按用户确认档提高）；</li>
     *   <li><b>重复</b> → 合并：把新证据补到已有那条上，pending 那条删掉
     *       （**不改已有正文** —— 改正文等于替用户改记忆）；</li>
     *   <li><b>冲突</b> → **保持 pending 并原样报给用户**。措辞相近但结论不同，
     *       可能是同义改写、也可能是作者改了主意，只有人能判断。</li>
     * </ul>
     *
     * @param memoryIds 只确认这几条（null/空 = 全部 pending）
     */
    AiMemoryConfirmVO confirm(Long userId, List<Long> memoryIds);

    /**
     * 列出某个用户自己的记忆。
     *
     * @param status 过滤状态（null/空 = 除已删除外全部）
     * @param type   过滤类型（null/空 = 不限）
     */
    List<AiMemoryVO> listMine(Long userId, String status, String type);

    /**
     * 改状态：{@code active}（启用）/ {@code disabled}（用户主动关掉）。
     *
     * <p>只允许在这两档之间切：{@code deleted} 要走 {@link #delete}，
     * 否则「删除」会变成一次普通的字段更新，清理动作（证据/画像）就不会发生。
     */
    AiMemoryVO setStatus(Long userId, Long memoryId, String status);

    /**
     * 删除一条记忆（软删 + 清证据 + 清派生画像）。
     *
     * @return 被删掉的记忆 id
     */
    Long delete(Long userId, Long memoryId);

    /**
     * 全部清除：该用户所有记忆、证据与派生画像。
     *
     * @return 清掉的记忆条数
     */
    int clearAll(Long userId);
}
