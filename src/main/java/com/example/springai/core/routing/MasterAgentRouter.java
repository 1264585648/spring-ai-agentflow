package com.example.springai.core.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.springai.core.agent.AgentPromptRegistry;
import com.example.springai.core.agent.AgentType;
import com.example.springai.core.routing.dto.DispatchPlan;
import com.example.springai.core.routing.dto.DispatchStep;
import com.example.springai.core.routing.dto.PlanType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MasterAgent 调度决策器 (MasterAgentRouter)
 * 职责:
 * 1. 研判用户输入的意图特征与任务复杂度；
 * 2. 识别单意图直通 vs 复合多意图依赖编排；
 * 3. 联动 AgentPromptRegistry 校验目标专家在线状态（防悬挂调度）；
 * 4. 产出强类型的调度计划契约 (DispatchPlan)。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MasterAgentRouter {

    private static final Pattern SERVICE_PATTERN = Pattern.compile("([a-zA-Z0-9_-]+-(?:service|app|server|api|gateway))", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACE_PATTERN = Pattern.compile("(?:trace(?:id)?|tid)[=:\\s]+([a-zA-Z0-9_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS_CODE_PATTERN = Pattern.compile("\\b(500|502|503|504)\\b");

    private final AgentPromptRegistry promptRegistry;

    /**
     * 根据用户诉求输入规划调度决策计划
     *
     * @param query 工程师输入的排障/咨询诉求
     * @return 结构化的调度计划 (DispatchPlan)
     */
    public DispatchPlan route(String query) {
        if (!StringUtils.hasText(query)) {
            return DispatchPlan.fallback("用户输入内容为空，无法生成调度计划");
        }

        String text = query.trim();
        Map<String, Object> contextParams = extractContextParams(text);

        log.debug("[MasterRouter] 正在分析排障意图, query: {}, 上下文: {}", text, contextParams);

        // 1. 复合多意图判定：同时涉及服务日志异常报错 与 数据库慢查/连接池打满
        if (isLogRelated(text) && isDbRelated(text)) {
            return planLogAndDbComposite(text, contextParams);
        }

        // 2. 单意图直通判定 - 日志与分布式链路排障
        if (isLogRelated(text)) {
            return planSingleExpert(
                    AgentType.LOG_DIAGNOSE_AGENT.getCode(),
                    AgentType.LOG_DIAGNOSE_AGENT.getName(),
                    "检索服务异常日志、解析报错堆栈并对比已知故障知识库",
                    contextParams
            );
        }

        // 3. 单意图直通判定 - 数据库性能与慢SQL诊断
        if (isDbRelated(text)) {
            return planSingleExpert(
                    AgentType.DB_DIAGNOSE_AGENT.getCode(),
                    AgentType.DB_DIAGNOSE_AGENT.getName(),
                    "诊断数据库慢查询、活跃连接数与长事务锁等待",
                    contextParams
            );
        }

        // 4. 单意图直通判定 - 应急止血与运维处置
        if (isSreRelated(text)) {
            return planSingleExpert(
                    AgentType.SRE_COPILOT_AGENT.getCode(),
                    AgentType.SRE_COPILOT_AGENT.getName(),
                    "制定应急止血处置方案，装配确认卡片并引导工程师核验执行",
                    contextParams
            );
        }

        // 5. 若挂载了通用智能体，直通 GENERAL_AGENT
        if (isAgentOnline(AgentType.GENERAL_AGENT.getCode())) {
            log.info("[MasterRouter] 未匹配到特定的专业排障专家, 转入通用智能体协同, query: {}", text);
            return planSingleExpert(
                    AgentType.GENERAL_AGENT.getCode(),
                    AgentType.GENERAL_AGENT.getName(),
                    "通用技术问答与综合建议",
                    contextParams
            );
        }

        // 6. 优雅降级为 FALLBACK
        log.info("[MasterRouter] 未匹配到特定的专业业务专家, 降级处理, query: {}", text);
        return DispatchPlan.fallback("当前诉求未命中已挂载的特定业务专家，建议输入 /help 查看排障与系统指令");
    }

    /**
     * 规划 日志异常排查 + 数据库诊断 复合协同计划
     */
    private DispatchPlan planLogAndDbComposite(String query, Map<String, Object> contextParams) {
        String logCode = AgentType.LOG_DIAGNOSE_AGENT.getCode();
        String dbCode = AgentType.DB_DIAGNOSE_AGENT.getCode();

        // 校验两个专家是否均在线
        if (!isAgentOnline(logCode)) {
            return DispatchPlan.fallback("复合任务规划受阻：日志分析专家 [" + logCode + "] 当前处于下线状态");
        }
        if (!isAgentOnline(dbCode)) {
            return DispatchPlan.fallback("复合任务规划受阻：数据库诊断专家 [" + dbCode + "] 当前处于下线状态");
        }

        List<DispatchStep> steps = new ArrayList<>();

        // Step 1: 先调 日志专家 提取报错堆栈与根因
        steps.add(DispatchStep.builder()
                .stepOrder(1)
                .targetAgent(logCode)
                .targetAgentName(AgentType.LOG_DIAGNOSE_AGENT.getName())
                .taskDesc("检索目标服务错误日志，提取核心报错堆栈与数据库异常提示")
                .inputParams(contextParams)
                .build());

        // Step 2: 再调 数据库专家 结合日志指标排查慢SQL与连接池
        steps.add(DispatchStep.builder()
                .stepOrder(2)
                .targetAgent(dbCode)
                .targetAgentName(AgentType.DB_DIAGNOSE_AGENT.getName())
                .taskDesc("结合 Step 1 日志异常，诊断数据库活跃连接水位、慢查询与长事务")
                .inputParams(contextParams)
                .build());

        String reason = "检测到复合排障诉求（服务日志异常 + 数据库性能瓶颈）：编排两步依赖协同，先定位日志堆栈，再深入数据库诊断。";
        log.info("[MasterRouter] ⚡ 成功生成复合多意图排障调度计划: 步骤数={}", steps.size());
        return DispatchPlan.composite(reason, steps);
    }

    /**
     * 规划单意图直通计划（带专家在线校验）
     */
    private DispatchPlan planSingleExpert(String agentCode, String agentName, String taskDesc, Map<String, Object> params) {
        if (!isAgentOnline(agentCode)) {
            log.warn("[MasterRouter] ⚠️ 目标业务专家 [{}] 当前未在线或已被禁用，阻断直通调度", agentCode);
            return DispatchPlan.fallback("目标业务专家 [" + agentName + " (" + agentCode + ")] 当前处于下线状态，无法响应此诉求");
        }

        log.info("[MasterRouter] ⚡ 生成单意图直通调度计划: target={}", agentCode);
        return DispatchPlan.single(agentCode, agentName, taskDesc, params);
    }

    /**
     * 校验业务专家当前是否在注册中心处于 ONLINE 且启用状态
     */
    public boolean isAgentOnline(String agentCode) {
        if (agentCode == null || promptRegistry == null) {
            return false;
        }
        return promptRegistry.getOnlineBusinessAgents().stream()
                .anyMatch(a -> a.getAgentCode().equalsIgnoreCase(agentCode.trim()));
    }

    /**
     * 从查询文本中提取服务名、TraceID、错误码等排障上下文
     */
    private Map<String, Object> extractContextParams(String query) {
        Map<String, Object> params = new LinkedHashMap<>();

        Matcher serviceMatcher = SERVICE_PATTERN.matcher(query);
        if (serviceMatcher.find()) {
            params.put("service", serviceMatcher.group(1));
        }

        Matcher traceMatcher = TRACE_PATTERN.matcher(query);
        if (traceMatcher.find()) {
            params.put("traceId", traceMatcher.group(1));
        }

        Matcher statusMatcher = STATUS_CODE_PATTERN.matcher(query);
        if (statusMatcher.find()) {
            params.put("statusCode", statusMatcher.group(1));
        }

        return Collections.unmodifiableMap(params);
    }

    private boolean isLogRelated(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("log") || lower.contains("日志") || lower.contains("报错")
                || lower.contains("error") || lower.contains("exception") || lower.contains("堆栈")
                || lower.contains("trace") || lower.contains("504") || lower.contains("500")
                || lower.contains("502") || lower.contains("oom") || lower.contains("超时");
    }

    private boolean isDbRelated(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("db") || lower.contains("数据库") || lower.contains("mysql")
                || lower.contains("慢sql") || lower.contains("慢查询") || lower.contains("连接池")
                || lower.contains("死锁") || lower.contains("锁等待") || lower.contains("事务")
                || lower.contains("hikari") || lower.contains("druid");
    }

    private boolean isSreRelated(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.contains("止血") || lower.contains("kill") || lower.contains("重启")
                || lower.contains("扩容") || lower.contains("降级") || lower.contains("熔断")
                || lower.contains("工单") || lower.contains("处置") || lower.contains("sop");
    }
}
