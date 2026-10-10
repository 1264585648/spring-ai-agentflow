package com.example.springai.troubleshoot.port;

import com.example.springai.troubleshoot.dto.TroubleshootActionParam;
import com.example.springai.troubleshoot.dto.TroubleshootActionResult;

/**
 * 应急止血与运维处置操作标准端口 (TroubleshootActionPort)
 * 必须在 Human-in-the-loop 经由工程师二次核验授权后调用，阻断大模型直接执行越权写操作
 */
public interface TroubleshootActionPort {

    /**
     * 执行核验通过的应急止血处置动作
     *
     * @param param 处置参数
     * @return 处置执行审计结果
     */
    TroubleshootActionResult executeAction(TroubleshootActionParam param);
}
