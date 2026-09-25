package com.stellarink.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.stellarink.ai.config.TestMasterKeyConfig;
import com.stellarink.ai.mapper.AiModelMapper;
import com.stellarink.ai.mapper.AiProviderConfigMapper;
import com.stellarink.ai.pojo.AiModel;
import com.stellarink.ai.pojo.AiProviderConfig;
import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.sharedmodel.dto.ai.AiModelSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelCapability;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.ai.AiModelVO;
import com.stellarink.sharedmodel.vo.ai.AiProviderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型库：加模型、绑角色、改库同步、删库拦截。
 *
 * <p>跑完整上下文 + H2（与 {@code AiProviderAdminControllerTest} 同一套），
 * 因为要验的正是「真的落库了、角色行真的被写对了」——切片测试证明不了这件事。
 *
 * <p>这一层的价值全在**一致性**上：模型库与角色行是两张表，最容易出的错是
 * 「库里改了 Key，角色还在用旧的」这种不报错的静默不一致，所以下面专门有一条用例盯它。
 */
@SpringBootTest
@ActiveProfiles("unittest")
@Import(TestMasterKeyConfig.class)
class AiModelLibraryServiceImplTest {

    private static final String PLAINTEXT_KEY = "sk-library-abcdefghijklmnop-9f3a";

    @Autowired
    private AiModelLibraryService libraryService;

    @Autowired
    private AiModelMapper modelMapper;

    @Autowired
    private AiProviderConfigMapper providerMapper;

    @AfterEach
    void cleanUp() {
        modelMapper.delete(Wrappers.<AiModel>lambdaQuery());
        providerMapper.delete(Wrappers.<AiProviderConfig>lambdaQuery());
    }

    private static AiModelSaveDTO dto(String name, String path, Set<AiModelCapability> capabilities) {
        AiModelSaveDTO dto = new AiModelSaveDTO();
        dto.setDisplayName(name);
        dto.setProvider("openai_compatible");
        dto.setBaseUrl("https://api.example.com/v1");
        dto.setModel(path);
        dto.setApiKey(PLAINTEXT_KEY);
        dto.setCapabilities(capabilities);
        return dto;
    }

    private Long addChatModel(String path) {
        return libraryService.save(dto("对话模型", path, Set.of(AiModelCapability.CHAT)), 1L).getId();
    }

    @Test
    @DisplayName("新增模型：列表能看到，落库是密文，响应只带掩码")
    void savesModelWithCiphertextOnly() {
        AiModelVO vo = libraryService.save(
                dto("DeepSeek Chat", "deepseek-chat", Set.of(AiModelCapability.CHAT)), 1L);

        assertNotNull(vo.getId());
        assertTrue(vo.getApiKeyConfigured());
        assertEquals("sk-…9f3a", vo.getApiKeyMask());
        assertEquals(List.of(AiModelCapability.CHAT), vo.getCapabilities());
        assertTrue(vo.getBoundRoles().isEmpty(), "还没绑定任何角色");

        AiModel row = modelMapper.selectById(vo.getId());
        String cipher = new String(row.getApiKeyCipher(), StandardCharsets.UTF_8);
        assertTrue(cipher.startsWith("v1:"), "落库内容不是密文：" + cipher);
        assertFalse(cipher.contains(PLAINTEXT_KEY));
    }

