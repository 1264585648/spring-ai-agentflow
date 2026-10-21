package com.example.springai.infra.client;

import com.example.springai.core.agent.AgentDefinition;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.agent.AgentType;
import com.example.springai.core.tool.InvocationContext;
import com.example.springai.core.tool.InvocationSource;
import com.example.springai.core.tool.RevocableInvocationPolicy;
import com.example.springai.core.tool.SideEffectLevel;
import com.example.springai.core.tool.ToolDescriptor;
import com.example.springai.core.tool.UnifiedInvocationGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * 多智能体专用 ChatClient 工厂 (AgentChatClientFactory)
 * 职责:
 * 1. 结合基础 ChatClient.Builder 与 AgentPromptRegistry；
 * 2. 将 AgentDefinition 的系统提示词、模型名称、温度参数与挂载工具白名单转化为真实生效的运行期配置 (F09)；
 * 3. 基于工具注册期的 {@link ToolDescriptor}（{@link SideEffectLevel}）校验只读边界，拒绝写/高危工具挂载；
 * 4. 将挂载的只读 @Tool 包装为受 {@link UnifiedInvocationGateway} 拦截的 ToolCallback，使大模型 Function Calling 统一收敛至同一调用入口。
 */
@Component
@Slf4j
public class AgentChatClientFactory {

    private static final Set<String> ALLOWED_MODEL_PREFIXES = Set.of(
            "gpt-", "o1-", "o3-", "qwen", "deepseek", "claude", "gemini",
            "glm", "llama", "doubao", "ernie", "moonshot", "kimi",
            "step", "yi-", "mistral", "baichuan", "minimax", "hunyuan",
            "spark", "custom-", "mock-", "test-", "local-"
    );

    private static final Set<String> FORBIDDEN_WRITE_TOOL_MARKERS = Set.of(
            "opsactiontool", "executeemergencyaction"
    );

    private static final Set<String> KNOWN_SAFE_TOOL_BEANS = Set.of(
            "logquerytool", "databasediagnosetool", "useraccounttool"
    );

    private final ChatClient.Builder baseChatClientBuilder;
    private final AgentPromptRegistry promptRegistry;
    private final UnifiedInvocationGateway invocationGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentChatClientFactory(ChatClient.Builder baseChatClientBuilder, AgentPromptRegistry promptRegistry) {
        this(baseChatClientBuilder, promptRegistry, new UnifiedInvocationGateway());
    }

    @Autowired
    public AgentChatClientFactory(
            ChatClient.Builder baseChatClientBuilder,
            AgentPromptRegistry promptRegistry,
            @Autowired(required = false) UnifiedInvocationGateway invocationGateway) {
        this.baseChatClientBuilder = baseChatClientBuilder;
        this.promptRegistry = promptRegistry;
        this.invocationGateway = invocationGateway != null ? invocationGateway : new UnifiedInvocationGateway();
    }

    public UnifiedInvocationGateway getInvocationGateway() {
        return invocationGateway;
    }

    public ChatClient createClient(AgentType agentType) {
        return createClient(agentType != null ? agentType.getCode() : null);
    }

    public ChatClient createClient(AgentType agentType, Object... tools) {
        return createClient(agentType != null ? agentType.getCode() : null, tools);
    }

    public ChatClient createClient(String agentCode) {
        return createClient(agentCode, new Object[0]);
    }

