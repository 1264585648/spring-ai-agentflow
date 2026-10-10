package com.example.springai.core.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.StandardReflectionParameterNameDiscoverer;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * L1 Tool 调度器。
 * 只执行 {@link L1ToolCatalog} 中登记的 @Tool 方法，并按 JSON 绑定方法参数。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class L1ToolDispatcher {

    private final L1ToolCatalog toolCatalog;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new StandardReflectionParameterNameDiscoverer();

    /**
     * 调度执行指定的 Tool。
     *
     * @param targetRef  目标标识，格式为 "beanName.methodName"，例如 "userAccountTool.queryBalance"
     * @param jsonParams 提取出来的结构化 JSON 参数字符串，例如 "{\"userId\": \"10001\"}"
     * @return 执行结果字符串
     */
    public String dispatch(String targetRef, String jsonParams) {
        if (targetRef == null || !targetRef.contains(".")) {
            throw new IllegalArgumentException("非法的 targetRef 格式，期望为 beanName.methodName，当前为: " + targetRef);
        }

        String[] parts = targetRef.trim().split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("targetRef 只能包含单个点分隔符 (bean.method): " + targetRef);
        }

        ToolInvocation invocation = toolCatalog.require(targetRef.trim(), jsonParams);
        Object bean = toolCatalog.resolveBean(invocation);
        Method targetMethod = AopUtils.selectInvocableMethod(invocation.method(), bean.getClass());

        try {
            Object[] args = resolveArguments(targetMethod, jsonParams);
            Object result = targetMethod.invoke(bean, args);

            if (result == null) {
                return "";
            }
            if (result instanceof String text) {
                return text;
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("[L1ToolDispatcher] 执行 Tool 异常 targetRef={}: {}", targetRef, e.getMessage(), e);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("执行工具方法失败: " + cause.getMessage(), e);
        }
    }

    /**
     * 根据方法形参解析并绑定 JSON 实参。
     */
    private Object[] resolveArguments(Method method, String jsonParams) throws Exception {
        Parameter[] parameters = method.getParameters();
        if (parameters.length == 0) {
            return new Object[0];
        }

        if (jsonParams == null || jsonParams.trim().isEmpty()) {
            return new Object[parameters.length];
        }

        JsonNode rootNode = objectMapper.readTree(jsonParams.trim());
        String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
        Object[] resolvedArgs = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> paramType = param.getType();
            String name = (paramNames != null && paramNames.length > i) ? paramNames[i] : param.getName();

            JsonNode targetNode = rootNode.has(name) ? rootNode.get(name) : null;

            if (targetNode == null && parameters.length == 1 && rootNode.isObject()) {
                var properties = rootNode.properties().iterator();
                if (properties.hasNext()) {
                    targetNode = properties.next().getValue();
                }
            }

            if (targetNode != null && !targetNode.isNull()) {
                if (paramType.equals(String.class)) {
                    resolvedArgs[i] = targetNode.isTextual() ? targetNode.asText() : targetNode.toString();
                } else if (paramType.equals(Integer.class) || paramType.equals(int.class)) {
                    resolvedArgs[i] = targetNode.asInt();
                } else if (paramType.equals(Long.class) || paramType.equals(long.class)) {
                    resolvedArgs[i] = targetNode.asLong();
                } else if (paramType.equals(Boolean.class) || paramType.equals(boolean.class)) {
                    resolvedArgs[i] = targetNode.asBoolean();
                } else {
                    resolvedArgs[i] = objectMapper.treeToValue(targetNode, paramType);
                }
            } else if (parameters.length == 1 && !rootNode.isNull() && !paramType.isPrimitive() && !paramType.equals(String.class)) {
                resolvedArgs[i] = objectMapper.treeToValue(rootNode, paramType);
            }
        }

        return resolvedArgs;
    }
}
