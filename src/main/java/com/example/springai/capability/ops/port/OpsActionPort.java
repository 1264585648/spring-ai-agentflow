package com.example.springai.capability.ops.port;

import com.example.springai.capability.ops.model.OpsActionParam;
import com.example.springai.capability.ops.model.OpsActionResult;
import java.util.Optional;

/** 已审批动作的基础设施端口。实现必须将幂等键交给实际执行系统。 */
public interface OpsActionPort {
    OpsActionResult executeAction(OpsActionParam param);
    default Optional<OpsActionResult> findExecutedResult(String idempotencyKey) { return Optional.empty(); }
    /** 受信任适配器声明；不能根据配置字符串推断真实端口是模拟的。 */
    default boolean isSimulation() { return false; }
}
