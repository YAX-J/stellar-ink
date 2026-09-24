package com.stellarink.ai.service.impl;

import com.stellarink.ai.config.MasterKeyProvider;
import com.stellarink.ai.mapper.AiProviderConfigMapper;
import com.stellarink.ai.pojo.AiProviderConfig;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.ProviderConnectivityChecker;
import com.stellarink.common.crypto.AesGcmCipher;
import com.stellarink.common.crypto.MasterKey;
import com.stellarink.sharedmodel.dto.ai.AiProviderSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.ai.AiProviderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型配置服务：加解密、掩码与「明文不外流」的回归。
 *
 * <p>这几条断言是面板安全性的底线：任何一条红了，都意味着 API Key 可能被读回或落库成明文。
 */
class AiProviderConfigServiceImplTest {

    private static final byte[] MASTER_KEY =
            MasterKey.load(Base64.getEncoder().encodeToString("stellar-ink-test-master-key-0000".getBytes(StandardCharsets.UTF_8)));

    private static final String PLAINTEXT_KEY = "sk-live-abcdefghijklmnop-9f3a";

    private AiProviderConfigMapper mapper;

    private AiProviderConfigService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiProviderConfigMapper.class);
        MasterKeyProvider provider = new MasterKeyProvider() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public byte[] get() {
                return MASTER_KEY;
            }
        };
        service = new AiProviderConfigServiceImpl(mapper, provider, new ProviderConnectivityChecker());
    }

    private static AiProviderSaveDTO dto(AiModelRole role, String apiKey) {
        AiProviderSaveDTO dto = new AiProviderSaveDTO();
        dto.setRole(role);
        dto.setDisplayName("DeepSeek Chat");
        dto.setProvider("openai_compatible");
        dto.setBaseUrl("https://api.deepseek.com/v1/");
        dto.setModel("deepseek-chat");
        dto.setApiKey(apiKey);
        dto.setTimeoutMs(30000);
        return dto;
    }

    private static AiProviderConfig stored(AiModelRole role, String plaintext) {
        AiProviderConfig row = new AiProviderConfig();
        row.setId(1L);
        row.setRole(role.getKey());
        row.setProvider("openai_compatible");
        row.setDisplayName("DeepSeek Chat");
        row.setBaseUrl("https://api.deepseek.com/v1");
        row.setModel("deepseek-chat");
        row.setApiKeyCipher(AesGcmCipher.encrypt(plaintext, MASTER_KEY).getBytes(StandardCharsets.UTF_8));
        row.setApiKeyMask(AesGcmCipher.mask(plaintext));
        row.setTimeoutMs(30000);
        row.setEnabled(1);
        return row;
    }

    @Test
    @DisplayName("首次保存：落库的是密文，返回的是掩码，明文一个字段都不出现")
    void saveEncryptsKeyAndNeverReturnsPlaintext() {
        when(mapper.selectOne(any())).thenReturn(null);

        AiProviderVO vo = service.save(dto(AiModelRole.CHAT, PLAINTEXT_KEY), 7L);

        ArgumentCaptor<AiProviderConfig> captor = ArgumentCaptor.forClass(AiProviderConfig.class);
        verify(mapper).insert(captor.capture());
        AiProviderConfig inserted = captor.getValue();

        // 落库内容必须是密文，且能解回原文（证明存的是「可用的密文」而不是乱码）
        String cipher = new String(inserted.getApiKeyCipher(), StandardCharsets.UTF_8);
        assertTrue(cipher.startsWith("v1:"), "落库内容不是密文格式");
        assertFalse(cipher.contains(PLAINTEXT_KEY), "明文 Key 被直接写进库了");
        assertEquals(PLAINTEXT_KEY, AesGcmCipher.decrypt(cipher, MASTER_KEY));

        // 返回体里只有掩码
        assertTrue(vo.getApiKeyConfigured());
        assertEquals("sk-…9f3a", vo.getApiKeyMask());
        assertFalse(vo.getApiKeyMask().contains("abcdefghijklmnop"), "掩码泄露了 Key 主体");
        assertEquals(7L, inserted.getUpdatedBy(), "审计字段必须记录操作者");
    }

    @Test
    @DisplayName("baseUrl 结尾斜杠被去掉，避免拼出双斜杠路径")
    void saveNormalizesBaseUrl() {
        when(mapper.selectOne(any())).thenReturn(null);

        service.save(dto(AiModelRole.CHAT, PLAINTEXT_KEY), 7L);

        ArgumentCaptor<AiProviderConfig> captor = ArgumentCaptor.forClass(AiProviderConfig.class);
        verify(mapper).insert(captor.capture());
        assertEquals("https://api.deepseek.com/v1", captor.getValue().getBaseUrl());
    }

    @Test
    @DisplayName("改模型名时留空 Key：沿用已存密钥，且不覆盖密文")
    void saveWithoutKeyKeepsExistingCipher() {
        AiProviderConfig existing = stored(AiModelRole.CHAT, PLAINTEXT_KEY);
        byte[] originalCipher = existing.getApiKeyCipher().clone();
        when(mapper.selectOne(any())).thenReturn(existing);

        AiProviderSaveDTO dto = dto(AiModelRole.CHAT, "");
        dto.setModel("deepseek-reasoner");
        AiProviderVO vo = service.save(dto, 7L);

        verify(mapper).updateById(existing);
        assertEquals("deepseek-reasoner", existing.getModel());
        assertTrue(java.util.Arrays.equals(originalCipher, existing.getApiKeyCipher()),
                "留空 Key 时不该改动已存密文");
        assertEquals("sk-…9f3a", vo.getApiKeyMask());
    }

    @Test
    @DisplayName("首次配置却不给 Key：直接报参数错误，而不是存一条没有密钥的配置")
    void saveWithoutKeyOnFirstTimeFails() {
        when(mapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.save(dto(AiModelRole.CHAT, ""), 7L));

        assertTrue(exception.getMessage().contains("API Key"), exception.getMessage());
    }

    @Test
    @DisplayName("列表只给掩码与状态，永远不给密文或明文")
    void listNeverExposesSecret() {
        when(mapper.selectList(any())).thenReturn(List.of(stored(AiModelRole.CHAT, PLAINTEXT_KEY)));

        List<AiProviderVO> list = service.list();

        assertEquals(1, list.size());
        AiProviderVO vo = list.get(0);
        assertEquals(AiModelRole.CHAT, vo.getRole());
        assertEquals("sk-…9f3a", vo.getApiKeyMask());
        assertEquals("unknown", vo.getLastCheckStatus(), "没自检过就该是 unknown，不能默认显示可用");
        // 序列化后的 JSON 里也不能出现明文或密文
        String json = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().valueToTree(vo).toString();
        assertFalse(json.contains(PLAINTEXT_KEY));
        assertFalse(json.contains("v1:"), "响应里不该出现密文本身");
    }

    @Test
    @DisplayName("运行时配置：解密给调用方，未配置 Key 的角色不出现（让调用方看到「没这个能力」）")
    void runtimeConfigsDecryptsAndSkipsUnconfigured() {
        AiProviderConfig configured = stored(AiModelRole.CHAT, PLAINTEXT_KEY);
        AiProviderConfig withoutKey = stored(AiModelRole.EMBEDDING, PLAINTEXT_KEY);
        withoutKey.setApiKeyCipher(null);
        withoutKey.setApiKeyMask(null);
        when(mapper.selectList(any())).thenReturn(List.of(configured, withoutKey));

        Map<String, AiProviderConfigService.RuntimeProvider> runtime = service.runtimeConfigs();

        assertEquals(1, runtime.size());
        assertTrue(runtime.containsKey("chat"));
        assertEquals(PLAINTEXT_KEY, runtime.get("chat").apiKey());
        assertFalse(runtime.containsKey("embedding"), "没有密钥的角色不该出现在运行时配置里");
    }

    @Test
    @DisplayName("删除按角色进行，返回是否真的删掉了")
    void deleteReportsWhetherAnythingWasRemoved() {
        when(mapper.delete(any())).thenReturn(1);
        assertTrue(service.delete(AiModelRole.RERANK));

        when(mapper.delete(any())).thenReturn(0);
        assertFalse(service.delete(AiModelRole.RERANK));
    }

    @Test
    @DisplayName("角色解析：大小写与空白都容错，非法值返回 null 而不是崩")
    void roleParsingIsForgiving() {
        assertEquals(AiModelRole.EMBEDDING, AiModelRole.parse(" Embedding "));
        assertEquals(AiModelRole.CHAT, AiModelRole.parse("CHAT"));
        assertNull(AiModelRole.parse("nope"));
        assertNull(AiModelRole.parse(""));
        assertNotNull(AiModelRole.parse("rerank"));
    }
}
