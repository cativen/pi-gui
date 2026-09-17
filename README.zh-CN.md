# Pi GUI — pi AI 编程智能体的 JetBrains 插件

[English](README.md) | **简体中文**

这是一个使用 Kotlin、JCEF 和 Swing 开发的 JetBrains 插件，可以在 IDE 内直接使用
[pi 编程智能体](https://github.com/earendil-works/pi)。聊天和设置界面使用 IDE 内置的 JCEF
运行时，项目集成、文件选择器、差异对比和确认对话框则使用 IntelliJ Platform 原生组件。
插件不启动本地 Web 服务器，也不加载远程 UI 页面。

功能设计参考了 [pi-web](https://github.com/agegr/pi-web)，但没有与其共享代码。

## 安装

1. 构建插件，或直接使用预构建的 ZIP：

   ```bash
   ./gradlew buildPlugin
   ```

   构建产物位于 `build/distributions/pi-gui-1.0.2.zip`。

2. 在 IDE 中打开 **Settings → Plugins → ⚙ → Install Plugin from Disk…**，选择 ZIP 文件并重启 IDE。

3. 使用 **Alt+Shift+P**，或点击 IDE 右侧的 **Pi GUI** 工具窗口打开插件。

### 环境要求

- JetBrains IDE 构建号 **243**（2024.3）或更高；已在 IntelliJ IDEA 2026.1 上验证。
- 本机已安装 `pi` CLI：

  ```bash
  npm i -g @earendil-works/pi-coding-agent
  ```

  插件会通过登录 Shell 的 `PATH` 查找 `pi`，因此支持 nvm、fnm 和 volta 等安装方式。
  如果安装位置比较特殊，可以在 **Settings → Tools → Pi GUI** 中手动指定路径。

### AI 凭据访问与隐私

未经你的明确授权，Pi GUI 不会读取 cc-switch 或已导入的 AI 凭据。首次导入前，权限弹窗会明确说明
Pi GUI 可能读取：

- `~/.cc-switch/cc-switch.db`、`~/.cc-switch/config.json`，或你选择的 `cc-switch.db` 文件；
- 环境变量 `ANTHROPIC_AUTH_TOKEN`、`ANTHROPIC_API_KEY` 或 `OPENAI_API_KEY`；
- 供应商 Base URL 和模型名称。

你选择的配置只会复制到本机的 `~/.pi/agent/providers.import.json` 和
`~/.pi/agent/models.json` 中由 Pi GUI 管理的条目，让本机安装的 pi CLI 可以向所选供应商进行身份验证。
凭据保留在本机；Pi GUI 不会把凭据发送给插件开发者，也不会收集遥测数据。只有在使用对应供应商时，
本机 pi CLI 才会把凭据发送给所选 AI 供应商。Pi GUI 不会读取完整的环境变量。如果拒绝授权，Pi GUI
不会读取凭据文件或 AI 环境变量，供应商导入功能将保持禁用。

## 功能

- **会话侧边栏** — 按时间倒序显示当前项目的所有 pi 会话。会话直接从
  `~/.pi/agent/sessions/` 读取，因此无需启动智能体即可立即打开历史记录。按 Delete 键可以删除会话。
- **流式聊天** — 实时显示智能体文本、思考过程和工具调用。
- **富文本渲染** — 支持 Markdown 标题、列表、表格、引用、链接和行内代码；围栏代码块使用
  IDE 语法高亮，并提供复制按钮。
- **可折叠详情** — 思考过程、工具调用和工具结果默认折叠，点击即可展开，让对话保持清晰。
- **斜杠命令** — 在输入框开头输入 `/`，即可补全 pi 的全部命令，包括 23 个内置命令
  （`/model`、`/new`、`/tree`、`/reload` 等），以及 `get_commands` 返回的扩展命令、
  提示词模板和技能。使用方向键移动，Enter 或 Tab 完成选择，Escape 关闭列表。内置命令会立即显示，
  其余命令会在 pi 返回结果后加入列表。

  pi 的内置命令原本只在终端 UI 中执行；如果把 `/new` 当作普通提示词发送，它只会作为文字进入模型。
  因此插件会自行处理这些命令：通过相同的 RPC 命令实现 `/compact`、`/session`、`/name`、
  `/copy`、`/export`、`/clone`、`/fork` 和 `/tree`，并通过插件界面处理 `/settings`、
  `/model`、`/thinking`、`/new`、`/resume`、`/hotkeys`、`/reload` 和 `/quit`。
  必须在 pi 终端中执行的 `/login`、`/logout`、`/trust`、`/share`、`/import`、
  `/changelog` 和 `/scoped-models` 会显示明确说明，而不会被当作普通提示词发送。
- **模型与思考级别** — 直接从输入区域切换模型和思考级别。
- **运行中引导** — 智能体运行时仍可发送消息，消息会作为 steering 请求排队。
- **扩展交互** — pi 扩展发出的 `select`、`confirm` 和 `input` 请求会显示为 IDE 原生对话框。
- **用量信息** — 每条响应显示模型、Token 数、缓存命中率、耗时和费用；悬停可查看提示词、输出、
  缓存和非缓存 Token 的详细信息。
- **实时计时** — 执行期间状态栏持续计时，完成后显示总耗时。
- **Git 分支** — 状态栏显示当前检出的分支。插件直接读取 `.git/HEAD`，不依赖 Git 插件，并在切换分支后刷新。
- **文件修改** — 输入框上方列出本次对话真正修改的文件及每个文件的 `+/−` 行数。点击文件可打开 IDE
  原生差异查看器，对比 HEAD 与工作区。只有成功返回的 `write` 或 `edit` 工具调用才会被记录，
  不会因为智能体声称修改了文件就加入列表。
- **附件** — 可以通过附件按钮、拖放或粘贴添加文件。图片（png、jpg、gif、webp、bmp；最多 10 个，
  单个不超过 10 MB）会以内联形式发送给模型；其他文件会作为 `@path` 引用，让智能体使用工具自行读取，
  避免大型日志或表格直接占用上下文窗口。
- **发送路径到 Pi GUI** — 在项目视图、编辑器标签页、导航栏、Scope 视图或编辑器选区中右键点击文件、
  文件夹或多个项目，即可把路径作为 `@mention` 插入输入框。该操作位于菜单顶部，也可以使用
  **Alt+Shift+A**。项目内的路径使用相对路径；编辑器选区会包含行号范围，例如 `@src/App.kt:12-34`。

## 与 pi 的通信方式

插件将 `pi --mode rpc` 作为子进程启动，并通过 stdin/stdout 使用逐行 JSON 协议通信：

```text
IDE  ──stdin──►  {"id":"1","type":"prompt","message":"..."}
IDE  ◄─stdout──  {"type":"agent_start"}
                 {"type":"message_start","message":{...}}
                 {"type":"message_update","assistantMessageEvent":{"type":"text_delta",...}}
                 {"type":"message_end","message":{...}}
                 {"type":"agent_settled"}
```

读取历史记录不会启动进程。只有发送消息时才会启动智能体；恢复已有会话时会传入
`--session <file>`。

会话采用树形结构：每条记录通过 `parentId` 指向父节点，聊天记录只显示当前叶子节点回溯到根节点的路径，
因此已经放弃的分支不会混入当前对话。

## 项目结构

```text
src/main/kotlin/dev/pi/gui/
  PiLocator.kt              通过登录 Shell 的 PATH 查找 pi 可执行文件
  action/                   工具窗口操作及“发送路径到 Pi GUI”右键菜单操作
  model/PiModels.kt         消息、会话和智能体状态类型
  commands/                 斜杠命令模型、pi 内置命令、缓存和探测
  rpc/PiRpcClient.kt        子进程、JSON 协议和请求关联
  rpc/StreamingAssistant.kt 将流式增量合并为完整消息
  rpc/PiJson.kt             pi JSON 到模型类型的转换
  session/SessionStore.kt   读取会话文件并沿当前分支生成记录
  ui/ChatPanel.kt           聊天、会话编排和原生状态栏
  ui/SessionListPanel.kt    会话侧边栏
  ui/transcript/            JCEF 聊天界面及 Swing 回退实现
  ui/settings/              JCEF 设置控制器及 Swing 回退实现
  web/                      JCEF 桥接、主题变量和 HTML 渲染器
  settings/                 持久化设置及 IDE 设置项
src/main/resources/web/     自包含的聊天/设置 HTML、CSS 和 JavaScript
```

## 开发

```bash
./gradlew test          # 单元测试、无头 UI 测试和 JCEF 桥接契约测试
./gradlew runIde        # 启动加载该插件的沙箱 IDE
./gradlew verifyPlugin  # 运行 JetBrains Plugin Verifier
./gradlew buildPlugin   # 生成可分发 ZIP
```

## 设置

**Settings → Tools → Pi GUI**

| 设置项 | 用途 |
| --- | --- |
| pi 可执行文件 | 覆盖自动探测到的路径 |
| 额外 CLI 参数 | 附加到每次启动命令，例如 `--models "anthropic/*"` |
| 显示思考过程 | 显示模型推理内容 |
| 默认展开工具调用 | 打开对话时展开工具调用 |
| 按 Enter 发送 | Enter 发送、Shift+Enter 换行，或使用相反行为 |
