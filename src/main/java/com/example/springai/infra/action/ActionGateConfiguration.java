package com.example.springai.infra.action;

import com.example.springai.core.card.CardRegistry;
import com.example.springai.core.card.CardSubmitHandler;
import com.example.springai.core.card.action.ActionAuthorizer;
import com.example.springai.core.card.action.ActionGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/** 无认证授权插件时默认拒绝真实写操作。 */
@Configuration(proxyBeanMethods = false)
public class ActionGateConfiguration {
    @Bean
    public ActionGate actionGate(CardRegistry registry, List<CardSubmitHandler> handlers,
                                 ObjectProvider<ActionAuthorizer> authorizers,
                                 @Value("${agentflow.actions.allow-mock-submissions:false}") boolean allowMock,
                                 @Value("${agentflow.actions.real-writes-enabled:false}") boolean enableReal) {
        return new ActionGate(registry, handlers, authorizers.getIfAvailable(() -> ActionAuthorizer.DENY_ALL), allowMock, enableReal);
    }
}
