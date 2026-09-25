package com.stellarink.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellarink.ai.config.MasterKeyProvider;
import com.stellarink.ai.mapper.AiProviderConfigMapper;
import com.stellarink.ai.pojo.AiProviderConfig;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.ProviderConnectivityChecker;
import com.stellarink.common.crypto.AesGcmCipher;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.sharedmodel.dto.ai.AiProviderSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.vo.ai.AiProviderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型配置服务实现。
 *
 * <p>密钥处理全部集中在这里，别的类不许碰 {@code apiKeyCipher}：
 * 保存时加密、列表时掩码、运行时解密。这样「明文只出现在内存里」是可以被 review 的。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProviderConfigServiceImpl implements AiProviderConfigService {

    private final AiProviderConfigMapper mapper;
    private final MasterKeyProvider masterKeyProvider;
    private final ProviderConnectivityChecker connectivityChecker;

    @Override
    public List<AiProviderVO> list() {
        return mapper.selectList(Wrappers.<AiProviderConfig>lambdaQuery()
                        .orderByAsc(AiProviderConfig::getId))
                .stream()
                .map(this::toVo)
                .toList();
    }

    @Override
    @Transactional
    public AiProviderVO save(AiProviderSaveDTO dto, Long actorUserId) {
        AiProviderConfig existing = mapper.selectOne(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getRole, dto.getRole().getKey())
                .last("limit 1"));

        String apiKey = dto.getApiKey() == null ? "" : dto.getApiKey().trim();
        boolean hasNewKey = !apiKey.isEmpty();
        if (!hasNewKey && (existing == null || existing.getApiKeyCipher() == null)) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR,
                    "首次配置该角色必须填写 API Key");
        }

        AiProviderConfig target = existing == null ? new AiProviderConfig() : existing;
        target.setRole(dto.getRole().getKey());
        // 面板手填 = 不再绑定模型库里某一条（下拉框那边绑定时会写回 modelId）
        target.setModelId(null);
        target.setProvider(dto.getProvider());
        target.setDisplayName(dto.getDisplayName());
        target.setBaseUrl(stripTrailingSlash(dto.getBaseUrl()));
        target.setModel(dto.getModel());
        target.setDimension(dto.getDimension());
        target.setTimeoutMs(dto.getTimeoutMs());
        target.setMaxTokens(dto.getMaxTokens());
        target.setTemperature(dto.getTemperature());
        target.setEnabled(Boolean.FALSE.equals(dto.getEnabled()) ? 0 : 1);
        target.setUpdatedBy(actorUserId);
        // 换了地址或模型，上一次的自检结论就失效了，避免面板显示过期的「可用」
        target.setLastCheckStatus("unknown");

        if (hasNewKey) {
            byte[] key = masterKeyProvider.get();
            target.setApiKeyCipher(
                    AesGcmCipher.encrypt(apiKey, key).getBytes(StandardCharsets.UTF_8));
            target.setApiKeyMask(AesGcmCipher.mask(apiKey));
        }

        if (existing == null) {
            mapper.insert(target);
        } else {
            // 走 updateWithModelId：手填配置要把 model_id 置空（解除与模型库条目的绑定），
            // 而 updateById 会忽略 null —— 表现是「手填保存了、下拉框还显示绑着库里的模型」
            mapper.updateWithModelId(target);
        }
        log.info("AI 模型配置已保存：role={}, model={}, keyChanged={}, actor={}",
                dto.getRole().getKey(), dto.getModel(), hasNewKey, actorUserId);
        return toVo(target);
    }

    @Override
    public boolean delete(AiModelRole role) {
        int removed = mapper.delete(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getRole, role.getKey()));
        if (removed > 0) {
            log.info("AI 模型配置已删除：role={}", role.getKey());
        }
        return removed > 0;
    }

    @Override
    public void recordCheckResult(AiModelRole role, ProviderConnectivityChecker.CheckResult result) {
        AiProviderConfig row = mapper.selectOne(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getRole, role.getKey())
                .last("limit 1"));
        if (row == null) {
            return;
        }
        row.setLastCheckStatus(result.ok() ? "ok" : "failed");
        row.setLastCheckMessage(result.message());
        row.setLastCheckedAt(LocalDateTime.now());
        mapper.updateById(row);
    }

    @Override
    public ProviderConnectivityChecker.CheckResult checkConnectivity(AiModelRole role) {
        AiProviderConfig row = mapper.selectOne(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getRole, role.getKey())
                .last("limit 1"));
        if (row == null) {
            throw BusinessExceptionHelper.of(ErrorCode.NOT_FOUND,
                    "该角色尚未配置模型，请先保存配置再自检");
        }
        ProviderConnectivityChecker.CheckResult result = connectivityChecker.check(row.getBaseUrl());
        recordCheckResult(role, result);
        log.info("模型端点自检：role={}, ok={}, cost={}ms", role.getKey(), result.ok(), result.latencyMs());
        return result;
    }

    @Override
    public Map<String, RuntimeProvider> runtimeConfigs() {
        List<AiProviderConfig> rows = mapper.selectList(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getEnabled, 1)
                .orderByAsc(AiProviderConfig::getId));

        Map<String, RuntimeProvider> result = new LinkedHashMap<>();
        byte[] masterKey = null;
        for (AiProviderConfig row : rows) {
            if (row.getApiKeyCipher() == null || row.getApiKeyCipher().length == 0) {
                // 未配置密钥的角色直接跳过：让调用方看到「没这个能力」，而不是拿到空 Key 去试
                continue;
            }
            if (masterKey == null) {
                masterKey = masterKeyProvider.get();
            }
            String cipher = new String(row.getApiKeyCipher(), StandardCharsets.UTF_8);
            result.put(row.getRole(), new RuntimeProvider(
                    row.getProvider(),
                    row.getBaseUrl(),
                    row.getModel(),
                    AesGcmCipher.decrypt(cipher, masterKey),
                    row.getDimension(),
                    row.getTimeoutMs(),
                    row.getMaxTokens(),
                    row.getTemperature()));
        }
        return result;
    }

    private AiProviderVO toVo(AiProviderConfig row) {
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
                .enabled(row.getEnabled() != null && row.getEnabled() == 1)
                .lastCheckStatus(row.getLastCheckStatus() == null ? "unknown" : row.getLastCheckStatus())
                .lastCheckMessage(row.getLastCheckMessage())
                .lastCheckedAt(row.getLastCheckedAt())
                .updatedAt(row.getUpdatedAt())
                .build();
    }

    /** 去掉结尾斜杠：拼接 {@code /chat/completions} 时不会出现双斜杠。 */
    private static String stripTrailingSlash(String url) {
        String trimmed = url == null ? "" : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