    /**
     * 为指定智能体创建专用的 ChatClient 实例，按最小权限与只读副作用分级绑定 Tools，并收敛至 UnifiedInvocationGateway。
     */
    public ChatClient createClient(String agentCode, Object... tools) {
        String systemPrompt = promptRegistry != null
                ? promptRegistry.getSystemPrompt(agentCode)
                : "你是一个企业级智能协同助手，请保持客观、严谨、条理清晰的沟通风格。";

        Optional<AgentDefinition> defOpt = (promptRegistry != null && StringUtils.hasText(agentCode))
                ? promptRegistry.getAgent(agentCode)
                : Optional.empty();
        if (defOpt == null) {
            defOpt = Optional.empty();
        }

        ChatClient.Builder builder = baseChatClientBuilder.clone();
        ChatClient.Builder sysBuilder = builder.defaultSystem(systemPrompt);
        if (sysBuilder != null) {
            builder = sysBuilder;
        }

        // 1. 解析并应用模型与采样参数 (F09)
        if (defOpt.isPresent()) {
            AgentDefinition def = defOpt.get();
            ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder();
            boolean hasCustomOptions = false;

            if (StringUtils.hasText(def.getModelName())) {
                String modelName = def.getModelName().trim();
                validateModelName(modelName);
                optionsBuilder.model(modelName);
                hasCustomOptions = true;
            }

            if (def.getTemperature() != null) {
                double temp = def.getTemperature().doubleValue();
                if (Double.isNaN(temp) || temp < 0.0 || temp > 2.0) {
                    throw new IllegalArgumentException(
                            "Invalid temperature for agent [" + agentCode + "]: " + temp + " (must be in [0.0, 2.0])");
                }
                optionsBuilder.temperature(temp);
                hasCustomOptions = true;
            }

            if (hasCustomOptions) {
                ChatClient.Builder optBuilder = builder.defaultOptions(optionsBuilder);
                if (optBuilder != null) {
                    builder = optBuilder;
                }
            }
        }

        // 2. 按最小权限与工具只读副作用分级解析允许挂载的工具集合 (F01 / F09 / T02)
        Object[] effectiveTools = resolveAuthorizedTools(agentCode, defOpt.orElse(null), tools);
        if (effectiveTools.length > 0) {
            ToolCallback[] interceptedCallbacks = buildInterceptedCallbacks(agentCode, effectiveTools);
            ChatClient.Builder cbBuilder = builder.defaultToolCallbacks(List.of(interceptedCallbacks));
            if (cbBuilder != null) {
                builder = cbBuilder;
            } else {
                ChatClient.Builder toolBuilder = builder.defaultTools(effectiveTools);
                if (toolBuilder != null) {
                    builder = toolBuilder;
                }
            }
        }

        Integer configVersion = defOpt.map(AgentDefinition::getVersion).orElse(0);
        String configuredModel = defOpt.map(AgentDefinition::getModelName).orElse("DEFAULT");
        Double configuredTemp = defOpt.map(AgentDefinition::getTemperature).orElse(null);
        log.info("[AgentChatClientFactory] 已构建智能体客户端: agentCode={}, configVersion={}, model={}, temperature={}, attachedToolsCount={}",
                agentCode, configVersion, configuredModel, configuredTemp, effectiveTools.length);

        return builder.build();
    }

    /**
     * 获取通用的纯净 ChatClient (使用通用兜底系统人设)
     */
    public ChatClient createDefaultClient() {
        return baseChatClientBuilder.clone()
                .defaultSystem("你是一个企业级智能协同助手，请保持客观、严谨、条理清晰的沟通风格。")
                .build();
    }

    private ToolCallback[] buildInterceptedCallbacks(String agentCode, Object[] effectiveTools) {
        Set<String> allowedToolIdentifiers = new LinkedHashSet<>();
        Map<String, ToolDescriptor> methodDescMap = new HashMap<>();

        for (Object toolBean : effectiveTools) {
            Class<?> userClass = ClassUtils.getUserClass(toolBean.getClass());
            ToolDescriptor beanDesc = ToolDescriptor.fromBeanInstance(toolBean);
            allowedToolIdentifiers.add(beanDesc.beanName().toLowerCase(Locale.ROOT));

            for (Method m : userClass.getMethods()) {
                if (m.isBridge() || m.isSynthetic() || !Modifier.isPublic(m.getModifiers())) {
                    continue;
                }
                Tool toolAnn = AnnotationUtils.findAnnotation(m, Tool.class);
                if (toolAnn == null) {
                    continue;
                }
                ToolDescriptor mDesc = ToolDescriptor.fromMethod(beanDesc.beanName(), userClass, m, true);
                if (!mDesc.isReadOnly()) {
                    throw new IllegalArgumentException("Non-read @Tool method (" + mDesc.sideEffectLevel()
                            + ") cannot be attached to ChatClient: " + mDesc.toolRef());
                }
                String toolDefName = StringUtils.hasText(toolAnn.name()) ? toolAnn.name().trim() : m.getName();
                allowedToolIdentifiers.add(m.getName().toLowerCase(Locale.ROOT));
                allowedToolIdentifiers.add(toolDefName.toLowerCase(Locale.ROOT));
                allowedToolIdentifiers.add(mDesc.toolRef().toLowerCase(Locale.ROOT));
                methodDescMap.put(toolDefName, mDesc);
                methodDescMap.put(m.getName(), mDesc);
            }
        }

        ToolCallback[] rawCallbacks = ToolCallbacks.from(effectiveTools);
        ToolCallback[] wrapped = new ToolCallback[rawCallbacks.length];
        Set<String> fallbackAllowlist = Set.copyOf(allowedToolIdentifiers);
        for (int i = 0; i < rawCallbacks.length; i++) {
            ToolCallback raw = rawCallbacks[i];
            String toolName = raw.getToolDefinition().name();
            ToolDescriptor desc = methodDescMap.getOrDefault(toolName,
                    new ToolDescriptor(toolName, toolName, toolName, SideEffectLevel.READ, "v1", "GENERAL"));
            wrapped[i] = new GatewayInterceptedToolCallback(raw, agentCode, fallbackAllowlist, desc);
        }
        return wrapped;
    }

