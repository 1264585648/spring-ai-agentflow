package com.example.springai.web.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods=false)
public class ApiAccessConfiguration implements WebMvcConfigurer {
    private final ApiAccessInterceptor interceptor;
    public ApiAccessConfiguration(ObjectProvider<WebAccessPolicy> policies,
            @Value("${agentflow.access.local-demo-enabled:false}") boolean demo,
            @Value("${troubleshoot.mode:mock}") String mode,
            @Value("${agentflow.actions.real-writes-enabled:false}") boolean realWrites) {
        if(demo && (!"mock".equals(mode) || realWrites)) throw new IllegalStateException("匿名演示只能搭配 Mock，禁止同时开启真实写操作");
        interceptor=new ApiAccessInterceptor(policies.getIfAvailable(),demo);
    }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**"); }
}
