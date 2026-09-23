package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.List;

/**
 * 一次装配的完整报告（骨架）。
 */
public final class AssemblyReport {

    private final List<AssemblyRecord> records;

    public AssemblyReport(List<AssemblyRecord> records) {
        this.records = List.copyOf(records);
    }

    public List<AssemblyRecord> records() {
        return records;
    }

    @Override
    public String toString() {
        return "AssemblyReport" + records;
    }
}
