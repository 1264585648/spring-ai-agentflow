package com.example.springai.pipeline.dispatcher;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class L1ToolDispatcherAllowlistTest {

    @Test
    void onlyAnnotatedToolsCanBeDispatched() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("sampleTool", SampleTool.class);
            context.refresh();

            L1ToolCatalog catalog = new L1ToolCatalog(context);
            catalog.init();
            L1ToolDispatcher dispatcher = new L1ToolDispatcher(catalog);

            assertTrue(catalog.isRegistered("sampleTool.queryBalance"));
            assertEquals("balance:10001",
                    dispatcher.dispatch("sampleTool.queryBalance", "{\"userId\":\"10001\"}"));
            assertEquals("two:7:REFUND",
                    dispatcher.dispatch("sampleTool.query", "{\"userId\":\"7\",\"type\":\"REFUND\"}"));

            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> dispatcher.dispatch("sampleTool.deleteAll", "{}"));
            assertTrue(rejected.getMessage().contains("不是已注册的 @Tool 方法"));
        }
    }

    static class SampleTool {

        @Tool(description = "查询余额")
        public String queryBalance(String userId) {
            return "balance:" + userId;
        }

        public String deleteAll() {
            return "deleted";
        }

        @Tool(description = "单参数查询")
        public String query(String userId) {
            return "one:" + userId;
        }

        @Tool(description = "双参数查询")
        public String query(String userId, String type) {
            return "two:" + userId + ":" + type;
        }
    }
}