    @Test
    @DisplayName("绑定：角色行被写入，字段从库里复制过来，并记住来源 modelId")
    void bindCopiesTheModelIntoTheRoleRow() {
        Long modelId = addChatModel("deepseek-chat");

        AiProviderVO bound = libraryService.bind(AiModelRole.CHAT, modelId, 7L);

        assertEquals(AiModelRole.CHAT, bound.getRole());
        assertEquals(modelId, bound.getModelId(), "要记住它来自库里的哪一条");
        assertEquals("deepseek-chat", bound.getModel());
        assertEquals("https://api.example.com/v1", bound.getBaseUrl());
        assertTrue(bound.getApiKeyConfigured());

        AiProviderConfig row = providerMapper.selectOne(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getRole, "chat"));
        assertNotNull(row);
        assertEquals(modelId, row.getModelId());
        assertEquals(7L, row.getUpdatedBy());
    }

    @Test
    @DisplayName("能力不匹配：纯 chat 模型不能绑到 embedding 角色，且要说清它支持什么")
    void refusesCapabilityMismatch() {
        Long modelId = addChatModel("deepseek-chat");

        BusinessException error = assertThrows(BusinessException.class,
                () -> libraryService.bind(AiModelRole.EMBEDDING, modelId, 1L));

        assertTrue(error.getMessage().contains("embedding"), error.getMessage());
        assertTrue(error.getMessage().contains("chat"), "要说出这个模型实际能干什么：" + error.getMessage());
        assertNull(providerMapper.selectOne(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getRole, "embedding")), "被拒绝时不该留下半份配置");
    }

    @Test
    @DisplayName("同端点同模型不许加两条：否则下拉框里两个一样的选项，各带一把不同的 Key")
    void rejectsDuplicateEndpointAndModel() {
        addChatModel("deepseek-chat");

        BusinessException error = assertThrows(BusinessException.class,
                () -> libraryService.save(dto("重复的", "deepseek-chat", Set.of(AiModelCapability.CHAT)), 1L));

        assertTrue(error.getMessage().contains("已经有同名模型"), error.getMessage());
    }

    @Test
    @DisplayName("改了库里的模型：已绑定角色的生效配置跟着变（防「换了 Key 却不生效」）")
    void propagatesLibraryEditsToBoundRoles() {
        Long modelId = addChatModel("deepseek-chat");
        libraryService.bind(AiModelRole.CHAT, modelId, 1L);

        AiModelSaveDTO edit = dto("改过的名字", "deepseek-chat", Set.of(AiModelCapability.CHAT));
        edit.setId(modelId);
        edit.setDisplayName("改过的名字");
        edit.setModel("deepseek-chat-v2");
        edit.setApiKey("sk-rotated-aaaaaaaaaaaaaaaa-1234");
        libraryService.save(edit, 2L);

        AiProviderConfig row = providerMapper.selectOne(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getRole, "chat"));
        assertEquals("deepseek-chat-v2", row.getModel(), "角色行必须跟着换成新模型名");
        assertEquals("改过的名字", row.getDisplayName());
        assertEquals("sk-…1234", row.getApiKeyMask(), "角色行必须跟着换成新 Key 的掩码");
        assertTrue(new String(row.getApiKeyCipher(), StandardCharsets.UTF_8)
                .contains("v1:"), "同步过去的是密文");

        AiModelVO model = libraryService.list().get(0);
        assertEquals(List.of("chat"), model.getBoundRoles(), "列表要显示它正被哪个角色使用");
    }

    @Test
    @DisplayName("apiKey 留空 = 沿用旧密钥：改个名字不必重填")
    void blankApiKeyKeepsTheStoredOne() {
        Long modelId = addChatModel("deepseek-chat");

        AiModelSaveDTO edit = dto("只改了名字", "deepseek-chat", Set.of(AiModelCapability.CHAT));
        edit.setId(modelId);
        edit.setDisplayName("只改了名字");
        edit.setApiKey("   ");

        AiModelVO saved = libraryService.save(edit, 1L);

        assertEquals("只改了名字", saved.getDisplayName());
        assertEquals("sk-…9f3a", saved.getApiKeyMask(), "留空时掩码不变，说明用的还是旧密钥");
    }

    @Test
    @DisplayName("删正在用的模型要被拦下；force 只解绑，不动角色当前生效的配置")
    void deleteBlocksWhenBoundButForceOnlyUnbinds() {
        Long modelId = addChatModel("deepseek-chat");
        libraryService.bind(AiModelRole.CHAT, modelId, 1L);

        BusinessException blocked = assertThrows(BusinessException.class,
                () -> libraryService.delete(modelId, false, 1L));
        assertTrue(blocked.getMessage().contains("chat"), blocked.getMessage());
        assertNotNull(modelMapper.selectById(modelId), "被拦下时不能真删掉");

        libraryService.delete(modelId, true, 1L);

        assertNull(modelMapper.selectById(modelId));
        AiProviderConfig row = providerMapper.selectOne(Wrappers.<AiProviderConfig>lambdaQuery()
                .eq(AiProviderConfig::getRole, "chat"));
        assertNotNull(row, "角色当前生效的配置要留着，别让正在跑的能力突然取不到模型");
        assertNull(row.getModelId(), "但解绑关系要清掉");
        assertEquals("deepseek-chat", row.getModel());
    }

    @Test
    @DisplayName("停用的模型不能被绑定")
    void refusesDisabledModel() {
        AiModelSaveDTO dto = dto("停用的", "deepseek-chat", Set.of(AiModelCapability.CHAT));
        dto.setEnabled(false);
        Long modelId = libraryService.save(dto, 1L).getId();

        BusinessException error = assertThrows(BusinessException.class,
                () -> libraryService.bind(AiModelRole.CHAT, modelId, 1L));

        assertTrue(error.getMessage().contains("已停用"), error.getMessage());
    }

    @Test
    @DisplayName("不支持的协议要当场拒绝，而不是等 Python 装配时才报「未知的 provider」")
    void rejectsUnknownProvider() {
        AiModelSaveDTO dto = dto("怪协议", "m", Set.of(AiModelCapability.CHAT));
        dto.setProvider("anthropic_native");

        BusinessException error = assertThrows(BusinessException.class,
                () -> libraryService.save(dto, 1L));

        assertTrue(error.getMessage().contains("不支持的协议"), error.getMessage());
    }
}
