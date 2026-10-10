package com.example.springai.web.controller;

import lombok.RequiredArgsConstructor;
import com.example.springai.core.routing.dto.CommandPaletteItem;
import com.example.springai.core.routing.L1RuleRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 快捷指令面板 REST 控制器 (CommandPaletteController)
 * 供前端斜杠悬浮菜单、指令按钮即时拉取全量可用指令清单
 */
@RestController
@RequestMapping("/api/v1/commands")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class CommandPaletteController {

    private final L1RuleRegistry ruleRegistry;

    /**
     * 获取全量可用快捷指令清单 (纯内存无锁读取，响应 < 1ms)
     */
    @GetMapping("/palette")
    public ResponseEntity<List<CommandPaletteItem>> getCommandPalette() {
        return ResponseEntity.ok(ruleRegistry.getCommandPalette());
    }
}
