package com.mobileclaw.app.ai

import android.util.Log
import com.mobileclaw.app.model.ShellResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// =============================================================================
// ToolDefinition - 工具定义
// =============================================================================

/**
 * 工具定义 - 描述 AI Agent 可以调用的一个工具。
 *
 * @property name       工具名称，用于唯一标识（如 "execute_command"）
 * @property description 工具描述，用于向 AI 说明该工具的功能
 * @property parameters 参数的 JSON Schema 描述（符合 JSON Schema 规范）
 * @property handler    实际的异步处理函数，接收 [JsonObject] 参数，返回 [ToolResult]
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val handler: suspend (JsonObject) -> ToolResult
)

// =============================================================================
// ToolResult - 工具执行结果
// =============================================================================

/**
 * 工具执行结果。
 *
 * @property success 是否执行成功
 * @property data    执行结果数据（字符串形式，如 stdout、文件内容等）
 * @property error   错误信息，仅 [success] 为 false 时有效，可为 null
 */
data class ToolResult(
    val success: Boolean,
    val data: String,
    val error: String? = null
) {
    companion object {
        /** 构造一个成功的执行结果。 */
        fun success(data: String): ToolResult = ToolResult(success = true, data = data, error = null)

        /** 构造一个失败的执行结果。 */
        fun failure(error: String, data: String = ""): ToolResult =
            ToolResult(success = false, data = data, error = error)
    }
}

// =============================================================================
// ToolRegistry - 工具注册表
// =============================================================================

/**
 * ToolRegistry - 管理 AI Agent 可用的所有工具。
 *
 * 提供工具注册、按名称查找、OpenAI 兼容的 function calling schema 生成、
 * 以及工具执行调度等核心功能。
 *
 * 构造时自动注册 15 个内置工具，涵盖 Shell 命令执行、Python 执行、
 * 文件操作、应用管理、屏幕操控、系统信息获取等常见场景。
 *
 * 使用示例：
 * ```
 * val registry = ToolRegistry(termuxBridge)
 * val functions = registry.getOpenAIFunctions()  // 生成 OpenAI tools 数组
 * val result = registry.executeTool("execute_command", JsonObject(mapOf(
 *     "command" to JsonPrimitive("ls -la")
 * )))
 * ```
 *
 * @param bridge TermuxBridge 实例，提供底层 Shell 命令执行、文件操作等能力
 */
class ToolRegistry(private val bridge: TermuxBridge) {

    companion object {
        private const val TAG = "ToolRegistry"
    }

    /** 内部工具注册表。 */
    private val tools = mutableMapOf<String, ToolDefinition>()

    init {
        registerDefaultTools()
    }

    // =============================================================================
    // 公开 API
    // =============================================================================

    /**
     * 注册一个工具。
     * 如果已存在同名工具，新的工具会覆盖旧的。
     *
     * @param tool 要注册的工具定义
     */
    fun registerTool(tool: ToolDefinition) {
        tools[tool.name] = tool
        Log.d(TAG, "Registered tool: ${tool.name}")
    }

    /**
     * 根据名称获取工具定义。
     *
     * @param name 工具名称
     * @return 匹配的 [ToolDefinition]，未找到时返回 null
     */
    fun getTool(name: String): ToolDefinition? = tools[name]

    /**
     * 生成 OpenAI 兼容的 function calling 工具列表（tools 数组）。
     *
     * 返回格式符合 OpenAI 的 function calling 规范：
     * ```json
     * [
     *   {
     *     "type": "function",
     *     "function": {
     *       "name": "tool_name",
     *       "description": "description",
     *       "parameters": { ... }
     *     }
     *   }
     * ]
     * ```
     *
     * @return 包含所有已注册工具 schema 的 [JsonArray]
     */
    fun getOpenAIFunctions(): JsonArray {
        return buildJsonArray {
            tools.forEach { (_, tool) ->
                add(buildJsonObject {
                    put("type", JsonPrimitive("function"))
                    putJsonObject("function") {
                        put("name", JsonPrimitive(tool.name))
                        put("description", JsonPrimitive(tool.description))
                        put("parameters", tool.parameters)
                    }
                })
            }
        }
    }

