package org.cn.liuwt.llmwiki.facade.model;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {
    private List<T> items;
    private long total;
    private long page;
    private long size;
    private long totalPages;
}