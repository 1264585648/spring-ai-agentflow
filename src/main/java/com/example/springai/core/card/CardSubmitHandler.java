package com.example.springai.core.card;

import com.example.springai.core.card.dto.CardSubmitRequest;
import com.example.springai.core.card.dto.CardSubmitResult;

/**
 * 核心 SPI 扩展点: 交互卡片提交处理器
 * 任何业务方 (如业务审批 BPM、HR 流程、IT 运维) 只需实现此接口即可无缝对接自己的后台系统
 */
public interface CardSubmitHandler {

    /**
     * 判断当前处理器是否支持该卡片类型
     *
     * @param cardType 卡片类型 (如: SPECIAL_APPROVAL, LEAVE_REQUEST, IT_APPLY)
     * @return 是否支持
     */
    boolean supports(String cardType);

    /**
     * 执行实际的业务提交逻辑 (如: 调用外部 OpenAPI、保存数据库)
     *
     * @param request 提交入参
     * @return 处理结果 (包含生成的业务单号 ticketId)
     */
    CardSubmitResult handleSubmit(CardSubmitRequest request);
}
