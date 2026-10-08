package com.example.springai.pipeline.dispatcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Iterator;
import java.util.Map;

/**
 * L1 Tool 反射调度器 (L1ToolDispatcher)
 * 核心职责:
 * 1. 动态反射执行已注册到 Spring 容器的各种 @Tool 组件；
 * 2. 自动完成 JSON 参数与 Java 方法入参的类型绑定与反序列化；
 * 3. 彻底实现“一次开发 Tool，双向无缝复用”（LLM Function Calling + L1 命令直通），零胶水适配代码。
 */
@Component
public class L1ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(L1ToolDispatcher.class);

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public L1ToolDispatcher(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 调度执行指定的 Tool
     *
     * @param targetRef  目标标识，格式为 "beanName.methodName"，例如 "userAccountTool.queryBalance"
     * @param jsonParams 提取出来的结构化 JSON 参数字符串，例如 "{\"userId\": \"10001\"}"
     * @return 执行结果字符串 (直接供 SSE 输出或后续消费)
     */
    public String dispatch(String targetRef, String jsonParams) {
        if (targetRef == null || !targetRef.contains(".")) {
            throw new IllegalArgumentException("非法的 targetRef 格式，期望为 beanName.methodName，当前为: " + targetRef);
        }

        String[] parts = targetRef.trim().split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("targetRef 只能包含单个点分隔符 (bean.method): " + targetRef);
        }

        String beanName = parts[0];
        String methodName = parts[1];

        Object beanInstance;
        try {
            beanInstance = applicationContext.getBean(beanName);
        } catch (Exception e) {
            log.error("[L1ToolDispatcher] 未能在 Spring 容器中找到 Bean: {}", beanName);
            throw new IllegalStateException("目标 Tool Bean 不存在: " + beanName, e);
        }

        // 查找目标方法
        Method targetMethod = findMatchingMethod(beanInstance.getClass(), methodName);
        if (targetMethod == null) {
            log.error("[L1ToolDispatcher] 类 {} 中不存在方法: {}", beanInstance.getClass().getSimpleName(), methodName);
            throw new NoSuchMethodError("未找到目标方法: " + methodName + " 在 " + beanInstance.getClass().getName());
        }

        try {
            Object[] args = resolveArguments(targetMethod, jsonParams);
            targetMethod.setAccessible(true);
            Object result = targetMethod.invoke(beanInstance, args);

            if (result == null) {
                return "";
            }
            if (result instanceof String) {
                return (String) result;
            }
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.error("[L1ToolDispatcher] 反射执行 Tool 异常 targetRef={}: {}", targetRef, e.getMessage(), e);
            throw new RuntimeException("执行工具方法失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查找方法（同名首个方法）
     */
    private Method findMatchingMethod(Class<?> clazz, String methodName) {
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 根据方法形参解析并绑定 JSON 实参
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

            // 若单参数方法且名字不匹配，尝试取 JSON 根节点的第一个字段值
            if (targetNode == null && parameters.length == 1 && rootNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = rootNode.fields();
                if (fields.hasNext()) {
                    targetNode = fields.next().getValue();
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
                // 如果是复合对象类型入参，尝试将整个 JSON 映射为该 DTO
                resolvedArgs[i] = objectMapper.treeToValue(rootNode, paramType);
            }
        }

        return resolvedArgs;
    }
}
