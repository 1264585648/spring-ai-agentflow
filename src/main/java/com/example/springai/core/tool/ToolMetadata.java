package com.example.springai.core.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具代码级副作用与元数据声明注解。
 * 在工具类或方法上固定声明其副作用级别（读 / 写 / 高危）、版本号与资源分类，
 * 防止外部配置篡改工具读写属性导致写工具被直接挂载给大模型。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolMetadata {

    /**
     * 工具副作用级别，默认按 Fail-Closed 原则设为 WRITE。
     */
    SideEffectLevel sideEffect() default SideEffectLevel.WRITE;

    /**
     * 工具契约版本号。
     */
    String version() default "v1";

    /**
     * 目标资源类型标识（如 SERVICE_LOG, DATABASE_DIAGNOSE, USER_ACCOUNT, OPS_RUNTIME）。
     */
    String resourceType() default "GENERAL";
}
