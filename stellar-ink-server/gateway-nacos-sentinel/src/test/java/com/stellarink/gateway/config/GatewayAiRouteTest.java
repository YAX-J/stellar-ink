package com.stellarink.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关路由配置：`/ai/**` 必须显式路由到 ai-service，且**只有它**能进 AI 服务。
 *
 * <p>为什么直接读配置文件而不是起 Spring 上下文：路由属于部署配置，
 * 起上下文会去连 Nacos / Redis（本机不一定有）；这里要守住的是「文件里到底写了什么」。
 * 真实链路的验证在 A2 接线完成后走 `curl → 网关 → ai-service`。
 *
 * <p>用正则而不是引入 YAML 解析依赖：只断言几个固定结构，
 * 为它新增一个仅测试用的依赖不值得（AGENTS.md：新增依赖要克制）。
 */
class GatewayAiRouteTest {

    private static final List<String> PROFILES = List.of("dev", "prod");

    /** 匹配 id=ai-service 的整段路由定义（到下一个同级 `- id:` 或文件结束/空行为止） */
    private static final Pattern AI_ROUTE_BLOCK = Pattern.compile(
            "- id: ai-service(?<body>.*?)(?=\\n\\s*- id:|\\z)", Pattern.DOTALL);

    private static String configOf(String profile) {
        Path file = Path.of("src", "main", "resources", "application-" + profile + ".yml");
        assertThat(file).exists();
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String aiRouteBlock(String profile) {
        var matcher = AI_ROUTE_BLOCK.matcher(configOf(profile));
        assertThat(matcher.find())
                .as(profile + " 缺少 id=ai-service 的路由")
                .isTrue();
        return matcher.group("body");
    }

    /**
     * 去掉整行注释后再断言。
     *
     * <p>为什么要这步：配置注释里会写「Python（8200）不配路由」这种说明文字，
     * 直接对原文做 doesNotContain 会把**说明**当成**配置**判失败。
     * 这里只关心真正生效的键值行。
     */
    private static String configWithoutCommentLines(String profile) {
        return configOf(profile).lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (left, right) -> left + "\n" + right);
    }

    @Test
    @DisplayName("dev 与 prod 都显式声明 /ai/** → lb://ai-service")
    void aiRouteExistsInBothProfiles() {
        for (String profile : PROFILES) {
            String block = aiRouteBlock(profile);
            assertThat(block).as(profile + " 的 AI 路由必须走服务发现").contains("lb://ai-service");
            assertThat(block).as(profile + " 的 AI 路由只应匹配 /ai/**").contains("Path=/ai/**");
        }
    }

    @Test
    @DisplayName("Python 的 8200 不得出现在任何网关路由里（只能由 ai-service 内网调用）")
    void pythonPortIsNeverRouted() {
        for (String profile : PROFILES) {
            String effective = configWithoutCommentLines(profile);
            assertThat(effective).doesNotContain("8200");
            assertThat(effective).doesNotContain("stellar-ink-ai");
        }
    }

    @Test
    @DisplayName("服务发现自动路由保持关闭：否则 /{serviceId}/** 会绕过显式路由与鉴权")
    void discoveryLocatorStaysDisabled() {
        for (String profile : PROFILES) {
            String effective = configWithoutCommentLines(profile);
            int locatorIndex = effective.indexOf("locator:");
            assertThat(locatorIndex).as(profile + " 应有 discovery.locator 配置").isGreaterThan(-1);
            String tail = effective.substring(locatorIndex, Math.min(effective.length(), locatorIndex + 200));
            assertThat(tail).as(profile + " 必须关闭 discovery locator").contains("enabled: false");
        }
    }

    @Test
    @DisplayName("网关自身不持有任何 AI 密钥：它只做路由与鉴权")
    void gatewayHasNoAiSecrets() {
        for (String profile : PROFILES) {
            String effective = configWithoutCommentLines(profile);
            assertThat(effective).doesNotContain("AI_API_KEY");
            assertThat(effective).doesNotContain("AI_SECRET_MASTER_KEY");
            // 内部签名由 ai-service 发起，密钥不该出现在网关
            assertThat(effective).doesNotContain("AI_INTERNAL_SECRET");
        }
    }
}
