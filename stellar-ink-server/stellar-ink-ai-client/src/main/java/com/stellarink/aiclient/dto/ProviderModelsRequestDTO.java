package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 拉取供应商模型清单的请求（Python {@code ProviderModelsRequest}）。
 *
 * <p>为什么要有这个端点：面板里的「添加模型」此前要**手打模型名**，填错只有等到第一次调用
 * 才知道。现在可以拿着供应商 + Key 把它实时的 {@code GET /models} 拉出来挑。
 *
 * <p>⚠️ <b>清单只能来自供应商</b>：代码里不许出现任何厂商或模型名的预设
 * （AGENTS §5「面板是模型的唯一来源」）。预置一份的后果是「面板里明明有，一填就 404」——
 * 厂商下架/改名时没人会记得回来改代码。
 *
 * <p>⚠️ {@code apiKey} 是**明文**，只用于这一次拉取：
 * <ul>
 *   <li>留空表示「用该用户**自己**保存的该角色密钥」（Python 读同一张 {@code ai_provider_config}
 *       表、同一把主密钥，但**只读他自己那一行**）；他自己没配过 → 400 + 可读提示；</li>
 *   <li><b>刻意不回落站长那份全局配置</b>：拉清单的 {@code baseUrl} 是用户自己填的，
 *       借出全局密钥就等于让任何登录用户把站长的 Key 发往他控制的公网地址 ——
 *       一次请求偷一把 Key。所以他用自己的密钥、去自己填的地址；</li>
 *   <li>它不由 Java 侧读写数据库，只是**原样转发**给 Python ——
 *       在这里解一次密等于多一条「明文出现过」的路径，而没有换来任何能力；</li>
 *   <li>任何日志、审计、响应都不得出现它（连掩码都不回）。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderModelsRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 协议实现：{@code openai_compatible} / {@code fake}（未知值由 Python 回 400 并列出可选值） */
    private String provider;

    /**
     * API 根地址（{@code /models} 由 Python 拼在后面）。
     *
     * <p>只允许公网地址：它是**服务端**拿去发请求的地址，而这个值来自浏览器表单 ——
     * 不校验就是开放 SSRF（口径与 {@code ProviderUrlPolicy} 里「用户那份」一致）。
     */
    private String baseUrl;

    /** 明文 API Key；留空表示用该用户已保存的该角色密钥 */
    private String apiKey;

    /** 从哪个角色的已保存配置里取密钥（默认 {@code chat}） */
    private String role;
}
