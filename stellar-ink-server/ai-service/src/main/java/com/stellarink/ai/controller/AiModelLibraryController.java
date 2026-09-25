package com.stellarink.ai.controller;

import com.stellarink.ai.service.AiModelLibraryService;
import com.stellarink.ai.service.ProviderConnectivityChecker;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.sharedmodel.dto.ai.AiModelSaveDTO;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.ai.AiModelVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型库接口（**全部要求 ADMIN**，与 {@link AiProviderAdminController} 同一套口径）。
 *
 * <p>面板流程：先在「模型库」里加好几个模型（每个模型标注它能干什么），
 * 再到角色卡片上用下拉框挑一个应用（见 {@code PUT /ai/admin/providers/{role}/model}）。
 * 这样就不再需要「同一个角色只能有一个模型、换个模型得把 Key 重填一遍」。
 *
 * <p>与角色配置一样：**没有任何接口能读回明文 Key**，列表只回掩码。
 */
@Slf4j
@RestController
@RequestMapping("/ai/admin/models")
@RequiredArgsConstructor
@Tag(name = "AI 模型库", description = "ADMIN：维护可选模型池，供各角色下拉选择")
public class AiModelLibraryController {

    private final AiModelLibraryService modelLibraryService;

    @GetMapping
    @Operation(summary = "列出模型库（密钥只回掩码，并带上正被哪些角色使用）")
    public Response<List<AiModelVO>> list() {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(modelLibraryService.list());
    }

    @PostMapping
    @Operation(summary = "新增或修改一个模型", description = "id 留空表示新增；apiKey 留空表示沿用已存密钥")
    public Response<AiModelVO> save(@Valid @RequestBody AiModelSaveDTO dto) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        return Response.success(modelLibraryService.save(dto, AuthHelper.loginId()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除一个模型", description = "正被角色使用时会被拦下；force=true 则只解绑")
    public Response<Boolean> delete(
            @PathVariable("id") Long id,
            @RequestParam(value = "force", defaultValue = "false") boolean force) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        modelLibraryService.delete(id, force, AuthHelper.loginId());
        return Response.success(true);
    }

    @PostMapping("/{id}/check")
    @Operation(summary = "端点连通性自检", description = "只验证 TCP 可达；模型与密钥是否有效由 Python 侧验证")
    public Response<AiProviderAdminController.CheckVO> check(@PathVariable("id") Long id) {
        AuthHelper.requireAtLeast(Role.ADMIN);
        ProviderConnectivityChecker.CheckResult result = modelLibraryService.check(id);
        return Response.success(new AiProviderAdminController.CheckVO(
                result.ok(), result.scope(), result.latencyMs(), result.message()));
    }
}
