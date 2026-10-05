package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 模型清单（Python {@code ProviderModelsResult}）。
 *
 * <p>⚠️ <b>这里没有、也不许有密钥字段</b>（连掩码都没有）：{@code apiKey} 只在请求里出现一次，
 * 用完即弃。加一个 {@code apiKeyMask} 看着「更贴心」，但它会让「我填的 Key 对不对」
 * 变成一个需要推理的问题，而它的代价是实打实的一条泄露路径。
 *
 * <p>三条必须原样透传给前端的字段：
 * <ul>
 *   <li>{@code source} = <b>实际请求的 baseUrl</b>（绝不含密钥）：让用户核对自己填得对不对。
 *       「拉到了 5 个模型」这句话，只有在同时知道「从哪儿拉的」之后才有意义；</li>
 *   <li>{@code truncated} = 是否因条数上限被截断：界面据此提示「还有更多，请手填完整名字」，
 *       而不是让用户以为「我这家就这几个模型」；</li>
 *   <li>{@code models} = 清单本身（协议为 {@code fake} 时是它自己那一个标识，
 *       此时 {@code source} 是字面量 {@code fake} 而不是 URL —— 我们没有向任何地址发过请求）。</li>
 * </ul>
 *
 * <p>⚠️ 清单为**空**是合法结果（供应商确实没列出来）：它与「拉取失败」是两件事，
 * 失败会以 4xx/5xx 的错误体返回，不会用一个空清单冒充成功。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderModelsResultDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模型清单（Python 侧已按 id 去重、按上限截断） */
    private List<ProviderModelDTO> models;

    /** 是否因条数上限被截断 */
    private Boolean truncated;

    /** 实际请求的 baseUrl（不含密钥，供用户核对）；协议为 fake 时是字面量 fake */
    private String source;
}
