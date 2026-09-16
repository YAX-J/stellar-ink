package com.stellarink.content.link.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.stellarink.common.auth.AuthHelper;
import com.stellarink.common.redis.RedisCache;
import com.stellarink.common.redis.RedisUtils;
import com.stellarink.content.cache.ContentCache;
import com.stellarink.content.link.mapper.LinkMapper;
import com.stellarink.content.link.pojo.Link;
import com.stellarink.sharedmodel.enums.ErrorCode;
import com.stellarink.sharedmodel.enums.Role;
import com.stellarink.sharedmodel.exception.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LinkServiceImplTest {

    private final LinkMapper linkMapper = mock(LinkMapper.class);
    private final RedisUtils redisUtils = mock(RedisUtils.class);
    private final ContentCache cache = new ContentCache(new RedisCache(redisUtils));
    private final LinkServiceImpl linkService = new LinkServiceImpl(linkMapper, cache);

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), Link.class);
    }

    @Test
    void shouldOnlyExposeApprovedLinksToPublicList() {
        when(linkMapper.selectList(any())).thenReturn(List.of(
                link(1L, LinkServiceImpl.APPROVED),
                link(2L, LinkServiceImpl.PENDING),
                link(3L, LinkServiceImpl.REJECTED)
        ));

        assertThat(linkService.listApproved())
                .extracting(item -> item.getId())
                .containsExactly(1L);
    }

    @Test
    void shouldOnlyReturnPendingLinksToAdmin() {
        when(linkMapper.selectList(any())).thenReturn(List.of(
                link(1L, LinkServiceImpl.APPROVED),
                link(2L, LinkServiceImpl.PENDING)
        ));

        try (MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            assertThat(linkService.listPending())
                    .extracting(item -> item.getId())
                    .containsExactly(2L);
            auth.verify(() -> AuthHelper.requireAtLeast(Role.ADMIN));
        }
    }

    @Test
    void shouldRejectPendingListWhenRoleCheckFails() {
        try (MockedStatic<AuthHelper> auth = Mockito.mockStatic(AuthHelper.class)) {
            auth.when(() -> AuthHelper.requireAtLeast(Role.ADMIN))
                    .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "权限不足"));

            assertThatThrownBy(linkService::listPending)
                    .isInstanceOf(BusinessException.class)
                    .extracting("code")
                    .isEqualTo(403);
            verify(linkMapper, never()).selectList(any());
        }
    }

    @Test
    void shouldRejectUnsupportedReviewStatus() {
        try (MockedStatic<AuthHelper> ignored = Mockito.mockStatic(AuthHelper.class)) {
            assertThatThrownBy(() -> linkService.review(1L, LinkServiceImpl.PENDING))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("审核状态只能是");
            verify(linkMapper, never()).selectById(any());
        }
    }

    @Test
    void shouldRejectRepeatedReview() {
        when(linkMapper.selectById(1L)).thenReturn(link(1L, LinkServiceImpl.APPROVED));

        try (MockedStatic<AuthHelper> ignored = Mockito.mockStatic(AuthHelper.class)) {
            assertThatThrownBy(() -> linkService.review(1L, LinkServiceImpl.REJECTED))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("已经审核过");
            verify(linkMapper, never()).updateById(any(Link.class));
        }
    }

    private Link link(Long id, int status) {
        Link link = new Link();
        link.setId(id);
        link.setName("站点 " + id);
        link.setUrl("https://example.com/" + id);
        link.setStatus(status);
        return link;
    }
}
