package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 扩展实现的声明式描述：唯一标识、装配顺序与依赖关系。
 *
 * @param id        同一扩展点下唯一的扩展标识
 * @param order     装配次序提示，数值小者优先；依赖关系优先于 order
 * @param dependsOn 本扩展依赖的其它扩展标识，依赖项必须先于本扩展装配
 */
public record ExtensionDescriptor(String id, int order, Set<String> dependsOn) {

    public ExtensionDescriptor {
        Objects.requireNonNull(id, "extension id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("extension id must not be blank");
        }
        dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        if (dependsOn.contains(id)) {
            throw new IllegalArgumentException("extension '" + id + "' must not depend on itself");
        }
    }

    public static ExtensionDescriptor of(String id, int order, String... dependsOn) {
        return new ExtensionDescriptor(id, order, dependsOn == null ? Set.of() : Set.of(dependsOn));
    }

    @Override
    public String toString() {
        return "ExtensionDescriptor{id='%s', order=%d, dependsOn=%s}".formatted(id, order, List.copyOf(dependsOn));
    }
}
