package com.stellarink.ai.service;

import com.stellarink.ai.pojo.AiModel;
import com.stellarink.sharedmodel.dto.ai.AiModelSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.vo.ai.AiModelVO;
import com.stellarink.sharedmodel.vo.ai.AiProviderVO;

import java.util.List;

/**
 * 模型库：面板里「加进来的那些模型」，以及把某一条**应用到角色**。
 *
 * <p>为什么要有这一层（而不是继续在角色表单里手填）：{@code ai_provider_config} 按角色唯一，
 * 所以「再加一个 chat 模型」会覆盖原来那行 —— 两个模型之间没法切换，Key 也得重填一遍。
 * 模型库把「模型」与「角色用哪个」拆开，面板上就变成下拉框选一条。
 *
 * <p>三条一致性约定：
 * <ol>
 *   <li><b>绑定是复制，但由后端保持同步</b>：绑定某条模型时把字段复制进角色行（Python 只读角色行，
 *       因此它完全不需要改）；之后**改库里那条模型时，会自动同步到所有绑定了它的角色** ——
 *       否则会出现「在库里换了 Key，问答还在用旧 Key」这种最难查的静默不一致。</li>
 *   <li><b>能力必须匹配</b>：纯 chat 模型绑到 embedding 角色直接报错，并说清它支持什么。</li>
 *   <li><b>删除要拦</b>：正被角色使用的模型不允许直接删（要删得先明确要求强制），
 *       避免「删完了下次问答才发现取不到模型」。</li>
 * </ol>
 */
public interface AiModelLibraryService {

    /** 模型库列表（密钥只回掩码，并带上「正被哪些角色使用」）。 */
    List<AiModelVO> list();

    /** 新增或修改一条模型；修改时会把变更同步到已绑定它的角色。 */
    AiModelVO save(AiModelSaveDTO dto, Long actorUserId);

    /**
     * 删除一条模型。
     *
     * @param force 为 true 时，即使正被角色使用也删（只解绑，不动角色当前生效的配置，
     *              免得正在跑的能力突然取不到模型）
     */
    void delete(Long id, boolean force, Long actorUserId);

    /** 对库里的某条模型做端点连通性自检，并把结论记回该条目。 */
    ProviderConnectivityChecker.CheckResult check(Long id);

    /** 把库里的某条模型应用到某个角色（校验能力匹配，然后复制成该角色当前生效的配置）。 */
    AiProviderVO bind(AiModelRole role, Long modelId, Long actorUserId);

    /** 取一条库内模型（内部用：绑定时读原文）。 */
    AiModel requireModel(Long id);
}
