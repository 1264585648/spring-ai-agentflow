package com.example.springai.core.tool;

import java.lang.reflect.Method;

/**
 * 一条已登记的 @Tool 调用目标。Bean 在真正调用时再取出，避免启动扫描形成循环依赖。
 */
public record ToolInvocation(
        String beanName,
        Method method,
        String[] parameterNames,
        ToolDescriptor descriptor
) {
    public ToolInvocation(String beanName, Method method, String[] parameterNames) {
        this(
                beanName,
                method,
                parameterNames,
                ToolDescriptor.fromMethod(
                        beanName,
                        method != null ? method.getDeclaringClass() : Object.class,
                        method,
                        true
                )
        );
    }

    public SideEffectLevel sideEffectLevel() {
        return descriptor != null ? descriptor.sideEffectLevel() : SideEffectLevel.READ;
    }
}