    private final class GatewayInterceptedToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final String agentCode;
        private final Set<String> fallbackAllowedTools;
        private final ToolDescriptor descriptor;

        private GatewayInterceptedToolCallback(
                ToolCallback delegate,
                String agentCode,
                Set<String> fallbackAllowedTools,
                ToolDescriptor descriptor) {
            this.delegate = delegate;
            this.agentCode = agentCode;
            this.fallbackAllowedTools = fallbackAllowedTools;
            this.descriptor = descriptor;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return invokeViaGateway(toolInput, () -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return invokeViaGateway(toolInput, () -> delegate.call(toolInput, toolContext));
        }

        private String invokeViaGateway(String toolInput, java.util.function.Supplier<String> supplier) {
            Map<String, Object> args = parseJsonArgs(toolInput);
            String targetResource = RevocableInvocationPolicy.extractTargetResource(args);
            Set<String> liveAllowedTools = resolveLiveAllowedTools(agentCode, fallbackAllowedTools);
            InvocationContext ctx = InvocationContext.builder()
                    .source(InvocationSource.AGENT_MODEL_TOOL)
                    .agentCode(agentCode)
                    .agentAllowedTools(liveAllowedTools)
                    .toolRef(descriptor.toolRef())
                    .sideEffectLevel(descriptor.sideEffectLevel())
                    .toolVersion(descriptor.version())
                    .resourceType(descriptor.resourceType())
                    .targetResource(targetResource)
                    .arguments(args)
                    .build();
            return invocationGateway.invokeRead(ctx, supplier);
        }
    }

