package com.stellarink.ai.service.impl;

import com.stellarink.ai.config.MasterKeyProvider;
import com.stellarink.ai.mapper.AiProviderConfigMapper;
import com.stellarink.ai.service.ProviderConnectivityChecker;
import com.stellarink.common.crypto.MasterKey;
import com.stellarink.sharedmodel.dto.ai.AiProviderSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.exception.BusinessException;
import com.stellarink.sharedmodel.vo.ai.AiProviderVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 个人模型配置的隔离口径（M12，**H2 真落库**）。
 *
 * <p>为什么必须用真库而不是 mock mapper：这一刀的核心不变量是<b>唯一键与查询范围</b> ——
 * 「两个用户各有一行 chat」靠的是 {@code uk_user_role (user_id, role)}，
 * 而 mock 掉的 mapper 永远不会告诉你「第二个用户保存时撞键了」。
 * M9 就是被 H2 的唯一键顶出过一个同类设计缺陷（幂等锚点少了 status）。
 *
 * <p>四条断言：
 * <ol>
 *   <li>两个用户各配自己的 chat 互不影响（唯一键带 user_id）；</li>
 *   <li>{@code listMine} 只返回自己的（不含全局、也不含别人的）；</li>
 *   <li>全局与个人并存，且个人不污染 `runtimeConfigs`（那份是给 Python 的全局视图）；</li>
 *   <li>embedding/rerank 与内网地址在个人这条路上被拒。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("unittest")
class AiProviderUserScopeTest {

    private static final byte[] MASTER_KEY = MasterKey.load(
            Base64.getEncoder()
                    .encodeToString("stellar-ink-test-master-key-0000".getBytes(StandardCharsets.UTF_8)));

    @Autowired
    private AiProviderConfigMapper mapper;

