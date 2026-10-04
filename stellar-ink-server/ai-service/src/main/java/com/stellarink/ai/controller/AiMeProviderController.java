package com.stellarink.ai.controller;

import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.ProviderConnectivityChecker;
import com.stellarink.ai.service.ProviderUrlPolicy;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.ai.AiProviderSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.ai.AiProviderVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 */
@Tag(name = "我的 AI 模型", description = "M12：个人模型配置（读者/作者可用；只放开 chat/fast/reasoning）")
@RestController
@RequestMapping("/ai/me/providers")
@RequiredArgsConstructor
public class AiMeProviderController {

    /**
     * 自检结果（与 `AiProviderAdminController.CheckVO` 同形）。
     *
     * <p>`scope` 是刻意留的：面板要能说出「这次自检测的是哪一份配置」——
     * 用户改了个人配置却看到全局那次的结论，会以为「我的配置生效了」。
     */
    public record CheckVO(boolean ok, String scope, long latencyMs, String message) {
    }

    private final AiProviderConfigService providerConfigService;

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

    /**
     * 解析角色键：无效值一律报参数错误。
     *
     * <p>与 {@code AiProviderAdminController} 同一个口径 —— 「删除成功但什么都没删」这类假成功
     * 比报错难查得多。
     */
    private static AiModelRole requireRole(String role) {
        AiModelRole parsed = AiModelRole.parse(role);
        if (parsed == null) {
            throw com.stellarink.common.exception.BusinessExceptionHelper.of(
                    com.stellarink.sharedmodel.enums.ErrorCode.PARAM_ERROR, "未知的模型角色：" + role);
        }
        return parsed;
    }
}
