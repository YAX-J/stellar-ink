package com.stellarink.aiclient.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 失效盘点的输入（Java → Python，E4-11）。
 *
 * <p>为什么要问 Python：**只有它知道当前的切块结果**（段落序号与内容哈希都是切块的产物）。
 * Java 侧只能把库里存的主张锚点交过去比对，这也正好守住了「Python 不碰库」那条边界。
 *
 * <p>为什么只带三个字段：判定的依据就是「这个段落还是不是那一版」，
 * 带全文只会让请求体变成几 MB，而判定结果不会因此更准。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiWikiStaleRequestDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private List<AnchorDTO> claims;

    /** 库里存的一条主张锚点。 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnchorDTO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long postId;

        private Integer chunkIndex;

        private String contentHash;
    }
}