    private AiProviderConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        MasterKeyProvider keyProvider = new MasterKeyProvider() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public byte[] get() {
                return MASTER_KEY;
            }
        };
        service = new AiProviderConfigServiceImpl(
                mapper, keyProvider, mock(ProviderConnectivityChecker.class));
        mapper.delete(null);
    }

    /**
     * ⚠️ **跑完也要清**：H2 是 `jdbc:h2:mem:...;DB_CLOSE_DELAY=-1` —— 同一个内存库在**测试类之间共享**。
     * 本类会写入全局行（{@code user_id = 0, role = chat}），而后面某个测试类只在 {@code @AfterEach}
     * 里清理（它假设自己是干净的起点），于是它第一条 chat 插入会撞上本类留下的行，
     * 报 `DuplicateKeyException` —— 而错误指向的那个类完全无辜。
     *
     * <p>实测踩到：全量套件里 `AiUsageServiceImplTest` 红了，单独跑却 9/9 通过。
     * 只清不清「之后」的写法在共享库上是不成立的。
     */
    @AfterEach
    void tearDown() {
        mapper.delete(null);
    }

    private static AiProviderSaveDTO dto(AiModelRole role, String baseUrl, String model) {
        AiProviderSaveDTO dto = new AiProviderSaveDTO();
        dto.setRole(role);
        dto.setDisplayName("测试 " + model);
        dto.setProvider("openai_compatible");
        dto.setBaseUrl(baseUrl);
        dto.setModel(model);
        dto.setApiKey("sk-test-abcdefghijklmnop-9f3a");
        return dto;
    }

    @Test
    @DisplayName("两个用户各有自己的 chat 配置，互不撞键、互不覆盖")
    void twoUsersCanEachOwnTheSameRole() {
        service.saveMine(dto(AiModelRole.CHAT, "https://api.example.com/v1", "user1-model"), 1L);
        service.saveMine(dto(AiModelRole.CHAT, "https://api.example.com/v1", "user2-model"), 2L);

        List<AiProviderVO> mine1 = service.listMine(1L);
        List<AiProviderVO> mine2 = service.listMine(2L);

        assertThat(mine1).hasSize(1);
        assertThat(mine2).hasSize(1);
        assertThat(mine1.get(0).getModel()).isEqualTo("user1-model");
        assertThat(mine2.get(0).getModel()).isEqualTo("user2-model");
    }

    @Test
    @DisplayName("listMine 只返回自己的：别人的与全局的都不出现")
    void listMineOnlyReturnsOwnRows() {
        service.save(dto(AiModelRole.CHAT, "https://api.example.com/v1", "global-model"), 99L);
        service.saveMine(dto(AiModelRole.CHAT, "https://api.example.com/v1", "mine-model"), 1L);

        assertThat(service.listMine(1L)).extracting(AiProviderVO::getModel)
                .containsExactly("mine-model");
        // 全局那条只有管理员能看到
        assertThat(service.list()).extracting(AiProviderVO::getModel)
                .containsExactly("global-model");
    }

    @Test
    @DisplayName("个人配置不污染 Python 读的全局视图（runtimeConfigs）")
    void personalRowsStayOutOfTheGlobalRuntimeView() {
        service.save(dto(AiModelRole.CHAT, "https://api.example.com/v1", "global-model"), 99L);
        service.saveMine(dto(AiModelRole.CHAT, "https://api.example.com/v1", "mine-model"), 1L);

        // 漏了过滤的话，某个用户的私人模型会变成**全站默认**（别人用他的 Key）= 最严重的串号
        assertThat(service.runtimeConfigs()).containsKey("chat");
        assertThat(service.runtimeConfigs().get("chat").model()).isEqualTo("global-model");
    }

    @Test
    @DisplayName("删掉个人配置后：那行没了，全局那份还在（回落到全局）")
    void deletingMineFallsBackToGlobal() {
        service.save(dto(AiModelRole.CHAT, "https://api.example.com/v1", "global-model"), 99L);
        service.saveMine(dto(AiModelRole.CHAT, "https://api.example.com/v1", "mine-model"), 1L);

        assertThat(service.deleteMine(1L, AiModelRole.CHAT)).isTrue();

        assertThat(service.listMine(1L)).isEmpty();
        assertThat(service.list()).hasSize(1);
        assertThat(service.deleteMine(1L, AiModelRole.CHAT)).isFalse();
    }

    @Test
    @DisplayName("个人配置不给配 embedding / rerank（向量索引只有一份）")
    void embeddingAndRerankAreRejectedForUsers() {
        // 让用户配一个不会生效的东西是最糟的交互：写入期就拒
        assertThatThrownBy(() -> service.saveMine(
                dto(AiModelRole.EMBEDDING, "https://api.example.com/v1", "bge-m3"), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持个人配置");
        assertThatThrownBy(() -> service.saveMine(
                dto(AiModelRole.RERANK, "https://api.example.com/v1", "bge-reranker"), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持个人配置");
        // 站长那条路照旧可以配（它们本来就只有全局这一份）
        service.save(dto(AiModelRole.EMBEDDING, "https://api.example.com/v1", "bge-m3"), 99L);
        assertThat(service.list()).hasSize(1);
    }

    @Test
    @DisplayName("内网地址：用户被拒、站长放行（自建推理就在 127.0.0.1）")
    void privateUrlIsRejectedForUsersOnly() {
        assertThatThrownBy(() -> service.saveMine(
                dto(AiModelRole.CHAT, "http://127.0.0.1:8000/v1", "local"), 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("公网地址");

        // 站长配同一地址是合法的：那是自建 vLLM 的标准形态
        AiProviderVO saved = service.save(
                dto(AiModelRole.CHAT, "http://127.0.0.1:8000/v1", "local"), 99L);
        assertThat(saved.getBaseUrl()).isEqualTo("http://127.0.0.1:8000/v1");
    }

    @Test
    @DisplayName("未登录（userId 为空）不许写个人配置")
    void anonymousCannotSave() {
        assertThatThrownBy(() -> service.saveMine(
                dto(AiModelRole.CHAT, "https://api.example.com/v1", "x"), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录");
    }
}
