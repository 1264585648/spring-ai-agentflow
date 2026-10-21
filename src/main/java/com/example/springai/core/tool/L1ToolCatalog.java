package com.example.springai.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.StandardReflectionParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已注册工具目录（含副作用安全分级元数据 ToolDescriptor）。
 * L1 直通只能调用已注册且副作用级别为 {@link SideEffectLevel#READ} 的 @Tool 方法，
 * 写操作或高危工具（WRITE / CRITICAL）严禁进入 L1 直通可调用池。
 */
@Component
@Slf4j
public class L1ToolCatalog {

    private final ApplicationContext applicationContext;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new StandardReflectionParameterNameDiscoverer();

    private volatile Map<String, List<ToolInvocation>> tools = Map.of();
    private volatile Map<String, ToolDescriptor> descriptors = Map.of();

    public L1ToolCatalog(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        if (!(applicationContext instanceof ConfigurableApplicationContext configurable)) {
            throw new IllegalStateException("L1ToolCatalog 需要 ConfigurableApplicationContext");
        }
        ConfigurableListableBeanFactory factory = configurable.getBeanFactory();
        Map<String, List<ToolInvocation>> found = new HashMap<>();
        Map<String, ToolDescriptor> foundDescriptors = new HashMap<>();

        for (String beanName : factory.getBeanDefinitionNames()) {
            Class<?> type;
            try {
                type = factory.getType(beanName);
            } catch (Exception ex) {
                log.debug("[L1ToolCatalog] 跳过无法解析类型的 Bean {}: {}", beanName, ex.getMessage());
                continue;
            }
            if (type == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(type);
            if (!declaresToolOrMetadata(userClass)) {
                continue;
            }
            registerType(found, foundDescriptors, beanName, userClass);
        }

        Map<String, List<ToolInvocation>> frozen = new HashMap<>();
        int methodCount = 0;
        for (Map.Entry<String, List<ToolInvocation>> entry : found.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
            methodCount += entry.getValue().size();
        }
        this.tools = Map.copyOf(frozen);
        this.descriptors = Map.copyOf(foundDescriptors);
        log.info("[L1ToolCatalog] 已登记只读 @Tool 目标 {} 个（方法 {} 个），总工具描述符 {} 个",
                this.tools.size(), methodCount, this.descriptors.size());
    }

    public Object resolveBean(ToolInvocation invocation) {
        return applicationContext.getBean(invocation.beanName());
    }

    public boolean isRegistered(String targetRef) {
        if (targetRef == null || targetRef.isBlank()) {
            return false;
        }
        return tools.containsKey(targetRef.trim());
    }

    public Optional<ToolDescriptor> findDescriptor(String targetRef) {
        if (targetRef == null || targetRef.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(descriptors.get(targetRef.trim()));
    }

    /**
     * 按 bean.method 找到唯一可调用的只读 @Tool 方法。同名重载按参数个数和参数名区分。
     */
    public ToolInvocation require(String targetRef, String jsonParams) {
        if (targetRef == null || targetRef.isBlank()) {
            throw new IllegalArgumentException("targetRef 不能为空");
        }
        String key = targetRef.trim();
        ToolDescriptor desc = descriptors.get(key);
        if (desc != null && !desc.isReadOnly()) {
            throw new IllegalArgumentException("targetRef 属于写/高危工具 (" + desc.sideEffectLevel()
                    + ")，不是可直通的只读 @Tool 方法: " + key);
        }
        List<ToolInvocation> candidates = tools.get(key);
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("targetRef 不是已注册的 @Tool 方法: " + key);
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        return selectOverload(key, candidates, jsonParams);
    }

    private void registerType(
            Map<String, List<ToolInvocation>> found,
            Map<String, ToolDescriptor> foundDescriptors,
            String beanName,
            Class<?> targetClass) {
        ToolMetadata classMeta = AnnotationUtils.findAnnotation(targetClass, ToolMetadata.class);
        if (classMeta != null) {
            foundDescriptors.put(beanName, new ToolDescriptor(
                    beanName, "", beanName, classMeta.sideEffect(), classMeta.version(), classMeta.resourceType()));
        }

        for (Method method : targetClass.getMethods()) {
            if (method.isBridge() || method.isSynthetic() || !Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            boolean hasTool = AnnotationUtils.findAnnotation(method, Tool.class) != null;
            boolean hasMeta = AnnotationUtils.findAnnotation(method, ToolMetadata.class) != null || classMeta != null;
            if (!hasTool && !hasMeta) {
                continue;
            }
            String key = beanName + "." + method.getName();
            ToolDescriptor descriptor = ToolDescriptor.fromMethod(beanName, targetClass, method, hasTool);
            foundDescriptors.putIfAbsent(key, descriptor);

            // 只有同时具备 @Tool 注解且副作用分级为 READ 的方法才允许进入 L1 直通可调用表
            if (!hasTool || !descriptor.isReadOnly()) {
                continue;
            }

            String[] names = parameterNameDiscoverer.getParameterNames(method);
            List<ToolInvocation> methods = found.computeIfAbsent(key, ignored -> new ArrayList<>());
            boolean alreadyRegistered = methods.stream()
                    .anyMatch(existing -> Arrays.equals(existing.method().getParameterTypes(), method.getParameterTypes()));
            if (!alreadyRegistered) {
                methods.add(new ToolInvocation(beanName, method, names, descriptor));
            }
        }
    }

    private boolean declaresToolOrMetadata(Class<?> type) {
        if (AnnotationUtils.findAnnotation(type, ToolMetadata.class) != null) {
            return true;
        }
        for (Method method : type.getMethods()) {
            if (AnnotationUtils.findAnnotation(method, Tool.class) != null
                    || AnnotationUtils.findAnnotation(method, ToolMetadata.class) != null) {
                return true;
            }
        }
        return false;
    }

    private ToolInvocation selectOverload(String targetRef, List<ToolInvocation> candidates, String jsonParams) {
        JsonNode root = parseJson(jsonParams);
        int fieldCount = jsonFieldCount(root);
        List<ToolInvocation> byCount = candidates.stream()
                .filter(candidate -> candidate.method().getParameterCount() == fieldCount)
                .toList();
        List<ToolInvocation> pool = byCount.isEmpty() ? candidates : byCount;
        if (pool.size() == 1) {
            return pool.get(0);
        }
        if (root != null && root.isObject()) {
            ToolInvocation best = null;
            int bestScore = -1;
            boolean tie = false;
            for (ToolInvocation candidate : pool) {
                int score = nameScore(candidate, root);
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                    tie = false;
                } else if (score == bestScore) {
                    tie = true;
                }
            }
            if (best != null && !tie && bestScore > 0) {
                return best;
            }
        }
        throw new IllegalArgumentException("targetRef 存在多个 @Tool 重载，无法唯一确定: " + targetRef);
    }

    private int nameScore(ToolInvocation candidate, JsonNode root) {
        String[] names = candidate.parameterNames();
        if (names == null) {
            return 0;
        }
        int score = 0;
        for (String name : names) {
            if (name != null && root.has(name)) {
                score++;
            }
        }
        return score;
    }

    private int jsonFieldCount(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            return 0;
        }
        if (root.isObject()) {
            return root.size();
        }
        return 1;
    }

    private JsonNode parseJson(String jsonParams) {
        if (jsonParams == null || jsonParams.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(jsonParams.trim());
        } catch (Exception ex) {
            return null;
        }
    }
}
