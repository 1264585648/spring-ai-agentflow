package com.example.springai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 通用开源演示工具库：用户账户与资产查询
 * 核心设计: 标注 Spring AI 原生的 @Tool 注解。
 * 既可供 LLM 做 Function Calling 自主推理，也可被 L1 规则引擎零胶水代码直接反射调度。
 */
@Component("userAccountTool")
@Slf4j
public class UserAccountTool {


    @Tool(description = "根据用户ID查询账户资产速查与待还余额")
    public String queryBalance(String userId) {
        log.info("[UserAccountTool] 执行用户账户查询: userId={}", userId);
        return String.format("📊 【账户资产速查】\n"
                + "• 用户编号：%s\n"
                + "• 账户状态：正常 (信用良好)\n"
                + "• 可用授信额度：50,000.00 元\n"
                + "• 当前待还余额：1,280.50 元\n"
                + "• 最近还款日：2026-10-15 (还有 7 天)\n"
                + "⚡ (数据由 userAccountTool.queryBalance 毫秒级直通返回，零大模型 Token 消耗)", userId);
    }

    @Tool(description = "根据用户ID和流水类型查询最新交易流水明细")
    public String queryTransactions(String userId, String type) {
        log.info("[UserAccountTool] 执行流水查询: userId={}, type={}", userId, type);
        return String.format("📋 【最新流水明细】用户：%s | 类型：%s\n"
                + "1. 2026-10-01 自动划扣 120.00 元 [成功]\n"
                + "2. 2026-10-05 主动还款 1,000.00 元 [成功]", userId, type != null ? type : "ALL");
    }
}
