package com.example.springai.infra.client;

import com.example.springai.core.agent.AgentDefinition;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.agent.AgentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.beans.Introspector;
import java.util.*;

/**
 * 多智能体专用 ChatClient 工厂 (AgentChatClientFactory)
 * 职责:
 * 1. 结合基础 ChatClient.Builder 与 AgentPromptRegistry；
 * 2. 将 AgentDefinition 的系统提示词、模型名称、温度参数与挂载工具白名单转化为真实生效的运行期配置 (F09)；
 * 3. 严格执行最小权限原则：校验模型与工具白名单，拒绝未知模型或未授权工具，杜绝写工具旁路 (F01/F09)。
 */
@Component
@Slf4j
@RequiredArgsConstructor
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

    private final ChatClient.Builder baseChatClientBuilder;
    private final AgentPromptRegistry promptRegistry;

    /**
     * 为指定智能体枚举类型创建专用 ChatClient 实例
     *
     * @param agentType 智能体枚举
     * @return 具备该智能体专属人设的 ChatClient
     */
    public ChatClient createClient(AgentType agentType) {
        return createClient(agentType != null ? agentType.getCode() : null);
    }

    /**
     * 为指定智能体枚举类型创建专用 ChatClient 实例并绑定专属 Tools
     *
     * @param agentType 智能体枚举
     * @param tools 该智能体专属挂载的候选 Spring @Tool Bean (可变参数)
     * @return 专属于该智能体的 ChatClient
     */
    public ChatClient createClient(AgentType agentType, Object... tools) {
        return createClient(agentType != null ? agentType.getCode() : null, tools);
    }

    /**
     * 为指定智能体创建专用的 ChatClient 实例 (无挂载工具)
     *
     * @param agentCode 智能体唯一编码 (如 QUERY_REWRITER, MASTER_AGENT 等)
     * @return 具备该智能体专属人设的 ChatClient
     */
    public ChatClient createClient(String agentCode) {
        return createClient(agentCode, new Object[0]);
    }

    /**
     * 为指定智能体创建专用的 ChatClient 实例并按最小权限绑定专属 Tools 与模型运行参数
     *
     * @param agentCode 智能体唯一编码
     * @param tools 调用方提供的候选只读/查询 @Tool Bean 集合
     * @return 专属于该智能体的 ChatClient
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

        // 2. 按最小权限解析允许挂载的工具集合 (F01 / F09 / T02)
        Object[] effectiveTools = resolveAuthorizedTools(agentCode, defOpt.orElse(null), tools);
        if (effectiveTools.length > 0) {
            ChatClient.Builder toolBuilder = builder.defaultTools(effectiveTools);
            if (toolBuilder != null) {
                builder = toolBuilder;
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

    private static final Set<String> KNOWN_SAFE_TOOL_BEANS = Set.of(
            "logquerytool", "databasediagnosetool", "useraccounttool"
    );

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

        // 2. 过滤并索引候选安全工具（严禁任何写操作工具进入候选池）
        Map<String, Object> safeToolMap = new LinkedHashMap<>();
        List<Object> allSafeTools = new ArrayList<>();
        for (Object tool : candidateTools) {
            if (tool == null) {
                continue;
            }
            String simpleName = tool.getClass().getSimpleName();
            if (isForbiddenWriteToolName(simpleName)) {
                throw new IllegalArgumentException("Direct write tool cannot be attached to ChatClient: " + simpleName);
            }
            String beanName = Introspector.decapitalize(simpleName).toLowerCase(Locale.ROOT);
            if (!KNOWN_SAFE_TOOL_BEANS.contains(beanName)) {
                throw new IllegalArgumentException("Unauthorized tool object passed to ChatClient: " + simpleName);
            }
            safeToolMap.put(beanName, tool);
            safeToolMap.put(simpleName.toLowerCase(Locale.ROOT), tool);
            allSafeTools.add(tool);
        }

        // 当 AgentPromptRegistry 中无该智能体定义时（如 Mock 测试桩或通用 TROUBLESHOOT_AGENT），保留全部已校验的安全只读工具
        if (def == null) {
            return allSafeTools.toArray();
        }

        // 若 attachedTools 字段未配置（null），按角色最小权限默认分配安全只读工具
        if (def.getAttachedTools() == null) {
            return resolveRoleDefaultTools(agentCode, safeToolMap);
        }

        // 若显式配置了 attachedTools（如 "[]"），解析后无有效工具，严格代表禁用该智能体的全部工具 (F09 修复)
        if (authorizedBeanNames.isEmpty()) {
            log.info("[AgentChatClientFactory] 智能体 [{}] 显式配置了空工具白名单，严格禁用所有工具", agentCode);
            return new Object[0];
        }

        // 3. 取 AgentDefinition 授权工具白名单与调用方候选工具的交集（确保 Agent A 绝不能获得 Agent B 的工具）
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

