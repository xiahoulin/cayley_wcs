package com.cayleywcs.common.api;

import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * 小型配置表可继续内存分页；历史大表使用数据库分页。
 */
public final class PageSupport {

    private PageSupport() {
    }

    public static <T> PageData<T> slice(List<T> all, PageSearch pageSearch) {
        int pageIndex = Math.max(pageSearch.getPageIndex(), 1);
        int pageSize = Math.max(pageSearch.getPageSize(), 1);
        int fromIndex = Math.min((pageIndex - 1) * pageSize, all.size());
        int toIndex = Math.min(fromIndex + pageSize, all.size());
        return new PageData<>(all.subList(fromIndex, toIndex), all.size());
    }

    public record Window(long offset, int size) {
    }

    public static <T> PageData<T> database(PageSearch pageSearch, LongSupplier count,
                                            Function<Window, List<T>> fetch) {
        int pageIndex = Math.max(pageSearch.getPageIndex(), 1);
        int pageSize = Math.max(pageSearch.getPageSize(), 1);
        long offset = ((long) pageIndex - 1L) * pageSize;
        long total = count.getAsLong();
        return new PageData<>(offset >= total ? List.of() : fetch.apply(new Window(offset, pageSize)), total);
    }
}
