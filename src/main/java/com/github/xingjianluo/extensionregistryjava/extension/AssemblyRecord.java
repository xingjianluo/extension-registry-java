package com.github.xingjianluo.extensionregistryjava.extension;

/**
 * 单条扩展的装配判定记录（骨架）。
 */
public record AssemblyRecord(int index, String id, String point, int declaredOrder,
                             java.util.List<String> dependencies, ExtensionState state,
                             String reason) {
}
