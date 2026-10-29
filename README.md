# Dify 工作助手

## 运行

要求 Java 17、Maven。项目沿用 Spring Boot 4.0.9。

在 D:\project\ai 中执行：

```powershell
mvn spring-boot:run
```

或在 IDEA 运行 `com.example.ai.AiApplication`。

打包与运行：

```powershell
mvn clean verify
java -jar target/ai-0.0.1-SNAPSHOT.jar
```

浏览器访问 http://localhost:8080 。运行 JAR 时请保持当前目录为项目根目录，才能加载本地配置。
原有 Maven Wrapper 配置为 3.10.0，本次使用系统 Maven 3.8.1 验证通过，未更改原 Wrapper。

## 当前真实 Dify 配置

- 服务地址：http://192.168.3.5:18082/v1
- 应用：问题分类 + 知识库 + 聊天机器人
- `/info` 实际返回 `mode: advanced-chat`，因此本地使用 `chatflow`。
- 调用 POST `/chat-messages`，blocking 模式。
- `/parameters` 返回 `user_input_form: []`，不需要额外必填变量。
- 密钥已保存在根目录 `application-local.yaml`，该文件已加入 `.gitignore`，不展示在前端或日志中。
- `application.yaml` 保留基础默认配置，通过 `spring.config.import` 加载本地配置。

本地配置示意（不要复制示意密钥覆盖真实配置）：

```yaml
dify:
  base-url: http://192.168.3.5:18082/v1
  api-key: 你的应用密钥
  mode: chatflow
```

切换普通 Workflow 时必须填写真实节点变量：

```yaml
dify:
  mode: workflow
  input-variable: 开始节点的文本变量名
  output-variable: 结束节点的文本输出变量名
  inputs:
    其他必填变量: 实际值
  connect-timeout: 10s
  read-timeout: 180s
```

支持 DIFY_BASE_URL、DIFY_API_KEY、DIFY_MODE、DIFY_INPUT_VARIABLE、DIFY_OUTPUT_VARIABLE 环境变量（本地配置存在同名值时以本地配置为准）。

## 已验证

- `mvn -B clean verify`：BUILD SUCCESS，17 项测试，0 失败、0 错误、0 跳过。
- 13 项模拟 HTTP 测试：Workflow 正常输入输出、Chatflow 会话复用、401、500、400、超时、执行失败、输出缺失、非法 JSON、空响应、HTTP 200 内嵌鉴权失败、配置缺失、连接失败。
- 3 项 Controller 测试：空消息/超长/会话校验、连续会话与新会话、异常后的忙碌状态释放与重复请求拦截。
- 1 项 Spring Boot 上下文启动测试。
- 真实 Dify HTTP 调用成功：设备电量咨询返回设备电量信息。
- 浏览器真实链路成功：页面 → Spring Boot → Dify → 页面，连续发送两条消息；第二条“设备电量问题”返回设备 1196、1065 的电量及其他设备缺少电量的说明。以上是验收时刻结果，未写入业务逻辑，不代表后续实时状态。
- 浏览器已验证空输入禁用、Enter 发送、Shift+Enter 换行、请求时发送/新建按钮禁用、新建会话清空、390px 手机布局无横向溢出。

## 文件职责

| 文件 | 职责 |
| --- | --- |
| pom.xml | 在现有依赖基础上增加 Thymeleaf |
| src/main/resources/application.yaml | 基础配置、超时、可选本地配置导入 |
| application-local.yaml（不入库） | 本机真实 Dify 地址、模式与密钥 |
| .gitignore | 排除密钥配置和运行日志 |
| src/main/java/com/example/ai/config/DifyConfig.java | Dify 配置绑定 |
| src/main/java/com/example/ai/service/DifyService.java | JDK HTTP 调用、两种模式、响应解析及错误分类 |
| src/main/java/com/example/ai/service/DifyException.java | 可显示给用户的调用错误 |
| src/main/java/com/example/ai/dto/ChatRequest.java | 消息及本地会话标识 |
| src/main/java/com/example/ai/dto/ChatResponse.java | 统一响应结构 |
| src/main/java/com/example/ai/controller/ChatController.java | 参数校验、HTTP 会话、并发限制和 API 返回 |
| src/main/java/com/example/ai/controller/PageController.java | 首页模板入口 |
| src/main/resources/templates/chat.html | 聊天页面结构 |
| src/main/resources/static/css/chat.css | 浅色桌面与手机样式 |
| src/main/resources/static/js/chat.js | 安全消息渲染、发送、加载态及新建会话 |
| src/main/resources/static/favicon.svg | 浏览器图标 |
| src/test/java/com/example/ai/DifyServiceTests.java | 模拟上游 HTTP 测试 |
| src/test/java/com/example/ai/ChatControllerTests.java | 输入、会话及并发状态测试 |
| src/test/java/com/example/ai/AiApplicationTests.java | 原有启动测试，保留 |
| README.md | 运行、配置、验证结果与限制 |

