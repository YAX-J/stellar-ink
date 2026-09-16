package com.stellarink.content.cache;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 只缓存分页响应真正需要的字段，避免把 MyBatis-Plus 的运行时分页配置写入 Redis。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CachedPage<T> {

    private List<T> records;
    private long total;
    private long current;
    private long size;

    public static <T> CachedPage<T> from(IPage<T> page) {
        return new CachedPage<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public IPage<T> toPage() {
        Page<T> page = new Page<>(current, size, total);
        page.setRecords(records == null ? List.of() : records);
        return page;
    }
}
