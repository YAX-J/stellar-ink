package com.stellarink.ai.controller;

import com.stellarink.aiclient.client.PythonAiClient;
import com.stellarink.aiclient.dto.ProviderModelsRequestDTO;
import com.stellarink.aiclient.dto.ProviderModelsResultDTO;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.ProviderConnectivityChecker;
import com.stellarink.ai.service.ProviderUrlPolicy;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.sharedmodel.dto.ai.AiProviderSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.ai.AiProviderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「我的 AI 模型」—— 个人模型配置（M12，读者/作者都能用，**不是** ADMIN 专属）。
 *
 * <p>与 {@code /ai/admin/providers} 的关系：那份是<b>站长配的全局默认</b>（一角色一行，全站共用），
 * 这份是<b>用户自己的</b>。没配的角色自动回落到全局 —— 所以用户可以只配 chat 一个角色，
 * 其余照旧。
 *
 * <p>三条口径：
 *
 * <ol>
 *   <li><b>只放开 chat / fast / reasoning</b>：embedding / rerank 不按用户隔离。
 *       向量索引只有一份，用另一个嵌入模型去检索，向量不在同一空间 —— 结果不是「差一点」而是错的。
 *       这里在写入期就拒掉，而不是让它保存成功却永不生效。</li>
 *   <li><b>地址只允许公网</b>：{@code baseUrl} 是服务端拿去发请求的地址，
 *       让普通用户填内网地址等于开放 SSRF（见 {@link ProviderUrlPolicy}）。</li>
 *   <li><b>身份只从会话来</b>：{@code userId} 由 {@code AuthHelper.loginId()} 取，
 *       绝不接受请求体里的 userId —— 否则谁都能改别人的配置。</li>
 * </ol>
 *
 * <p>{@code POST /ai/me/providers/models}（拉取供应商的模型清单）挂在这份门槛上：
 * 它让面板的「添加模型」不必再手打模型名，因此**门槛就是登录**（与保存我的配置同档）。
 * 三条口径写在 {@link #listProviderModels} 上：清单只能来自供应商实时返回、
 * 明文密钥只用一次（响应里连掩码都没有）、**不进调用账也不占配额**。
 */
@Slf4j
@Tag(name = "我的 AI 模型", description = "M12：个人模型配置（读者/作者可用；只放开 chat/fast/reasoning）")
@RestController
@RequestMapping("/ai/me/providers")
@RequiredArgsConstructor
public class AiMeProviderController {

    /** 缺省协议：与 {@code AiProviderSaveDTO.provider} 的默认值一致（面板不选就是它） */
    static final String OPENAI_COMPATIBLE_PROVIDER = "openai_compatible";
    /** 离线自测协议：不发任何出站请求，因此不需要地址与密钥 */
    static final String FAKE_PROVIDER = "fake";
    static final String DEFAULT_PROVIDER = OPENAI_COMPATIBLE_PROVIDER;

    /**
     * 自检结果（与 `AiProviderAdminController.CheckVO` 同形）。
     *
     * <p>`scope` 是刻意留的：面板要能说出「这次自检测的是哪一份配置」——
     * 用户改了个人配置却看到全局那次的结论，会以为「我的配置生效了」。
     */
    public record CheckVO(boolean ok, String scope, long latencyMs, String message) {
    }

    private final AiProviderConfigService providerConfigService;

    /**
     * Python 客户端：拉模型清单是**转发**，Java 侧不解密密钥、不加工清单。
     *
     * <p>为什么密钥由 Python 取：{@code apiKey} 留空时要读该用户已保存的配置并解密，
     * 而「谁掌握主密钥」这件事只该有一处（Python 侧本来就要解密才能发请求）。
     * Java 在这里解一次密，只会多一条「明文出现过」的路径，换不来任何能力。
     */
    private final PythonAiClient pythonAiClient;

    @GetMapping
    @Operation(
            summary = "我的模型配置",
            description = "只返回我自己配的（不含全局那份），面板据此显示「已覆盖 / 用全局」")
    public Response<List<AiProviderVO>> listMine() {
        return Response.success(providerConfigService.listMine(AuthHelper.loginId()));
    }

    @PostMapping
    @Operation(
            summary = "保存我的模型配置",
            description = "apiKey 留空表示沿用已存密钥；地址只允许公网（内网地址会被拒）")
    public Response<AiProviderVO> saveMine(@Valid @RequestBody AiProviderSaveDTO dto) {
        return Response.success(providerConfigService.saveMine(dto, AuthHelper.loginId()));
    }

    @DeleteMapping("/{role}")
    @Operation(summary = "删除我的模型配置", description = "删掉后该角色自动回落到站长的全局配置")
    public Response<Boolean> deleteMine(@PathVariable("role") String role) {
        return Response.success(providerConfigService.deleteMine(AuthHelper.loginId(), requireRole(role)));
    }

    @PostMapping("/{role}/check")
    @Operation(summary = "我的模型配置连通性自检", description = "只验证端点 TCP 可达")
    public Response<CheckVO> checkMine(@PathVariable("role") String role) {
        ProviderConnectivityChecker.CheckResult result =
                providerConfigService.checkMine(AuthHelper.loginId(), requireRole(role));
        return Response.success(new CheckVO(result.ok(), result.scope(), result.latencyMs(), result.message()));
    }

    @PostMapping("/models")
    @Operation(
            summary = "拉取供应商的模型清单（实时）",
            description = "拿着供应商 + Key 去请求它的 /models，把可选模型列出来给面板挑；"
                    + "apiKey 留空表示沿用**我自己**已保存的该角色密钥（不借站长那份全局）。"
                    + "密钥只在这一次请求里出现，响应里连掩码都没有")
    public Response<ProviderModelsResultDTO> listProviderModels(
            @Valid @RequestBody ProviderModelsRequestDTO request) {
        Long userId = AuthHelper.loginId();

        // 角色与协议都在这一层先判一遍：它们是**参数问题**，在这里拒掉就不用白跑一次外部请求。
        // ⚠️ 可选值只从 AiModelRole 取（与 Python 的 `_ROLE_CAPABILITY` 同源），
        // 不在这里另写一份字符串清单 —— 两处清单迟早分叉
        AiModelRole role = request.getRole() == null || request.getRole().isBlank()
                ? AiModelRole.CHAT
                : requireRole(request.getRole());
        String provider = request.getProvider() == null || request.getProvider().isBlank()
                ? DEFAULT_PROVIDER
                : request.getProvider().trim();
        requireProvider(provider);

        // baseUrl 只允许公网（allowPrivate=false）：它与 `/ai/me/providers` 的保存同一条口径 ——
        // 地址是**服务端**拿去发请求的，而这个值来自浏览器表单（登录用户都能填），
        // 不校验就是开放 SSRF。Python 侧还会按同一规则再校一次（权威在那里）。
        // fake 不发请求，因此不需要地址（面板把协议选成 fake 时地址栏可以是空的）
        if (!FAKE_PROVIDER.equals(provider)) {
            ProviderUrlPolicy.check(request.getBaseUrl(), false);
        }

        ProviderModelsRequestDTO internal = ProviderModelsRequestDTO.builder()
                .provider(provider)
                .baseUrl(trimmed(request.getBaseUrl()))
                .apiKey(trimmed(request.getApiKey()))
                .role(role.getKey())
                .build();

        // ⚠️ **不进调用账、不占配额**：拉清单不调用任何模型（零 Token、零费用）。
        // 记一笔零成本的调用会让成本看板上的数字不再是「模型花了多少」；
        // 而把一次不花钱的请求拦在配额外面，只会让用户以为「额度用完了连模型名都拉不了」——
        // 与 `/ai/agent/verify` 完全同一条口径。
        ProviderModelsResultDTO result = pythonAiClient.providerModels(internal);

        // 审计：谁、哪个协议、哪个角色、拉到几条、有没有截断。
        // ⚠️ **不记 baseUrl、不记 apiKey**：前者可能带密钥（路径或查询串），后者是明文
        log.info("AI 模型清单已拉取：userId={} provider={} role={} models={} truncated={}",
                userId,
                provider,
                role.getKey(),
                result == null || result.getModels() == null ? 0 : result.getModels().size(),
                result == null ? null : result.getTruncated());
        return Response.success(result);
    }

    /**
     * 解析角色键：无效值一律报参数错误。
     *
     * <p>与 {@code AiProviderAdminController} 同一个口径 —— 「删除成功但什么都没删」这类假成功
     * 比报错难查得多。
     */
    private static AiModelRole requireRole(String role) {
        AiModelRole parsed = AiModelRole.parse(role);
        if (parsed == null) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR, "未知的模型角色：" + role);
        }
        return parsed;
    }

    /**
     * 校验协议实现：只认 {@code openai_compatible} 与 {@code fake}。
     *
     * <p>在 Java 侧先拒一次的理由不是「不信任 Python」，而是**别白跑一次外部请求**：
     * Python 同样会拒绝（并把可选值列出来），两边口径由这条单测与
     * {@code model_listing.SUPPORTED_PROVIDERS} 对齐。
     */
    private static void requireProvider(String provider) {
        if (!FAKE_PROVIDER.equals(provider) && !OPENAI_COMPATIBLE_PROVIDER.equals(provider)) {
            throw BusinessExceptionHelper.of(
                    ErrorCode.PARAM_ERROR,
                    "未知的 provider：" + provider + "（当前支持 "
                            + OPENAI_COMPATIBLE_PROVIDER + " 与 " + FAKE_PROVIDER + "）");
        }
    }

    /** 空白字符串按「没给」处理：契约里只该有一种「没给」（null），别让 "" 也成立。 */
    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
