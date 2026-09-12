package dev.pi.gui.session

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import dev.pi.gui.PiLocator
import dev.pi.gui.model.PiMessage
import dev.pi.gui.model.SessionInfo
import dev.pi.gui.rpc.PiJson
import dev.pi.gui.rpc.asDoubleOrNull
import dev.pi.gui.rpc.asLongOrNull
import dev.pi.gui.rpc.asStringOrNull
import dev.pi.gui.rpc.getAsJsonObjectOrNull
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads pi session files straight off disk.
 *
 * Browsing history never needs an agent process — pi persists everything to
 * `~/.pi/agent/sessions/<encoded-cwd>/<timestamp>_<uuid>.jsonl`, one JSON object per line.
 * A session is a *tree*: every entry carries a `parentId`, and the visible conversation is the
 * path from the root down to the active leaf.
 */
object SessionStore {
    private val LOG = Logger.getInstance(SessionStore::class.java)

    /** Gaps longer than this are idle time between turns, not time the model spent working. */
    private const val MAX_INFERRED_STEP_MS = 30 * 60 * 1000L

    /** pi encodes a working directory into a directory name by replacing separators with '-'. */
    fun encodeCwd(cwd: String): String {
        val normalized = cwd.replace('\\', '/').trimEnd('/')
        return "-" + normalized.replace(Regex("[/\\\\:]"), "-") + "--"
    }

