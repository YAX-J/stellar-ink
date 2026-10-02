package com.stellarink.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.WritingStyleRequestDTO;
import com.stellarink.aiclient.dto.WritingStyleResultDTO;
import com.stellarink.ai.mapper.AiStyleProfileMapper;
import com.stellarink.ai.pojo.AiStyleProfile;
import com.stellarink.ai.service.AiStyleProfileService;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.vo.ai.AiStyleProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 派生风格画像的落库实现（M9-3b）。
 *
 * <p>三条口径：
 *
 * <ol>
 *   <li><b>样本不足时不落库</b>：Python 会回 {@code evidenceSufficient=false}（读得太少，
 *       统计量没有意义）。这时候**存一份「样本不足」的画像**比不存更糟 ——
 *       它看起来像一版正常画像，而下游会拿它当「这位作者的风格」用。
 *       所以如实报错并让用户去多写几篇。</li>
 *   <li><b>版本号递增</b>：画像会随新文章变化，而「什么时候变成这样的」在排查
 *       「AI 建议忽然不像我了」时是关键信息。</li>
 *   <li><b>删除记忆会连它一起清</b>（见 {@code AiMemoryServiceImpl.delete}）——
 *       这正是它落库的理由：清一个不存在的东西，看起来永远是对的。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiStyleProfileServiceImpl implements AiStyleProfileService {

    private final AiStyleProfileMapper styleProfileMapper;
    private final PythonAiClient pythonAiClient;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public AiStyleProfileVO refresh(Long userId, Long authorId) {
        WritingStyleResultDTO result = pythonAiClient.writingStyle(
                WritingStyleRequestDTO.builder().authorId(authorId).build());
        // ⚠️ 顺序要紧：样本不足时 Python 回的正是「profile 为空 + evidenceSufficient=false」，
        // 先判 profile 为空会把「多写几篇就好了」说成「生成失败，请稍后重试」——
        // 后者会让用户反复重试一个不会变好的请求（测试就是这么抓出来的）
        if (result != null && Boolean.FALSE.equals(result.getEvidenceSufficient())) {
            // 样本不足：**不落库**。存一份「样本不足」的画像看起来像一版正常画像，
            // 而下游会拿它当「这位作者的风格」用
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR,
                    "文章样本还不足以生成风格画像，再多写几篇试试");
        }
        if (result == null || result.getProfile() == null) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR, "风格画像生成失败，请稍后重试");
        }
        int version = nextVersion(userId);
        AiStyleProfile row = new AiStyleProfile();
        row.setUserId(userId);
        row.setVersion(version);
        row.setPayload(toJson(result));
        styleProfileMapper.insert(row);
        log.info("风格画像已落库：userId={} authorId={} version={}", userId, authorId, version);
        return toView(row);
    }

    @Override
    public AiStyleProfileVO latest(Long userId) {
        AiStyleProfile row = styleProfileMapper.selectOne(new LambdaQueryWrapper<AiStyleProfile>()
                .eq(AiStyleProfile::getUserId, userId)
                .orderByDesc(AiStyleProfile::getVersion)
                .last("LIMIT 1"));
        return row == null ? null : toView(row);
    }

    /** 下一版版本号 = 现有最大版本 + 1（没有则从 1 开始）。 */
    private int nextVersion(Long userId) {
        List<AiStyleProfile> rows = styleProfileMapper.selectList(
                new LambdaQueryWrapper<AiStyleProfile>().eq(AiStyleProfile::getUserId, userId));
        return rows.stream().mapToInt(AiStyleProfile::getVersion).max().orElse(0) + 1;
    }

    private String toJson(WritingStyleResultDTO result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException error) {
            // 序列化失败不该静默存一个空串：「画像存在但内容为空」很难查
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR,
                    "风格画像序列化失败：" + error.getOriginalMessage());
        }
    }

    private AiStyleProfileVO toView(AiStyleProfile row) {
        return AiStyleProfileVO.builder()
                .id(row.getId())
                .userId(row.getUserId())
                .version(row.getVersion())
                .profile(row.getPayload())
                .createdAt(row.getCreatedAt())
                .build();
    }
}
