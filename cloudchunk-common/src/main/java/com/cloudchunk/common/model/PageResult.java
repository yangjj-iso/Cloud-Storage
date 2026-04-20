package com.cloudchunk.common.model;

import java.util.Collections;
import java.util.List;

public class PageResult<T> {

    private long total;
    private long page;
    private long size;
    private List<T> records;

    public PageResult() {}

    public PageResult(long total, long page, long size, List<T> records) {
        this.total = total;
        this.page = page;
        this.size = size;
        this.records = records == null ? Collections.emptyList() : records;
    }

    public static <T> PageResult<T> empty(long page, long size) {
        return new PageResult<>(0, page, size, Collections.emptyList());
    }

    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public long getPage() { return page; }
    public void setPage(long page) { this.page = page; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public List<T> getRecords() { return records; }
    public void setRecords(List<T> records) { this.records = records; }
}
