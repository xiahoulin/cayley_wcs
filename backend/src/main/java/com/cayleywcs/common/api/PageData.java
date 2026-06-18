package com.cayleywcs.common.api;

import java.util.List;

public record PageData<T>(
        List<T> rows,
        long totals
) {
}
