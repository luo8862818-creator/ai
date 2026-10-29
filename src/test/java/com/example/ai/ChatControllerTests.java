package com.example.ai;

import com.example.ai.controller.ChatController;
import com.example.ai.dto.ChatRequest;
import com.example.ai.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatControllerTests {
    @Test void validation() {
        var service = mock(DifyService.class);
        var controller = new ChatController(service);
        var session = new MockHttpSession();
        assertEquals(400, controller.send(new ChatRequest(" ", "test"), session).getStatusCode().value());
        assertEquals(400, controller.send(new ChatRequest("a".repeat(10001), "test"), session).getStatusCode().value());
        assertEquals(400, controller.send(new ChatRequest("a", null), session).getStatusCode().value());
        verifyNoInteractions(service);
    }
    @Test void sessionReuseAndNewConversation() {
        var service = mock(DifyService.class);
        when(service.send(anyString(), anyString(), nullable(String.class))).thenReturn(new DifyService.Reply("真实测试结果", "conv-1"));
        var controller = new ChatController(service);
        var session = new MockHttpSession();
        controller.send(new ChatRequest("first", "session-1"), session);
        controller.send(new ChatRequest("second", "session-1"), session);
        verify(service).send(eq("second"), anyString(), eq("conv-1"));
        controller.send(new ChatRequest("new", "session-2"), session);
        verify(service).send(eq("new"), anyString(), isNull());
    }
    @Test void failureReleasesBusyState() {
        var service = mock(DifyService.class);
        when(service.send(anyString(), anyString(), nullable(String.class))).thenThrow(new DifyException("超时"));
        var controller = new ChatController(service);
        var session = new MockHttpSession();
        assertEquals(502, controller.send(new ChatRequest("test", "session"), session).getStatusCode().value());
        assertEquals(false, session.getAttribute("busy"));
        session.setAttribute("busy", true);
        assertEquals(409, controller.send(new ChatRequest("test", "session"), session).getStatusCode().value());
    }
    @Test void streamProgressCompletionAndSession() throws Exception {
        var service = mock(DifyService.class);
        when(service.sendWithProgress(anyString(), anyString(), nullable(String.class), any())).thenAnswer(invocation -> {
            java.util.function.Consumer<DifyService.Progress> callback = invocation.getArgument(3);
            callback.accept(new DifyService.Progress("tool", "getAllPersonVitals", "step-1"));
            callback.accept(new DifyService.Progress("answer", "结果", "answer"));
            return new DifyService.Reply("完整结果", "conv-stream");
        });
        var session = new MockHttpSession();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        new ChatController(service).stream(new ChatRequest("test", "session-1"), session, response);
        String body = response.getContentAsString();
        assertTrue(response.getContentType().startsWith("text/event-stream"));
        assertTrue(body.contains("event: tool"));
        assertTrue(body.contains("event: answer"));
        assertTrue(body.contains("event: complete"));
        assertTrue(body.contains("elapsedMs"));
        assertTrue(body.contains("完整结果"));
        assertEquals("conv-stream", session.getAttribute("conversationId"));
        assertEquals(false, session.getAttribute("busy"));
    }
    @Test void streamFailureAndValidation() throws Exception {
        var service = mock(DifyService.class);
        when(service.sendWithProgress(anyString(), anyString(), nullable(String.class), any())).thenThrow(new DifyException("测试超时"));
        var controller = new ChatController(service);
        var session = new MockHttpSession();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        controller.stream(new ChatRequest("test", "session-1"), session, response);
        assertTrue(response.getContentAsString().contains("event: error"));
        assertEquals(false, session.getAttribute("busy"));
        assertEquals(400, controller.stream(new ChatRequest(" ", "session-1"), session,
            new org.springframework.mock.web.MockHttpServletResponse()).getStatusCode().value());
        session.setAttribute("busy", true);
        assertEquals(409, controller.stream(new ChatRequest("test", "session-1"), session,
            new org.springframework.mock.web.MockHttpServletResponse()).getStatusCode().value());
    }
}