    /**
     * 执行指定名称的工具。
     *
     * 根据工具名称查找已注册的工具，若未找到则返回失败结果。
     * 找到后调用工具对应的 handler 并传入参数。
     *
     * @param name 工具名称
     * @param args 工具参数，符合该工具定义的 JSON Schema
     * @return 工具执行结果
     */
    suspend fun executeTool(name: String, args: JsonObject): ToolResult {
        val tool = tools[name] ?: return ToolResult.failure("Tool not found: $name")
        return try {
            tool.handler(args)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool '$name'", e)
            ToolResult.failure("Execution error: ${e.message ?: "Unknown error"}")
        }
    }

    /**
     * 获取所有已注册的工具列表。
     *
     * @return 所有工具定义的列表
     */
    fun getAllTools(): List<ToolDefinition> = tools.values.toList()

    /**
     * 获取所有已注册工具的名称列表。
     *
     * @return 工具名称字符串列表
     */
    fun getToolNames(): List<String> = tools.keys.toList()

    // =============================================================================
    // 默认工具注册
    // =============================================================================

    /**
     * 注册 15 个内置默认工具。
     * 在构造器 init 块中调用。
     */
    private fun registerDefaultTools() {
        registerTool(createExecuteCommandTool())
        registerTool(createRunPythonTool())
        registerTool(createWriteFileTool())
        registerTool(createReadFileTool())
        registerTool(createOpenAppTool())
        registerTool(createSearchWebTool())
        registerTool(createGenerateCodeTool())
        registerTool(createInstallApkTool())
        registerTool(createGetScreenInfoTool())
        registerTool(createClickScreenTool())
        registerTool(createTypeTextTool())
        registerTool(createCreatePythonFileTool())
        registerTool(createCreateShellScriptTool())
        registerTool(createListDirectoryTool())
        registerTool(createGetSystemInfoTool())
    }

    // -------------------------------------------------------------------------
    // 1. execute_command - 执行 Shell 命令
    // -------------------------------------------------------------------------

