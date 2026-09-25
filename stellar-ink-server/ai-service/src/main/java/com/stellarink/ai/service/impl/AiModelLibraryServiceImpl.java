package com.stellarink.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellarink.ai.config.MasterKeyProvider;
import com.stellarink.ai.mapper.AiModelMapper;
import com.stellarink.ai.mapper.AiProviderConfigMapper;
import com.stellarink.ai.pojo.AiModel;
import com.stellarink.ai.pojo.AiProviderConfig;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.ProviderConnectivityChecker;
import com.stellarink.common.crypto.AesGcmCipher;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.sharedmodel.dto.ai.AiModelSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelCapability;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.vo.ai.AiModelVO;
import com.stellarink.sharedmodel.vo.ai.AiProviderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型库实现。这里也是「模型改了要同步到角色」的唯一执行点 —— 见 {@link #propagate}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelLibraryServiceImpl implements AiModelLibraryService {

    /** Python 侧的 {@code registry._build} 只认这两个协议实现 */
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("openai_compatible", "fake");

    private final AiModelMapper modelMapper;
    private final AiProviderConfigMapper providerMapper;
    private final MasterKeyProvider masterKeyProvider;
    private final ProviderConnectivityChecker connectivityChecker;

    @Override
    public List<AiModelVO> list() {
        List<AiModel> rows = modelMapper.selectList(
                Wrappers.<AiModel>lambdaQuery().orderByAsc(AiModel::getId));
        Map<Long, List<String>> boundRoles = boundRolesByModelId();
        return rows.stream().map(row -> toVo(row, boundRoles)).toList();
    }

    @Override
    @Transactional
    public AiModelVO save(AiModelSaveDTO dto, Long actorUserId) {
        String provider = normalizeProvider(dto.getProvider());
        String baseUrl = stripTrailingSlash(dto.getBaseUrl());
        String modelName = dto.getModel().trim();

        AiModel existing = dto.getId() == null ? null : modelMapper.selectById(dto.getId());
        if (dto.getId() != null && existing == null) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "要修改的模型不存在：id=" + dto.getId());
        }

        String apiKey = dto.getApiKey() == null ? "" : dto.getApiKey().trim();
        boolean hasNewKey = !apiKey.isEmpty();
        if (!hasNewKey && (existing == null || existing.getApiKeyCipher() == null)) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR, "新增模型必须填写 API Key");
        }
        if (dto.getCapabilities() == null || dto.getCapabilities().isEmpty()) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR, "至少要勾选一种能力（对话/嵌入/重排）");
        }

        AiModel target = existing == null ? new AiModel() : existing;
        target.setDisplayName(dto.getDisplayName().trim());
        target.setProvider(provider);
        target.setBaseUrl(baseUrl);
        target.setModel(modelName);
        applyCapabilities(target, dto.getCapabilities());
        target.setDimension(dto.getDimension());
        target.setTimeoutMs(dto.getTimeoutMs() == null ? 30_000 : dto.getTimeoutMs());
        target.setMaxTokens(dto.getMaxTokens());
        target.setTemperature(dto.getTemperature());
        target.setEnabled(Boolean.FALSE.equals(dto.getEnabled()) ? 0 : 1);
        target.setUpdatedBy(actorUserId);
        // 端点或模型变了，上一次的自检结论就过期了：面板不该显示一个已经不对的「可用」
        target.setLastCheckStatus("unknown");

        if (hasNewKey) {
            byte[] masterKey = masterKeyProvider.get();
            target.setApiKeyCipher(
                    AesGcmCipher.encrypt(apiKey, masterKey).getBytes(StandardCharsets.UTF_8));
            target.setApiKeyMask(AesGcmCipher.mask(apiKey));
        }

        try {
            if (existing == null) {
                modelMapper.insert(target);
            } else {
                modelMapper.updateById(target);
            }
        } catch (DuplicateKeyException duplicated) {
            // 撞唯一键时给一句能照做的话，而不是让 500 把原始 SQL 报错抛给用户
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR,
                    "这个端点下已经有同名模型（" + baseUrl + " / " + modelName + "），"
                            + "直接改那一条即可，不必新增");
        }

        int synced = existing == null ? 0 : propagate(target, hasNewKey, actorUserId);
        log.info("模型库已保存：id={}, model={}, capabilities={}, keyChanged={}, syncedRoles={}, actor={}",
                target.getId(), modelName, capabilitiesOf(target), hasNewKey, synced, actorUserId);
        return toVo(target, boundRolesByModelId());
    }

    @Override
    @Transactional
    public void delete(Long id, boolean force, Long actorUserId) {
        requireModel(id);
        List<AiProviderConfig> bound = providerRowsOf(id);
        if (!bound.isEmpty() && !force) {
            String roles = bound.stream().map(AiProviderConfig::getRole).collect(Collectors.joining("、"));
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR,
                    "该模型正被 " + roles + " 角色使用，先给这些角色换一个模型再删"
                            + "（确实要删就带上 force=true，它只会解绑，不会动角色当前生效的配置）");
        }
        for (AiProviderConfig row : bound) {
            // 置空必须走 updateWithModelId：updateById 会忽略 null，表现是「删了模型、绑定还在」
            row.setModelId(null);
            row.setUpdatedBy(actorUserId);
            providerMapper.updateWithModelId(row);
        }
        modelMapper.deleteById(id);
        log.info("模型库已删除：id={}, force={}, unboundRoles={}, actor={}", id, force, bound.size(), actorUserId);
    }

    @Override
    public ProviderConnectivityChecker.CheckResult check(Long id) {
        AiModel row = requireModel(id);
        ProviderConnectivityChecker.CheckResult result = connectivityChecker.check(row.getBaseUrl());
        row.setLastCheckStatus(result.ok() ? "ok" : "failed");
        row.setLastCheckMessage(result.message());
        row.setLastCheckedAt(java.time.LocalDateTime.now());
        modelMapper.updateById(row);
        log.info("模型库自检：id={}, ok={}, cost={}ms", id, result.ok(), result.latencyMs());
        return result;
    }

    @Override
    @Transactional
    public AiProviderVO bind(AiModelRole role, Long modelId, Long actorUserId) {
        AiModel model = requireModel(modelId);
        if (model.getEnabled() == null || model.getEnabled() != 1) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR,
                    "模型「" + model.getDisplayName() + "」已停用，不能绑定到角色");
        }
        List<AiModelCapability> capabilities = capabilitiesOf(model);
        if (!capabilities.contains(role.capability())) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR,
                    "模型「" + model.getDisplayName() + "」不具备 " + role.capability().getKey()
                            + " 能力（它标的是 " + describe(capabilities) + "），不能用于「" + role.getLabel() + "」角色");
        }

        AiProviderConfig target = providerMapper.selectOne(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getRole, role.getKey())
                .last("limit 1"));
        if (target == null) {
            target = new AiProviderConfig();
        }
        copyFromModel(target, model, role, actorUserId);

        if (target.getId() == null) {
            providerMapper.insert(target);
        } else {
            providerMapper.updateWithModelId(target);
        }
        log.info("角色 {} 已绑定模型库条目：modelId={}, model={}, actor={}",
                role.getKey(), model.getId(), model.getModel(), actorUserId);
        return toProviderVo(target);
    }

    @Override
    public AiModel requireModel(Long id) {
        AiModel row = id == null ? null : modelMapper.selectById(id);
        if (row == null) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND, "模型库里没有这条模型：id=" + id);
        }
        return row;
    }

    /**
     * 把库里的这条模型同步到**所有绑定它的角色**上。
     *
     * <p>为什么必须做：绑定是「复制字段」，如果只改库不同步，就会出现
     * 「在库里换了 Key / 换了模型名，问答还在用旧的」—— 面板显示新值、运行用的是旧值，
     * 而且没有任何报错。库是唯一事实来源，角色行只是它的投影，所以这里整体覆盖
     * （包括角色的 timeout/maxTokens：它们在绑定时也是从库里复制的）。
     */
    private int propagate(AiModel model, boolean keyChanged, Long actorUserId) {
        List<AiProviderConfig> bound = providerRowsOf(model.getId());
        for (AiProviderConfig row : bound) {
            AiModelRole role = AiModelRole.parse(row.getRole());
            if (role == null) {
                // 角色键不认识（人工改过库）：不动它，但留一条日志，别静默跳过
                log.warn("跳过同步：角色键无法识别 role={}, modelId={}", row.getRole(), model.getId());
                continue;
            }
            copyFromModel(row, model, role, actorUserId);
            providerMapper.updateWithModelId(row);
        }
        if (!bound.isEmpty()) {
            log.info("模型库改动已同步到角色：modelId={}, roles={}, keyChanged={}",
                    model.getId(),
                    bound.stream().map(AiProviderConfig::getRole).toList(),
                    keyChanged);
        }
        return bound.size();
    }

    /** 绑定/同步的字段复制：**库 → 角色行**，只在这一处发生。 */
    private static void copyFromModel(
            AiProviderConfig target, AiModel model, AiModelRole role, Long actorUserId) {
        target.setRole(role.getKey());
        target.setModelId(model.getId());
        target.setProvider(model.getProvider());
        target.setDisplayName(model.getDisplayName());
        target.setBaseUrl(model.getBaseUrl());
        target.setModel(model.getModel());
        target.setDimension(model.getDimension());
        target.setTimeoutMs(model.getTimeoutMs());
        target.setMaxTokens(model.getMaxTokens());
        target.setTemperature(model.getTemperature());
        target.setEnabled(model.getEnabled());
        target.setUpdatedBy(actorUserId);
        // 换了模型/端点，上一次的自检结论跟着失效
        target.setLastCheckStatus("unknown");
        if (model.getApiKeyCipher() != null && model.getApiKeyCipher().length > 0) {
            target.setApiKeyCipher(model.getApiKeyCipher());
            target.setApiKeyMask(model.getApiKeyMask());
        }
    }

    private Map<Long, List<String>> boundRolesByModelId() {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        for (AiProviderConfig row : providerMapper.selectList(
                Wrappers.<AiProviderConfig>lambdaQuery().isNotNull(AiProviderConfig::getModelId))) {
            result.computeIfAbsent(row.getModelId(), key -> new ArrayList<>()).add(row.getRole());
        }
        result.values().forEach(roles -> roles.sort(Comparator.naturalOrder()));
        return result;
    }

    private List<AiProviderConfig> providerRowsOf(Long modelId) {
        return providerMapper.selectList(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getModelId, modelId)
                .orderByAsc(AiProviderConfig::getId));
    }

    private static String normalizeProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR,
                    "不支持的协议：" + provider + "（当前支持 " + String.join(" / ", SUPPORTED_PROVIDERS) + "）");
        }
        return normalized;
    }

    private static List<AiModelCapability> capabilitiesOf(AiModel row) {
        List<AiModelCapability> result = new ArrayList<>(3);
        if (isOn(row.getCapChat())) {
            result.add(AiModelCapability.CHAT);
        }
        if (isOn(row.getCapEmbedding())) {
            result.add(AiModelCapability.EMBEDDING);
        }
        if (isOn(row.getCapRerank())) {
            result.add(AiModelCapability.RERANK);
        }
        return result;
    }

    private static void applyCapabilities(AiModel target, Set<AiModelCapability> capabilities) {
        target.setCapChat(capabilities.contains(AiModelCapability.CHAT) ? 1 : 0);
        target.setCapEmbedding(capabilities.contains(AiModelCapability.EMBEDDING) ? 1 : 0);
        target.setCapRerank(capabilities.contains(AiModelCapability.RERANK) ? 1 : 0);
    }

    private static boolean isOn(Integer flag) {
        return flag != null && flag == 1;
    }

    private static String describe(List<AiModelCapability> capabilities) {
        return capabilities.isEmpty()
                ? "无能力"
                : capabilities.stream().map(AiModelCapability::getKey).collect(Collectors.joining("/"));
    }

    private AiModelVO toVo(AiModel row, Map<Long, List<String>> boundRoles) {
        boolean configured = row.getApiKeyCipher() != null && row.getApiKeyCipher().length > 0;
        return AiModelVO.builder()
                .id(row.getId())
                .displayName(row.getDisplayName())
                .provider(row.getProvider())
                .baseUrl(row.getBaseUrl())
                .model(row.getModel())
                .apiKeyConfigured(configured)
                .apiKeyMask(configured ? row.getApiKeyMask() : null)
                .capabilities(capabilitiesOf(row))
                .dimension(row.getDimension())
                .timeoutMs(row.getTimeoutMs())
                .maxTokens(row.getMaxTokens())
                .temperature(row.getTemperature())
                .enabled(isOn(row.getEnabled()))
                .boundRoles(boundRoles.getOrDefault(row.getId(), List.of()))
                .lastCheckStatus(row.getLastCheckStatus() == null ? "unknown" : row.getLastCheckStatus())
                .lastCheckMessage(row.getLastCheckMessage())
                .lastCheckedAt(row.getLastCheckedAt())
                .updatedAt(row.getUpdatedAt())
                .build();
    }

    private static AiProviderVO toProviderVo(AiProviderConfig row) {
        boolean configured = row.getApiKeyCipher() != null && row.getApiKeyCipher().length > 0;
        return AiProviderVO.builder()
                .id(row.getId())
                .role(AiModelRole.parse(row.getRole()))
                .modelId(row.getModelId())
                .displayName(row.getDisplayName())
                .provider(row.getProvider())
                .baseUrl(row.getBaseUrl())
                .model(row.getModel())
                .apiKeyConfigured(configured)
                .apiKeyMask(configured ? row.getApiKeyMask() : null)
                .dimension(row.getDimension())
                .timeoutMs(row.getTimeoutMs())
                .maxTokens(row.getMaxTokens())
                .temperature(row.getTemperature())
                .enabled(isOn(row.getEnabled()))
                .lastCheckStatus(row.getLastCheckStatus() == null ? "unknown" : row.getLastCheckStatus())
                .lastCheckMessage(row.getLastCheckMessage())
                .lastCheckedAt(row.getLastCheckedAt())
                .updatedAt(row.getUpdatedAt())
                .build();
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