## 使用和限制

页面可直接点击“查询设备电量”，也可输入“设备电量问题”。业务能力和结果由现有 Dify 分类器、知识库及工具决定，前端不会编造设备数据。不同提问措辞可能被分类器分流到不同分支；如需保证所有同义提问都查询设备，应在 Dify 调整分类提示词与测试集。

Chatflow conversation_id 存在服务器 HttpSession，用户身份与浏览器会话绑定；新建会话使用新的本地 UUID，避免向前端暴露 Dify conversation_id。当前仅维护一个活动聊天，刷新后页面消息清空，服务重启后内存会话丢失。普通 Workflow 没有自动跨轮记忆。

不含数据库、登录、文件上传、流式输出。文本通过 textContent 渲染，Markdown 符号按原文显示。同步调用超时可配置；超时不代表 Dify 任务被取消，涉及写入操作时应先查运行记录，避免重试重复执行。

当前设备接口的返回可能出现电量百分比与 batLow 标志不一致，这属于上游数据；页面按 Dify 原始回答展示。未对生产数据执行写入任务，也未修改 Dify 工作流。

模拟测试不替代真实 Dify 失败场景；401/500/超时等通过本地 HTTP 模拟服务验证，未故意破坏真实服务制造故障。

补充浏览器验证：模拟 502 错误正常显示；包含 HTML 的错误文本只显示原文，没有创建图片节点，也没有执行脚本。最终页面更新后再次执行 mvn -B verify，17 项测试全部通过。

## 客服窗口界面更新
页面现为桌面 460px 宽的客服聊天小窗口，手机全屏自适应；删除左侧栏与历史聊天。右上角刷新按钮重新加载页面、清空消息并生成新的本地会话标识，请求中禁用刷新以避免误操作。现有 Dify 接口配置保持不变。
本次纯前端调整使用 mvn -B package -DskipTests 打包，后端测试未重复执行。
本次浏览器验收：真实设备电量咨询成功；刷新后消息数为 0；390px 手机屏幕无横向溢出；页面无 JavaScript 异常。页面资源已增加版本标识以避免旧浏览器缓存干扰。

## AI 工作测试界面更新
标题改为 AI 工作测试，保留居中单聊天窗口及刷新按钮，机器人 SVG 头像用于标题、欢迎区域和 AI 回复。支持安全 DOM 排版：段落、加粗、代码、标题、列表及可横向滚动的表格。未引入外部 Markdown 依赖。
本次打包成功；浏览器使用模拟回复验证了编号列表、表格、机器人头像、居中及 390px 手机布局，HTML 不会执行，页面无脚本异常。Dify 后端未改动，本次未重复真实 Dify 调用或后端测试。

## 通过配置选择 Agent / Workflow / Chatflow

编辑项目根目录 `application-local.yaml`，修改 `dify.mode` 并重启应用：

| mode | 应用类型 | 上游接口 | 会话 |
| --- | --- | --- | --- |
| agent | Dify Agent | /chat-messages，streaming | 自动复用 conversation_id |
| agent-chat | 旧版 Dify Agent | /chat-messages，streaming | 自动复用 conversation_id |
| workflow | 普通工作流 | /workflows/run，blocking | 独立执行，无自动多轮记忆 |
| chatflow | 对话工作流 | /chat-messages，blocking | 自动复用 conversation_id |

Agent 示例：

```yaml
dify:
  base-url: http://192.168.3.5:18082/v1
  api-key: 你的Agent应用密钥
  mode: agent
  inputs: {}
```

工作流示例：

```yaml
dify:
  base-url: http://192.168.3.5:18082/v1
  api-key: 你的Workflow应用密钥
  mode: workflow
  input-variable: 你的开始节点文本变量名
  output-variable: 你的结束节点文本变量名
  inputs: {}
```

切换时必须使用目标应用的 API Key，模式名不会将同一应用转换成其他类型。额外必填输入放在 `dify.inputs`；工作流的文本消息会覆盖 `input-variable` 对应的值。完整配置示例在 `application-dify.example.yaml`，此文件不会自动加载。本地文件优先级高于 application.yaml，若本地仍配置 chatflow，仅修改 application.yaml 默认值不会切换。

Agent 的 SSE 事件由后端汇总，页面显示最终答案；不会展示 agent_thought 或工具调用细节。新版 Agent 最终完整 message 覆盖增量片段，避免答案重复；message_replace 同样覆盖已有答案。收到 error、缺少结束事件、缺少会话 ID、空答案会明确报错。超时由 read-timeout 控制，超时不意味着上游任务停止。