    private Set<String> resolveLiveAllowedTools(String agentCode, Set<String> fallbackAllowedTools) {
        if (promptRegistry == null || !StringUtils.hasText(agentCode)) {
            return fallbackAllowedTools;
        }
        Optional<AgentDefinition> defOpt = promptRegistry.getAgent(agentCode);
        if (defOpt == null || defOpt.isEmpty()) {
            return fallbackAllowedTools;
        }
        AgentDefinition def = defOpt.get();
        if (def.getAttachedTools() == null) {
            return fallbackAllowedTools;
        }
        List<String> configured = parseAttachedTools(def.getAttachedTools());
        if (configured.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> liveSet = new LinkedHashSet<>();
        for (String raw : configured) {
            liveSet.add(raw.trim().toLowerCase(Locale.ROOT));
            liveSet.add(normalizeToolBeanName(raw).toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(liveSet);
    }

    private Map<String, Object> parseJsonArgs(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            JsonNode node = objectMapper.readTree(json.trim());
            if (node != null && node.isObject()) {
                return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception ignored) {
        }
        return Collections.emptyMap();
    }

    private void validateModelName(String modelName) {
        String lower = modelName.toLowerCase(Locale.ROOT);
        if (lower.contains("unknown") || lower.contains("invalid") || lower.contains("forbidden")) {
            throw new IllegalArgumentException("Unsupported or unknown modelName: " + modelName);
        }
        boolean prefixMatched = ALLOWED_MODEL_PREFIXES.stream().anyMatch(lower::startsWith);
        if (!prefixMatched) {
            throw new IllegalArgumentException("Unsupported or unknown modelName: " + modelName);
        }
    }

    private Object[] resolveAuthorizedTools(String agentCode, AgentDefinition def, Object[] candidateTools) {
        // 1. 若存在 AgentDefinition，先严格校验其配置的 attachedTools 是否均为已知安全只读工具
        List<String> configuredTools = (def != null)
                ? parseAttachedTools(def.getAttachedTools())
                : Collections.emptyList();

        Set<String> authorizedBeanNames = new LinkedHashSet<>();
        for (String rawName : configuredTools) {
            if (isForbiddenWriteToolName(rawName)) {
                throw new IllegalArgumentException("Forbidden write tool configured for agent [" + agentCode + "]: " + rawName);
            }
            String normalized = normalizeToolBeanName(rawName).toLowerCase(Locale.ROOT);
            if (!KNOWN_SAFE_TOOL_BEANS.contains(normalized)) {
                throw new IllegalArgumentException(
                        "Unknown or unauthorized tool [" + rawName + "] configured for agent [" + agentCode + "]");
            }
            authorizedBeanNames.add(normalized);
        }

        if (candidateTools == null || candidateTools.length == 0) {
            return new Object[0];
        }

        // 2. 基于工具注册期的 ToolDescriptor (SideEffectLevel) 过滤并索引候选安全只读工具
        Map<String, Object> safeToolMap = new LinkedHashMap<>();
        List<Object> allSafeTools = new ArrayList<>();
        for (Object tool : candidateTools) {
            if (tool == null) {
                continue;
            }
            Class<?> userClass = ClassUtils.getUserClass(tool.getClass());
            String simpleName = userClass.getSimpleName();
            ToolDescriptor descriptor = ToolDescriptor.fromBeanInstance(tool);
            if (isForbiddenWriteToolName(simpleName) || descriptor.sideEffectLevel().requiresApproval()) {
                if (isForbiddenWriteToolName(simpleName)) {
                    throw new IllegalArgumentException("Direct write tool cannot be attached to ChatClient: " + simpleName);
                }
                throw new IllegalArgumentException("Unauthorized or non-read tool object (" + descriptor.sideEffectLevel()
                        + ") passed to ChatClient: " + simpleName);
            }
            String beanName = Introspector.decapitalize(simpleName).toLowerCase(Locale.ROOT);
            safeToolMap.put(beanName, tool);
            safeToolMap.put(simpleName.toLowerCase(Locale.ROOT), tool);
            allSafeTools.add(tool);
        }

        if (def == null) {
            return allSafeTools.toArray();
        }

        if (def.getAttachedTools() == null) {
            return resolveRoleDefaultTools(agentCode, safeToolMap);
        }

        if (authorizedBeanNames.isEmpty()) {
            log.info("[AgentChatClientFactory] 智能体 [{}] 显式配置了空工具白名单，严格禁用所有工具", agentCode);
            return new Object[0];
        }

        // 3. 取 AgentDefinition 授权工具白名单与调用方候选只读工具的交集
        Set<Object> selected = new LinkedHashSet<>();
        for (String authBean : authorizedBeanNames) {
            Object matched = safeToolMap.get(authBean);
            if (matched != null) {
                selected.add(matched);
            }
        }
        return selected.toArray();
    }

    private Object[] resolveRoleDefaultTools(String agentCode, Map<String, Object> safeToolMap) {
        if (agentCode == null) {
            return new Object[0];
        }
        return switch (agentCode.toUpperCase(Locale.ROOT)) {
            case "LOG_DIAGNOSE_AGENT" -> {
                Object logTool = safeToolMap.get("logquerytool");
                yield logTool != null ? new Object[]{logTool} : new Object[0];
            }
            case "DB_DIAGNOSE_AGENT" -> {
                Object dbTool = safeToolMap.get("databasediagnosetool");
                yield dbTool != null ? new Object[]{dbTool} : new Object[0];
            }
            case "SRE_COPILOT_AGENT", "TROUBLESHOOT_AGENT" -> {
                Set<Object> both = new LinkedHashSet<>();
                if (safeToolMap.containsKey("logquerytool")) {
                    both.add(safeToolMap.get("logquerytool"));
                }
                if (safeToolMap.containsKey("databasediagnosetool")) {
                    both.add(safeToolMap.get("databasediagnosetool"));
                }
                yield both.toArray();
            }
            default -> new Object[0];
        };
    }

    private List<String> parseAttachedTools(String attachedToolsJson) {
        if (!StringUtils.hasText(attachedToolsJson)) {
            return Collections.emptyList();
        }
        String trimmed = attachedToolsJson.trim();
        if ("[]".equals(trimmed)) {
            return Collections.emptyList();
        }
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String clean = part.trim().replace("\"", "").replace("'", "").trim();
            if (!clean.isEmpty()) {
                result.add(clean);
            }
        }
        return result;
    }

    private String normalizeToolBeanName(String rawName) {
        String clean = rawName.trim();
        int dotIdx = clean.indexOf('.');
        if (dotIdx > 0) {
            clean = clean.substring(0, dotIdx).trim();
        }
        return clean;
    }

    private boolean isForbiddenWriteToolName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String marker : FORBIDDEN_WRITE_TOOL_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
