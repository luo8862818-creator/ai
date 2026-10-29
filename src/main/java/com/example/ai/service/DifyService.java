package com.example.ai.service;

import com.example.ai.config.DifyConfig;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DifyService {
    private static final Logger log = LoggerFactory.getLogger(DifyService.class);
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "dify-response-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final DifyConfig config;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();
    public record Reply(String answer, String conversationId) {}
    // Only these public progress fields may leave the backend. Never forward raw thought/tool payloads.
    public record Progress(String type, String text, String stepId) {}

    public DifyService(DifyConfig config) {
        this.config = config;
        client = HttpClient.newBuilder().connectTimeout(config.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    // Read published opening questions; tool availability must be curated in Dify.
    public List<String> suggestions() {
        if (config.getApiKey().isBlank() || "workflow".equals(config.getMode())) return List.of();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.getBaseUrl().replaceAll("/+$", "") + "/parameters"))
                .timeout(config.getConnectTimeout())
                .header("Authorization", "Bearer " + config.getApiKey()).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            checkHttpStatus(response.statusCode());
            JsonNode questions = readJson(response.body()).path("suggested_questions");
            List<String> result = new ArrayList<>();
            if (questions.isArray()) for (JsonNode question : questions) {
                if (question.isString() && !question.asString().isBlank() && question.asString().length() <= 200)
                    result.add(question.asString());
                if (result.size() == 6) break;
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (IOException | RuntimeException e) {
            log.warn("[DIFY] 无法读取开场推荐问题");
            return List.of();
        }
    }

    public Reply send(String message, String user, String conversationId) {
        return sendWithProgress(message, user, conversationId, progress -> {});
    }

    public Reply sendWithProgress(String message, String user, String conversationId, Consumer<Progress> progress) {
        if (config.getApiKey().isBlank()) throw new DifyException("请配置 Dify API Key");
        boolean agent = "agent".equals(config.getMode()) || "agent-chat".equals(config.getMode());
        boolean chat = agent || "chatflow".equals(config.getMode());
        if (!chat && !"workflow".equals(config.getMode())) throw new DifyException("dify.mode 仅支持 agent、agent-chat、workflow 或 chatflow");
        if (!chat && (config.getInputVariable().isBlank() || config.getOutputVariable().isBlank()))
            throw new DifyException("请在 application-local.yaml 配置工作流的 input-variable 和 output-variable，名称必须与 Dify 节点一致");
        Map<String, Object> inputs = new LinkedHashMap<>(config.getInputs());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", inputs);
        body.put("response_mode", agent ? "streaming" : "blocking");
        body.put("user", user);
        if (chat) {
            body.put("query", message);
            body.put("conversation_id", conversationId == null ? "" : conversationId);
        } else inputs.put(config.getInputVariable(), message);
        String url = config.getBaseUrl().replaceAll("/+$", "") + (chat ? "/chat-messages" : "/workflows/run");
        log.info("[DIFY] 请求模式 {}，准备调用 Dify", config.getMode());
        progress.accept(new Progress("status", agent ? "正在连接 Agent" : "正在执行工作流", "connect"));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(config.getReadTimeout())
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            if (agent) return requestAgent(req, progress);
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            checkHttpStatus(response.statusCode());
            JsonNode root = readJson(response.body());
            int code = root.path("code").asInt(0);
            if (code == 401 || code == 403) throw new DifyException("API 网关返回认证失败，请确认当前地址是否为 Dify 服务 API");
            if (root.has("code") && !root.has("answer") && !root.has("data")) throw new DifyException("Dify 返回业务错误，请检查应用配置和服务日志");
            if (chat) {
                String answer = text(root.path("answer"));
                String next = text(root.path("conversation_id"));
                return new Reply(answer, next);
            }
            JsonNode data = root.path("data");
            String state = data.path("status").asString("");
            log.info("[DIFY] 工作流执行状态 {}", state);
            if (!"succeeded".equals(state)) throw new DifyException(state.isBlank() ? "Dify 返回格式异常：缺少工作流执行状态" : "Dify 工作流执行失败（" + state + "），请在 Dify 运行日志中查看失败节点");
            JsonNode output = data.path("outputs").path(config.getOutputVariable());
            if (output.isMissingNode() || output.isNull()) throw new DifyException("工作流输出变量不存在，请检查 output-variable 配置");
            return new Reply(text(output), null);
        } catch (HttpTimeoutException e) {
            throw new DifyException("Dify 请求超时，任务可能仍在运行，请先检查运行记录，避免重复执行任务");
        } catch (IOException e) {
            throw new DifyException("无法连接 Dify 服务或连接已中断，请检查网络、地址和端口");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DifyException("Dify 请求已中断");
        } catch (IllegalArgumentException e) {
            throw new DifyException("Dify 配置无效，请检查 API 地址、密钥格式和超时时间");
        }
    }

    private Reply requestAgent(HttpRequest request, Consumer<Progress> progress) throws IOException, InterruptedException {
        long started = System.nanoTime();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            checkHttpStatus(response.statusCode());
            AtomicBoolean timedOut = new AtomicBoolean();
            // HttpRequest timeout alone does not cover reads after an InputStream body has been returned.
            long remaining = Math.max(1, config.getReadTimeout().toNanos() - (System.nanoTime() - started));
            ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
                timedOut.set(true);
                try { input.close(); } catch (IOException ignored) { /* reader reports failure */ }
            }, remaining, TimeUnit.NANOSECONDS);
            try {
                AgentStream state = new AgentStream(progress);
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                StringBuilder data = new StringBuilder();
                progress.accept(new Progress("status", "Agent 已连接，正在处理", "connected"));
                String line;
                while (!state.completed && (line = reader.readLine()) != null) {
                    if (line.stripLeading().startsWith("{") && data.isEmpty()) {
                        JsonNode error = readJson(line);
                        int code = error.path("code").asInt(0);
                        if (code == 401 || code == 403)
                            throw new DifyException("Dify Agent 鉴权失败，请检查 application-local.yaml 中实际生效的应用密钥");
                        throw new DifyException("Dify Agent 未返回流式回复，请检查模式、对应应用密钥和 API 地址");
                    }
                    if (line.isEmpty()) {
                        state.accept(data.toString());
                        data.setLength(0);
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                    }
                }
                if (timedOut.get()) throw new HttpTimeoutException("Agent response deadline exceeded");
                if (!data.isEmpty() && !state.completed) state.accept(data.toString());
                return state.reply();
            } catch (IOException e) {
                if (timedOut.get()) throw new HttpTimeoutException("Agent response deadline exceeded");
                throw e;
            } finally { deadline.cancel(false); }
        }
    }

    private class AgentStream {
        final Consumer<Progress> progress;
        final StringBuilder answer = new StringBuilder();
        final Set<String> reportedTools = new HashSet<>();
        String conversation = "";
        boolean completed;
        boolean generating;
        AgentStream(Consumer<Progress> progress) { this.progress = progress; }
        void accept(String data) {
            if (data.isBlank() || "[DONE]".equals(data)) return;
            JsonNode event;
            try { event = readJson(data); }
            catch (DifyException e) { throw new DifyException("Dify Agent 流式返回格式异常"); }
            String kind = event.path("event").asString("");
            if ("error".equals(kind)) throw new DifyException("Dify Agent 执行失败，请检查应用模型、工具配置和 Dify 运行日志");
            String id = event.path("conversation_id").asString("");
            if (!id.isBlank()) conversation = id;
            switch (kind) {
                case "agent_thought" -> {
                    // Expose tool identifiers only, never thought, observation, tool_input or reasoning.
                    String tool = event.path("tool").asString("");
                    String step = event.path("id").asString(event.path("position").asString(""));
                    if (!tool.isBlank()) {
                        String key = step + ":" + tool;
                        if (reportedTools.add(key)) progress.accept(new Progress("tool", tool, key));
                    }
                }
                case "agent_message", "message", "message_replace" -> {
                    String chunk = event.path("answer").asString("");
                    if (!generating && !chunk.isEmpty()) {
                        generating = true;
                        progress.accept(new Progress("status", "正在生成回复", "answer"));
                    }
                    if (!"agent_message".equals(kind)) answer.setLength(0);
                    answer.append(chunk);
                    progress.accept(new Progress("answer", answer.toString(), "answer"));
                }
                case "message_end" -> completed = true;
                default -> { /* Heartbeats and private/internal fields are ignored. */ }
            }
        }
        Reply reply() {
            if (!completed) throw new DifyException("Dify Agent 回复未完整结束，请检查运行记录后再重试");
            if (answer.toString().isBlank()) throw new DifyException("Dify Agent 未返回文本答案，请检查应用配置");
            if (conversation.isBlank()) throw new DifyException("Dify Agent 返回格式异常：缺少 conversation_id");
            return new Reply(answer.toString(), conversation);
        }
    }

    private void checkHttpStatus(int status) {
        log.info("[DIFY] HTTP 状态 {}", status);
        if (status == 401 || status == 403) throw new DifyException("Dify API 鉴权失败，请检查密钥、应用类型和网关地址");
        if (status == 429) throw new DifyException("Dify 请求过于频繁或配额不足，请稍后重试");
        if (status >= 500) throw new DifyException("Dify 服务异常（HTTP " + status + "），请检查 Dify 服务日志");
        if (status == 400 || status == 422) throw new DifyException("Dify 请求参数不匹配，请检查应用类型、输入变量及必填参数");
        if (status < 200 || status >= 300) throw new DifyException("Dify 请求失败（HTTP " + status + "），请检查 API 地址和应用配置");
    }
    private JsonNode readJson(String body) {
        JsonNode root;
        try { root = json.readTree(body); }
        catch (RuntimeException e) { throw new DifyException("Dify 返回格式异常，预期 JSON，请检查 API 地址或网关"); }
        if (root == null || !root.isObject()) throw new DifyException("Dify 返回格式异常或空响应");
        return root;
    }
    private String text(JsonNode value) {
        if (!value.isString() || value.asString().isBlank()) throw new DifyException("Dify 文本输出为空或类型不是文本，请检查输出节点");
        return value.asString();
    }
}