参考 Dify 官方文档：https://docs.dify.ai/en/api-reference/guides/agent 及 https://docs.dify.ai/en/api-reference/guides/chat 。

本次模式选择更新验证：mvn -B test 共 25 项测试全部通过，新增 8 项覆盖 Agent SSE 与模式校验。Agent 与普通 Workflow 使用模拟 HTTP 响应验证；本次未获得对应的新应用密钥，未声称完成两类新应用的真实联调。本地现有 chatflow 配置保持不变。

## Agent 真实联调修复（2026-10-28）

本次发现 application-local.yaml 中保留了旧 Chatflow 密钥，覆盖了 application.yaml 中新填写的 Agent 密钥，导致实际调用的应用与 mode 不一致。已将本地密钥更新为用户指定的 Agent 应用密钥，保留 mode: agent 和正确 API 基址 http://192.168.3.5:18082/v1；从公共 application.yaml 移除真实密钥，密钥统一维护在被 Git 忽略的本地配置中。

已使用真实密钥读取 /info，确认应用名 demo，mode 为 agent；/parameters 没有额外必填变量。Agent 网页地址 /agent/OhrMQHHUdpPvdcCY 是 Web 应用入口，后端调用地址仍为 /v1/chat-messages。

已增加 Agent 返回非 SSE JSON 时的鉴权/应用配置错误提示，并把失效会话提示改为“刷新页面”。新增两项测试覆盖网关 JSON 鉴权失败与错误应用 JSON 响应。mvn -B verify：27 项测试全部通过，BUILD SUCCESS；已重新打包并启动。

浏览器真实链路验收：发送“你好，请用一句话介绍你能做什么。”成功返回健康监护与系统运维能力说明；继续询问“你刚才提到哪些能力？”成功返回承接上文的回答。两轮均无错误消息、无残留等待状态，页面无 JavaScript 异常。测试没有修改 Dify 应用配置。
追加真实业务提问：查询设备电量时，Agent 返回“当前系统未配置设备状态查询工具，无法为您查询设备电量”。消息链路正常，但此 Agent 的设备查询能力需要在 Dify 配置工具后验证，不能把此前 Chatflow 的查询能力视为此 Agent 已具备的能力。

## 实时处理过程与计时（2026-10-28）

- 桌面聊天窗口由 460px 加宽为 820px，手机自适应；答案表格支持内部横向滚动。
- 新增 POST /api/chat/stream，以 SSE 推送 status、tool、answer、complete、error；原有 /send JSON 接口继续兼容。
- Agent 上游逐帧读取，答案实时更新；最终完整 message 覆盖增量答案。收到 message_end 即停止等待并关闭上游连接。
- 处理卡片显示实时秒数，完成后使用后端 elapsedMs 显示实际处理耗时；用时包含网络连接、等待、工具执行及答案生成，并非模型纯推理时间。过程内的时间是从请求开始的累计时刻，不是各工具单独执行时长。
- 过程可展开/收起，记录公开阶段和真实工具名称。只从 agent_thought 中提取 tool 和事件标识，不向前端转发 thought、observation、tool_input 或模型私有推理内容；未返回工具事件时不会编造工具记录。
- Workflow / Chatflow 保留 blocking 上游调用，页面仍有实时总计时、开始和完成状态；不伪造内部节点进度。
- SSE 请求沿用 HttpSession 会话及重复提交拦截；失败也保留实际用时和错误信息，不会一直停在等待状态。未完成的部分答案标明未完成。
- Agent 流式读取增加独立的总超时关闭，避免响应头返回后长时间读取不受 HttpRequest 超时约束。关闭页面会结束本地读取，不保证取消上游工具任务。
- 当前采用 Servlet 请求线程处理一条流式连接，适用于本地低并发使用；未引入数据库、WebSocket、第三方前端框架。

验证：32 项自动化测试全部通过，Maven 打包成功。新增测试覆盖实时事件先于最终完成到达、工具事件去重、禁止私有字段输出、响应体读取超时、流式接口完成/错误/会话/重复请求校验。浏览器真实 Agent 问候耗时 8.13 秒；真实心率查询耗时 6.53 秒，页面展示了 getAllPersonVitals 工具调用及实际返回结果。具体数值仅为验收时刻结果。已验证 0.00 → 1.11 秒实时更新、完成后计时停止、展开过程、390px 手机无横向溢出。

协议参考：https://docs.dify.ai/en/api-reference/guides/agent 。
补充浏览器验证：将 SSE 按每 3 字节拆分，中文与 CRLF 帧均正确解析；模拟错误后显示 1.25 秒并停止等待，HTML 错误文本不会执行；刷新后清空聊天。
