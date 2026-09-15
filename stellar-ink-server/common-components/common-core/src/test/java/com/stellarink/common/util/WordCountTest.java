package com.stellarink.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 字数口径测试：中文按字、英文按字符、Markdown 标记不计入。
 * 这些用例同时是前端 utils/wordCount.js 的对齐基准。
 */
class WordCountTest {

    @Test
    @DisplayName("空内容与纯空白都算 0 字")
    void emptyContent() {
        assertEquals(0, WordCount.count(null));
        assertEquals(0, WordCount.count(""));
        assertEquals(0, WordCount.count("   \n\t  "));
    }

    @Test
    @DisplayName("中文逐字计，空格与换行不计")
    void chineseText() {
        /* 把今天的三个念头捡进同一颗星星里。 → 16 字 + 句号 = 17 */
        assertEquals(17, WordCount.count("把今天的三个念头捡进同一颗星星里。"));
        assertEquals(17, WordCount.count("把今天的三个念头\n捡进同一颗星星里。"));
        assertEquals(17, WordCount.count("把今天的三个念头 捡进同一颗星星里。"));
    }

    @Test
    @DisplayName("标题井号、引用符、列表符号都不算字")
    void markdownStructure() {
        /* 现象 = 2 —— 井号与空格被剥掉 */
        assertEquals(2, WordCount.count("## 现象"));
        /* 现象(2) + 网关返回(4) + 503(3) + 中文句号(1) = 10。
         * 中文句号不在标记符号集内，照常计入；ASCII 的「503.」才会被当成列表序号。 */
        assertEquals(10, WordCount.count("## 现象\n\n网关返回 503。"));
        /* 行首数字 + 句点 + 空格按列表序号处理，只留「有序列」= 3 */
        assertEquals(3, WordCount.count("1. 有序列"));
        /* 第一项 = 3 */
        assertEquals(3, WordCount.count("- 第一项"));
        /* 引用的内容 = 5 */
        assertEquals(5, WordCount.count("> 引用的内容"));
        /* 有序列表项 = 5 */
        assertEquals(5, WordCount.count("1. 有序列表项"));
    }

    @Test
    @DisplayName("强调符号与行内代码反引号不算字")
    void markdownMarks() {
        /* 加粗文字 = 4 */
        assertEquals(4, WordCount.count("**加粗**文字"));
        /* code文字 = 4 + 2 = 6 */
        assertEquals(6, WordCount.count("`code`文字"));
    }

    @Test
    @DisplayName("围栏标记不算字，代码本身算字")
    void codeFence() {
        /* 围栏行整体去掉，只剩 code = 4 */
        assertEquals(4, WordCount.count("```\ncode\n```"));
        /* code / curl / -s / url = 4 + 4 + 2 + 3 */
        assertEquals(13, WordCount.count("```bash\ncode\ncurl -s url\n```"));
    }

    @Test
    @DisplayName("链接只算显示文字，URL 不算字")
    void markdownLink() {
        assertEquals(2, WordCount.count("[星笺](https://example.com/a/b)"));
    }
}
