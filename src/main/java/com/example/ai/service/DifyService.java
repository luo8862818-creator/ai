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
import org.slf4j.MDC;
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
            log.warn("[DIFY] 无法读取开场推荐问题 exceptionType={}", e.getClass().getSimpleName());
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
        log.info("[DIFY] request mode={} path={} timeoutMs={} messageChars={} continuingConversation={}",
            config.getMode(), chat ? "/chat-messages" : "/workflows/run", config.getReadTimeout().toMillis(),
            message.length(), conversationId != null && !conversationId.isBlank());
        progress.accept(new Progress("status", agent ? "正在连接 Agent" : "正在执行工作流", "connect"));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(config.getReadTimeout())
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", agent ? "text/event-stream" : "application/json")
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
            log.warn("[DIFY] timeout exceptionType={} limitMs={}", e.getClass().getSimpleName(), config.getReadTimeout().toMillis());
            throw new DifyException("Dify 请求超时，任务可能仍在运行，请先检查运行记录，避免重复执行任务");
        } catch (IOException e) {
            log.warn("[DIFY] network_failure exceptionType={} causeType={}", e.getClass().getSimpleName(), e.getCause() == null ? "none" : e.getCause().getClass().getSimpleName());
            throw new DifyException("无法连接 Dify 服务或连接已中断，请检查网络、地址和端口");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DifyException("Dify 请求已中断");
        } catch (IllegalArgumentException e) {
            throw new DifyException("Dify 配置无效，请检查 API 地址、密钥格式和超时时间");
        }
    }

    private Reply requestAgent(HttpRequest request, Consumer<Progress> progress) throws IOException, InterruptedException {
        try (StreamStats stats = new StreamStats()) {
        long started = stats.started;
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            stats.headers = true;
            log.info("[DIFY] stream_headers status={} elapsedMs={} contentType={}", response.statusCode(), stats.elapsedMs(),
                safe(response.headers().firstValue("Content-Type").orElse("unspecified")));
            checkHttpStatus(response.statusCode());
            AtomicBoolean timedOut = new AtomicBoolean();
            // HttpRequest timeout alone does not cover reads after an InputStream body has been returned.
            long remaining = Math.max(1, config.getReadTimeout().toNanos() - (System.nanoTime() - started));
            ScheduledFuture<?> deadline = DEADLINES.schedule(() -> {
                timedOut.set(true);
                try { input.close(); } catch (IOException ignored) { /* reader reports failure */ }
            }, remaining, TimeUnit.NANOSECONDS);
            try {
                AgentStream state = new AgentStream(progress, stats);
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                StringBuilder data = new StringBuilder();
                progress.accept(new Progress("status", "Agent 已连接，正在处理", "connected"));
                String line;
                while (!state.completed && (line = reader.readLine()) != null) {
                    stats.lastReceived = System.nanoTime();
                    if (line.length() + data.length() > 262144)
                        throw new DifyException("Dify 单个流式事件过大，已停止接收，请检查 Agent 输出");
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
                Reply reply = state.reply();
                stats.completed = true;
                return reply;
            } catch (IOException e) {
                if (timedOut.get()) throw new HttpTimeoutException("Agent response deadline exceeded");
                throw e;
            } finally { deadline.cancel(false); }
        }
        }
    }

    private class AgentStream {
        final Consumer<Progress> progress;
        final StringBuilder answer = new StringBuilder();
        final Set<String> reportedTools = new HashSet<>();
        String conversation = "";
        boolean completed;
        boolean generating;
        final StreamStats stats;
        boolean sawAgentMessage;
        AgentStream(Consumer<Progress> progress, StreamStats stats) { this.progress = progress; this.stats = stats; }
        void accept(String data) {
            if (completed || data.isBlank() || "[DONE]".equals(data)) return;
            JsonNode event;
            try { event = readJson(data); }
            catch (DifyException e) { throw new DifyException("Dify Agent 流式返回格式异常"); }
            String kind = event.path("event").asString("");
            stats.event(event, kind);
            if (stats.events > config.getMaxStreamEvents())
                throw new DifyException("Dify 事件数量超过限制，已停止接收，请检查 Agent 是否循环执行");
            if ("error".equals(kind)) {
                log.warn("[DIFY] upstream_error code={} status={} taskId={} messageId={}",
                    safe(event.path("code").asString("")), event.path("status").asInt(0), stats.taskId, stats.messageId);
                throw new DifyException("Dify Agent 执行失败，请检查应用模型、工具配置和 Dify 运行日志");
            }
            String id = event.path("conversation_id").asString("");
            if (!id.isBlank()) conversation = id;
            switch (kind) {
                case "agent_thought" -> {
                    // Expose tool identifiers only, never thought, observation, tool_input or reasoning.
                    String tool = event.path("tool").asString("");
                    String step = event.path("id").asString(event.path("position").asString(""));
                    if (!tool.isBlank()) {
                        String key = step + ":" + tool;
                        if (reportedTools.add(key)) {
                            log.info("[DIFY] tool stepId={} tool={} elapsedMs={}", safe(step), safe(tool), stats.elapsedMs());
                            progress.accept(new Progress("tool", tool, key));
                        }
                    }
                }
                case "agent_message", "message", "message_replace" -> {
                    String chunk = event.path("answer").asString("");
                    if (!generating && !chunk.isEmpty()) {
                        generating = true;
                        progress.accept(new Progress("status", "正在生成回复", "answer"));
                    }
                    // Agent's closing message is a complete snapshot. Plain message-only streams use deltas.
                    boolean replace = "message_replace".equals(kind) || ("message".equals(kind) && sawAgentMessage);
                    if ((replace ? 0L : answer.length()) + chunk.length() > config.getMaxAnswerChars())
                        throw new DifyException("Dify 回答长度超过限制，已停止接收，请检查 Agent 是否重复输出");
                    if (replace) answer.setLength(0);
                    if ("agent_message".equals(kind)) sawAgentMessage = true;
                    answer.append(chunk);
                    stats.answer(answer.length());
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

    // Explicit allowlist: never log prompts, answers, thought, tool input/output, or Authorization.
    private static String safe(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[^a-zA-Z0-9_.:/,;= +-]", "_");
        return cleaned.substring(0, Math.min(160, cleaned.length()));
    }

    private class StreamStats implements AutoCloseable {
        final long started = System.nanoTime();
        final String requestId = Optional.ofNullable(MDC.get("requestId")).orElse("direct-call");
        final ConcurrentMap<String, java.util.concurrent.atomic.LongAdder> counts = new ConcurrentHashMap<>();
        volatile long lastReceived = started;
        volatile long events;
        volatile int answerChars;
        volatile boolean headers;
        volatile boolean completed;
        volatile String taskId = "", messageId = "", conversationId = "";
        boolean firstAnswer;
        final ScheduledFuture<?> heartbeat = DEADLINES.scheduleAtFixedRate(() -> summary("waiting"), 15, 15, TimeUnit.SECONDS);
        long elapsedMs() { return (System.nanoTime() - started) / 1_000_000; }
        void event(JsonNode event, String kind) {
            events++;
            // Bound counter keys even if the upstream emits arbitrary event names.
            String key = switch (kind) {
                case "agent_message", "message", "message_replace", "agent_thought", "message_end", "error", "ping",
                     "workflow_started", "workflow_finished", "node_started", "node_finished" -> kind;
                default -> "other";
            };
            counts.computeIfAbsent(key, ignored -> new java.util.concurrent.atomic.LongAdder()).increment();
            if (event.has("task_id")) taskId = safe(event.path("task_id").asString(""));
            if (event.has("message_id")) messageId = safe(event.path("message_id").asString(""));
            if (event.has("conversation_id")) conversationId = safe(event.path("conversation_id").asString(""));
            if (events == 1) log.info("[DIFY] first_event type={} elapsedMs={} taskId={} messageId={}", safe(kind), elapsedMs(), taskId, messageId);
            log.debug("[DIFY] event type={} index={} answerChunkChars={} elapsedMs={}", safe(kind), events, event.path("answer").asString("").length(), elapsedMs());
        }
        void answer(int chars) {
            answerChars = chars;
            if (!firstAnswer && chars > 0) {
                firstAnswer = true;
                log.info("[DIFY] first_answer elapsedMs={} taskId={}", elapsedMs(), taskId);
            }
        }
        void summary(String state) {
            log.info("[DIFY] stream_summary requestId={} state={} headers={} elapsedMs={} idleMs={} events={} answerChars={} eventCounts={} taskId={} messageId={} conversationId={}",
                requestId, state, headers, elapsedMs(), (System.nanoTime() - lastReceived) / 1_000_000,
                events, answerChars, counts, taskId, messageId, conversationId);
        }
        @Override public void close() {
            heartbeat.cancel(false);
            summary(completed ? "completed" : "incomplete");
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
