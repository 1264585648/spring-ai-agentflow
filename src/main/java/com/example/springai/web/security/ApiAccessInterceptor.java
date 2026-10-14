package com.example.springai.web.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;
import java.security.Principal;
import java.util.Set;

/** 数据面与管理面默认拒绝。仅显式开启的本机 Mock 演示允许匿名访问。 */
public final class ApiAccessInterceptor implements HandlerInterceptor {
    private final WebAccessPolicy policy;
    private final boolean localDemo;
    public ApiAccessInterceptor(WebAccessPolicy policy, boolean localDemo) { this.policy=policy; this.localDemo=localDemo; }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws IOException {
        if(localDemo && Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())) return true;
        Principal principal=request.getUserPrincipal();
        if(principal==null || principal.getName()==null || principal.getName().isBlank()) return reject(response,401,"AUTHENTICATION_REQUIRED");
        if(policy==null) return reject(response,403,"ACCESS_POLICY_NOT_CONFIGURED");
        try { policy.authorize(principal,request); return true; }
        catch(SecurityException ex) { return reject(response,403,"ACCESS_DENIED"); }
    }
    private static boolean reject(HttpServletResponse response,int status,String reason) throws IOException {
        response.setStatus(status); response.setContentType("application/json"); response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":"+status+",\"message\":\""+reason+"\",\"retryable\":false}");
        return false;
    }
}
