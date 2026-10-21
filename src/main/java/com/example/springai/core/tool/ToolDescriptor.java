package com.example.springai.core.tool;

import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * 工具注册期不可变描述符（含副作用分级、版本号与资源类型）。
 */
public record ToolDescriptor(
        String beanName,
        String methodName,
        String toolRef,
        SideEffectLevel sideEffectLevel,
        String version,
        String resourceType
) {
    public ToolDescriptor {
        beanName = beanName == null ? "" : beanName.trim();
        methodName = methodName == null ? "" : methodName.trim();
        toolRef = (toolRef == null || toolRef.isBlank())
                ? (methodName.isEmpty() ? beanName : beanName + "." + methodName)
                : toolRef.trim();
        sideEffectLevel = sideEffectLevel == null ? SideEffectLevel.WRITE : sideEffectLevel;
        version = (version == null || version.isBlank()) ? "v1" : version.trim();
        resourceType = (resourceType == null || resourceType.isBlank()) ? "GENERAL" : resourceType.trim();
    }

    public boolean isReadOnly() {
        return sideEffectLevel == SideEffectLevel.READ;
    }

    /**
     * 从工具 Bean 类与方法解析工具描述符。
     * 优先读取方法级 {@link ToolMetadata}，其次读取类级 {@link ToolMetadata}（兼容 Spring AOP 代理类）。
     */
    public static ToolDescriptor fromMethod(String beanName, Class<?> targetClass, Method method, boolean hasSpringAiToolAnnotation) {
        Class<?> userClass = targetClass != null ? ClassUtils.getUserClass(targetClass) : null;
        String effectiveBeanName = (beanName == null || beanName.isBlank())
                ? (userClass != null ? Introspector.decapitalize(userClass.getSimpleName()) : "")
                : beanName.trim();
        String methodName = method != null ? method.getName() : "";
        String toolRef = methodName.isEmpty() ? effectiveBeanName : effectiveBeanName + "." + methodName;

        ToolMetadata methodMeta = method != null ? AnnotationUtils.findAnnotation(method, ToolMetadata.class) : null;
        ToolMetadata classMeta = userClass != null ? AnnotationUtils.findAnnotation(userClass, ToolMetadata.class) : null;
        ToolMetadata effectiveMeta = methodMeta != null ? methodMeta : classMeta;

        if (effectiveMeta != null) {
            return new ToolDescriptor(
                    effectiveBeanName,
                    methodName,
                    toolRef,
                    effectiveMeta.sideEffect(),
                    effectiveMeta.version(),
                    effectiveMeta.resourceType()
            );
        }

        // 未标注 @ToolMetadata 时：仅当显式带有 @Tool（兼容无注解测试夹具）才视为 READ，其余一律 Fail-Closed 视为 WRITE
        SideEffectLevel fallbackLevel = hasSpringAiToolAnnotation ? SideEffectLevel.READ : SideEffectLevel.WRITE;
        return new ToolDescriptor(effectiveBeanName, methodName, toolRef, fallbackLevel, "v1", "GENERAL");
    }

    /**
     * 从候选工具 Bean 实例解析类级别描述符（供 AgentChatClientFactory 校验候选工具对象）。
     * 若类中任一公开方法声明了 WRITE / CRITICAL 级别的 {@link ToolMetadata}，按最严格级别收敛（Fail-Closed）。
     */
    public static ToolDescriptor fromBeanInstance(Object toolBean) {
        if (toolBean == null) {
            throw new IllegalArgumentException("toolBean 不能为空");
        }
        Class<?> userClass = ClassUtils.getUserClass(toolBean.getClass());
        String simpleName = userClass.getSimpleName();
        String beanName = Introspector.decapitalize(simpleName);
        ToolMetadata classMeta = AnnotationUtils.findAnnotation(userClass, ToolMetadata.class);

        SideEffectLevel StrictestMethodLevel = null;
        ToolMetadata strictestMethodMeta = null;
        for (Method m : userClass.getMethods()) {
            if (m.isBridge() || m.isSynthetic() || !Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            ToolMetadata mMeta = AnnotationUtils.findAnnotation(m, ToolMetadata.class);
            if (mMeta != null && (StrictestMethodLevel == null || mMeta.sideEffect().ordinal() > StrictestMethodLevel.ordinal())) {
                StrictestMethodLevel = mMeta.sideEffect();
                strictestMethodMeta = mMeta;
            }
        }

        if (StrictestMethodLevel != null && StrictestMethodLevel.requiresApproval()) {
            return new ToolDescriptor(
                    beanName,
                    "",
                    beanName,
                    StrictestMethodLevel,
                    strictestMethodMeta.version(),
                    strictestMethodMeta.resourceType()
            );
        }

        if (classMeta != null) {
            return new ToolDescriptor(beanName, "", beanName,
                    classMeta.sideEffect(), classMeta.version(), classMeta.resourceType());
        }

        if (strictestMethodMeta != null) {
            return new ToolDescriptor(beanName, "", beanName,
                    strictestMethodMeta.sideEffect(), strictestMethodMeta.version(), strictestMethodMeta.resourceType());
        }

        // 未声明 @ToolMetadata 的未知对象，按 Fail-Closed 原则视为 WRITE（不允许挂给 ChatClient）
        return new ToolDescriptor(beanName, "", beanName, SideEffectLevel.WRITE, "v1", "UNKNOWN");
    }
}
