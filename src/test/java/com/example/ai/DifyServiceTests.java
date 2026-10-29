package com.example.ai;

import com.example.ai.config.DifyConfig;
import com.example.ai.service.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class DifyServiceTests {
    HttpServer server;
    DifyConfig config;
    AtomicReference<String> request = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    String response = "";
    int status = 200;
    long delay = 0;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            try {
                request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                path.set(exchange.getRequestURI().getPath());
                assertEquals("Bearer test-key", exchange.getRequestHeaders().getFirst("Authorization"));
                if (delay > 0) Thread.sleep(delay);
                byte[] data = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, data.length);
                exchange.getResponseBody().write(data);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        config = new DifyConfig();
        config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        config.setApiKey("test-key");
        config.setMode("workflow");
        config.setInputVariable("question");
        config.setOutputVariable("result");
    }
    @AfterEach void cleanup() { server.stop(0); }
    DifyService.Reply send() { return new DifyService(config).send("设备电量", "test-user", null); }
    void error(String expected) { assertTrue(assertThrows(DifyException.class, this::send).getMessage().contains(expected)); }
    @Test void workflowSuccessAndPayload() {
        response = "{\"data\":{\"status\":\"succeeded\",\"outputs\":{\"result\":\"请充电\"}}}";
        assertEquals("请充电", send().answer());
        assertEquals("/v1/workflows/run", path.get());
        assertTrue(request.get().contains("question"));
        assertFalse(request.get().contains("conversation_id"));
    }
    @Test void chatflowConversation() {
        config.setMode("chatflow");
        response = "{\"answer\":\"请充电\",\"conversation_id\":\"conv-1\"}";
        DifyService service = new DifyService(config);
        assertEquals("conv-1", service.send("你好", "test-user", null).conversationId());
        service.send("继续", "test-user", "conv-1");
        assertTrue(request.get().contains("conv-1"));
        assertEquals("/v1/chat-messages", path.get());
    }
    @Test void unauthorized() { status = 401; error("鉴权失败"); }
    @Test void upstream500() { status = 500; error("服务异常"); }
    @Test void invalidInputs() { status = 400; error("参数不匹配"); }
    @Test void timedOut() { delay = 250; config.setReadTimeout(Duration.ofMillis(50)); error("超时"); }
    @Test void workflowFailed() { response = "{\"data\":{\"status\":\"failed\"}}"; error("执行失败"); }
    @Test void missingOutput() { response = "{\"data\":{\"status\":\"succeeded\",\"outputs\":{}}}"; error("输出变量不存在"); }
    @Test void invalidJson() { response = "<html>gateway</html>"; error("格式异常"); }
    @Test void emptyResponse() { response = "null"; error("空响应"); }
    @Test void gateway401In200() { response = "{\"code\":401,\"msg\":\"denied\"}"; error("网关返回认证失败"); }
    @Test void missingConfig() { config.setInputVariable(""); error("input-variable"); }
    @Test void unavailable() { server.stop(0); error("无法连接"); }
    String event(String json) { return "data: " + json + "\n\n"; }
    @Test void agentStreamAndConversation() {
        config.setMode("agent");
        config.setInputVariable(""); config.setOutputVariable("");
        config.setInputs(java.util.Map.of("department", "support"));
        response = ": heartbeat\n\n" + event("{\"event\":\"agent_thought\",\"thought\":\"private\"}")
            + event("{\"event\":\"agent_message\",\"answer\":\"你\",\"conversation_id\":\"agent-conv\"}")
            + event("{\"event\":\"agent_message\",\"answer\":\"好\"}")
            + event("{\"event\":\"message\",\"answer\":\"你好！\"}")
            + event("{\"event\":\"message_end\"}");
        var reply = new DifyService(config).send("测试", "test-user", "previous-conv");
        assertEquals("你好！", reply.answer());
        assertEquals("agent-conv", reply.conversationId());
        assertEquals("/v1/chat-messages", path.get());
        var body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(request.get());
        assertEquals("streaming", body.path("response_mode").asString());
        assertEquals("previous-conv", body.path("conversation_id").asString());
        assertEquals("support", body.path("inputs").path("department").asString());
        assertEquals("测试", body.path("query").asString());
    }
    @Test void legacyAgentChunks() {
        config.setMode("agent-chat");
        response = event("{\"event\":\"agent_message\",\"answer\":\"你\",\"conversation_id\":\"c1\"}")
            + event("{\"event\":\"agent_message\",\"answer\":\"好\"}")
            + event("{\"event\":\"message_end\"}");
        assertEquals("你好", send().answer());
    }
    @Test void agentError() {
        config.setMode("agent"); response = event("{\"event\":\"error\",\"message\":\"secret\"}"); error("执行失败");
    }
    @Test void agentIncompleteStream() {
        config.setMode("agent"); response = event("{\"event\":\"agent_message\",\"answer\":\"partial\"}"); error("未完整结束");
    }
    @Test void agentInvalidStream() {
        config.setMode("agent"); response = "data: invalid\n\n"; error("格式异常");
    }
    @Test void unknownMode() { config.setMode("invalid"); error("dify.mode"); }
    @Test void agentEmptyAnswer() {
        config.setMode("agent"); response = event("{\"event\":\"message_end\",\"conversation_id\":\"c1\"}"); error("未返回文本");
    }
    @Test void agentMissingConversation() {
        config.setMode("agent"); response = event("{\"event\":\"agent_message\",\"answer\":\"hello\"}")
            + event("{\"event\":\"message_end\"}"); error("conversation_id");
    }
    @Test void agentGatewayAuthError() {
        config.setMode("agent"); response = "{\"code\":401,\"msg\":\"denied\"}"; error("鉴权失败");
    }
    @Test void agentWrongApplicationJson() {
        config.setMode("agent"); response = "{\"answer\":\"unexpected blocking response\"}"; error("未返回流式回复");
    }
    @Test void progressExposesToolNamesButNeverPrivatePayloads() {
        config.setMode("agent");
        response = event("{\"event\":\"agent_thought\",\"id\":\"step-1\",\"tool\":\"getAllPersonVitals\",\"thought\":\"PRIVATE_THOUGHT\",\"tool_input\":\"PRIVATE_INPUT\",\"observation\":\"PRIVATE_RESULT\",\"conversation_id\":\"c1\"}")
            + event("{\"event\":\"agent_thought\",\"id\":\"step-1\",\"tool\":\"getAllPersonVitals\"}")
            + event("{\"event\":\"agent_message\",\"answer\":\"结果\"}")
            + event("{\"event\":\"message\",\"answer\":\"完整结果\"}")
            + event("{\"event\":\"message_end\"}");
        var events = new java.util.ArrayList<DifyService.Progress>();
        var reply = new DifyService(config).sendWithProgress("心率", "test-user", null, events::add);
        assertEquals("完整结果", reply.answer());
        assertEquals(1, events.stream().filter(e -> e.type().equals("tool")).count());
        assertTrue(events.stream().anyMatch(e -> e.text().equals("getAllPersonVitals")));
        assertFalse(events.toString().contains("PRIVATE_"));
        assertEquals("完整结果", events.stream().filter(e -> e.type().equals("answer")).reduce((a, b) -> b).orElseThrow().text());
    }
    @Test void progressArrivesBeforeAgentCompletes() throws Exception {
        config.setMode("agent");
        var sawTool = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.removeContext("/v1/");
        server.createContext("/v1/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                var out = exchange.getResponseBody();
                out.write(event("{\"event\":\"agent_thought\",\"tool\":\"test_tool\",\"id\":\"s1\",\"conversation_id\":\"c1\"}").getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(3, java.util.concurrent.TimeUnit.SECONDS);
                out.write((event("{\"event\":\"agent_message\",\"answer\":\"ok\"}") + event("{\"event\":\"message_end\"}")).getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        var result = java.util.concurrent.CompletableFuture.supplyAsync(() ->
            new DifyService(config).sendWithProgress("test", "test-user", null, e -> {
                if (e.type().equals("tool")) sawTool.countDown();
            }));
        try {
            assertTrue(sawTool.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(result.isDone(), "Progress must be delivered while the upstream stream is still open");
        } finally { release.countDown(); }
        assertEquals("ok", result.get(3, java.util.concurrent.TimeUnit.SECONDS).answer());
    }
    @Test void agentBodyReadHonorsDeadline() {
        config.setMode("agent");
        config.setReadTimeout(Duration.ofMillis(150));
        server.removeContext("/v1/");
        server.createContext("/v1/", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(event("{\"event\":\"agent_thought\"}").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                Thread.sleep(500);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        error("超时");
    }
}
