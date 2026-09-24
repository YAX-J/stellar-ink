package com.stellarink.gateway.config;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SaTokenConfigureTest {

    private final SaTokenConfigure configure = new SaTokenConfigure();

    @Test
    void shouldUseStatelessJwtLogic() {
        assertThat(configure.getStpLogic()).isInstanceOf(StpLogicJwtForStateless.class);
    }

    @Test
    void shouldProtectPendingLinksBeforePublicGetRule() {
        assertThat(SaTokenConfigure.requiresAdminRead("GET", "/links/pending")).isTrue();
        assertThat(SaTokenConfigure.requiresAdminRead("GET", "/links")).isFalse();
        assertThat(SaTokenConfigure.requiresAdminRead("POST", "/links/pending")).isFalse();
    }

    @Test
    void shouldProtectAuthorReadEndpointsBeforePublicGetRule() {
        assertThat(SaTokenConfigure.requiresAuthorRead("GET", "/posts/mine")).isTrue();
        assertThat(SaTokenConfigure.requiresAuthorRead("GET", "/notes/mine")).isTrue();
        assertThat(SaTokenConfigure.requiresAuthorRead("GET", "/notes/review")).isTrue();
        assertThat(SaTokenConfigure.requiresAuthorRead("GET", "/notes")).isFalse();
        assertThat(SaTokenConfigure.requiresAuthorRead("POST", "/notes/review")).isFalse();
    }

    @Test
    void shouldExposeOnlyAiHealthPublicly() {
        assertThat(SaTokenConfigure.isPublicAi("GET", "/ai/health")).isTrue();
        // 探活之外的 AI 路径一律不公开；比对是全等而不是前缀匹配
        assertThat(SaTokenConfigure.isPublicAi("GET", "/ai/health/x")).isFalse();
        assertThat(SaTokenConfigure.isPublicAi("POST", "/ai/health")).isFalse();
        assertThat(SaTokenConfigure.isPublicAi("GET", "/ai/admin/providers")).isFalse();
        assertThat(SaTokenConfigure.isPublicAi("GET", "/ai/qa/stream")).isFalse();
    }

    @Test
    void shouldRequireAdminForAllAiAdminMethods() {
        // 关键：GET 也必须拦下 —— 它返回的是「用了哪家模型、端点在哪」这类配置情报
        assertThat(SaTokenConfigure.aiRequiresAdmin("/ai/admin/providers")).isTrue();
        assertThat(SaTokenConfigure.aiRequiresAdmin("/ai/admin/providers/runtime")).isTrue();
        assertThat(SaTokenConfigure.aiRequiresAdmin("/ai/admin/jobs/job-1")).isTrue();
        // 相似前缀不能被误判：/ai/administrator 不是管理端路径
        assertThat(SaTokenConfigure.aiRequiresAdmin("/ai/administrator")).isFalse();
        assertThat(SaTokenConfigure.aiRequiresAdmin("/ai/qa/stream")).isFalse();
        assertThat(SaTokenConfigure.aiRequiresAdmin(null)).isFalse();
    }

    @Test
    void shouldRequireAuthorForAiWritingWritesOnly() {
        assertThat(SaTokenConfigure.aiRequiresAuthor("POST", "/ai/writing/suggest")).isTrue();
        // 读接口交给后面的「GET 全放行」，这里不重复判定
        assertThat(SaTokenConfigure.aiRequiresAuthor("GET", "/ai/writing/suggest")).isFalse();
        assertThat(SaTokenConfigure.aiRequiresAuthor("OPTIONS", "/ai/writing/suggest")).isFalse();
        assertThat(SaTokenConfigure.aiRequiresAuthor("POST", "/ai/qa/stream")).isFalse();
        assertThat(SaTokenConfigure.aiRequiresAuthor("POST", null)).isFalse();
    }
}
