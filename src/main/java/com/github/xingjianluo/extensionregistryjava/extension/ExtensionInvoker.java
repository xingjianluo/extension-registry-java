package com.github.xingjianluo.extensionregistryjava.extension;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 扩展点调用器：按装配顺序依次调用某扩展点上的可用扩展。
 *
 * <p>对调用方的结果承诺：
 * <ul>
 *   <li>被禁用的扩展不执行，返回 {@code enabled=false, value=null, error=null}，
 *       结果稳定可预期，不产生任何惰性副作用；</li>
 *   <li>初始化失败的扩展不执行，状态为 {@link ExtensionState#FAILED} 并带回失败原因；</li>
 *   <li>单个扩展 {@code execute()} 抛错被隔离在该条结果内（{@code error} 字段），
 *       不影响后续扩展执行，也不会向上抛出；</li>
 *   <li>调用开始时获取一次快照，调用期间发生注册或开关切换不影响本次结果。</li>
 * </ul>
 */
public final class ExtensionInvoker {

    private static final Logger log = LoggerFactory.getLogger(ExtensionInvoker.class);

    private ExtensionInvoker() {
    }

    /**
     * 按装配顺序调用扩展点 {@code point} 上的全部扩展。
     *
     * @param registry 扩展注册表
     * @param point    扩展点标识
     * @param input    入参
     * @return 与该扩展点上扩展一一对应的调用结果（装配顺序），永不为 null
     */
    public static <I, O> List<ExtensionResult<O>> invokeAll(ExtensionRegistry registry,
                                                            String point, I input) {
        // 一次取同一份不可变快照上的“描述 + 实例”绑定，保证整次调用视图一致；
        // 调用期间发生注册、动态增删或开关切换都不影响本次结果，也不会读到中间视图。
        List<ExtensionRegistry.ResolvedExtension> resolved = registry.resolveExtensions(point);
        List<ExtensionResult<O>> results = new ArrayList<>(resolved.size());

        for (ExtensionRegistry.ResolvedExtension re : resolved) {
            ExtensionDescriptor d = re.descriptor();
            if (d.state() == ExtensionState.FAILED) {
                log.debug("跳过初始化失败扩展: id='{}'", d.id());
                results.add(new ExtensionResult<>(d.id(), ExtensionState.FAILED, false, null, d.failure()));
                continue;
            }
            if (!d.enabled()) {
                log.debug("跳过已禁用扩展: id='{}'", d.id());
                results.add(new ExtensionResult<>(d.id(), ExtensionState.INITIALIZED, false, null, null));
                continue;
            }
            @SuppressWarnings("unchecked")
            Extension<I, O> extension = (Extension<I, O>) re.extension();
            O value = null;
            Throwable error = null;
            try {
                value = extension.execute(input);
            } catch (Throwable t) {
                // 故障隔离：单个扩展执行失败不影响其它扩展与调用方。
                log.error("扩展执行失败（已隔离）: id='{}' reason={}", d.id(), t.toString());
                error = t;
            }
            results.add(new ExtensionResult<>(d.id(), ExtensionState.INITIALIZED, true, value, error));
        }
        return results;
    }

    /**
     * 仅返回成功执行扩展的输出，顺序即装配顺序；禁用、失败、执行异常的扩展被跳过。
     */
    public static <I, O> List<O> invokeEnabled(ExtensionRegistry registry, String point, I input) {
        List<ExtensionResult<O>> all = invokeAll(registry, point, input);
        List<O> values = new ArrayList<>(all.size());
        for (ExtensionResult<O> r : all) {
            if (r.succeeded()) {
                values.add(r.value());
            }
        }
        return values;
    }
}
