package com.example.springai.web.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class ApiAccessInterceptorTest {
    @Test void anonymousDataAndAdminRequestsAreRejectedEvenWithForgedIdentityHeader() throws Exception {
        for(String path:new String[]{"/api/v1/chat/ask","/api/v1/card/submit","/api/v1/admin/faqs","/api/v1/chat/cancel"}) {
            var request=new MockHttpServletRequest("POST",path); request.addHeader("X-User-Id","admin");
            var response=new MockHttpServletResponse();
            assertFalse(new ApiAccessInterceptor(null,false).preHandle(request,response,new Object()));
            assertEquals(401,response.getStatus()); assertTrue(response.getContentAsString().contains("AUTHENTICATION_REQUIRED"));
        }
    }
    @Test void principalAloneDoesNotProvideAuthorization() throws Exception {
        var request=new MockHttpServletRequest(); request.setUserPrincipal(()->"alice"); var response=new MockHttpServletResponse();
        assertFalse(new ApiAccessInterceptor(null,false).preHandle(request,response,new Object())); assertEquals(403,response.getStatus());
    }
    @Test void policyUsesServerPrincipalAndSeparatelyEnforcesAdminRole() throws Exception {
        var interceptor=new ApiAccessInterceptor((principal,request)-> {
            assertEquals("alice",principal.getName());
            if(request.getRequestURI().contains("/admin/")) throw new SecurityException("admin role required");
        },false);
        var request=new MockHttpServletRequest("GET","/api/v1/admin/faqs"); request.setUserPrincipal(()->"alice"); request.addHeader("X-User-Id","admin");
        var response=new MockHttpServletResponse(); assertFalse(interceptor.preHandle(request,response,new Object())); assertEquals(403,response.getStatus());
        request.setRequestURI("/api/v1/chat/ask"); assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
    }
    @Test void localDemoDoesNotTrustForwardedLoopbackAddress() throws Exception {
        var request=new MockHttpServletRequest(); request.setRemoteAddr("203.0.113.10"); request.addHeader("X-Forwarded-For","127.0.0.1");
        var interceptor=new ApiAccessInterceptor(null,true); var response=new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request,response,new Object())); assertEquals(401,response.getStatus());
        request.setRemoteAddr("127.0.0.1"); assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),new Object()));
    }
}
