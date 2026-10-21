package com.example.springai.core.tool;

/**
 * 工具副作用安全分级枚举（对应 12 号架构文档：工具注册时标明读 / 写 / 高危）。
 * <ul>
 *   <li>{@link #READ}：纯只读查询工具，无外部系统状态变更副作用，经统一调用入口实时鉴权后可供大模型或 L1 直通调用；</li>
 *   <li>{@link #WRITE}：普通写操作工具，禁止直接暴露给大模型或 L1 直通，必须以提案形式经 ActionGate 审批后执行；</li>
 *   <li>{@link #CRITICAL}：高危写操作工具（如 Kill 数据库会话、生产连接池扩容、重启实例），必须经 ActionGate 审批并在执行前实时复核权限。</li>
 * </ul>
 */
public enum SideEffectLevel {
    READ,
    WRITE,
    CRITICAL;

    public boolean isReadOnly() {
        return this == READ;
    }

    public boolean requiresApproval() {
        return this == WRITE || this == CRITICAL;
    }
}
