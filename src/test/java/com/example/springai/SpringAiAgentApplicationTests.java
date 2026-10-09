package com.example.springai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

@SpringBootTest
class SpringAiAgentApplicationTests {

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private org.springframework.ai.chat.model.ChatModel chatModel;

    @Autowired(required = false)
    private org.springframework.ai.chat.client.ChatClient.Builder chatClientBuilder;

    @Test
    void contextLoads() {
        assertTrue(chatModel != null, "ChatModel 应由 Spring AI 自动装配");
        assertTrue(chatClientBuilder != null, "ChatClient.Builder 应由 Spring AI 自动装配");
    }
}
