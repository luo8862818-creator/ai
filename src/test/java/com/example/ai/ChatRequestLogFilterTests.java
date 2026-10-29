package com.example.ai;

import com.example.ai.config.ChatRequestLogFilter;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class ChatRequestLogFilterTests {
    @Test void correlatesRequestAndClearsThreadContextOnFailure() {
        var request = new MockHttpServletRequest("POST", "/api/chat/stream");
        request.setServletPath("/api/chat/stream");
        request.addHeader("X-Request-Id", "untrusted-client-input");
        var response = new MockHttpServletResponse();
        assertThrows(ServletException.class, () -> new ChatRequestLogFilter().doFilter(request, response, (req, res) -> {
            String id = response.getHeader("X-Request-Id");
            assertNotNull(id);
            assertEquals(id, MDC.get("requestId"));
            assertNotEquals("untrusted-client-input", id);
            throw new ServletException("test");
        }));
        assertNull(MDC.get("requestId"));
    }
}
