package com.example.springai.core.tool;

import java.lang.reflect.Method;

/**
 * 一条已登记的 @Tool 调用目标。Bean 在真正调用时再取出，避免启动扫描形成循环依赖。
 */
public record ToolInvocation(String beanName, Method method, String[] parameterNames) {
}
