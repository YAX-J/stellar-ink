package com.stellarink.ai.corpus.service.impl;

import com.stellarink.ai.corpus.mapper.AiContentSnapshotMapper;
import com.stellarink.ai.corpus.pojo.AiContentSnapshot;
import com.stellarink.contentclient.client.ContentCorpusClient;
import com.stellarink.sharedmodel.enums.CorpusKind;
import com.stellarink.sharedmodel.response.Response;
import com.stellarink.sharedmodel.vo.corpus.CorpusItemVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusSliceVO;
import com.stellarink.sharedmodel.vo.corpus.CorpusSyncResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语料投影同步的四条语义。
 *
 * <p>最要紧的一条是**删除侧**：上游不再返回的文档（下架 / 已删 / **笔记转私有**）
 * 必须从投影表消失 —— 漏删的后果是私有内容继续被问答引用，属于不能接受的一类。
 * 另一条是**失败侧**：拉取失败时一行都不能删（否则一次网络抖动就清空知识库）。
 */
class CorpusSyncServiceImplTest {

    private ContentCorpusClient client;
    private AiContentSnapshotMapper mapper;
    private CorpusSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(ContentCorpusClient.class);
        mapper = Mockito.mock(AiContentSnapshotMapper.class);
        service = new CorpusSyncServiceImpl(client, mapper);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    @DisplayName("首次同步：上游的每一条都插入")
    void insertsEverythingOnFirstSync() {
        mockUpstream(List.of(item(CorpusKind.POST, 1L, "文章一", "hash-1"),
                item(CorpusKind.NOTE, 2L, "笔记二", "hash-2")));
        when(mapper.selectCount(any())).thenReturn(2L);

        CorpusSyncResultVO result = service.sync();

        assertThat(result.isFailed()).isFalse();
        assertThat(result.getUpstream()).isEqualTo(2);
        assertThat(result.getInserted()).isEqualTo(2);
        assertThat(result.getUpdated()).isZero();
        assertThat(result.getRemoved()).isZero();
        assertThat(result.getTotal()).isEqualTo(2);

        ArgumentCaptor<AiContentSnapshot> captor = ArgumentCaptor.forClass(AiContentSnapshot.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(AiContentSnapshot::getKind)
                .containsExactlyInAnyOrder("post", "note");
        // 文章与笔记 id 各自自增：kind + contentId 才是文档标识
        assertThat(captor.getAllValues()).extracting(AiContentSnapshot::getContentId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("哈希变了才更新；没变的行原样保留（不把「没变」标成「刚同步」）")
    void updatesOnlyChangedRows() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row(10L, "post", 1L, "hash-1"),
                row(11L, "note", 2L, "hash-old")));
        mockUpstream(List.of(item(CorpusKind.POST, 1L, "文章一", "hash-1"),
                item(CorpusKind.NOTE, 2L, "笔记二", "hash-new")));

        CorpusSyncResultVO result = service.sync();

        assertThat(result.getUnchanged()).isEqualTo(1);
        assertThat(result.getUpdated()).isEqualTo(1);
        verify(mapper, never()).insert(any(AiContentSnapshot.class));
        ArgumentCaptor<AiContentSnapshot> captor = ArgumentCaptor.forClass(AiContentSnapshot.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(11L);
        assertThat(captor.getValue().getDocHash()).isEqualTo("hash-new");
    }

    @Test
    @DisplayName("上游不再返回的行必须删除（下架 / 已删 / 笔记转私有都走这条路）")
    void deletesRowsMissingUpstream() {
        when(mapper.selectList(any())).thenReturn(List.of(
                row(10L, "post", 1L, "hash-1"),
                row(11L, "note", 2L, "hash-2"),
                row(12L, "note", 3L, "hash-3")));
        // 只剩文章 1：笔记 2 被删、笔记 3 转成了私有
        mockUpstream(List.of(item(CorpusKind.POST, 1L, "文章一", "hash-1")));

        CorpusSyncResultVO result = service.sync();

        assertThat(result.getRemoved()).isEqualTo(2);
        assertThat(result.getUnchanged()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(mapper).deleteByIds(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(11L, 12L);
    }

    @Test
    @DisplayName("拉取失败：一行都不删、不写，只如实回报失败原因")
    void neverWipesWhenUpstreamFails() {
        when(mapper.selectCount(any())).thenReturn(3L);
        when(client.slice(isNull(), isNull(), anyInt()))
                .thenThrow(new IllegalStateException("connect timed out"));

        CorpusSyncResultVO result = service.sync();

        assertThat(result.isFailed()).isTrue();
        assertThat(result.getReason()).contains("connect timed out");
        assertThat(result.getTotal()).isEqualTo(3);
        verify(mapper, never()).deleteByIds(any());
        verify(mapper, never()).insert(any(AiContentSnapshot.class));
        verify(mapper, never()).updateById(any(AiContentSnapshot.class));
    }

    @Test
    @DisplayName("清单分页：truncated 为真时用 maxUpdatedAt 续拉，直到拿到全部")
    void followsCursorUntilComplete() {
        CorpusSliceVO first = slice(List.of(item(CorpusKind.POST, 1L, "一", "h1")),
                true, LocalDateTime.of(2026, 10, 4, 10, 0));
        CorpusSliceVO second = slice(List.of(item(CorpusKind.POST, 2L, "二", "h2")), false, null);
        when(client.slice(isNull(), isNull(), anyInt())).thenReturn(Response.success(first));
        when(client.slice(LocalDateTime.of(2026, 10, 4, 10, 0), null, 1000))
                .thenReturn(Response.success(second));
        when(mapper.selectCount(any())).thenReturn(2L);

        CorpusSyncResultVO result = service.sync();

        assertThat(result.getUpstream()).isEqualTo(2);
        assertThat(result.getInserted()).isEqualTo(2);
    }

    @Test
    @DisplayName("游标不推进时提前结束，不死循环（上游有 bug 也不能把服务挂住）")
    void stopsWhenCursorDoesNotAdvance() {
        LocalDateTime stuck = LocalDateTime.of(2026, 10, 4, 10, 0);
        when(client.slice(isNull(), isNull(), anyInt()))
                .thenReturn(Response.success(slice(List.of(item(CorpusKind.POST, 1L, "一", "h1")), true, stuck)));
        when(client.slice(stuck, null, 1000))
                .thenReturn(Response.success(slice(List.of(item(CorpusKind.POST, 1L, "一", "h1")), true, stuck)));

        CorpusSyncResultVO result = service.sync();

        // 只应拉两页就停（第一页 + 游标不动的那一页），而不是 MAX_PAGES 页
        verify(client, times(2)).slice(any(), any(), anyInt());
        assertThat(result.isFailed()).isFalse();
    }

    @Test
    @DisplayName("上游为空 = 真的没有公开内容：删除全部（隐私优先于可恢复成本）")
    void emptyUpstreamRemovesEverything() {
        when(mapper.selectList(any())).thenReturn(List.of(row(10L, "post", 1L, "h1")));
        when(client.slice(isNull(), isNull(), anyInt()))
                .thenReturn(Response.success(slice(List.of(), false, null)));

        CorpusSyncResultVO result = service.sync();

        assertThat(result.getRemoved()).isEqualTo(1);
        verify(mapper).deleteByIds(any());
    }

    private void mockUpstream(List<CorpusItemVO> items) {
        when(client.slice(isNull(), isNull(), anyInt()))
                .thenReturn(Response.success(slice(items, false, null)));
    }

    private CorpusSliceVO slice(List<CorpusItemVO> items, boolean truncated, LocalDateTime maxUpdatedAt) {
        CorpusSliceVO slice = new CorpusSliceVO();
        slice.setItems(new ArrayList<>(items));
        slice.setTruncated(truncated);
        slice.setLimit(1000);
        slice.setMaxUpdatedAt(maxUpdatedAt);
        return slice;
    }

    private CorpusItemVO item(CorpusKind kind, Long id, String title, String docHash) {
        CorpusItemVO item = new CorpusItemVO();
        item.setKind(kind);
        item.setId(id);
        item.setTitle(title);
        item.setDocHash(docHash);
        item.setUpdatedAt(LocalDateTime.of(2026, 10, 4, 10, 0));
        return item;
    }

    private AiContentSnapshot row(Long id, String kind, Long contentId, String docHash) {
        AiContentSnapshot row = new AiContentSnapshot();
        row.setId(id);
        row.setKind(kind);
        row.setContentId(contentId);
        row.setTitle("标题");
        row.setDocHash(docHash);
        row.setWordCount(0);
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 4, 10, 0));
        row.setSyncedAt(LocalDateTime.of(2026, 10, 4, 10, 0));
        return row;
    }
}