    private fun createExecuteCommandTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("要执行的 Shell 命令字符串"))
                }
                putJsonObject("timeout_ms") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("超时时间（毫秒），默认 30000"))
                    put("default", JsonPrimitive(30000))
                }
                putJsonObject("as_root") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("是否以 root 权限执行"))
                    put("default", JsonPrimitive(false))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("command"))
            }
        }

        return ToolDefinition(
            name = "execute_command",
            description = "在设备上执行一条 Shell 命令，返回标准输出和标准错误输出。支持通过 Shizuku 以特权身份执行，自动降级到本地 Runtime.exec。",
            parameters = parameters,
            handler = { args ->
                val command = args["command"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: command")
                val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 30_000L
                val asRoot = args["as_root"]?.jsonPrimitive?.booleanOrNull ?: false

                @Suppress("UNUSED_VARIABLE")
                val rootFlag = asRoot // 预留，实际 asRoot 由 TermuxBridge 内部处理

                val result = bridge.executeCommand(command, timeoutMs)
                ToolResult(
                    success = result.isSuccess,
                    data = buildString {
                        if (result.stdout.isNotBlank()) {
                            append("STDOUT:\n${result.stdout}")
                        }
                        if (result.stderr.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append("STDERR:\n${result.stderr}")
                        }
                        if (isEmpty()) {
                            append("(no output)")
                        }
                    },
                    error = if (!result.isSuccess) {
                        "Exit code: ${result.exitCode}. ${result.stderr.take(500)}"
                    } else null
                )
            }
        )
    }

    // -------------------------------------------------------------------------
    // 2. run_python - 执行 Python 代码
    // -------------------------------------------------------------------------

    private fun createRunPythonTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("code") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("要执行的 Python 代码"))
                }
                putJsonObject("timeout_ms") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("超时时间（毫秒），默认 60000"))
                    put("default", JsonPrimitive(60000))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("code"))
            }
        }

        return ToolDefinition(
            name = "run_python",
            description = "在设备上执行 Python 代码。优先使用 Termux 环境中的 Python，否则回退到系统 python3/python。临时脚本在运行后自动清理。",
            parameters = parameters,
            handler = { args ->
                val code = args["code"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: code")
                val timeoutMs = args["timeout_ms"]?.jsonPrimitive?.longOrNull ?: 60_000L

                val result = bridge.executePython(code, timeoutMs)
                ToolResult(
                    success = result.isSuccess,
                    data = buildString {
                        if (result.stdout.isNotBlank()) {
                            append("STDOUT:\n${result.stdout}")
                        }
                        if (result.stderr.isNotBlank()) {
                            if (isNotEmpty()) append("\n")
                            append("STDERR:\n${result.stderr}")
                        }
                        if (isEmpty()) {
                            append("(no output)")
                        }
                    },
                    error = if (!result.isSuccess) {
                        "Exit code: ${result.exitCode}. ${result.stderr.take(500)}"
                    } else null
                )
            }
        )
    }

    // -------------------------------------------------------------------------
    // 3. write_file - 写入文件
    // -------------------------------------------------------------------------

    private fun createWriteFileTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("文件绝对路径"))
                }
                putJsonObject("content") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("要写入的文件内容"))
                }
                putJsonObject("append") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("是否追加到文件末尾（而非覆盖）"))
                    put("default", JsonPrimitive(false))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("content"))
            }
        }

        return ToolDefinition(
            name = "write_file",
            description = "将内容写入指定文件。自动创建父目录。支持覆盖写入和追加写入两种模式。优先通过 Shizuku 执行，否则使用本地文件 I/O。",
            parameters = parameters,
            handler = { args ->
                val path = args["path"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: path")
                val content = args["content"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: content")
                val append = args["append"]?.jsonPrimitive?.booleanOrNull ?: false

                if (append) {
                    // 追加模式：先读取现有内容再写入
                    val existing = bridge.readFile(path)
                    val newContent = if (existing.isSuccess && existing.stdout.isNotBlank()) {
                        existing.stdout + "\n" + content
                    } else {
                        content
                    }
                    val result = bridge.writeFile(path, newContent)
                    ToolResult(
                        success = result.isSuccess,
                        data = if (result.isSuccess) "Content appended to $path" else result.stderr,
                        error = if (!result.isSuccess) "Failed to append: ${result.stderr.take(500)}" else null
                    )
                } else {
                    val result = bridge.writeFile(path, content)
                    ToolResult(
                        success = result.isSuccess,
                        data = if (result.isSuccess) "Content written to $path" else result.stderr,
                        error = if (!result.isSuccess) "Failed to write: ${result.stderr.take(500)}" else null
                    )
                }
            }
        )
    }

    // -------------------------------------------------------------------------
    // 4. read_file - 读取文件
    // -------------------------------------------------------------------------

    private fun createReadFileTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("文件绝对路径"))
                }
                putJsonObject("max_lines") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("最大读取行数，不指定则读取全部"))
                }
                putJsonObject("encoding") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("文件编码，默认 UTF-8"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
            }
        }

        return ToolDefinition(
            name = "read_file",
            description = "读取指定文件的内容。支持通过 Shizuku 以特权身份读取系统文件，自动降级到本地文件 I/O。",
            parameters = parameters,
            handler = { args ->
                val path = args["path"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: path")
                val maxLines = args["max_lines"]?.jsonPrimitive?.intOrNull

                val result = bridge.readFile(path)
                if (!result.isSuccess) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        data = "",
                        error = "Failed to read file: ${result.stderr.take(500)}"
                    )
                }

                val content = result.stdout
                val truncated = if (maxLines != null && maxLines > 0) {
                    val lines = content.lines()
                    val limited = lines.take(maxLines).joinToString("\n")
                    if (lines.size > maxLines) {
                        limited + "\n... (${lines.size - maxLines} more lines truncated)"
                    } else limited
                } else {
                    content
                }

                ToolResult.success(truncated)
            }
        )
    }

    // -------------------------------------------------------------------------
    // 5. open_app - 打开应用
    // -------------------------------------------------------------------------

    private fun createOpenAppTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("package_name") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("应用包名，如 com.tencent.mm"))
                }
                putJsonObject("activity_name") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Activity 完整类名，如 .ui.LauncherUI。不指定则启动默认入口 Activity"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("package_name"))
            }
        }

        return ToolDefinition(
            name = "open_app",
            description = "通过 am start 命令打开 Android 应用。可指定目标 Activity，不指定则启动应用默认入口。",
            parameters = parameters,
            handler = { args ->
                val packageName = args["package_name"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: package_name")
                val activityName = args["activity_name"]?.jsonPrimitive?.content

                val command = if (activityName != null) {
                    "am start -n \"$packageName/$activityName\""
                } else {
                    "monkey -p $packageName 1"
                }

                val result = bridge.executeCommand(command, 15_000L)
                ToolResult(
                    success = result.isSuccess,
                    data = if (result.isSuccess) "Opened app: $packageName" else result.stderr,
                    error = if (!result.isSuccess) "Failed to open app: ${result.stderr.take(500)}" else null
                )
            }
        )
    }

    // -------------------------------------------------------------------------
    // 6. search_web - 搜索网络
    // -------------------------------------------------------------------------

    private fun createSearchWebTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("搜索关键词或查询语句"))
                }
                putJsonObject("max_results") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("最大返回结果数，默认 5"))
                    put("default", JsonPrimitive(5))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("query"))
            }
        }

        return ToolDefinition(
            name = "search_web",
            description = "通过 curl 调用搜索引擎 API 搜索网络。返回搜索结果摘要。需要设备具备网络连接。",
            parameters = parameters,
            handler = { args ->
                val query = args["query"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: query")
                val maxResults = args["max_results"]?.jsonPrimitive?.intOrNull ?: 5

                // 使用 DuckDuckGo 的轻量级搜索 API（无需 API Key，适用于移动端）
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val command = "curl -s --max-time 15 \"https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1\" 2>/dev/null || echo '{\"error\":\"curl failed\"}'"

                val result = bridge.executeCommand(command, 20_000L)
                if (!result.isSuccess) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        data = "",
                        error = "Web search failed: ${result.stderr.take(500)}"
                    )
                }

                // 尝试解析 JSON 响应，提取摘要和链接
                val output = result.stdout.trim()
                val summary = if (output.startsWith("{")) {
                    try {
                        // 简单提取 AbstractText 和 RelatedTopics
                        val abstractMatch = Regex("\"AbstractText\"\\s*:\\s*\"([^\"]*)\"").find(output)
                        val abstract = abstractMatch?.groupValues?.getOrNull(1)?.ifBlank { null }

                        val topics = Regex("\"Text\"\\s*:\\s*\"([^\"]*)\"").findAll(output)
                            .take(maxResults)
                            .map { it.groupValues[1] }
                            .toList()

                        buildString {
                            if (abstract != null) {
                                append("Abstract: $abstract\n\n")
                            }
                            if (topics.isNotEmpty()) {
                                append("Related Topics:\n")
                                topics.forEachIndexed { index, topic ->
                                    append("${index + 1}. $topic\n")
                                }
                            }
                            if (isEmpty()) {
                                append("No results found.")
                            }
                        }
                    } catch (e: Exception) {
                        "Search completed (raw response length: ${output.length})"
                    }
                } else {
                    "Search completed (raw response length: ${output.length})"
                }

                ToolResult.success(summary.ifBlank { "No results found for: $query" })
            }
        )
    }

    // -------------------------------------------------------------------------
    // 7. generate_code - 生成代码
    // -------------------------------------------------------------------------

    private fun createGenerateCodeTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("language") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("目标编程语言：python / shell / kotlin / java"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("python"))
                        add(JsonPrimitive("shell"))
                        add(JsonPrimitive("kotlin"))
                        add(JsonPrimitive("java"))
                    })
                }
                putJsonObject("task_description") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("任务描述，说明要生成的代码需要实现什么功能"))
                }
                putJsonObject("requirements") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("额外要求，如输入输出格式、依赖库等"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("language"))
                add(JsonPrimitive("task_description"))
            }
        }

        return ToolDefinition(
            name = "generate_code",
            description = "生成指定语言的代码文件。支持 Python、Shell、Kotlin、Java。创建脚本文件到应用缓存目录并返回路径。",
            parameters = parameters,
            handler = { args ->
                val language = args["language"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: language")
                val taskDescription = args["task_description"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: task_description")
                val requirements = args["requirements"]?.jsonPrimitive?.content ?: ""

                val supportedLanguages = setOf("python", "shell", "kotlin", "java")
                if (language.lowercase() !in supportedLanguages) {
                    return@ToolDefinition ToolResult.failure(
                        "Unsupported language: $language. Supported: ${supportedLanguages.joinToString(", ")}"
                    )
                }

                // 在 /data/local/tmp 下创建代码文件（通过 shell 命令）
                val extension = when (language.lowercase()) {
                    "python" -> "py"
                    "shell" -> "sh"
                    "kotlin" -> "kt"
                    "java" -> "java"
                    else -> "txt"
                }

                val timestamp = System.currentTimeMillis()
                val filePath = "/data/local/tmp/generated_$timestamp.$extension"

                // 写入代码模板（实际的代码内容由 AI 生成，此处创建一个占位文件，真正的代码内容应通过 write_file 写入）
                val header = buildString {
                    appendLine("# ============================================")
                    appendLine("# Language: $language")
                    appendLine("# Task: $taskDescription")
                    if (requirements.isNotBlank()) {
                        appendLine("# Requirements: $requirements")
                    }
                    appendLine("# ============================================")
                    appendLine()
                }

                val writeResult = bridge.writeFile(filePath, header)
                if (!writeResult.isSuccess) {
                    return@ToolDefinition ToolResult.failure("Failed to create code file: ${writeResult.stderr.take(500)}")
                }

                val resultData = buildString {
                    appendLine("Code file created:")
                    appendLine("  Path: $filePath")
                    appendLine("  Language: $language")
                    appendLine("  Task: $taskDescription")
                    appendLine()
                    appendLine("To write actual code content, use the write_file tool with:")
                    appendLine("  path: $filePath")
                }

                ToolResult.success(resultData)
            }
        )
    }

    // -------------------------------------------------------------------------
    // 8. install_apk - 安装 APK
    // -------------------------------------------------------------------------

    private fun createInstallApkTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("apk_path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("APK 文件的绝对路径"))
                }
                putJsonObject("force_install") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("是否强制安装（替换已安装的版本）"))
                    put("default", JsonPrimitive(false))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("apk_path"))
            }
        }

        return ToolDefinition(
            name = "install_apk",
            description = "通过 pm install 命令安装 APK 文件。需要 Shizuku 或 root 权限。支持 -r 选项覆盖安装已有应用。",
            parameters = parameters,
            handler = { args ->
                val apkPath = args["apk_path"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: apk_path")
                val forceInstall = args["force_install"]?.jsonPrimitive?.booleanOrNull ?: false

                val fileCheck = bridge.readFile(apkPath)
                if (!fileCheck.isSuccess) {
                    return@ToolDefinition ToolResult.failure("APK file not found or not readable: $apkPath")
                }

                val installFlags = if (forceInstall) " -r" else ""
                val command = "pm install$installFlags --user 0 \"$apkPath\" 2>&1"
                val result = bridge.executeCommand(command, 120_000L)

                val output = (result.stdout + result.stderr).trim()
                val success = result.isSuccess || output.contains("Success", ignoreCase = true)

                ToolResult(
                    success = success,
                    data = if (success) "APK installed successfully: $apkPath" else output,
                    error = if (!success) "Installation failed: ${output.take(500)}" else null
                )
            }
        )
    }

    // -------------------------------------------------------------------------
    // 9. get_screen_info - 获取屏幕信息
    // -------------------------------------------------------------------------

    private fun createGetScreenInfoTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("detail") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("信息详细程度：basic（基础）或 full（完整）"))
                    put("default", JsonPrimitive("basic"))
                }
            }
        }

        return ToolDefinition(
            name = "get_screen_info",
            description = "获取当前设备的屏幕信息，包括分辨率、密度、刷新率、显示区域等。通过 wm size、wm density 等命令采集。",
            parameters = parameters,
            handler = { args ->
                val detail = args["detail"]?.jsonPrimitive?.content ?: "basic"

                val sizeResult = bridge.executeCommand("wm size", 5_000L)
                val densityResult = bridge.executeCommand("wm density", 5_000L)
                val dumpsysResult = bridge.executeCommand("dumpsys display 2>/dev/null | grep -E 'mBaseDisplayInfo|mDisplayHeight|mDisplayWidth|density' | head -20", 10_000L)

                val info = buildString {
                    appendLine("=== Screen Info ===")
                    appendLine("Size: ${sizeResult.stdout.trim().removePrefix("Physical size: ")}")
                    appendLine("Density: ${densityResult.stdout.trim().removePrefix("Physical density: ")}")

                    if (detail == "full") {
                        appendLine()
                        appendLine("--- Display Details ---")
                        appendLine(dumpsysResult.stdout.trim())
                    }
                }

                ToolResult.success(info.trimEnd())
            }
        )
    }

    // -------------------------------------------------------------------------
    // 10. click_screen - 点击屏幕
    // -------------------------------------------------------------------------

    private fun createClickScreenTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("x") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("点击位置的 X 坐标（像素）"))
                }
                putJsonObject("y") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("点击位置的 Y 坐标（像素）"))
                }
                putJsonObject("duration_ms") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("按压持续时间（毫秒），0 表示普通点击，大于 0 表示长按"))
                    put("default", JsonPrimitive(0))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("x"))
                add(JsonPrimitive("y"))
            }
        }

        return ToolDefinition(
            name = "click_screen",
            description = "通过 input tap 命令模拟屏幕点击。支持普通点击和长按（通过 duration_ms 参数控制）。",
            parameters = parameters,
            handler = { args ->
                val x = args["x"]?.jsonPrimitive?.intOrNull
                    ?: return@ToolDefinition ToolResult.failure("Missing or invalid required parameter: x")
                val y = args["y"]?.jsonPrimitive?.intOrNull
                    ?: return@ToolDefinition ToolResult.failure("Missing or invalid required parameter: y")
                val durationMs = args["duration_ms"]?.jsonPrimitive?.intOrNull ?: 0

                val command = if (durationMs > 0) {
                    // 使用 swipe 实现长按（起点和终点相同）
                    "input swipe $x $y $x $y $durationMs"
                } else {
                    "input tap $x $y"
                }

                val result = bridge.executeCommand(command, 10_000L)
                ToolResult(
                    success = result.isSuccess,
                    data = if (result.isSuccess) "Clicked at ($x, $y)" else result.stderr,
                    error = if (!result.isSuccess) "Click failed: ${result.stderr.take(500)}" else null
                )
            }
        )
    }

    // -------------------------------------------------------------------------
    // 11. type_text - 输入文本
    // -------------------------------------------------------------------------

    private fun createTypeTextTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("要输入的文本内容"))
                }
                putJsonObject("delay_ms") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("每字符输入间隔（毫秒），默认 0"))
                    put("default", JsonPrimitive(0))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("text"))
            }
        }

        return ToolDefinition(
            name = "type_text",
            description = "通过 input text 命令在当前焦点输入框中输入文本。注意：仅支持 ASCII 字符，特殊字符和空格可能被忽略。",
            parameters = parameters,
            handler = { args ->
                val text = args["text"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: text")
                val delayMs = args["delay_ms"]?.jsonPrimitive?.intOrNull ?: 0

                // input text 不完全支持所有字符，用空格替换特殊字符
                val sanitized = text.replace("'", "\\'")
                val command = "input text '$sanitized'"

                val result = bridge.executeCommand(command, 10_000L)

                if (delayMs > 0 && result.isSuccess) {
                    // 如果指定了延迟，可以使用 sendevent 方式逐字符输入
                    // 但简单场景下直接使用 input text 即可
                    kotlinx.coroutines.delay(delayMs.toLong() * text.length)
                }

                ToolResult(
                    success = result.isSuccess,
                    data = if (result.isSuccess) "Typed text (${text.length} chars)" else result.stderr,
                    error = if (!result.isSuccess) "Type text failed: ${result.stderr.take(500)}" else null
                )
            }
        )
    }

    // -------------------------------------------------------------------------
    // 12. create_python_file - 创建 Python 脚本文件
    // -------------------------------------------------------------------------

    private fun createCreatePythonFileTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("file_path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Python 脚本文件的保存路径"))
                }
                putJsonObject("content") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Python 脚本内容"))
                }
                putJsonObject("make_executable") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("是否赋予可执行权限"))
                    put("default", JsonPrimitive(false))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("file_path"))
                add(JsonPrimitive("content"))
            }
        }

        return ToolDefinition(
            name = "create_python_file",
            description = "创建一个 Python 脚本文件并写入内容。可赋予可执行权限。支持通过 write_file 确保文件写入。",
            parameters = parameters,
            handler = { args ->
                val filePath = args["file_path"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: file_path")
                val content = args["content"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: content")
                val makeExecutable = args["make_executable"]?.jsonPrimitive?.booleanOrNull ?: false

                // 确保文件以 .py 结尾
                val finalPath = if (filePath.endsWith(".py", ignoreCase = false)) filePath else "$filePath.py"

                // 添加 shebang（如果缺少）
                val finalContent = if (!content.startsWith("#!", ignoreCase = false)) {
                    "#!/usr/bin/env python3\n$content"
                } else content

                // 写入文件
                val writeResult = bridge.writeFile(finalPath, finalContent)
                if (!writeResult.isSuccess) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        data = "",
                        error = "Failed to create Python file: ${writeResult.stderr.take(500)}"
                    )
                }

                // 赋予可执行权限
                if (makeExecutable) {
                    bridge.executeCommand("chmod +x \"$finalPath\"", 5_000L)
                }

                // 验证文件是否写入成功
                val verifyResult = bridge.readFile(finalPath)
                val verified = verifyResult.isSuccess && verifyResult.stdout.isNotBlank()

                ToolResult(
                    success = verified,
                    data = buildString {
                        appendLine("Python file created: $finalPath")
                        if (makeExecutable) appendLine("Executable: true")
                        appendLine("Size: ${finalContent.length} bytes")
                    },
                    error = if (!verified) "File verification failed" else null
                )
            }
        )
    }

    // -------------------------------------------------------------------------
    // 13. create_shell_script - 创建 Shell 脚本文件
    // -------------------------------------------------------------------------

    private fun createCreateShellScriptTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("file_path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Shell 脚本文件的保存路径"))
                }
                putJsonObject("content") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Shell 脚本内容"))
                }
                putJsonObject("make_executable") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("是否赋予可执行权限"))
                    put("default", JsonPrimitive(true))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("file_path"))
                add(JsonPrimitive("content"))
            }
        }

        return ToolDefinition(
            name = "create_shell_script",
            description = "创建一个 Shell 脚本文件并写入内容。默认赋予可执行权限。自动添加 #!/system/bin/sh shebang（如果缺少）。",
            parameters = parameters,
            handler = { args ->
                val filePath = args["file_path"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: file_path")
                val content = args["content"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: content")
                val makeExecutable = args["make_executable"]?.jsonPrimitive?.booleanOrNull ?: true

                // 确保文件以 .sh 结尾
                val finalPath = if (filePath.endsWith(".sh", ignoreCase = false)) filePath else "$filePath.sh"

                // 添加 shebang（如果缺少）
                val finalContent = if (!content.startsWith("#!", ignoreCase = false)) {
                    "#!/system/bin/sh\n$content"
                } else content

                // 写入文件
                val writeResult = bridge.writeFile(finalPath, finalContent)
                if (!writeResult.isSuccess) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        data = "",
                        error = "Failed to create shell script: ${writeResult.stderr.take(500)}"
                    )
                }

                // 赋予可执行权限
                if (makeExecutable) {
                    bridge.executeCommand("chmod +x \"$finalPath\"", 5_000L)
                }

                // 验证文件
                val verifyResult = bridge.readFile(finalPath)
                val verified = verifyResult.isSuccess && verifyResult.stdout.isNotBlank()

                ToolResult(
                    success = verified,
                    data = buildString {
                        appendLine("Shell script created: $finalPath")
                        if (makeExecutable) appendLine("Executable: true")
                        appendLine("Size: ${finalContent.length} bytes")
                    },
                    error = if (!verified) "File verification failed" else null
                )
            }
        )
    }

    // -------------------------------------------------------------------------
    // 14. list_directory - 列出目录内容
    // -------------------------------------------------------------------------

    private fun createListDirectoryTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("要列出的目录路径"))
                }
                putJsonObject("recursive") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("是否递归列出子目录"))
                    put("default", JsonPrimitive(false))
                }
                putJsonObject("show_hidden") {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("是否显示隐藏文件（以 . 开头的文件）"))
                    put("default", JsonPrimitive(false))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
            }
        }

        return ToolDefinition(
            name = "list_directory",
            description = "列出指定目录中的文件和子目录。支持递归列出和显示隐藏文件。通过 ls 命令实现。",
            parameters = parameters,
            handler = { args ->
                val path = args["path"]?.jsonPrimitive?.content
                    ?: return@ToolDefinition ToolResult.failure("Missing required parameter: path")
                val recursive = args["recursive"]?.jsonPrimitive?.booleanOrNull ?: false
                val showHidden = args["show_hidden"]?.jsonPrimitive?.booleanOrNull ?: false

                val flags = buildString {
                    append("ls -l")
                    if (showHidden) append("a")
                    if (recursive) append("R")
                    append(" ")
                    append(path.replace(" ", "\\ "))
                }

                val result = bridge.executeCommand(flags, 10_000L)
                if (!result.isSuccess) {
                    return@ToolDefinition ToolResult(
                        success = false,
                        data = "",
                        error = "Failed to list directory: ${result.stderr.take(500)}"
                    )
                }

                val output = result.stdout.trim()
                val summary = if (output.isBlank()) {
                    "(empty directory)"
                } else {
                    buildString {
                        appendLine("Directory: $path")
                        if (recursive) appendLine("Mode: recursive")
                        if (showHidden) appendLine("Show hidden: true")
                        appendLine("---")
                        append(output)
                    }
                }

                ToolResult.success(summary)
            }
        )
    }

    // -------------------------------------------------------------------------
    // 15. get_system_info - 获取系统信息
    // -------------------------------------------------------------------------

    private fun createGetSystemInfoTool(): ToolDefinition {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("category") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("信息分类：all（全部）、cpu、memory、battery、storage、network"))
                    put("default", JsonPrimitive("all"))
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("all"))
                        add(JsonPrimitive("cpu"))
                        add(JsonPrimitive("memory"))
                        add(JsonPrimitive("battery"))
                        add(JsonPrimitive("storage"))
                        add(JsonPrimitive("network"))
                    })
                }
            }
        }

        return ToolDefinition(
            name = "get_system_info",
            description = "获取设备系统信息，包括 CPU、内存、电池、存储、网络等。通过读取 /proc 文件系统和系统属性采集。",
            parameters = parameters,
            handler = { args ->
                val category = args["category"]?.jsonPrimitive?.content ?: "all"

                val info = buildString {
                    appendLine("=== System Information ===")

                    when (category.lowercase()) {
                        "all", "cpu" -> {
                            appendLine()
                            appendLine("--- CPU ---")
                            val cpuInfo = bridge.executeCommand("cat /proc/cpuinfo 2>/dev/null | head -20", 5_000L)
                            appendLine(cpuInfo.stdout.trim().ifBlank { "N/A" })
                            val cpuUsage = bridge.executeCommand("top -bn1 2>/dev/null | head -5", 5_000L)
                            if (cpuUsage.isSuccess) {
                                appendLine(cpuUsage.stdout.trim())
                            }
                        }
                    }

                    when (category.lowercase()) {
                        "all", "memory" -> {
                            appendLine()
                            appendLine("--- Memory ---")
                            val memInfo = bridge.executeCommand("cat /proc/meminfo 2>/dev/null | head -10", 5_000L)
                            appendLine(memInfo.stdout.trim().ifBlank { "N/A" })
                        }
                    }

                    when (category.lowercase()) {
                        "all", "battery" -> {
                            appendLine()
                            appendLine("--- Battery ---")
                            val batteryInfo = bridge.executeCommand("dumpsys battery 2>/dev/null", 5_000L)
                            appendLine(batteryInfo.stdout.trim().ifBlank { "N/A" })
                        }
                    }

                    when (category.lowercase()) {
                        "all", "storage" -> {
                            appendLine()
                            appendLine("--- Storage ---")
                            val storageInfo = bridge.executeCommand("df -h /data /sdcard 2>/dev/null", 5_000L)
                            appendLine(storageInfo.stdout.trim().ifBlank { "N/A" })
                        }
                    }

                    when (category.lowercase()) {
                        "all", "network" -> {
                            appendLine()
                            appendLine("--- Network ---")
                            val networkInfo = bridge.executeCommand("dumpsys connectivity 2>/dev/null | grep -E 'ActiveNetwork|Transport|Capabilities' | head -10", 5_000L)
                            appendLine(networkInfo.stdout.trim().ifBlank { "N/A" })
                        }
                    }
                }

                ToolResult.success(info.trimEnd())
            }
        )
    }
}