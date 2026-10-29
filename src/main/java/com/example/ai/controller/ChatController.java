package com.example.ai.controller;

import com.example.ai.dto.*;
import com.example.ai.service.*;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private final DifyService service;
    public ChatController(DifyService service) { this.service = service; }
    @GetMapping("/suggestions")
    public List<String> suggestions() { return service.suggestions(); }

    @PostMapping("/send")
    public ResponseEntity<ChatResponse> send(@RequestBody ChatRequest request, HttpSession session) {
        if (request.message() == null || request.message().isBlank()) return bad("消息不能为空");
        if (request.message().length() > 10000) return bad("消息不能超过 10000 个字符");
        String id = request.sessionId();
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}")) return bad("会话标识无效，请刷新页面后重试");
        synchronized (session) {
            if (Boolean.TRUE.equals(session.getAttribute("busy"))) return ResponseEntity.status(409).body(ChatResponse.error("当前会话正在处理请求，请稍候"));
            session.setAttribute("busy", true);
        }
        try {
            String user = session.getId() + "-" + id;
            String conversation;
            synchronized (session) {
                if (!id.equals(session.getAttribute("localId"))) {
                    session.setAttribute("localId", id);
                    session.removeAttribute("conversationId");
                }
                conversation = (String) session.getAttribute("conversationId");
            }
            log.info("[CHAT] 收到用户消息 sessionId={}", id);
            DifyService.Reply reply = service.send(request.message().strip(), user, conversation);
            if (reply.conversationId() != null) session.setAttribute("conversationId", reply.conversationId());
            log.info("[CHAT] 成功返回 AI 答案 sessionId={}", id);
            return ResponseEntity.ok(ChatResponse.ok(reply.answer(), id));
        } catch (DifyException e) {
            log.warn("[CHAT] 返回错误：{}", e.getMessage());
            return ResponseEntity.status(502).body(ChatResponse.error(e.getMessage()));
        } finally { session.setAttribute("busy", false); }
    }
    // Stream public progress and answer updates using the existing servlet connection.
    @PostMapping("/stream")
    public ResponseEntity<ChatResponse> stream(@RequestBody ChatRequest request, HttpSession session,
                                               HttpServletResponse response) throws IOException {
        if (request.message() == null || request.message().isBlank()) return bad("消息不能为空");
        if (request.message().length() > 10000) return bad("消息不能超过 10000 个字符");
        String id = request.sessionId();
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}")) return bad("会话标识无效，请刷新页面后重试");
        synchronized (session) {
            if (Boolean.TRUE.equals(session.getAttribute("busy")))
                return ResponseEntity.status(409).body(ChatResponse.error("当前会话正在处理请求，请稍候"));
            session.setAttribute("busy", true);
        }
        long started = System.nanoTime();
        try {
            response.setContentType("text/event-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control", "no-cache, no-transform");
            response.setHeader("X-Accel-Buffering", "no");
            emit(response, "status", Map.of("text", "已收到问题，准备处理", "stepId", "received", "elapsedMs", 0));
            String conversation;
            synchronized (session) {
                if (!id.equals(session.getAttribute("localId"))) {
                    session.setAttribute("localId", id);
                    session.removeAttribute("conversationId");
                }
                conversation = (String) session.getAttribute("conversationId");
            }
            log.info("[CHAT] 开始流式请求 sessionId={}", id);
            DifyService.Reply reply = service.sendWithProgress(request.message().strip(), session.getId() + "-" + id,
                conversation, progress -> {
                    try {
                        emit(response, progress.type(), Map.of("text", progress.text(), "stepId", progress.stepId(),
                            "elapsedMs", elapsed(started)));
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
            if (reply.conversationId() != null) session.setAttribute("conversationId", reply.conversationId());
            emit(response, "complete", Map.of("answer", reply.answer(), "sessionId", id, "elapsedMs", elapsed(started)));
            log.info("[CHAT] 流式回复完成 sessionId={} elapsedMs={}", id, elapsed(started));
        } catch (DifyException e) {
            log.warn("[CHAT] 流式请求失败：{}", e.getMessage());
            emit(response, "error", Map.of("message", e.getMessage(), "elapsedMs", elapsed(started)));
        } catch (UncheckedIOException e) {
            log.info("[CHAT] 浏览器已断开流式连接 sessionId={}", id);
        } catch (RuntimeException e) {
            log.error("[CHAT] 流式处理发生异常，类型={}", e.getClass().getSimpleName());
            emit(response, "error", Map.of("message", "服务处理异常，请检查后端日志", "elapsedMs", elapsed(started)));
        } finally { session.setAttribute("busy", false); }
        return null;
    }
    private long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private final JsonMapper streamJson = JsonMapper.builder().build();
    private void emit(HttpServletResponse response, String event, Object data) throws IOException {
        response.getWriter().write("event: " + event + "\ndata: " + streamJson.writeValueAsString(data) + "\n\n");
        response.getWriter().flush();
        response.flushBuffer();
    }
    private ResponseEntity<ChatResponse> bad(String message) { return ResponseEntity.badRequest().body(ChatResponse.error(message)); }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ChatResponse> invalidJson() { return bad("请求格式错误，请发送有效的 JSON 消息"); }
}


