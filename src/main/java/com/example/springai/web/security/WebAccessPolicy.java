package com.example.springai.web.security;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;

/** 由宿主应用接入认证后的用户权限和资源归属校验。请求头中的自报身份不是 Principal。 */
@FunctionalInterface
public interface WebAccessPolicy {
    /** 拒绝时抛出 SecurityException。实现必须同时校验租户/会话/动作归属和管理权限。 */
    void authorize(Principal principal, HttpServletRequest request);
}
