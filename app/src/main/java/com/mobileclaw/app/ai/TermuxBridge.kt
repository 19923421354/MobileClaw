package com.mobileclaw.app.ai

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.mobileclaw.app.model.ShellResult
import com.mobileclaw.app.shizuku.ShizukuManager
import com.mobileclaw.app.shizuku.ShizukuService
import com.mobileclaw.app.shizuku.ShizukuServiceBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Termux/shell 命令执行桥接器。
 *
 * 提供统一的 Shell 命令执行、Python 脚本执行、文件系统操作以及
 * Termux 检测与集成能力。所有系统级操作优先通过 Shizuku（特权进程）
 * 执行，Shizuku 不可用时自动降级为本地 [Runtime.exec] 执行。
 *
 * 核心能力：
 * - Shell 命令执行（Shizuku 优先，本地回退）
 * - Python 代码执行（Termux 环境优先，系统 python 回退）
 * - 文件读写操作
 * - Termux 安装检测与集成
 * - APK 安装
 * - 临时脚本文件创建
 * - Java 代码编译与运行
 * - 命令可用性检测
 *
 * @param context 应用上下文，用于包管理器查询、缓存目录访问等
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"

        /** Termux 包名。 */
        private const val TERMUX_PACKAGE = "com.termux"

        /** Termux 用户空间前缀。 */
        private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

        /** Termux Python 可执行文件路径。 */
        private const val TERMUX_PYTHON = "$TERMUX_PREFIX/bin/python"

        /** Termux Bash 可执行文件路径。 */
        private const val TERMUX_BASH = "$TERMUX_PREFIX/bin/bash"

        /** 默认 Shell 命令超时时间（毫秒）。 */
        private const val DEFAULT_SHELL_TIMEOUT_MS = 30_000L

        /** 默认 Python 执行超时时间（毫秒）。 */
        private const val DEFAULT_PYTHON_TIMEOUT_MS = 60_000L

        /** 文件操作超时时间（毫秒）。 */
        private const val FILE_OP_TIMEOUT_MS = 10_000L

        /** Java 编译与运行超时时间（毫秒）。 */
        private const val JAVA_TIMEOUT_MS = 120_000L
    }

    // ==================================================================================
    // 内部工具方法
    // ==================================================================================

    /**
     * 通过 Shizuku 执行 shell 命令。
     *
     * @param command   要执行的命令
     * @param timeoutMs 超时时间（毫秒）
     * @return 执行结果
     */
    private suspend fun executeViaShizuku(command: String, timeoutMs: Long): ShellResult {
        if (!ShizukuManager.isShizukuAvailable()) {
            throw IllegalStateException("Shizuku is not available")
        }
        if (!ShizukuServiceBinder.isBound()) {
            ShizukuServiceBinder.bind(context)
        }
        val service = ShizukuServiceBinder.requireService()
        val result = service.executeShell(command, timeoutMs)
        return ShellResult(
            exitCode = result.getOrElse(0) { "-1" }.toIntOrNull() ?: -1,
            stdout = result.getOrElse(1) { "" },
            stderr = result.getOrElse(2) { "" }
        )
    }

    /**
     * 通过本地 [Runtime.exec] 执行 shell 命令。
     *
     * @param command   要执行的命令
     * @param timeoutMs 超时时间（毫秒）
     * @return 执行结果
     */
    private suspend fun executeLocal(command: String, timeoutMs: Long): ShellResult {
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val stdoutBuilder = StringBuilder()
                val stderrBuilder = StringBuilder()

                val stdoutThread = Thread {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { stdoutBuilder.append(it).append('\n') }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "read stdout failed", e)
                    }
                }
                val stderrThread = Thread {
                    try {
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { stderrBuilder.append(it).append('\n') }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "read stderr failed", e)
                    }
                }

                stdoutThread.start()
                stderrThread.start()

                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    stdoutThread.join(200)
                    stderrThread.join(200)
                    stderrBuilder.append("[timeout after ").append(timeoutMs).append("ms]")
                    return@withContext ShellResult(
                        exitCode = -1,
                        stdout = stdoutBuilder.toString(),
                        stderr = stderrBuilder.toString()
                    )
                }

                stdoutThread.join(1000)
                stderrThread.join(1000)

                val exitCode = try {
                    process.exitValue()
                } catch (e: Exception) {
                    -1
                }
                ShellResult(
                    exitCode = exitCode,
                    stdout = stdoutBuilder.toString(),
                    stderr = stderrBuilder.toString()
                )
            } catch (e: Exception) {
                Log.e(TAG, "local execute failed: $command", e)
                ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = e.message ?: "local execution failed"
                )
            }
        }
    }

    /**
     * 执行 shell 命令，Shizuku 优先，失败时自动降级为本地执行。
     *
     * @param command   要执行的命令
     * @param timeoutMs 超时时间（毫秒）
     * @return 执行结果
     */
    private suspend fun execute(command: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): ShellResult {
        return try {
            if (ShizukuManager.isShizukuAvailable()) {
                executeViaShizuku(command, timeoutMs)
            } else {
                executeLocal(command, timeoutMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku execution failed, falling back to local: ${e.message}")
            try {
                executeLocal(command, timeoutMs)
            } catch (e2: Exception) {
                Log.e(TAG, "both Shizuku and local execution failed", e2)
                ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "execution failed: ${e2.message}"
                )
            }
        }
    }

    // ==================================================================================
    // 1. executeCommand —— 执行 Shell 命令
    // ==================================================================================

    /**
     * 执行 Shell 命令。
     *
     * 优先通过 Shizuku 以特权身份执行，Shizuku 不可用时降级为本地执行。
     * 支持超时控制，超时后强制终止进程。
     *
     * @param command   要执行的 Shell 命令
     * @param timeoutMs 超时时间（毫秒），默认 30 秒
     * @return [ShellResult] 包含 stdout、stderr 和退出码
     */
    suspend fun executeCommand(
        command: String,
        timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS
    ): ShellResult {
        Log.d(TAG, "executeCommand: $command")
        return execute(command, timeoutMs)
    }

    // ==================================================================================
    // 2. executePython —— 执行 Python 代码
    // ==================================================================================

    /**
     * 执行 Python 代码。
     *
     * 将 Python 代码写入临时文件，然后通过 Python 解释器执行。
     * 执行策略：
     * 1. 优先使用 Termux 环境中的 Python
     * 2. 回退到系统 Python（python3 或 python）
     * 3. 若均不可用，返回错误结果
     *
     * @param code     要执行的 Python 代码
     * @param timeoutMs 超时时间（毫秒），默认 60 秒
     * @return 执行结果
     */
    suspend fun executePython(
        code: String,
        timeoutMs: Long = DEFAULT_PYTHON_TIMEOUT_MS
    ): ShellResult {
        Log.d(TAG, "executePython: code length=${code.length}")

        val pythonPath = resolvePythonPath()
        if (pythonPath == null) {
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Python is not available. Install Termux with 'pkg install python' or ensure system python is installed."
            )
        }

        val scriptFile = createTempScriptFile(code, "py") ?: return ShellResult(
            exitCode = -1,
            stdout = "",
            stderr = "Failed to create temporary Python script file"
        )

        return try {
            val command = "$pythonPath \"$scriptFile\""
            execute(command, timeoutMs)
        } finally {
            // 清理临时文件
            try {
                if (scriptFile.exists()) {
                    scriptFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete temp Python script: $scriptFile", e)
            }
        }
    }

    /**
     * 解析可用的 Python 解释器路径。
     *
     * 查找顺序：
     * 1. Termux 环境中的 Python（/data/data/com.termux/files/usr/bin/python）
     * 2. 系统 python3
     * 3. 系统 python
     *
     * @return Python 解释器的绝对路径，未找到时返回 null
     */
    private fun resolvePythonPath(): String? {
        val candidates = listOf(
            TERMUX_PYTHON,
            "/data/data/com.termux/files/usr/bin/python3",
            "python3",
            "python"
        )

        for (candidate in candidates) {
            if (candidate.startsWith("/")) {
                // 绝对路径：直接检查文件是否存在
                if (File(candidate).exists()) {
                    return candidate
                }
            } else {
                // 相对路径：通过 which 检查
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "which $candidate"))
                    val output = process.inputStream.bufferedReader().readText().trim()
                    if (output.isNotEmpty() && process.waitFor(2, TimeUnit.SECONDS) && output.contains(candidate)) {
                        return candidate
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "checking $candidate failed", e)
                }
            }
        }
        return null
    }

    // ==================================================================================
    // 3. writeFile —— 写入文件
    // ==================================================================================

    /**
     * 将内容写入指定文件。
     *
     * 优先通过 Shizuku 执行写入操作，Shizuku 不可用时使用本地文件 I/O。
     * 自动创建父目录。
     *
     * @param path    文件绝对路径
     * @param content 要写入的文件内容
     * @return 执行结果，exitCode 为 0 表示写入成功
     */
    suspend fun writeFile(path: String, content: String): ShellResult {
        Log.d(TAG, "writeFile: $path")

        // 尝试通过 Shizuku 执行写入
        if (ShizukuManager.isShizukuAvailable()) {
            // 使用 base64 编码避免特殊字符问题
            val encoded = android.util.Base64.encodeToString(
                content.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            // 先创建目录，再解码写入
            val parentDir = File(path).parent
            val command = buildString {
                if (parentDir != null) {
                    append("mkdir -p '")
                    append(parentDir.replace("'", "'\\''"))
                    append("' && ")
                }
                append("echo '$encoded' | base64 -d > '")
                append(path.replace("'", "'\\''"))
                append("'")
            }
            val result = execute(command, FILE_OP_TIMEOUT_MS)
            if (result.isSuccess) {
                return result
            }
            // Shizuku 写入失败，降级到本地
            Log.w(TAG, "Shizuku writeFile failed, falling back to local I/O")
        }

        // 本地文件 I/O 降级
        return withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                file.parentFile?.mkdirs()
                file.writeText(content, Charsets.UTF_8)
                ShellResult(exitCode = 0, stdout = "File written: $path (${content.length} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "writeFile failed: $path", e)
                ShellResult(
                    exitCode = -1,
                    stderr = "Failed to write file: ${e.message}"
                )
            }
        }
    }

    // ==================================================================================
    // 4. readFile —— 读取文件
    // ==================================================================================

    /**
     * 读取指定文件的内容。
     *
     * 优先通过 Shizuku 执行读取操作，Shizuku 不可用时使用本地文件 I/O。
     *
     * @param path 文件绝对路径
     * @return 执行结果，stdout 包含文件内容
     */
    suspend fun readFile(path: String): ShellResult {
        Log.d(TAG, "readFile: $path")

        // 尝试通过 Shizuku 执行读取
        if (ShizukuManager.isShizukuAvailable()) {
            try {
                if (!ShizukuServiceBinder.isBound()) {
                    ShizukuServiceBinder.bind(context)
                }
                val service = ShizukuServiceBinder.requireService()
                val content = service.readFile(path)
                if (content.isNotEmpty()) {
                    return ShellResult(exitCode = 0, stdout = content)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku readFile failed, falling back to local I/O", e)
            }
        }

        // 本地文件 I/O 降级
        return withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                if (!file.exists()) {
                    return@withContext ShellResult(
                        exitCode = -1,
                        stderr = "File not found: $path"
                    )
                }
                if (!file.canRead()) {
                    return@withContext ShellResult(
                        exitCode = -1,
                        stderr = "File not readable: $path"
                    )
                }
                val content = file.readText(Charsets.UTF_8)
                ShellResult(exitCode = 0, stdout = content)
            } catch (e: Exception) {
                Log.e(TAG, "readFile failed: $path", e)
                ShellResult(
                    exitCode = -1,
                    stderr = "Failed to read file: ${e.message}"
                )
            }
        }
    }

    // ==================================================================================
    // 5. isTermuxInstalled —— 检查 Termux 是否已安装
    // ==================================================================================

    /**
     * 检查 Termux 是否已安装。
     *
     * 检测方式：
     * 1. 通过 [PackageManager] 查询 Termux 包是否存在
     * 2. 检查 Termux 用户空间目录是否存在
     *
     * @return true 表示 Termux 已安装
     */
    fun isTermuxInstalled(): Boolean {
        // 方式一：通过 PackageManager 查询
        try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            return true
        } catch (e: PackageManager.NameNotFoundException) {
            Log.d(TAG, "Termux package not found via PackageManager")
        }

        // 方式二：检查 Termux 用户空间目录
        if (File(TERMUX_PREFIX).exists()) {
            return true
        }

        // 方式三：通过 Shizuku 检查（如果可用）
        try {
            if (ShizukuManager.isShizukuAvailable()) {
                val result = runBlockingSafe {
                    execute("pm list packages | grep -q '^package:$TERMUX_PACKAGE' && echo 'found' || echo 'not found'")
                }
                if (result.isSuccess && result.stdout.trim() == "found") {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Shizuku check for Termux failed", e)
        }

        return false
    }

    // ==================================================================================
    // 6. isPythonAvailable —— 检查 Python 是否可用
    // ==================================================================================

    /**
     * 检查 Python 是否可用。
     *
     * 检测范围包括 Termux Python 和系统 Python。
     *
     * @return true 表示 Python 解释器可用
     */
    fun isPythonAvailable(): Boolean {
        return resolvePythonPath() != null
    }

    // ==================================================================================
    // 7. installPackage —— 安装 APK
    // ==================================================================================

    /**
     * 通过 `pm install` 命令安装 APK 包。
     *
     * 需要 Shizuku 权限或 root 权限。如果 Shizuku 不可用，将返回错误。
     *
     * @param packageName APK 文件的绝对路径
     * @return 执行结果
     */
    suspend fun installPackage(packageName: String): ShellResult {
        Log.d(TAG, "installPackage: $packageName")

        if (!ShizukuManager.isShizukuAvailable()) {
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shizuku is not available. pm install requires system privileges."
            )
        }

        // 检查文件是否存在
        val apkFile = File(packageName)
        if (!apkFile.exists()) {
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "APK file not found: $packageName"
            )
        }

        // 执行 pm install
        val command = "pm install -r --user 0 \"$packageName\" 2>&1"
        val result = execute(command, 120_000L) // APK 安装超时设 2 分钟

        // 解析 pm install 的输出
        val output = (result.stdout + result.stderr).trim()
        if (result.isSuccess || output.contains("Success", ignoreCase = true)) {
            return ShellResult(
                exitCode = 0,
                stdout = "Package installed successfully: $packageName",
                stderr = if (!result.isSuccess) output else ""
            )
        }

        return result
    }

    // ==================================================================================
    // 8. createScriptFile —— 创建临时脚本文件
    // ==================================================================================

    /**
     * 创建临时脚本文件并赋予可执行权限。
     *
     * 文件创建在应用缓存目录中，确保应用有写入权限。
     * 脚本文件会在应用重启时被自动清理（位于缓存目录）。
     *
     * @param content   脚本内容
     * @param extension 脚本文件扩展名（默认 "sh"）
     * @return 脚本文件的绝对路径，创建失败时返回 null
     */
    fun createScriptFile(content: String, extension: String = "sh"): String? {
        return try {
            val scriptDir = File(context.cacheDir, "scripts")
            scriptDir.mkdirs()

            val fileName = "script_${UUID.randomUUID().toString().take(8)}.$extension"
            val scriptFile = File(scriptDir, fileName)

            // 写入脚本内容
            scriptFile.writeText(content, Charsets.UTF_8)

            // 赋予可执行权限
            scriptFile.setExecutable(true)

            // 对于 shell 脚本，添加 shebang（如果缺少）
            if (extension == "sh" && !content.startsWith("#!")) {
                val withShebang = "#!/system/bin/sh\n$content"
                scriptFile.writeText(withShebang, Charsets.UTF_8)
            } else if (extension == "py" && !content.startsWith("#!")) {
                val withShebang = "#!/usr/bin/env python\n$content"
                scriptFile.writeText(withShebang, Charsets.UTF_8)
            }

            val absolutePath = scriptFile.absolutePath
            Log.d(TAG, "Created script file: $absolutePath")
            absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create script file", e)
            null
        }
    }

    // ==================================================================================
    // 9. compileAndRunJava —— 编译并运行 Java 代码
    // ==================================================================================

    /**
     * 编译并运行 Java 代码。
     *
     * 执行策略：
     * 1. 如果 Termux 中安装了 JDK（openjdk-17 或类似），使用 Termux 的 javac + java
     * 2. 否则，尝试使用 ecj（Eclipse Compiler for Java）+ dx 编译为 dex，
     *    再通过 app_process 或 dalvikvm 运行
     * 3. 均不可用时，返回错误结果
     *
     * 注意：代码中不应包含包声明，类名应与文件名一致（默认为 Main）。
     *
     * @param code Java 源代码
     * @return 执行结果，stdout 包含程序输出
     */
    suspend fun compileAndRunJava(code: String): ShellResult {
        Log.d(TAG, "compileAndRunJava: code length=${code.length}")

        // 提取类名（默认 Main）
        val className = extractClassName(code) ?: "Main"
        val packageName = extractPackageName(code)
        val fullyQualifiedName = if (packageName != null) "$packageName.$className" else className

        // 创建临时工作目录
        val workDir = File(context.cacheDir, "java_compilation_${UUID.randomUUID().toString().take(8)}")
        try {
            workDir.mkdirs()

            val sourceFile = File(workDir, "$className.java")
            sourceFile.writeText(code, Charsets.UTF_8)

            // 策略 1：尝试使用 Termux JDK
            val termuxJavac = "$TERMUX_PREFIX/bin/javac"
            val termuxJava = "$TERMUX_PREFIX/bin/java"
            if (File(termuxJavac).exists() && File(termuxJava).exists()) {
                return compileAndRunWithTermuxJdk(
                    sourceFile = sourceFile,
                    workDir = workDir,
                    className = className,
                    fullyQualifiedName = fullyQualifiedName,
                    javacPath = termuxJavac,
                    javaPath = termuxJava
                )
            }

            // 策略 2：尝试使用 ecj + dx + app_process
            val ecjJar = findEcjJar()
            val dxPath = findDxPath()
            if (ecjJar != null && dxPath != null) {
                return compileAndRunWithDx(
                    sourceFile = sourceFile,
                    workDir = workDir,
                    className = className,
                    fullyQualifiedName = fullyQualifiedName,
                    ecjJar = ecjJar,
                    dxPath = dxPath
                )
            }

            // 策略 3：在 Termux 中尝试通过 pkg 查找 java
            if (isTermuxInstalled()) {
                val detectResult = execute("$TERMUX_BASH -c 'which java 2>/dev/null || echo NOT_FOUND'", 5000)
                val javaPath = detectResult.stdout.trim()
                if (javaPath.isNotEmpty() && javaPath != "NOT_FOUND") {
                    val javacPath = javaPath.replace("java", "javac")
                    if (File(javacPath).exists()) {
                        return compileAndRunWithTermuxJdk(
                            sourceFile = sourceFile,
                            workDir = workDir,
                            className = className,
                            fullyQualifiedName = fullyQualifiedName,
                            javacPath = javacPath,
                            javaPath = javaPath
                        )
                    }
                }
            }

            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Java compilation requires a JDK. Install Termux and run 'pkg install openjdk-17' to enable Java support."
            )
        } catch (e: Exception) {
            Log.e(TAG, "compileAndRunJava failed", e)
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Java compilation failed: ${e.message}"
            )
        } finally {
            // 清理临时文件
            try {
                workDir.deleteRecursively()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clean up Java work dir: $workDir", e)
            }
        }
    }

    /**
     * 使用 Termux JDK 编译并运行 Java 代码。
     */
    private suspend fun compileAndRunWithTermuxJdk(
        sourceFile: File,
        workDir: File,
        className: String,
        fullyQualifiedName: String,
        javacPath: String,
        javaPath: String
    ): ShellResult {
        // 编译
        val compileCommand = "$javacPath -d \"${workDir.absolutePath}\" \"${sourceFile.absolutePath}\" 2>&1"
        val compileResult = execute(compileCommand, JAVA_TIMEOUT_MS)
        if (!compileResult.isSuccess) {
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Compilation failed:\n${compileResult.stderr}\n${compileResult.stdout}"
            )
        }

        // 运行
        val runCommand = "$javaPath -cp \"${workDir.absolutePath}\" $fullyQualifiedName 2>&1"
        return execute(runCommand, JAVA_TIMEOUT_MS)
    }

    /**
     * 使用 ecj + dx + app_process 编译并运行 Java 代码。
     */
    private suspend fun compileAndRunWithDx(
        sourceFile: File,
        workDir: File,
        className: String,
        fullyQualifiedName: String,
        ecjJar: String,
        dxPath: String
    ): ShellResult {
        // 步骤 1：使用 ecj 编译 .java 为 .class
        val ecjCommand = "java -jar \"$ecjJar\" -d \"${workDir.absolutePath}\" \"${sourceFile.absolutePath}\" 2>&1"
        val ecjResult = execute(ecjCommand, JAVA_TIMEOUT_MS)
        if (!ecjResult.isSuccess) {
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "ECJ compilation failed:\n${ecjResult.stderr}\n${ecjResult.stdout}"
            )
        }

        // 步骤 2：使用 dx 将 .class 打包为 .dex
        val dexDir = File(workDir, "dex")
        dexDir.mkdirs()
        val dxCommand = "$dxPath --dex --output=\"${dexDir.absolutePath}/classes.dex\" \"${workDir.absolutePath}\" 2>&1"
        val dxResult = execute(dxCommand, JAVA_TIMEOUT_MS)
        if (!dxResult.isSuccess) {
            return ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Dex conversion failed:\n${dxResult.stderr}\n${dxResult.stdout}"
            )
        }

        // 步骤 3：使用 app_process 运行 dex
        val runCommand = "app_process -Djava.class.path=\"${dexDir.absolutePath}/classes.dex\" / $fullyQualifiedName 2>&1"
        return execute(runCommand, JAVA_TIMEOUT_MS)
    }

    /**
     * 从 Java 源代码中提取类名。
     */
    private fun extractClassName(code: String): String? {
        // 匹配 public class <Name> 或 class <Name>
        val regex = Regex("""(?:public\s+)?(?:abstract\s+|final\s+)?class\s+(\w+)""")
        return regex.find(code)?.groupValues?.getOrNull(1)
    }

    /**
     * 从 Java 源代码中提取包名。
     */
    private fun extractPackageName(code: String): String? {
        val regex = Regex("""package\s+([\w.]+)\s*;""")
        return regex.find(code)?.groupValues?.getOrNull(1)
    }

    /**
     * 查找 ecj.jar（Eclipse Compiler for Java）的位置。
     *
     * 搜索 Termux 环境以及可能的系统路径。
     */
    private fun findEcjJar(): String? {
        val candidates = listOf(
            "$TERMUX_PREFIX/share/java/ecj.jar",
            "$TERMUX_PREFIX/share/eclipse-ecj.jar",
            "/system/framework/ecj.jar",
            "/data/data/com.termux/files/home/.termux/ecj.jar"
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    /**
     * 查找 dx 工具的位置。
     *
     * dx 是 Android SDK 中的工具，用于将 .class 文件转换为 .dex。
     * 在 Termux 中可通过 `pkg install dx` 安装。
     */
    private fun findDxPath(): String? {
        val candidates = listOf(
            "$TERMUX_PREFIX/bin/dx",
            "/system/bin/dx",
            "/system/xbin/dx"
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    // ==================================================================================
    // 10. checkCommandAvailability —— 检查命令是否可用
    // ==================================================================================

    /**
     * 检查指定命令是否存在于 PATH 中。
     *
     * 使用 `command -v` 或 `which` 进行检测。
     *
     * @param command 要检查的命令名称
     * @return true 表示命令可用
     */
    fun checkCommandAvailability(command: String): Boolean {
        // 优先检查绝对路径
        if (command.startsWith("/")) {
            return File(command).exists()
        }

        // 通过 `command -v` 检查
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v \"$command\""))
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor(2, TimeUnit.SECONDS) && output.isNotEmpty()) {
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "command -v $command failed", e)
        }

        // 回退到 which
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "which \"$command\""))
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor(2, TimeUnit.SECONDS) && output.isNotEmpty()) {
                return true
            }
        } catch (e: Exception) {
            Log.d(TAG, "which $command failed", e)
        }

        // 如果以上都不行，通过 Shizuku 再检查一次
        if (ShizukuManager.isShizukuAvailable()) {
            try {
                val result = runBlockingSafe {
                    execute("command -v \"$command\" 2>/dev/null || echo NOT_FOUND")
                }
                val output = result.stdout.trim()
                return output.isNotEmpty() && output != "NOT_FOUND" && !output.contains("not found", ignoreCase = true)
            } catch (e: Exception) {
                Log.d(TAG, "Shizuku check for $command failed", e)
            }
        }

        return false
    }

    // ==================================================================================
    // 辅助方法
    // ==================================================================================

    /**
     * 创建临时脚本文件（内部使用，用于 Python 等代码执行）。
     *
     * @param content   文件内容
     * @param extension 文件扩展名
     * @return 创建的临时文件对象，失败时返回 null
     */
    private fun createTempScriptFile(content: String, extension: String): File? {
        return try {
            val scriptDir = File(context.cacheDir, "temp_scripts")
            scriptDir.mkdirs()

            val fileName = "tmp_${UUID.randomUUID().toString().take(8)}.$extension"
            val file = File(scriptDir, fileName)
            file.writeText(content, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create temp script file", e)
            null
        }
    }

    /**
     * 在协程上下文中安全地执行挂起函数。
     * 用于从非协程（如同步检测方法）调用挂起函数。
     */
    private fun <T> runBlockingSafe(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking {
            withContext(Dispatchers.IO) {
                block()
            }
        }
    }

    // ==================================================================================
    // JSON 工具方法（供外部使用）
    // ==================================================================================

    /**
     * 将 [ShellResult] 转换为 [JsonObject]。
     *
     * 便于将执行结果序列化为 JSON 格式，用于 AI 模型输入或日志记录。
     *
     * @param result Shell 执行结果
     * @return 包含 stdout、stderr、exitCode 和 isSuccess 的 JSON 对象
     */
    fun shellResultToJson(result: ShellResult): JsonObject {
        return JsonObject(
            mapOf(
                "stdout" to JsonPrimitive(result.stdout),
                "stderr" to JsonPrimitive(result.stderr),
                "exitCode" to JsonPrimitive(result.exitCode),
                "isSuccess" to JsonPrimitive(result.isSuccess)
            )
        )
    }

    /**
     * 将 Shell 命令执行结果封装为 [JsonObject]。
     *
     * 方便 AI 模块解析执行结果。
     *
     * @param command 执行的命令
     * @param result  执行结果
     * @return 包含命令和结果的 JSON 对象
     */
    fun commandResultToJson(command: String, result: ShellResult): JsonObject {
        return JsonObject(
            mapOf(
                "command" to JsonPrimitive(command),
                "stdout" to JsonPrimitive(result.stdout),
                "stderr" to JsonPrimitive(result.stderr),
                "exitCode" to JsonPrimitive(result.exitCode),
                "isSuccess" to JsonPrimitive(result.isSuccess)
            )
        )
    }
}