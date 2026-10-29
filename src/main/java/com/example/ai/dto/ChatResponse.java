package com.example.ai.dto;

public record ChatResponse(boolean success, String message, Data data) {
    public record Data(String answer, String sessionId) {}
    public static ChatResponse ok(String answer, String sessionId) {
        return new ChatResponse(true, "请求成功", new Data(answer, sessionId));
    }
    public static ChatResponse error(String message) { return new ChatResponse(false, message, null); }
}
