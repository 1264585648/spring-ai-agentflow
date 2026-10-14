package com.example.springai.capability.ops.port;

import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.model.OpsActionResult;

import java.util.Optional;

/**
 * 应急止血与运维处置操作标准端口 (OpsActionPort)
 * 必须在 Human-in-the-loop 经由工程师二次核验授权后调用，阻断大模型直接执行越权写操作
 */
public interface OpsActionPort {

    /**
     * 执行核验通过的应急止血处置动作
     *
     * @param param 处置参数
     * @return 处置执行审计结果
     */
    OpsActionResult executeAction(OpsActionParam param);

    /**
     * 根据幂等键查询是否已执行完成（用于网络超时等未知状态下的幂等反查，防止盲目重试导致二次副作用）
     *
     * @param idempotencyKey 幂等执行键 (如 actionId)
     * @return 若外部已执行则返回原执行结果
     */
    default Optional<OpsActionResult> findExecutedResult(String idempotencyKey) {
        return Optional.empty();
    }
}
