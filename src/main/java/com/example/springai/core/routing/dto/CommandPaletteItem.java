package com.example.springai.core.routing.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 斜杠命令面板展示项 DTO (CommandPaletteItem)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommandPaletteItem {

    /**
     * 规则唯一标识码 (如 CMD_SYS_PING)
     */
    private String code;

    /**
     * 触发前缀 (如 /query, /help, #ping)
     */
    private String prefix;

    /**
     * 命令展示名称 (如 快速查账指令)
     */
    private String name;

    /**
     * 补全填充模板 (如 /query user_id=)
     */
    private String template;

    /**
     * 详细描述与操作指引
     */
    private String description;

    /**
     * 执行目标类型 (STATIC_TEXT / TOOL)
     */
    private String targetType;

    /**
     * 前端图标标识 (Zap / HelpCircle / Wrench)
     */
    private String icon;
}