    /** All sessions recorded for [projectPath], newest first. */
    fun listSessionsForProject(projectPath: String): List<SessionInfo> {
        val root = PiLocator.sessionsDir()
        if (!root.isDirectory) return emptyList()

        val canonicalTarget = canonical(projectPath)
        val result = mutableListOf<SessionInfo>()

        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { file ->
                if (!file.isFile || !file.name.endsWith(".jsonl")) return@forEach
                val info = readSessionInfo(file) ?: return@forEach
                // Raw compare first: `canonical()` touches the filesystem, and with the header
                // cache this is the only per-file cost left on a refresh.
                if (info.cwd == projectPath || canonical(info.cwd) == canonicalTarget) result.add(info)
            }
        }
        return result.sortedByDescending { it.lastModified }
    }

    /** Every session pi knows about, newest first. */
    fun listAllSessions(limit: Int = 500): List<SessionInfo> {
        val root = PiLocator.sessionsDir()
        if (!root.isDirectory) return emptyList()
        val files = mutableListOf<File>()
        root.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                dir.listFiles()?.filterTo(files) { it.isFile && it.name.endsWith(".jsonl") }
            }
        }
        return files.sortedByDescending { it.lastModified() }
            .take(limit)
            .mapNotNull { readSessionInfo(it) }
    }

    private fun canonical(path: String): String = try {
        File(path).canonicalPath
    } catch (e: Exception) {
        File(path).absolutePath
    }.let { if (PiLocator.isWindows()) it.lowercase() else it }

    // -------------------------------------------------------------- header cache

    /** What a session file looked like when its header was last parsed. */
    private class CachedHeader(val lastModified: Long, val length: Long, val info: SessionInfo?)

    /**
     * Session headers already parsed, keyed by absolute path.
     *
     * The sidebar refreshes after every turn, and each refresh walks *every* session file of
     * *every* project under `~/.pi/agent/sessions`. Without this cache that means re-reading and
     * re-parsing megabytes of JSONL — repeated allocation churn and filesystem traffic — to
     * discover that one file changed. A [SessionInfo] is a handful of small strings, so even
     * thousands of entries cost a fraction of what a single re-parse allocates.
     */
    private val headerCache = ConcurrentHashMap<String, CachedHeader>()

    private const val MAX_CACHED_HEADERS = 2_000

    /** Drops cached headers; used by tests that rewrite files within one millisecond. */
    fun dropHeaderCache() = headerCache.clear()

    /**
     * Cheap header read: pulls the session id/cwd/name plus a preview of the first user message
     * without materializing the whole transcript. Results are cached per file until its size or
     * modification time changes.
     */
    fun readSessionInfo(file: File): SessionInfo? {
        val mtime = file.lastModified()
        val length = file.length()
        val key = file.absolutePath
        headerCache[key]
            ?.takeIf { it.lastModified == mtime && it.length == length }
            ?.let { return it.info }

        val info = readSessionInfoUncached(file)
        if (headerCache.size >= MAX_CACHED_HEADERS) headerCache.clear()
        headerCache[key] = CachedHeader(mtime, length, info)
        return info
    }

    private fun readSessionInfoUncached(file: File): SessionInfo? {
        return try {
            var id: String? = null
            var cwd: String? = null
            var createdAt = file.lastModified()
            var name: String? = null
            var preview: String? = null
            var messageCount = 0

            Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = parseLine(line) ?: continue
                    when (obj.get("type")?.asStringOrNull()) {
                        "session" -> {
                            id = obj.get("id")?.asStringOrNull()
                            cwd = obj.get("cwd")?.asStringOrNull()
                            obj.get("timestamp")?.asStringOrNull()?.let { ts ->
                                parseIsoTimestamp(ts)?.let { createdAt = it }
                            }
                        }
                        "session_info" -> name = obj.get("name")?.asStringOrNull() ?: name
                        "message" -> {
                            messageCount++
                            if (preview == null) {
                                val msg = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
                                if (msg?.get("role")?.asStringOrNull() == "user") {
                                    preview = PiJson.parseUserMessage(msg)?.text?.takeIf { it.isNotBlank() }
                                }
                            }
                        }
                    }
                }
            }

            val sessionId = id ?: return null
            SessionInfo(
                id = sessionId,
                filePath = file.absolutePath,
                cwd = cwd ?: "",
                name = name,
                lastModified = file.lastModified(),
                createdAt = createdAt,
                preview = preview,
                messageCount = messageCount,
            )
        } catch (e: Exception) {
            LOG.debug("Unreadable session file: ${file.absolutePath}", e)
            null
        }
    }

    /**
     * Materialize the conversation on the session's active branch.
     *
     * Entries form a tree; we take the last entry in file order as the active leaf and walk
     * `parentId` links back to the root, so messages from abandoned branches stay hidden.
     */
    fun readTranscript(file: File): List<PiMessage> {
        if (!file.isFile) return emptyList()
        val entries = LinkedHashMap<String, JsonObject>()
        var lastId: String? = null

        try {
            Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val obj = parseLine(line) ?: continue
                    val type = obj.get("type")?.asStringOrNull() ?: continue
                    if (type == "session") continue
                    val id = obj.get("id")?.asStringOrNull() ?: continue
                    entries[id] = obj
                    lastId = id
                }
            }
        } catch (e: Exception) {
            LOG.warn("Failed reading transcript ${file.absolutePath}", e)
            return emptyList()
        }

        // Walk from the active leaf back to the root, then flip into chronological order.
        val chain = ArrayList<JsonObject>()
        var cursor = lastId
        val guard = HashSet<String>()
        while (cursor != null && guard.add(cursor)) {
            val entry = entries[cursor] ?: break
            chain.add(entry)
            cursor = entry.get("parentId")?.asStringOrNull()
        }
        chain.reverse()

        val messages = mutableListOf<PiMessage>()
        // Session files record no explicit duration, so a step's cost is inferred from the gap to
        // the previous entry. Absurd gaps (an idle session resumed hours later) are dropped.
        var previousTimestamp: Long? = null

        chain.forEach { entry ->
            when (entry.get("type")?.asStringOrNull()) {
                "message" -> {
                    val msg = entry.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    val parsed = PiJson.parseMessage(msg) ?: return@forEach
                    if (parsed is PiMessage.Assistant) {
                        val previous = previousTimestamp
                        val current = parsed.timestamp
                        if (previous != null && current != null) {
                            val delta = current - previous
                            if (delta in 1..MAX_INFERRED_STEP_MS) parsed.durationMs = delta
                        }
                    }
                    parsed.timestamp?.let { previousTimestamp = it }
                    messages.add(parsed)
                }
                "compaction" -> {
                    val tokens = entry.get("tokensBefore")?.asLongOrNull()
                    messages.add(
                        PiMessage.Notice(
                            buildString {
                                append("Context compacted")
                                if (tokens != null) append(" (was ").append(formatTokens(tokens)).append(" tokens)")
                            }
                        )
                    )
                }
            }
        }
        return messages
    }

    fun deleteSession(file: File): Boolean {
        headerCache.remove(file.absolutePath)
        return try {
            file.delete()
        } catch (e: Exception) {
            LOG.warn("Failed to delete session ${file.absolutePath}", e)
            false
        }
    }

    /**
     * Renames a session by appending a `session_info` entry — the same mechanism pi itself uses
     * when naming a session, and readers take the *last* such entry, so renaming is simply
     * writing a newer one. No agent process is needed, and pi's own TUI shows the same name.
     */
    fun renameSession(file: File, name: String): Boolean {
        val clean = name.replace(Regex("\\s+"), " ").trim().take(MAX_SESSION_NAME_LENGTH)
        if (clean.isEmpty() || !file.isFile) return false
        return try {
            val parentId = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8).use { reader ->
                var id: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    parseLine(line)?.get("id")?.asStringOrNull()?.let { id = it }
                }
                id
            }
            val entry = JsonObject().apply {
                addProperty("type", "session_info")
                addProperty("id", UUID.randomUUID().toString())
                parentId?.let { addProperty("parentId", it) }
                addProperty("timestamp", Instant.now().toString())
                addProperty("name", clean)
            }
            Files.write(
                file.toPath(),
                (entry.toString() + "\n").toByteArray(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND,
            )
            // The append changes length (and usually mtime), but drop the entry explicitly so a
            // same-millisecond write can never serve the stale header.
            headerCache.remove(file.absolutePath)
            true
        } catch (e: Exception) {
            LOG.warn("Failed renaming session ${file.absolutePath}", e)
            false
        }
    }

    private const val MAX_SESSION_NAME_LENGTH = 200

    private fun parseLine(line: String): JsonObject? = try {
        JsonParser.parseString(line).takeIf { it.isJsonObject }?.asJsonObject
    } catch (e: Exception) {
        null
    }

    private fun parseIsoTimestamp(value: String): Long? = try {
        java.time.Instant.parse(value).toEpochMilli()
    } catch (e: Exception) {
        null
    }

    fun formatTokens(count: Long): String = when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
        else -> count.toString()
    }

    fun formatDuration(millis: Long): String = when {
        millis < 1_000 -> "${millis}ms"
        millis < 10_000 -> String.format("%.1fs", millis / 1000.0)
        millis < 60_000 -> "${millis / 1000}s"
        else -> "${millis / 60_000}m ${(millis % 60_000) / 1000}s"
    }

    fun formatPercent(fraction: Double): String = "${Math.round(fraction * 100)}%"

    /**
     * One-line-per-fact summary of a `get_session_stats` payload, for `/session`.
     * Fields pi omits (there is no `contextUsage` before a model has answered) are left out
     * rather than shown as zero.
     */
    fun describeStats(data: JsonObject): String {
        val lines = mutableListOf<String>()

        // Field names verified against pi 0.84.2: the payload carries a breakdown, not the single
        // `messageCount` the RPC docs show.
        val total = data.get("totalMessages")?.asLongOrNull()
            ?: data.get("messageCount")?.asLongOrNull()
        if (total != null) {
            val breakdown = listOfNotNull(
                data.get("userMessages")?.asLongOrNull()?.let { "$it from you" },
                data.get("assistantMessages")?.asLongOrNull()?.let { "$it from pi" },
                data.get("toolCalls")?.asLongOrNull()?.let { "$it tool calls" },
            )
            lines.add(
                "Messages: $total" +
                    if (breakdown.isEmpty()) "" else " (${breakdown.joinToString(", ")})"
            )
        }

        val tokens = data.getAsJsonObjectOrNull("tokens")
        if (tokens != null) {
            val total = tokens.get("total")?.asLongOrNull() ?: 0L
            val parts = listOfNotNull(
                tokens.get("input")?.asLongOrNull()?.let { "in ${formatTokens(it)}" },
                tokens.get("output")?.asLongOrNull()?.let { "out ${formatTokens(it)}" },
                tokens.get("cacheRead")?.asLongOrNull()?.let { "cache read ${formatTokens(it)}" },
                tokens.get("cacheWrite")?.asLongOrNull()?.let { "cache write ${formatTokens(it)}" },
            )
            lines.add("Tokens: ${formatTokens(total)} (${parts.joinToString(", ")})")
        }

        data.get("cost")?.asDoubleOrNull()?.let { lines.add(String.format("Cost: $%.4f", it)) }

        val context = data.getAsJsonObjectOrNull("contextUsage")
        if (context != null) {
            val used = context.get("tokens")?.asLongOrNull()
            val window = context.get("contextWindow")?.asLongOrNull()
            val percent = context.get("percent")?.asDoubleOrNull()
            if (used != null && window != null) {
                val suffix = percent?.let { " (${Math.round(it)}%)" }.orEmpty()
                lines.add("Context: ${formatTokens(used)} / ${formatTokens(window)}$suffix")
            }
        }

        return if (lines.isEmpty()) "No session stats available yet."
        else lines.joinToString("\n")
    }

    /**
     * Renders a `get_tree` payload as an indented outline, for `/tree`.
     *
     * Read-only on purpose: pi exposes no RPC command to move the active branch, so the plugin can
     * show the shape of the session but cannot switch to another branch the way the TUI does.
     */
    fun describeTree(data: JsonObject): String {
        val roots = PiJson.asArray(data.get("tree")) ?: return "This session has no entries yet."
        val leafId = data.get("leafId")?.asStringOrNull()
        val out = StringBuilder()
        var lines = 0
        var dropped = 0

        /**
         * Depth only advances at a real branch point. A session is a chain — one entry per turn,
         * each the child of the last — so indenting per level would push a 200-message session
         * 200 columns to the right and hide the thing the outline is for.
         */
        fun walk(node: JsonObject, depth: Int) {
            val entry = node.getAsJsonObjectOrNull("entry")
            val id = entry?.get("id")?.asStringOrNull()
            val children = PiJson.asArray(node.get("children"))
                ?.mapNotNull { it.takeIf { c -> c.isJsonObject }?.asJsonObject }
                .orEmpty()

            val isLeaf = id != null && id == leafId
            val isBranch = children.size > 1
            // Tool results and tool-only assistant turns say nothing about where the conversation
            // went; a branch point and the current position always do.
            val label = describeEntry(entry, node)
                ?: if (isBranch) "${children.size} branches from here" else null

            if (label != null || isLeaf) {
                if (lines < MAX_TREE_LINES) {
                    out.append("  ".repeat(depth))
                        .append(if (isLeaf) "▸ " else if (isBranch) "⑂ " else "· ")
                        .append(label ?: describeType(entry))
                        .append('\n')
                    lines++
                } else {
                    dropped++
                }
            }

            val childDepth = if (children.size > 1) depth + 1 else depth
            children.forEach { walk(it, childDepth) }
        }

        roots.mapNotNull { it.takeIf { r -> r.isJsonObject }?.asJsonObject }
            .forEach { walk(it, 0) }

        if (out.isEmpty()) return "This session has no entries yet."
        if (dropped > 0) out.append("… and $dropped more entries\n")
        return "Session tree (▸ = current position, indent = branch):\n\n$out"
    }

    private const val MAX_TREE_LINES = 200

    private fun describeType(entry: JsonObject?): String =
        entry?.get("type")?.asStringOrNull() ?: "entry"

    /** @return the line to print, or null when the entry carries no navigational information. */
    private fun describeEntry(entry: JsonObject?, node: JsonObject): String? {
        node.get("label")?.asStringOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        if (entry == null) return null

        // pi's tree carries settings changes alongside messages; they are part of the chain, so
        // they are shown rather than skipped — just compactly.
        when (entry.get("type")?.asStringOrNull()) {
            "model_change" -> {
                val provider = entry.get("provider")?.asStringOrNull()
                val model = entry.get("modelId")?.asStringOrNull()
                return "model → " + listOfNotNull(provider, model).joinToString("/")
            }
            "thinking_level_change" ->
                return "thinking → ${entry.get("thinkingLevel")?.asStringOrNull() ?: "?"}"
        }

        val message = entry.getAsJsonObjectOrNull("message") ?: return null
        val role = message.get("role")?.asStringOrNull()
        val text = PiJson.parseMessage(message)?.let { parsed ->
            when (parsed) {
                is PiMessage.User -> parsed.text
                is PiMessage.Assistant -> parsed.blocks
                    .filterIsInstance<dev.pi.gui.model.ContentBlock.Text>()
                    .joinToString(" ") { it.text }
                else -> null
            }
        }

        val label = text?.replace(Regex("\\s+"), " ")?.trim()?.take(70)
        // A tool result, or an assistant turn that only made tool calls: no navigational value.
        if (label.isNullOrBlank()) return null
        return if (role != null) "$role: $label" else label
    }
}
