# Pi GUI — JetBrains plugin for the pi AI coding agent

**English** | [简体中文](README.zh-CN.md)

A JetBrains plugin (Kotlin + JCEF/Swing) that puts the [pi coding agent](https://github.com/earendil-works/pi)
inside your IDE. Chat and settings use the IDE's bundled JCEF runtime, while project integration,
file pickers, diffs and confirmations use IntelliJ Platform components. There is no local web
server and no remote UI origin.

Feature design is inspired by [pi-web](https://github.com/agegr/pi-web); the implementation shares
no code with it.

## Install

1. Build the plugin (or use the prebuilt zip):
   ```bash
   ./gradlew buildPlugin
   ```
   The artifact lands at `build/distributions/pi-gui-1.0.1.zip`.

2. In your IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, restart.

3. Open it with **Alt+Shift+P**, or the **Pi GUI** tool window on the right edge.

### Requirements

- A JetBrains IDE on build **243** (2024.3) or newer — verified against IntelliJ IDEA 2026.1.
- The `pi` CLI on your machine:
  ```bash
  npm i -g @earendil-works/pi-coding-agent
  ```
  The plugin finds `pi` through your login-shell `PATH` (so nvm/fnm/volta installs work). If it
  lives somewhere unusual, set the path in **Settings → Tools → Pi GUI**.

## What it does

- **Session sidebar** — every pi session recorded for the current project, newest first. Sessions
  are read straight from `~/.pi/agent/sessions/`, so history opens instantly without starting an
  agent. Delete with the Delete key.
- **Streaming chat** — assistant text, thinking blocks and tool calls stream in live.
- **Rich rendering** — Markdown (headings, lists, tables, blockquotes, links, inline code) plus
  fenced code blocks rendered with IDE syntax highlighting and a copy button.
- **Collapsible detail** — thinking blocks, tool calls and tool results fold away by default so the
  conversation stays readable; expand any of them on click.
- **Slash commands** — type `/` at the start of the composer to complete every command pi knows:
  its 23 built-ins (`/model`, `/new`, `/tree`, `/reload`, …) plus the extension commands, prompt
  templates and skills reported by `get_commands`. Arrow keys move, Enter or Tab completes, Escape
  closes. Built-ins appear instantly; the rest arrive as soon as pi answers.

  pi only runs its built-ins inside its own terminal UI — sending `/new` as a prompt would reach
  the model as the literal text. So the plugin performs them itself, through the RPC commands that
  back the same behaviour (`/compact`, `/session`, `/name`, `/copy`, `/export`, `/clone`, `/fork`,
  `/tree`) or through its own UI (`/settings`, `/model`, `/thinking`, `/new`, `/resume`,
  `/hotkeys`, `/reload`, `/quit`). The seven that need pi's terminal — `/login`, `/logout`,
  `/trust`, `/share`, `/import`, `/changelog`, `/scoped-models` — say so instead of being sent.
- **Model & thinking controls** — switch model or thinking level from the composer.
- **Steering** — send while the agent is running and the message is queued as steering.
- **Extension prompts** — `select` / `confirm` / `input` requests from pi extensions surface as
  native IDE dialogs.
- **Usage footer** — model, token count, **cache hit rate**, **elapsed time** and cost per
  response. Hover for a breakdown of prompt vs. output and cached vs. fresh tokens.
- **Live timing** — the status strip counts up while a step runs and reports how long it took
  once it finishes.
- **Git branch** — the checked-out branch is shown in the status strip, read straight from
  `.git/HEAD` (no dependency on the Git plugin) and refreshed when you switch branches.
- **Edits** — a strip above the composer lists the files the conversation actually changed, with
  per-file `+/−` line counts. Clicking one opens the IDE's own diff viewer (HEAD vs working tree).
  A file is only listed when a `write`/`edit` tool call returned *without* an error — never
  because the assistant said it changed something.
- **Attachments** — add files with the composer's attach button, by dropping them on the input,
  or by pasting. Images (png/jpg/gif/webp/bmp, up to 10 files of 10 MB) are sent inline so the
  model can see them; any other file is attached as an `@path` reference for the agent to read
  with its own tools, which keeps a large log or spreadsheet out of the context window.
- **Send path to Pi GUI** — right-click a file, a folder, a multi-selection in the project view, an
  editor tab, the navigation bar, the scope view, or a selection inside the editor, and the path is
  inserted into the composer as an `@mention`. It sits at the top of each menu, since these menus
  are long enough to run off the screen. A selection adds its line range (`@src/App.kt:12-34`).
  Also on **Alt+Shift+A**. Paths are relative to the project root when the target lives inside it.

## How it talks to pi

The plugin drives `pi --mode rpc` as a child process and speaks pi's newline-delimited JSON
protocol over stdin/stdout:

```
IDE  ──stdin──►  {"id":"1","type":"prompt","message":"..."}
IDE  ◄─stdout──  {"type":"agent_start"}
                 {"type":"message_start","message":{...}}
                 {"type":"message_update","assistantMessageEvent":{"type":"text_delta",...}}
                 {"type":"message_end","message":{...}}
                 {"type":"agent_settled"}
```

Reading history never starts a process — the agent is launched only when you send a message, and
resumed sessions are passed through `--session <file>`.

Sessions are a *tree*: each entry links to a `parentId`, and the transcript follows the path from
the active leaf back to the root, so abandoned branches stay hidden.

## Layout

```
src/main/kotlin/dev/pi/gui/
  PiLocator.kt              finds the pi executable via the login-shell PATH
  action/                   tool-window actions + "Send … to Pi GUI" context-menu action
  model/PiModels.kt         message, session and agent-state types
  commands/                 slash-command model, pi's built-ins, cache and probe
  rpc/PiRpcClient.kt        child process + JSON protocol + request correlation
  rpc/StreamingAssistant.kt accumulates streaming deltas into a message
  rpc/PiJson.kt             pi JSON → model types (both tool-call spellings)
  session/SessionStore.kt   reads ~/.pi/agent/sessions/*.jsonl, walks the active branch
  ui/ChatPanel.kt           chat/session orchestration and native status strip
  ui/SessionListPanel.kt    session sidebar
  ui/transcript/            JCEF chat surface plus Swing fallback
  ui/settings/              JCEF settings controller plus Swing fallback
  web/                      JCEF bridge, theme tokens and HTML renderer
  settings/                 persisted settings and IDE configurable
src/main/resources/web/     self-contained chat/settings HTML, CSS and JavaScript
```

## Development

```bash
./gradlew test          # unit + headless UI and JCEF bridge contract tests
./gradlew runIde        # sandbox IDE with the plugin loaded
./gradlew verifyPlugin  # JetBrains plugin verifier
./gradlew buildPlugin   # distributable zip
```

## Settings

**Settings → Tools → Pi GUI**

| Setting | Purpose |
| --- | --- |
| pi executable | Override auto-detection |
| Extra CLI arguments | Appended to every launch, e.g. `--models "anthropic/*"` |
| Show thinking blocks | Render the model's reasoning |
| Expand tool calls by default | Start tool calls expanded |
| Send on Enter | Enter sends, Shift+Enter newlines (or the reverse) |
