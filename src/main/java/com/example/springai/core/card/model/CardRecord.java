package com.example.springai.core.card.model;

import com.example.springai.core.card.state.CardState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 服务端签发与执行记录；由存储实现保护，不得将内部实例交给调用方。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardRecord {
    private String actionId;
    private String sessionId;
    private String turnId;
    private String cardType;
    private String title;
    private InteractiveCard snapshot;
    @Builder.Default
    private CardState state = CardState.ISSUED;
    private long issueTime;
    private long expireAt;
    private Long submitTime;
    private String ticketId;
    private String failReason;
    /** 每次签发的唯一版本，不使用毫秒时间戳充当版本。 */
    private String issuanceId;
    public boolean isExpired() { return isExpired(System.currentTimeMillis()); }
    public boolean isExpired(long nowMillis) { return nowMillis >= expireAt; }
}
