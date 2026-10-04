package com.stellarink.ai.service;

import com.stellarink.sharedmodel.enums.AiModelRole;
import com.stellarink.common.exception.BusinessExceptionHelper;
import com.stellarink.sharedmodel.enums.ErrorCode;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * {@code baseUrl} 的安全策略（M12：个人模型配置的 SSRF 闸门）。
 *
 * <p><b>为什么必须做</b>：{@code baseUrl} 是<b>服务端</b>拿着去发请求的地址。只让站长填时它只是配置，
 * 一旦读者/作者也能填，任何人都能让服务去打内网 —— 云元数据（169.254.169.254）、Nacos、内网服务，
 * 而报错信息还会把响应内容带回来。
 *
 * <p>规则分两档，判据是<b>谁填的</b>：
 *
 * <ul>
 *   <li><b>站长填的（全局配置）</b>：允许 loopback 与私网 —— 自建 vLLM / Ollama 就在
 *       {@code http://127.0.0.1:8000/v1}，禁掉等于把最正当的用法堵死；但<b>链路本地一律拒绝</b>
 *       （那是云元数据网段，没有模型端点会部署在那里）。</li>
 *   <li><b>用户填的（个人配置）</b>：只允许公网；并尽量解析一次域名，解析到内网也拒绝
 *       （挡住「公网域名指向 10.x」这类绕过）。</li>
 * </ul>
 *
 * <p>⚠️ <b>与 Python 侧同一套规则，两处必须同时改</b>：
 * {@code stellar-ink-ai/app/providers/url_policy.py} 是<b>权威</b>（请求是它发的），
 * 这里这一份是<b>写入期</b>的 UX —— 让用户在保存时就看到「个人配置只能填公网地址」，
 * 而不是等到第一次问答才报错。两侧单测各盯一份，改规则时一起改。
 *
 * <p>⚠️ <b>诚实的残留风险</b>：DNS rebinding（检查时解析到公网、请求时解析到内网）在配置期拦不住。
 * 根治要靠在<b>网络层</b>限制 Python 的出网范围，这条策略只是第一道闸。
 */
public final class ProviderUrlPolicy {

    /** 云元数据服务的地址：任何一档都拒绝（连站长也不给）。 */
    private static final String[] METADATA_HOSTS = {"metadata.google.internal", "metadata.goog"};

    /** 一看就不该是模型端点的主机名后缀（内网惯用名）。 */
    private static final String[] PRIVATE_SUFFIXES =
            {".localhost", ".local", ".internal", ".home.arpa"};

    private ProviderUrlPolicy() {
    }

    /**
     * 校验一个 {@code baseUrl}；不合法时抛业务异常（消息可直接给用户看）。
     *
     * @param allowPrivate 是否允许内网/loopback（<b>只有站长填的全局配置才为 true</b>）
     */
    public static void check(String url, boolean allowPrivate) {
        String raw = url == null ? "" : url.trim();
        if (raw.isEmpty()) {
            throw badRequest("接口地址不能为空", "填 API 根地址，例如 https://api.example.com/v1");
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException error) {
            throw badRequest("接口地址格式不对：" + error.getMessage(), "填 API 根地址，不要带查询串");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw badRequest("接口地址必须以 http:// 或 https:// 开头（当前是 " + scheme + "）", null);
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
            throw badRequest("接口地址里不要带用户名/密码",
                    "凭据请填在 API Key 一栏；写进 URL 会被日志与审计记录下来");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw badRequest("接口地址里没有主机名", null);
        }
        for (String metadata : METADATA_HOSTS) {
            if (host.equals(metadata)) {
                // 连站长也不给这个口子：它不是模型端点
                throw badRequest("该地址是云元数据服务，不能作为模型端点", null);
            }
        }

        InetAddress literal = asIpLiteral(host);
        if (literal != null) {
            checkAddress(literal, host, allowPrivate);
            return;
        }
        boolean privateName = host.equals("localhost") || endsWithAny(host, PRIVATE_SUFFIXES);
        if (privateName) {
            if (!allowPrivate) {
                throw privateNotAllowed(host, null);
            }
            return;
        }
        if (!allowPrivate) {
            checkResolved(host);
        }
    }

    /** 供面板过滤下拉框：这个角色能否按用户配置（与 Python 的 {@code USER_SCOPED_ROLES} 一致）。 */
    public static boolean isUserScoped(AiModelRole role) {
        return role == AiModelRole.CHAT || role == AiModelRole.FAST || role == AiModelRole.REASONING;
    }

    private static void checkAddress(InetAddress address, String host, boolean allowPrivate) {
        if (address.isLinkLocalAddress()) {
            // 含 169.254.169.254：云元数据/自动配置网段，任何一档都拒绝
            throw badRequest("「" + host + "」属于链路本地地址，不能作为模型端点",
                    "这一网段是云元数据/自动配置用的，不是模型服务");
        }
        if (allowPrivate) {
            return;
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw privateNotAllowed(host, null);
        }
    }

    /**
     * 解析域名，任一结果落在内网就拒绝。
     *
     * <p>解析失败<b>不拦</b>：DNS 还没生效、离线开发都可能解析不了，
     * 而那种情况下的报错应该来自真正的调用（「连不上」），不是这里一句「地址不合法」。
     */
    private static void checkResolved(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException error) {
            return;
        }
        for (InetAddress address : addresses) {
            if (address.isLinkLocalAddress()) {
                throw badRequest("「" + host + "」解析到链路本地地址，不能作为模型端点", null);
            }
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isAnyLocalAddress()) {
                throw badRequest(
                        "「" + host + "」解析到内网地址（" + address.getHostAddress()
                                + "），个人配置只能填公网地址",
                        null);
            }
        }
    }

    /**
     * 主机名是不是 IP 字面量；不是则返回 {@code null}。
     *
     * <p>刻意不用 {@code InetAddress.getByName}：那会对普通域名也发一次 DNS 查询，
     * 而这里的判断只需「它长得像不像 IP」，查询留给 {@link #checkResolved}。
     */
    private static InetAddress asIpLiteral(String host) {
        boolean looksLikeIpv4 = host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean looksLikeIpv6 = host.contains(":");
        if (!looksLikeIpv4 && !looksLikeIpv6) {
            return null;
        }
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException error) {
            return null;
        }
    }

    private static boolean endsWithAny(String host, String[] suffixes) {
        for (String suffix : suffixes) {
            if (host.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static RuntimeException privateNotAllowed(String host, String detail) {
        return badRequest(
                "「" + host + "」是内网或本机地址，个人配置只能填公网地址",
                detail != null ? detail : "要用自建推理服务请让站长在「AI 实验室」里配成全局模型");
    }

    /**
     * 统一成「可操作提示」。
     *
     * <p>刻意<b>不</b>给 {@code BusinessExceptionHelper} 加第三个参数的重载：一个概念多一种签名，
     * 早晚会出现「一半调用点写了 detail、一半没写」；把提示拼进消息里，读起来也是一句话。
     */
    private static RuntimeException badRequest(String message, String hint) {
        return BusinessExceptionHelper.of(
                ErrorCode.PARAM_ERROR, hint == null ? message : message + "（" + hint + "）");
    }
}
