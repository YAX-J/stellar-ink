package com.stellarink.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整篇内容哈希（语料快照的变更判据）。
 *
 * <p>三条断言对应三件会被误判的事：换行符不同不该算「变了」、内容变了必须算「变了」、
 * 以及它与段落哈希的<b>用途区别</b>（后者由 Python 算，这里只确认自己的输出是标准 SHA-256）。
 */
class HashesTest {

    @Test
    @DisplayName("同一篇内容：换行符与首尾空白不同，哈希必须相同")
    void normalizationKeepsTheHashStable() {
        String lf = "标题\n第一段\n第二段";
        String crlf = "标题\r\n第一段\r\n第二段";
        String padded = "  标题\r\n第一段\r\n第二段  \n";

        assertThat(Hashes.docHash("标题", lf))
                .isEqualTo(Hashes.docHash("标题", crlf))
                .isEqualTo(Hashes.docHash(" 标题 ", padded));
    }

    @Test
    @DisplayName("内容或标题变了，哈希必须变（否则对账会漏掉改动）")
    void contentOrTitleChangesTheHash() {
        String base = Hashes.docHash("标题", "正文");

        assertThat(Hashes.docHash("标题", "正文改了一个字")).isNotEqualTo(base);
        assertThat(Hashes.docHash("新标题", "正文")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("输出是标准的小写十六进制 SHA-256（64 位）")
    void outputShapeIsStable() {
        // 与 `printf '%s' 'abc' | sha256sum` 的已知值对齐：跨语言可核对
        assertThat(Hashes.sha256Hex("abc")).isEqualTo(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Hashes.docHash(null, null)).hasSize(64);
        assertThat(Hashes.sha256Hex("abc")).matches("[0-9a-f]{64}");
    }
}
