package com.stellarink.ai.controller;

import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.AiProviderConfigService;
import com.stellarink.ai.service.ProviderConnectivityChecker;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.sharedmodel.dto.ai.AiModelBindDTO;
import com.stellarink.sharedmodel.dto.ai.AiProviderSaveDTO;
import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 模型配置面板的后端接口（**全部要求 ADMIN**）。
 *
 * <p>为什么连「列表」也要 ADMIN：返回内容本身就是配置情报（用了哪家模型、端点、额度上限），
 * 对普通读者没有任何用途。鉴权是双层的：网关按角色拦截（M1 接上路由），
 * 服务内再用 {@link AuthHelper#requireAtLeast} 复核一次 —— 网关配置漏了也不会漏出去。
 *
 * <p>不提供的接口：任何「读取明文 Key」的查询。忘了 Key 只能重填一次，
 * 这比提供一个可被越权调用的读接口安全得多。
 */
@Slf4j
@RestController
@RequestMapping("/ai/admin/providers")
@RequiredArgsConstructor
@Tag(name = "AI 模型配置", description = "ADMIN：选择模型供应商、填写 API Key、连通性自检")
public class AiProviderAdminController {

    private final AiProviderConfigService providerConfigService;
    private final AiModelLibraryService modelLibraryService;

    @GetMapping
    @Operation(summary = "列出所有角色的模型配置（密钥只回掩码）")
    public Response<List<AiProviderVO>> list() {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(providerConfigService.list());
    }

    @PostMapping
    @Operation(summary = "新增或更新某个角色的模型配置", description = "apiKey 留空表示沿用已存密钥")
    public Response<AiProviderVO> save(@Valid @RequestBody AiProviderSaveDTO dto) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(providerConfigService.save(dto, AuthHelper.loginId()));
    }

    @DeleteMapping("/{role}")
    @Operation(summary = "删除某个角色的模型配置")
    public Response<Boolean> delete(@PathVariable("role") String role) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(providerConfigService.delete(requireRole(role)));
    }

    /**
     * 把模型库里的某条模型应用到该角色（面板下拉框选完之后走这里）。
     *
     * <p>为什么用 PUT 而不是又开一个 POST：这是「把角色的当前选择改成 X」，幂等 ——
     * 同一个 modelId 提交多次结果一致，也符合「一个角色一份生效配置」的资源语义。
     */
    @PutMapping("/{role}/model")
    @Operation(summary = "为角色选择模型库里的一个模型",
            description = "会校验能力是否匹配（embedding 角色不能用纯 chat 模型），并复制成该角色当前生效的配置")
    public Response<AiProviderVO> bind(
            @PathVariable("role") String role,
            @Valid @RequestBody AiModelBindDTO dto) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(
                modelLibraryService.bind(requireRole(role), dto.getModelId(), AuthHelper.loginId()));
    }

    @PostMapping("/{role}/check")
    @Operation(summary = "连通性自检", description = "只验证端点 TCP 可达；模型与密钥是否有效由 Python 侧在 M1 后验证")
    public Response<CheckVO> check(@PathVariable("role") String role) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        ProviderConnectivityChecker.CheckResult result =
                providerConfigService.checkConnectivity(requireRole(role));
        return Response.success(new CheckVO(result.ok(), result.scope(), result.latencyMs(), result.message()));
    }

    /** 解析角色键：无效值一律报参数错误，避免「删除成功但什么都没删」这类假成功。 */
    private static AiModelRole requireRole(String role) {
        AiModelRole parsed = AiModelRole.parse(role);
        if (parsed == null) {
            throw BusinessExceptionHelper.of(ErrorCode.PARAM_ERROR, "未知的模型角色：" + role);
        }
        return parsed;
    }

    /** 自检结果：刻意不含 baseUrl 与任何密钥信息。 */
    public record CheckVO(boolean ok, String scope, long latencyMs, String message) {
    }

    /** 运行时配置的读取（内网专用，M1 加 HMAC 后由 Python 调用）。 */
    @GetMapping("/runtime")
    @Operation(summary = "运行时配置（含解密后的密钥）", description = "仅内网；M1 起由 Python 经签名请求读取")
    public Response<Map<String, AiProviderConfigService.RuntimeProvider>> runtime() {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(providerConfigService.runtimeConfigs());
    }
}
