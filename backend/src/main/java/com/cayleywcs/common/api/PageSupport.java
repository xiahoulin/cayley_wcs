package com.cayleywcs.common.api;

import java.util.List;

/**
 * 与 CayleyWMS 一致：MyBatis-Plus 未启用分页插件，业务侧做内存切片分页。
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
}
