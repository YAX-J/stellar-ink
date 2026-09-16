package com.stellarink.content.meteor.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.common.util.Pagination;
import com.stellarink.content.cache.CachedPage;
import com.stellarink.content.cache.ContentCache;
import com.stellarink.content.meteor.mapper.MeteorMapper;
import com.stellarink.content.meteor.pojo.Meteor;
import com.stellarink.sharedmodel.dto.meteor.MeteorCreateDTO;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.meteor.MeteorVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
@Slf4j
@RestController
@RequestMapping("/meteors")
@RequiredArgsConstructor
public class MeteorController {

    private static final TypeReference<CachedPage<MeteorVO>> CACHE_TYPE = new TypeReference<>() { };

    private final MeteorMapper meteorMapper;
    private final ContentCache cache;

    @GetMapping
    public Response<IPage<MeteorVO>> list(@RequestParam(required = false, defaultValue = "1") Integer page,
                                           @RequestParam(required = false, defaultValue = "24") Integer size) {
        Pagination.requireValid(page, size);
        String cacheKey = cache.versionedKey("meteor", "page", page, size);
        CachedPage<MeteorVO> cached = cache.getOrLoad(cacheKey, CACHE_TYPE, ContentCache.DEFAULT_TTL,
                () -> CachedPage.from(loadMeteors(page, size)));
        return Response.success(cached.toPage());
    }

    private IPage<MeteorVO> loadMeteors(int page, int size) {
        IPage<Meteor> items = meteorMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Meteor>().orderByDesc(Meteor::getId));
        return items.convert(this::toVO);
    }

    /** 发射流星（作者及以上） */
    @PostMapping
    public Response<Void> create(@Valid @RequestBody MeteorCreateDTO dto) {
        AuthHelper.requireAtLeast(Role.AUTHOR);
        if (!StringUtils.hasText(dto.getContent())) {
            throw BusinessExceptionHelper.of("此刻的念头是空的，写一句再发射。");
        }
        Meteor entity = new Meteor();
        entity.setUserId(AuthHelper.loginId());
        entity.setContent(dto.getContent().trim());
        entity.setCreatedAt(LocalDateTime.now());
        meteorMapper.insert(entity);
        cache.invalidate("meteor");
        log.info("发射流星 id={}", entity.getId());
        return Response.success();
    }

    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        AuthHelper.requireAtLeast(Role.AUTHOR);
        Meteor entity = meteorMapper.selectById(id);
        if (entity == null) {
            throw BusinessExceptionHelper.of("这颗流星不存在。");
        }
        if (AuthHelper.currentRole() != Role.ADMIN
                && !AuthHelper.loginId().equals(entity.getUserId())) {
            throw BusinessExceptionHelper.of("不能熄灭别人的流星。");
        }
        meteorMapper.deleteById(entity.getId());
        cache.invalidate("meteor");
        log.info("删除流星 id={}", id);
        return Response.success();
    }

    private MeteorVO toVO(Meteor entity) {
        MeteorVO vo = new MeteorVO();
        vo.setId(entity.getId());
        vo.setUserId(entity.getUserId());
        vo.setContent(entity.getContent());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
