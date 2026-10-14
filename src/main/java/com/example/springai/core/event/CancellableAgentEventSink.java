package com.example.springai.core.event;

import com.example.springai.core.card.model.InteractiveCard;
import com.example.springai.core.context.AgentContext;
import java.util.List;
import java.util.Objects;

/** 取消/超期之后不再接受业务输出或新卡片；终态错误仍由运行时送达。 */
public final class CancellableAgentEventSink implements AgentEventSink {
    private final AgentContext context;
    private final AgentEventSink delegate;
    public CancellableAgentEventSink(AgentContext context, AgentEventSink delegate) {
        this.context=Objects.requireNonNull(context); this.delegate=Objects.requireNonNull(delegate);
    }
    private boolean active() { return !context.isCancelledOrExpired(System.currentTimeMillis()) && !Thread.currentThread().isInterrupted(); }
    public boolean thinking(String value) { return active() && delegate.thinking(value); }
    public boolean progress(String stage,String description) { return active() && delegate.progress(stage,description); }
    public boolean message(String value) { return active() && delegate.message(value); }
    public boolean card(InteractiveCard card) { return active() && delegate.card(card); }
    public boolean recommend(List<String> values) { return active() && delegate.recommend(values); }
    public boolean done() { return active() && delegate.done(); }
    public boolean error(String value) { return delegate.error(value); }
}
