package com.stellarink.ai.service;

import com.stellarink.sharedmodel.dto.ai.AiProviderSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.vo.ai.AiProviderVO;

import java.util.List;
import java.util.Map;

/**
 * 模型配置服务：面板 CRUD + 加解密 + 供 Python 读取的运行时配置。
 *
 * <p>安全口径：
 * <ul>
 *   <li>列表/详情**只返回掩码**，没有任何接口能把明文 Key 读回来；</li>
 *   <li>{@link #runtimeConfigs()} 是唯一给出明文的方法，只允许经内网签名路由（M1）调用，
 *       且返回值不得写入日志；</li>
 *   <li>保存时 {@code apiKey} 留空表示沿用已存密钥，避免「只改模型名」时被迫重填。</li>
 * </ul>
 */
public interface AiProviderConfigService {

    /** 面板列表：按角色顺序返回，密钥一律脱敏。 */
    List<AiProviderVO> list();

    /** 新增或更新某个角色的配置（按角色唯一）。 */
    AiProviderVO save(AiProviderSaveDTO dto, Long actorUserId);

    /** 删除某个角色的配置；不存在时返回 false。 */
    boolean delete(AiModelRole role);

    /** 记录一次连通性自检结论（只写状态与可读说明，不写密钥）。 */
    void recordCheckResult(AiModelRole role, ProviderConnectivityChecker.CheckResult result);

    /** 对某个角色的端点做一次连通性自检，并把结论记进配置行。 */
    ProviderConnectivityChecker.CheckResult checkConnectivity(AiModelRole role);

    // --------------------------------------------------------------- 个人配置（M12，读者/作者）

    /**
     * 列出<b>某个用户</b>的个人配置（不含全局那份：面板要能分清「这是我自己配的」）。
     *
     * <p>个人配置只放开 chat / fast / reasoning —— 见 {@link ProviderUrlPolicy#isUserScoped(AiModelRole)}：
     * embedding/rerank 由 Python 侧刻意忽略用户行（向量索引只有一份，换嵌入模型检索是错的）。
     */
    List<AiProviderVO> listMine(Long userId);

    /** 保存某个用户的个人配置：地址按「只允许公网」校验，角色必须是用户级角色。 */
    AiProviderVO saveMine(AiProviderSaveDTO dto, Long userId);

    /** 删除某个用户的个人配置（删完自动回落到全局配置）。 */
    boolean deleteMine(Long userId, AiModelRole role);

    /** 某个用户的个人配置连通性自检。 */
    ProviderConnectivityChecker.CheckResult checkMine(Long userId, AiModelRole role);

    /**
     * 运行时配置：按角色给出**解密后的**调用参数，供 Python 侧取用。
     *
     * <p>只包含启用的角色；未配置 Key 的角色不会出现在结果里 —— 调用方应据此判断
     * 「该能力未配置」，而不是带着空密钥去请求模型。
     */
    Map<String, RuntimeProvider> runtimeConfigs();

    /** 某次调用的最小参数集（含明文密钥，务必只在内存与内网之间流转）。 */
    record RuntimeProvider(
            String provider,
            String baseUrl,
            String model,
            String apiKey,
            Integer dimension,
            Integer timeoutMs,
            Integer maxTokens,
            java.math.BigDecimal temperature) {
    }
}
