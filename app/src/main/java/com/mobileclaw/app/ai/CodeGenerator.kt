package com.mobileclaw.app.ai

import android.content.Context
import android.util.Log
import com.mobileclaw.app.model.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

// =============================================================================
//  CodeGenResult - 代码生成结果
// =============================================================================

/**
 * 代码生成或执行的结果。
 *
 * @property success        操作是否成功
 * @property filePath       生成/执行的代码文件路径（可能为 null）
 * @property code           生成的代码内容
 * @property executionResult 执行结果文本（仅执行操作时填充）
 * @property error          错误信息（操作失败时填充）
 */
data class CodeGenResult(
    val success: Boolean,
    val filePath: String? = null,
    val code: String = "",
    val executionResult: String? = null,
    val error: String? = null
) {
    companion object {
        /** 构造成功结果。 */
        fun success(
            filePath: String? = null,
            code: String = "",
            executionResult: String? = null
        ): CodeGenResult = CodeGenResult(
            success = true,
            filePath = filePath,
            code = code,
            executionResult = executionResult
        )

        /** 构造失败结果。 */
        fun failure(error: String, code: String = ""): CodeGenResult = CodeGenResult(
            success = false,
            code = code,
            error = error
        )
    }
}

// =============================================================================
//  APK 模板内嵌数据
// =============================================================================

/**
 * Android APK 最小项目模板。
 *
 * 包含一个完整的可编译 Android 项目骨架，用于 [CodeGenerator.generateAndroidApk]。
 * 模板使用 com.example.app 作为默认包名，可在生成时通过参数替换。
 */
private object ApkTemplate {

    /** AndroidManifest.xml 模板。 */
    const val ANDROID_MANIFEST_XML = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{{PACKAGE_NAME}}">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="{{APP_NAME}}"
        android:supportsRtl="true"
        android:theme="@style/Theme.AppCompat.Light.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

    /** MainActivity.java 模板。 */
    const val MAIN_ACTIVITY_JAVA = """package {{PACKAGE_NAME}};

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        TextView textView = new TextView(this);
        textView.setText("Hello from MobileClaw!");
        textView.setTextSize(24);
        textView.setGravity(android.view.Gravity.CENTER);
        textView.setTextColor(0xFF2196F3.toInt());

        setContentView(textView);
    }
}
"""

    /** build.gradle (Module: app) 模板。 */
    const val BUILD_GRADLE_APP = """apply plugin: 'com.android.application'
apply plugin: 'org.jetbrains.kotlin.android'

android {
    namespace '{{PACKAGE_NAME}}'
    compileSdk 34

    defaultConfig {
        applicationId '{{PACKAGE_NAME}}'
        minSdk 21
        targetSdk 34
        versionCode 1
        versionName '1.0'
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'androidx.core:core-ktx:1.12.0'
}
"""

    /** settings.gradle 模板。 */
    const val SETTINGS_GRADLE = """pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = '{{APP_NAME}}'
include ':app'
"""

    /** build.gradle (Project) 模板。 */
    const val BUILD_GRADLE_PROJECT = """plugins {
    id 'com.android.application' version '8.2.2' apply false
    id 'org.jetbrains.kotlin.android' version '1.9.22' apply false
}
"""

    /** gradle.properties 模板。 */
    const val GRADLE_PROPERTIES = """org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
"""

    /** gradle-wrapper.properties 模板。 */
    const val GRADLE_WRAPPER_PROPERTIES = """distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
"""

    /** local.properties 模板。 */
    const val LOCAL_PROPERTIES = """## This file is automatically generated by MobileClaw CodeGenerator.
sdk.dir=/opt/android-sdk
"""

    /** proguard-rules.pro 模板。 */
    const val PROGUARD_RULES = """# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
"""

    /**
     * 将模板中的占位符替换为实际值。
     *
     * @param template  模板字符串
     * @param packageName 包名
     * @param appName   应用名称
     * @return 替换后的字符串
     */
    fun resolve(template: String, packageName: String, appName: String): String {
        return template
            .replace("{{PACKAGE_NAME}}", packageName)
            .replace("{{APP_NAME}}", appName)
    }
}

// =============================================================================
//  CodeGenerator - 代码生成引擎
// =============================================================================

/**
 * CodeGenerator - 在 Android 设备上生成并执行代码。
 *
 * 核心能力：
 * - 使用 AIGateway（LLM）进行智能代码生成
 * - 支持生成 Python、Shell、Kotlin、Java 代码
 * - 支持生成可编译的 Android APK 项目
 * - 支持通过 TermuxBridge 执行生成的代码
 * - 支持基于模板创建项目骨架
 *
 * 当 AIGateway 可用时，优先使用 LLM 生成代码；
 * 当 AIGateway 不可用时，使用内置模板或回退策略。
 *
 * @param context       Android 应用上下文，用于文件系统操作
 * @param termuxBridge  Termux 桥接器，用于执行代码和文件操作
 * @param gateway       AIGateway 实例（可选），用于 LLM 代码生成
 */
class CodeGenerator(
    private val context: Context,
    private val termuxBridge: TermuxBridge,
    private val gateway: AIGateway? = null
) {

    // =========================================================================
    //  常量
    // =========================================================================

    companion object {
        private const val TAG = "CodeGenerator"

        /** 代码生成基础目录（应用缓存目录下）。 */
        private const val CODE_DIR_NAME = "generated_code"

        /** Python 子目录。 */
        private const val PYTHON_DIR = "python"

        /** Shell 子目录。 */
        private const val SHELL_DIR = "shell"

        /** Kotlin 子目录。 */
        private const val KOTLIN_DIR = "kotlin"

        /** Java 子目录。 */
        private const val JAVA_DIR = "java"

        /** APK 子目录。 */
        private const val APK_DIR = "apk_projects"

        /** 默认 LLM 超时时间（毫秒）。 */
        private const val LLM_TIMEOUT_MS = 120_000L

        /** 默认代码执行超时时间（毫秒）。 */
        private const val EXECUTION_TIMEOUT_MS = 60_000L

        /** 系统提示词模板 - Python 生成。 */
        private const val SYSTEM_PROMPT_PYTHON = """你是一个专业 Python 代码生成助手。请根据用户的任务描述生成可直接运行的 Python 代码。
要求：
1. 只输出代码，不要包含任何解释、说明或 markdown 代码块标记
2. 代码应该完整、可直接运行
3. 包含必要的 import 语句
4. 代码应该健壮，包含适当的错误处理
5. 如果任务需要用户输入，使用命令行参数或标准输入
6. 添加必要的注释说明关键步骤
7. 输出纯 Python 代码，不要用 ```python ``` 包裹"""

        /** 系统提示词模板 - Shell 生成。 */
        private const val SYSTEM_PROMPT_SHELL = """你是一个专业 Shell 脚本生成助手。请根据用户的任务描述生成可直接运行的 Shell 脚本。
要求：
1. 只输出脚本代码，不要包含任何解释、说明或 markdown 代码块标记
2. 脚本应该以 #!/system/bin/sh 开头
3. 包含必要的错误检查和退出处理
4. 添加必要的注释说明关键步骤
5. 输出纯 Shell 代码，不要用 ```bash ``` 包裹"""

        /** 系统提示词模板 - Kotlin 生成。 */
        private const val SYSTEM_PROMPT_KOTLIN = """你是一个专业 Kotlin 代码生成助手。请根据用户的描述生成完整的 Kotlin 类或代码。
要求：
1. 只输出代码，不要包含任何解释、说明或 markdown 代码块标记
2. 代码应该完整、可编译
3. 包含必要的 import 语句
4. 添加必要的注释
5. 输出纯 Kotlin 代码，不要用 ```kotlin ``` 包裹"""

        /** 系统提示词模板 - Java 生成。 */
        private const val SYSTEM_PROMPT_JAVA = """你是一个专业 Java 代码生成助手。请根据用户的描述生成完整的 Java 类或代码。
要求：
1. 只输出代码，不要包含任何解释、说明或 markdown 代码块标记
2. 代码应该完整、可编译
3. 包含必要的 import 语句
4. 添加必要的注释
5. 输出纯 Java 代码，不要用 ```java ``` 包裹"""

        /** 系统提示词模板 - 通用代码生成。 */
        private const val SYSTEM_PROMPT_GENERAL = """你是一个专业代码生成助手。请根据用户的描述生成代码。
要求：
1. 只输出代码，不要包含任何解释、说明或 markdown 代码块标记
2. 代码应该完整、可直接使用
3. 包含必要的 import 语句
4. 添加必要的注释说明关键步骤
5. 输出纯代码，不要用任何 markdown 代码块包裹"""
    }

    // =========================================================================
    //  内部状态
    // =========================================================================

    /** 代码生成根目录。 */
    private val codeGenDir: File = File(context.cacheDir, CODE_DIR_NAME).also { it.mkdirs() }

    /** JSON 编解码器。 */
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** OkHttp 客户端，用于 LLM API 调用。 */
    private val llmClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(LLM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(LLM_TIMEOUT_MS + 30_000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // =========================================================================
    //  核心方法：生成 Python 代码
    // =========================================================================

    /**
     * 根据任务描述生成 Python 代码，保存到文件，并可选执行。
     *
     * 流程：
     * 1. 使用 LLM 生成 Python 代码
     * 2. 清理代码（移除 markdown 包裹等）
     * 3. 保存到 .py 文件
     * 4. 通过 TermuxBridge 执行
     *
     * @param task 任务描述
     * @return 生成结果，包含文件路径、代码和执行输出
     */
    suspend fun generatePython(task: String): CodeGenResult {
        Log.d(TAG, "generatePython: task=$task")

        // 步骤 1：调用 LLM 生成代码
        val rawCode = callLLM(SYSTEM_PROMPT_PYTHON, task)
        if (rawCode == null) {
            return CodeGenResult.failure(
                error = "LLM 生成 Python 代码失败：API 调用无返回或未配置",
                code = ""
            )
        }

        val cleanedCode = cleanGeneratedCode(rawCode, "python")
        if (cleanedCode.isBlank()) {
            return CodeGenResult.failure(
                error = "LLM 返回空代码",
                code = rawCode
            )
        }

        // 步骤 2：保存到文件
        val filePath = saveCodeFile(cleanedCode, PYTHON_DIR, "py")
        if (filePath == null) {
            return CodeGenResult.failure(
                error = "无法保存 Python 文件到缓存目录",
                code = cleanedCode
            )
        }

        Log.d(TAG, "Python 代码已保存到: $filePath")

        // 步骤 3：通过 TermuxBridge 执行
        val executionResult = withContext(Dispatchers.IO) {
            try {
                val result = termuxBridge.executePython(cleanedCode, EXECUTION_TIMEOUT_MS)
                formatShellResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Python 执行失败", e)
                "执行错误: ${e.message}"
            }
        }

        return CodeGenResult.success(
            filePath = filePath,
            code = cleanedCode,
            executionResult = executionResult
        )
    }

    // =========================================================================
    //  核心方法：生成 Android APK 项目
    // =========================================================================

    /**
     * 根据描述生成一个简单的 Android APK 项目。
     *
     * 使用内置模板创建一个完整的 Android 项目结构，包括：
     * - AndroidManifest.xml
     * - MainActivity.java
     * - build.gradle（Module + Project）
     * - settings.gradle
     * - Gradle wrapper 配置
     *
     * 如果 Termux 环境中安装了 aapt/dx/apksigner，会尝试直接编译 APK；
     * 否则创建一个可编译的 Gradle 项目。
     *
     * @param description APK 描述（应用的名称、功能等）
     * @return 生成结果，包含项目路径和详情
     */
    suspend fun generateAndroidApk(description: String): CodeGenResult {
        Log.d(TAG, "generateAndroidApk: description=$description")

        // 从描述中提取应用名称和包名
        val appName = extractAppName(description) ?: "MyApp"
        val packageName = "com.example.${appName.lowercase().replace(Regex("[^a-z0-9]"), "")}"
        val projectDir = File(codeGenDir, "$APK_DIR/${packageName.replace('.', '_')}_${timestampSuffix()}")
        val appDir = File(projectDir, "app/src/main/java/${packageName.replace('.', '/')}")

        return withContext(Dispatchers.IO) {
            try {
                // 创建项目目录结构
                projectDir.mkdirs()
                appDir.mkdirs()

                val resDir = File(projectDir, "app/src/main/res/values")
                resDir.mkdirs()

                val mipmapDir = File(projectDir, "app/src/main/res/mipmap-hdpi")
                mipmapDir.mkdirs()

                // 写入 AndroidManifest.xml
                val manifestFile = File(projectDir, "app/src/main/AndroidManifest.xml")
                manifestFile.writeText(ApkTemplate.resolve(ApkTemplate.ANDROID_MANIFEST_XML, packageName, appName))

                // 写入 MainActivity.java
                val activityFile = File(appDir, "MainActivity.java")
                activityFile.writeText(ApkTemplate.resolve(ApkTemplate.MAIN_ACTIVITY_JAVA, packageName, appName))

                // 写入 build.gradle (app module)
                val buildGradleApp = File(projectDir, "app/build.gradle")
                buildGradleApp.writeText(ApkTemplate.resolve(ApkTemplate.BUILD_GRADLE_APP, packageName, appName))

                // 写入 build.gradle (project)
                val buildGradleProject = File(projectDir, "build.gradle")
                buildGradleProject.writeText(ApkTemplate.BUILD_GRADLE_PROJECT)

                // 写入 settings.gradle
                val settingsGradle = File(projectDir, "settings.gradle")
                settingsGradle.writeText(ApkTemplate.resolve(ApkTemplate.SETTINGS_GRADLE, packageName, appName))

                // 写入 gradle.properties
                val gradleProperties = File(projectDir, "gradle.properties")
                gradleProperties.writeText(ApkTemplate.GRADLE_PROPERTIES)

                // 写入 gradle-wrapper.properties
                val gradleWrapperDir = File(projectDir, "gradle/wrapper")
                gradleWrapperDir.mkdirs()
                val wrapperProperties = File(gradleWrapperDir, "gradle-wrapper.properties")
                wrapperProperties.writeText(ApkTemplate.GRADLE_WRAPPER_PROPERTIES)

                // 写入 local.properties
                val localProperties = File(projectDir, "local.properties")
                localProperties.writeText(ApkTemplate.LOCAL_PROPERTIES)

                // 写入 proguard-rules.pro
                val proguardFile = File(projectDir, "app/proguard-rules.pro")
                proguardFile.writeText(ApkTemplate.PROGUARD_RULES)

                // 写入 res/values/strings.xml
                val stringsXml = File(resDir, "strings.xml")
                stringsXml.writeText(
                    """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">$appName</string>
</resources>"""
                )

                // 写入 res/values/themes.xml
                val themesXml = File(resDir, "themes.xml")
                themesXml.writeText(
                    """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.AppCompat.Light.NoActionBar" parent="Theme.AppCompat.Light.NoActionBar" />
</resources>"""
                )

                // 尝试使用 Termux 中的 aapt/dx/apksigner 直接编译 APK
                val apkResult = tryBuildApkDirect(projectDir, packageName, appName)

                val projectPath = projectDir.absolutePath
                val summary = buildString {
                    appendLine("Android 项目已生成: $projectPath")
                    appendLine("包名: $packageName")
                    appendLine("应用名: $appName")
                    appendLine()
                    appendLine("项目结构:")
                    appendLine("  $projectPath/")
                    appendLine("  ├── build.gradle")
                    appendLine("  ├── settings.gradle")
                    appendLine("  ├── gradle.properties")
                    appendLine("  ├── local.properties")
                    appendLine("  ├── gradle/wrapper/gradle-wrapper.properties")
                    appendLine("  └── app/")
                    appendLine("      ├── build.gradle")
                    appendLine("      ├── proguard-rules.pro")
                    appendLine("      └── src/main/")
                    appendLine("          ├── AndroidManifest.xml")
                    appendLine("          └── java/$packageName/")
                    appendLine("              └── MainActivity.java")
                    if (apkResult != null) {
                        appendLine()
                        appendLine("直接编译结果: ${if (apkResult.isSuccess) "成功" else "失败"}")
                        appendLine(apkResult.stdout.ifBlank { apkResult.stderr })
                    } else {
                        appendLine()
                        appendLine("提示: 如需直接编译 APK，请确保 Termux 中已安装:")
                        appendLine("  pkg install aapt dx apksigner")
                    }
                }

                CodeGenResult.success(
                    filePath = projectPath,
                    code = summary
                )
            } catch (e: Exception) {
                Log.e(TAG, "APK 项目生成失败", e)
                CodeGenResult.failure(error = "APK 项目生成失败: ${e.message}")
            }
        }
    }

    // =========================================================================
    //  核心方法：生成 Shell 脚本
    // =========================================================================

    /**
     * 根据任务描述生成 Shell 脚本并保存。
     *
     * @param task 任务描述
     * @return 生成结果，包含文件路径和代码
     */
    suspend fun generateShellScript(task: String): CodeGenResult {
        Log.d(TAG, "generateShellScript: task=$task")

        val rawCode = callLLM(SYSTEM_PROMPT_SHELL, task)
        if (rawCode == null) {
            return CodeGenResult.failure(
                error = "LLM 生成 Shell 脚本失败：API 调用无返回或未配置",
                code = ""
            )
        }

        val cleanedCode = cleanGeneratedCode(rawCode, "shell")
        if (cleanedCode.isBlank()) {
            return CodeGenResult.failure(error = "LLM 返回空代码", code = rawCode)
        }

        // 确保脚本以 shebang 开头
        val finalCode = if (!cleanedCode.startsWith("#!")) {
            "#!/system/bin/sh\n$cleanedCode"
        } else {
            cleanedCode
        }

        val filePath = saveCodeFile(finalCode, SHELL_DIR, "sh")
        if (filePath == null) {
            return CodeGenResult.failure(error = "无法保存 Shell 文件", code = finalCode)
        }

        // 赋予可执行权限
        withContext(Dispatchers.IO) {
            try {
                File(filePath).setExecutable(true)
            } catch (e: Exception) {
                Log.w(TAG, "设置可执行权限失败: $filePath", e)
            }
        }

        // 执行脚本
        val executionResult = withContext(Dispatchers.IO) {
            try {
                val result = termuxBridge.executeCommand("sh \"$filePath\"", EXECUTION_TIMEOUT_MS)
                formatShellResult(result)
            } catch (e: Exception) {
                "执行错误: ${e.message}"
            }
        }

        return CodeGenResult.success(
            filePath = filePath,
            code = finalCode,
            executionResult = executionResult
        )
    }

    // =========================================================================
    //  核心方法：生成 Kotlin 类
    // =========================================================================

    /**
     * 根据描述生成 Kotlin 类代码并保存。
     *
     * @param description 类描述，包括类名、功能、属性等
     * @return 生成结果，包含文件路径和代码
     */
    suspend fun generateKotlinClass(description: String): CodeGenResult {
        Log.d(TAG, "generateKotlinClass: description=$description")

        val rawCode = callLLM(SYSTEM_PROMPT_KOTLIN, description)
        if (rawCode == null) {
            return CodeGenResult.failure(
                error = "LLM 生成 Kotlin 代码失败：API 调用无返回或未配置",
                code = ""
            )
        }

        val cleanedCode = cleanGeneratedCode(rawCode, "kotlin")
        if (cleanedCode.isBlank()) {
            return CodeGenResult.failure(error = "LLM 返回空代码", code = rawCode)
        }

        // 从代码中提取类名作为文件名
        val className = extractKotlinClassName(cleanedCode) ?: "GeneratedClass"
        val filePath = saveCodeFile(cleanedCode, KOTLIN_DIR, "kt", className)

        if (filePath == null) {
            return CodeGenResult.failure(error = "无法保存 Kotlin 文件", code = cleanedCode)
        }

        Log.d(TAG, "Kotlin 代码已保存到: $filePath")
        return CodeGenResult.success(filePath = filePath, code = cleanedCode)
    }

    // =========================================================================
    //  核心方法：生成 Java 类
    // =========================================================================

    /**
     * 根据描述生成 Java 类代码并保存。
     *
     * @param description 类描述，包括类名、功能、属性、方法等
     * @return 生成结果，包含文件路径和代码
     */
    suspend fun generateJavaClass(description: String): CodeGenResult {
        Log.d(TAG, "generateJavaClass: description=$description")

        val rawCode = callLLM(SYSTEM_PROMPT_JAVA, description)
        if (rawCode == null) {
            return CodeGenResult.failure(
                error = "LLM 生成 Java 代码失败：API 调用无返回或未配置",
                code = ""
            )
        }

        val cleanedCode = cleanGeneratedCode(rawCode, "java")
        if (cleanedCode.isBlank()) {
            return CodeGenResult.failure(error = "LLM 返回空代码", code = rawCode)
        }

        // 从代码中提取类名作为文件名
        val className = extractJavaClassName(cleanedCode) ?: "GeneratedClass"
        val filePath = saveCodeFile(cleanedCode, JAVA_DIR, "java", className)

        if (filePath == null) {
            return CodeGenResult.failure(error = "无法保存 Java 文件", code = cleanedCode)
        }

        Log.d(TAG, "Java 代码已保存到: $filePath")
        return CodeGenResult.success(filePath = filePath, code = cleanedCode)
    }

    // =========================================================================
    //  核心方法：执行已生成的代码
    // =========================================================================

    /**
     * 执行一个已生成的代码文件。
     *
     * 根据文件扩展名自动选择执行方式：
     * - .py   -> 通过 TermuxBridge.executePython() 执行
     * - .sh   -> 通过 TermuxBridge.executeCommand() 执行
     * - .java -> 通过 TermuxBridge.compileAndRunJava() 编译并执行
     * - .kt   -> 提示 Kotlin 执行需要 Gradle 或 Kotlin 编译器
     *
     * @param filePath 要执行的代码文件绝对路径
     * @return 执行结果
     */
    suspend fun executeGeneratedCode(filePath: String): CodeGenResult {
        Log.d(TAG, "executeGeneratedCode: filePath=$filePath")

        val file = File(filePath)
        if (!file.exists()) {
            return CodeGenResult.failure(error = "文件不存在: $filePath")
        }
        if (!file.isFile) {
            return CodeGenResult.failure(error = "路径不是文件: $filePath")
        }

        val extension = file.extension.lowercase()
        val code = withContext(Dispatchers.IO) {
            try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                return@withContext null as String?
            }
        } ?: return CodeGenResult.failure(error = "无法读取文件: $filePath")

        val executionResult = when (extension) {
            "py" -> executePythonFile(file)
            "sh" -> executeShellFile(file)
            "java" -> executeJavaFile(file)
            "kt", "kts" -> {
                // Kotlin 执行需要 Kotlin 编译器或 Gradle
                try {
                    // 尝试通过 Termux 中的 kotlinc 或 kotlin 命令执行
                    val result = termuxBridge.executeCommand(
                        "kotlin \"$filePath\" 2>&1",
                        EXECUTION_TIMEOUT_MS
                    )
                    formatShellResult(result)
                } catch (e: Exception) {
                    "Kotlin 执行需要 Kotlin 编译器。\n" +
                        "请确保 Termux 中已安装 kotlin: pkg install kotlin\n" +
                        "或使用 Android Studio 打开项目编译。\n" +
                        "错误: ${e.message}"
                }
            }
            else -> {
                // 尝试作为 Shell 脚本执行
                try {
                    val result = termuxBridge.executeCommand(
                        "sh \"$filePath\" 2>&1",
                        EXECUTION_TIMEOUT_MS
                    )
                    formatShellResult(result)
                } catch (e: Exception) {
                    "不支持的文件类型: .$extension，无法执行"
                }
            }
        }

        return CodeGenResult.success(
            filePath = filePath,
            code = code,
            executionResult = executionResult
        )
    }

    // =========================================================================
    //  核心方法：基于模板创建项目
    // =========================================================================

    /**
     * 基于模板创建项目骨架。
     *
     * 支持的模板类型：
     * - "python" / "py"        : Python 项目模板
     * - "shell" / "sh"         : Shell 脚本模板
     * - "kotlin" / "kt"        : Kotlin 项目模板
     * - "java"                 : Java 项目模板
     * - "android" / "apk"      : Android APK 项目模板（同 [generateAndroidApk]）
     * - "web"                  : 简单的 HTML/CSS/JS 前端项目模板
     * - "python_script"        : 单个 Python 文件模板
     *
     * @param template 模板名称
     * @param params   模板参数（如项目名称、包名等）
     * @return 生成结果，包含项目路径和详情
     */
    suspend fun createProjectFromTemplate(
        template: String,
        params: Map<String, String>
    ): CodeGenResult {
        Log.d(TAG, "createProjectFromTemplate: template=$template, params=$params")

        val projectName = params["projectName"] ?: params["name"] ?: "GeneratedProject"
        val packageName = params["packageName"] ?: params["package"] ?: "com.example.${projectName.lowercase().replace(Regex("[^a-z0-9]"), "")}"
        val author = params["author"] ?: "MobileClaw"

        return when (template.lowercase()) {
            "python", "py" -> createPythonProject(projectName, author)
            "shell", "sh" -> createShellProject(projectName, author)
            "kotlin", "kt" -> createKotlinProject(projectName, packageName, author)
            "java" -> createJavaProject(projectName, packageName, author)
            "android", "apk" -> {
                // 委托给 generateAndroidApk
                val description = params["description"] ?: "A simple Android app named $projectName"
                generateAndroidApk(description)
            }
            "web" -> createWebProject(projectName, author)
            "python_script" -> {
                // 生成单个 Python 文件
                val task = params["task"] ?: "Create a Python script that prints 'Hello from $projectName'"
                generatePython(task)
            }
            else -> {
                // 尝试使用 LLM 生成自定义模板
                val prompt = buildString {
                    appendLine("请根据以下模板名称和参数生成项目骨架代码。")
                    appendLine("模板: $template")
                    appendLine("项目名: $projectName")
                    appendLine("包名: $packageName")
                    params.forEach { (key, value) ->
                        appendLine("$key: $value")
                    }
                    appendLine()
                    appendLine("请输出项目的主要文件内容，每个文件用文件名作为标题。")
                }
                val rawCode = callLLM(SYSTEM_PROMPT_GENERAL, prompt)
                if (rawCode != null) {
                    val cleanedCode = cleanGeneratedCode(rawCode, "text")
                    val projectDir = File(codeGenDir, "custom_${template}_${timestampSuffix()}")
                    withContext(Dispatchers.IO) {
                        projectDir.mkdirs()
                        val readmeFile = File(projectDir, "output.txt")
                        readmeFile.writeText(cleanedCode)
                    }
                    CodeGenResult.success(
                        filePath = projectDir.absolutePath,
                        code = cleanedCode
                    )
                } else {
                    CodeGenResult.failure(error = "未知模板: $template，且 LLM 不可用")
                }
            }
        }
    }

    // =========================================================================
    //  内部：LLM 调用
    // =========================================================================

    /**
     * 调用 LLM 生成代码。
     *
     * 优先使用 AIGateway 的配置进行 API 调用。如果 AIGateway 未配置或不可用，
     * 返回 null，由调用方使用回退策略。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return LLM 返回的文本，失败时返回 null
     */
    private suspend fun callLLM(systemPrompt: String, userPrompt: String): String? {
        // 尝试使用 AIGateway
        if (gateway != null && gateway.isConfigured()) {
            return callLLMViaGateway(systemPrompt, userPrompt)
        }

        // AIGateway 不可用，返回 null
        Log.w(TAG, "AIGateway 未配置，无法调用 LLM")
        return null
    }

    /**
     * 通过 AIGateway 的配置调用 LLM API。
     *
     * 使用 OkHttp 直接调用 OpenAI 兼容的 Chat Completions API。
     * 复用 AIGateway 的 API 配置（apiKey, baseUrl, model）。
     */
    private suspend fun callLLMViaGateway(systemPrompt: String, userPrompt: String): String? {
        val config = gateway!!.currentConfig()
        if (config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) {
            Log.w(TAG, "AIGateway 配置不完整")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = userPrompt)
                )

                val requestBody = ChatCompletionRequest(
                    model = config.model,
                    messages = messages,
                    stream = false,
                    temperature = 0.3,
                    maxTokens = 4096
                )
                val bodyStr = json.encodeToString(requestBody)
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = bodyStr.toRequestBody(mediaType)

                // 构建 URL
                val base = config.baseUrl.trimEnd('/')
                val url = when {
                    base.endsWith("/chat/completions") -> base
                    Regex("""/v\d+$""").containsMatchIn(base) -> "$base/chat/completions"
                    else -> "$base/v1/chat/completions"
                }

                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .post(body)
                    .build()

                val response = llmClient.newCall(request).execute()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errorBody = resp.body?.string()?.take(500) ?: ""
                        Log.e(TAG, "LLM API 请求失败: HTTP ${resp.code} $errorBody")
                        return@withContext null
                    }
                    val raw = resp.body?.string().orEmpty()
                    val completionResp = json.decodeFromString(
                        ChatCompletionResponse.serializer(), raw
                    )
                    val content = completionResp.choices.firstOrNull()?.message?.content
                    if (content.isNullOrBlank()) {
                        Log.w(TAG, "LLM 返回空内容")
                        return@withContext null
                    }
                    content
                }
            } catch (e: Exception) {
                Log.e(TAG, "LLM API 调用异常", e)
                null
            }
        }
    }

    // =========================================================================
    //  内部：代码清理与提取
    // =========================================================================

    /**
     * 清理 LLM 生成的代码，移除 markdown 代码块标记等。
     *
     * @param rawCode   LLM 原始返回的代码
     * @param language  代码语言（用于识别 markdown 代码块标记）
     * @return 清理后的纯代码文本
     */
    private fun cleanGeneratedCode(rawCode: String, language: String): String {
        var code = rawCode.trim()

        // 移除 ```language ... ``` 包裹
        val codeBlockRegex = Regex("""```(?:\w+)?\s*\n?(.*?)\n?```""", RegexOption.DOT_MATCHES_ALL)
        val match = codeBlockRegex.find(code)
        if (match != null) {
            code = match.groupValues[1].trim()
        }

        // 如果代码被 ` 包裹（单行），移除 `
        if (code.startsWith("`") && code.endsWith("`")) {
            code = code.removeSurrounding("`").trim()
        }

        // 移除行首行尾的空白行
        code = code.trim()

        // 如果代码行数少于 3 行且包含 "Here is" 等说明性文字，尝试提取代码块
        if (code.lines().size <= 3 && (code.contains("Here is", ignoreCase = true) ||
                code.contains("以下", ignoreCase = true) ||
                code.contains("这是", ignoreCase = true))) {
            // 重新尝试提取任何代码块
            val anyCodeBlock = Regex("""```.*?\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
            val anyMatch = anyCodeBlock.find(rawCode)
            if (anyMatch != null) {
                code = anyMatch.groupValues[1].trim()
            }
        }

        return code
    }

    /**
     * 从 Kotlin 代码中提取类名/函数名。
     *
     * @param code Kotlin 代码
     * @return 类名或函数名，无法提取时返回 null
     */
    private fun extractKotlinClassName(code: String): String? {
        // 匹配 class, object, data class, sealed class, fun 等顶层声明
        val classRegex = Regex("""(?:class|object|data class|sealed class|abstract class|open class)\s+(\w+)""")
        val match = classRegex.find(code)
        if (match != null) return match.groupValues[1]

        // 匹配顶层函数
        val funRegex = Regex("""^fun\s+(\w+)""", RegexOption.MULTILINE)
        val funMatch = funRegex.find(code)
        return funMatch?.groupValues?.getOrNull(1)
    }

    /**
     * 从 Java 代码中提取类名。
     *
     * @param code Java 代码
     * @return 类名，无法提取时返回 null
     */
    private fun extractJavaClassName(code: String): String? {
        val regex = Regex("""(?:public\s+)?(?:abstract\s+|final\s+)?class\s+(\w+)""")
        return regex.find(code)?.groupValues?.getOrNull(1)
    }

    /**
     * 从 APK 描述中提取应用名称。
     *
     * @param description 应用描述
     * @return 提取的应用名，无法提取时返回 null
     */
    private fun extractAppName(description: String): String? {
        // 尝试匹配 "名为XXX" 或 "叫XXX" 等
        val nameRegex = Regex("""(?:名为|叫|叫做|名称[是为]?)\s*[「「【]?(.+?)[」」】]?(?:\s*(?:的|的应用|的APP|的App|项目))?""")
        val match = nameRegex.find(description)
        if (match != null) {
            val name = match.groupValues[1].trim()
            if (name.isNotBlank() && name.length <= 50) {
                return name.replace(Regex("""[\\/:*?"<>|]"""), "_")
            }
        }

        // 尝试取第一个有意义的词汇
        val words = description.trim().split(Regex("[\\s,，。]+"))
        for (word in words) {
            if (word.length in 2..20 && !word.contains("应用") && !word.contains("app") &&
                !word.contains("生成") && !word.contains("创建") && !word.contains("一个")
            ) {
                return word.replace(Regex("""[\\/:*?"<>|]"""), "_")
            }
        }

        return null
    }

    // =========================================================================
    //  内部：文件操作
    // =========================================================================

    /**
     * 将代码内容保存到文件。
     *
     * @param code      代码内容
     * @param subDir    子目录名称（如 "python", "shell"）
     * @param extension 文件扩展名（如 "py", "sh"）
     * @param fileName  文件名（不含扩展名），默认使用 UUID
     * @return 保存后的文件绝对路径，失败时返回 null
     */
    private fun saveCodeFile(
        code: String,
        subDir: String,
        extension: String,
        fileName: String? = null
    ): String? {
        return try {
            val dir = File(codeGenDir, subDir)
            dir.mkdirs()

            val name = fileName ?: "gen_${UUID.randomUUID().toString().take(8)}"
            val file = File(dir, "$name.$extension")
            file.writeText(code, Charsets.UTF_8)
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "保存代码文件失败", e)
            null
        }
    }

    /**
     * 生成时间戳后缀，用于项目目录命名。
     */
    private fun timestampSuffix(): String {
        val now = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        return now.format(java.util.Date())
    }

    // =========================================================================
    //  内部：代码执行
    // =========================================================================

    /**
     * 执行 Python 文件。
     */
    private suspend fun executePythonFile(file: File): String {
        return try {
            val code = file.readText(Charsets.UTF_8)
            val result = termuxBridge.executePython(code, EXECUTION_TIMEOUT_MS)
            formatShellResult(result)
        } catch (e: Exception) {
            "执行错误: ${e.message}"
        }
    }

    /**
     * 执行 Shell 文件。
     */
    private suspend fun executeShellFile(file: File): String {
        return try {
            val result = termuxBridge.executeCommand(
                "sh \"${file.absolutePath}\" 2>&1",
                EXECUTION_TIMEOUT_MS
            )
            formatShellResult(result)
        } catch (e: Exception) {
            "执行错误: ${e.message}"
        }
    }

    /**
     * 编译并执行 Java 文件。
     */
    private suspend fun executeJavaFile(file: File): String {
        return try {
            val code = file.readText(Charsets.UTF_8)
            val result = termuxBridge.compileAndRunJava(code)
            formatShellResult(result)
        } catch (e: Exception) {
            "执行错误: ${e.message}"
        }
    }

    // =========================================================================
    //  内部：APK 直接编译
    // =========================================================================

    /**
     * 尝试使用 Termux 中的 Android 构建工具直接编译 APK。
     *
     * 需要以下工具（可通过 pkg install 安装）：
     * - aapt（Android Asset Packaging Tool）
     * - dx（Dalvik Executable converter）
     * - apksigner（APK 签名工具）
     *
     * @param projectDir  项目目录
     * @param packageName 包名
     * @param appName     应用名
     * @return 编译结果，如果工具不可用则返回 null
     */
    private suspend fun tryBuildApkDirect(
        projectDir: File,
        packageName: String,
        appName: String
    ): ShellResult? {
        return withContext(Dispatchers.IO) {
            try {
                // 检查必要工具
                if (!termuxBridge.checkCommandAvailability("aapt")) {
                    Log.d(TAG, "aapt 不可用，跳过直接 APK 编译")
                    return@withContext null
                }

                val buildDir = File(projectDir, "build")
                buildDir.mkdirs()

                val genDir = File(buildDir, "gen")
                genDir.mkdirs()

                val dexDir = File(buildDir, "dex")
                dexDir.mkdirs()

                val apkDir = File(buildDir, "apk")
                apkDir.mkdirs()

                val manifestPath = File(projectDir, "app/src/main/AndroidManifest.xml").absolutePath
                val javaSourceDir = File(projectDir, "app/src/main/java/${packageName.replace('.', '/')}").absolutePath

                // 步骤 1: 使用 aapt 编译资源
                Log.d(TAG, "步骤 1: aapt 编译资源")
                val aaptPackageCmd = buildString {
                    append("aapt package -f -m ")
                    append("-J \"${genDir.absolutePath}\" ")
                    append("-M \"$manifestPath\" ")
                    append("-S \"${projectDir.absolutePath}/app/src/main/res\" ")
                    append("-I /system/framework/android.jar ")
                    append("2>&1")
                }
                val aaptResult = termuxBridge.executeCommand(aaptPackageCmd, 60_000)
                if (!aaptResult.isSuccess) {
                    Log.w(TAG, "aapt package 失败: ${aaptResult.stderr}")
                    return@withContext aaptResult
                }

                // 步骤 2: 编译 Java 源文件
                Log.d(TAG, "步骤 2: 编译 Java 源文件")
                // 使用 ecj 或 javac 编译
                val javacPath = if (termuxBridge.checkCommandAvailability("javac")) "javac" else "ecj"
                val compileCmd = "$javacPath -d \"${buildDir.absolutePath}/classes\" " +
                    "-classpath /system/framework/android.jar " +
                    "\"$javaSourceDir/MainActivity.java\" 2>&1"
                val compileResult = termuxBridge.executeCommand(compileCmd, 120_000)
                if (!compileResult.isSuccess) {
                    Log.w(TAG, "Java 编译失败: ${compileResult.stderr}")
                    return@withContext compileResult
                }

                // 步骤 3: 使用 dx 将 .class 转为 .dex
                Log.d(TAG, "步骤 3: dx 转 dex")
                if (termuxBridge.checkCommandAvailability("dx")) {
                    val dxCmd = "dx --dex --output=\"${dexDir.absolutePath}/classes.dex\" " +
                        "\"${buildDir.absolutePath}/classes\" 2>&1"
                    val dxResult = termuxBridge.executeCommand(dxCmd, 120_000)
                    if (!dxResult.isSuccess) {
                        Log.w(TAG, "dx 转换失败: ${dxResult.stderr}")
                        return@withContext dxResult
                    }
                }

                // 步骤 4: 使用 aapt 打包 APK
                Log.d(TAG, "步骤 4: aapt 打包 APK")
                val apkUnsignedPath = "${apkDir.absolutePath}/app-unsigned.apk"
                val aaptPackCmd = "aapt package -f " +
                    "-M \"$manifestPath\" " +
                    "-S \"${projectDir.absolutePath}/app/src/main/res\" " +
                    "-I /system/framework/android.jar " +
                    "-F \"$apkUnsignedPath\" " +
                    "${dexDir.absolutePath} 2>&1"
                val aaptPackResult = termuxBridge.executeCommand(aaptPackCmd, 60_000)
                if (!aaptPackResult.isSuccess) {
                    Log.w(TAG, "aapt 打包失败: ${aaptPackResult.stderr}")
                    return@withContext aaptPackResult
                }

                // 步骤 5: 如果 apksigner 可用，签名 APK
                if (termuxBridge.checkCommandAvailability("apksigner")) {
                    Log.d(TAG, "步骤 5: apksigner 签名 APK")
                    val apkSignedPath = "${apkDir.absolutePath}/app-signed.apk"
                    // 生成 debug 密钥库（如果不存在）
                    val keystorePath = "${buildDir.absolutePath}/debug.keystore"
                    if (!File(keystorePath).exists()) {
                        val keytoolCmd = "keytool -genkey -v -keystore \"$keystorePath\" " +
                            "-alias debug -keyalg RSA -keysize 2048 -validity 10000 " +
                            "-storepass android -keypass android -dname \"CN=MobileClaw, OU=MobileClaw, O=MobileClaw, L=Unknown, ST=Unknown, C=US\" 2>&1"
                        termuxBridge.executeCommand(keytoolCmd, 30_000)
                    }
                    val signCmd = "apksigner sign --ks \"$keystorePath\" --ks-pass pass:android " +
                        "--ks-key-alias debug --out \"$apkSignedPath\" \"$apkUnsignedPath\" 2>&1"
                    val signResult = termuxBridge.executeCommand(signCmd, 60_000)
                    if (signResult.isSuccess) {
                        Log.d(TAG, "APK 签名成功: $apkSignedPath")
                    } else {
                        Log.w(TAG, "APK 签名失败: ${signResult.stderr}")
                    }
                }

                Log.d(TAG, "APK 直接编译流程完成")
                ShellResult(
                    exitCode = 0,
                    stdout = "APK 已生成到: ${apkDir.absolutePath}"
                )
            } catch (e: Exception) {
                Log.e(TAG, "APK 直接编译异常", e)
                ShellResult(
                    exitCode = -1,
                    stderr = "APK 直接编译异常: ${e.message}"
                )
            }
        }
    }

    // =========================================================================
    //  内部：模板项目创建
    // =========================================================================

    /**
     * 创建 Python 项目模板。
     */
    private suspend fun createPythonProject(
        projectName: String,
        author: String
    ): CodeGenResult = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(codeGenDir, "python_${projectName}_${timestampSuffix()}")
            projectDir.mkdirs()

            // 创建 main.py
            val mainPy = File(projectDir, "main.py")
            mainPy.writeText(
                """#!/usr/bin/env python3
"""
                        .trimIndent() + """
# ${projectName}
# Author: ${author}
# Generated by MobileClaw CodeGenerator

import sys


def main():
    print("Hello from ${projectName}!")
    print("Python version:", sys.version)


if __name__ == "__main__":
    main()
"""
            )

            // 创建 requirements.txt
            val requirements = File(projectDir, "requirements.txt")
            requirements.writeText("# ${projectName}\n# Add your dependencies here\n")

            // 创建 README.md
            val readme = File(projectDir, "README.md")
            readme.writeText(
                """# ${projectName}

Generated by MobileClaw CodeGenerator.

## Usage

python main.py

## Dependencies

pip install -r requirements.txt
"""
            )

            val summary = buildString {
                appendLine("Python 项目已创建: ${projectDir.absolutePath}")
                appendLine("  ├── main.py")
                appendLine("  ├── requirements.txt")
                appendLine("  └── README.md")
            }

            CodeGenResult.success(
                filePath = projectDir.absolutePath,
                code = summary
            )
        } catch (e: Exception) {
            CodeGenResult.failure(error = "创建 Python 项目失败: ${e.message}")
        }
    }

    /**
     * 创建 Shell 项目模板。
     */
    private suspend fun createShellProject(
        projectName: String,
        author: String
    ): CodeGenResult = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(codeGenDir, "shell_${projectName}_${timestampSuffix()}")
            projectDir.mkdirs()

            val mainSh = File(projectDir, "main.sh")
            mainSh.writeText(
                """#!/system/bin/sh
#
# ${projectName}
# Author: ${author}
# Generated by MobileClaw CodeGenerator
#

echo "Hello from ${projectName}!"
echo "Current directory: $(pwd)"
echo "Shell: ${'$'}SHELL"
"""
            )
            mainSh.setExecutable(true)

            val summary = buildString {
                appendLine("Shell 项目已创建: ${projectDir.absolutePath}")
                appendLine("  └── main.sh")
            }

            CodeGenResult.success(
                filePath = projectDir.absolutePath,
                code = summary
            )
        } catch (e: Exception) {
            CodeGenResult.failure(error = "创建 Shell 项目失败: ${e.message}")
        }
    }

    /**
     * 创建 Kotlin 项目模板。
     */
    private suspend fun createKotlinProject(
        projectName: String,
        packageName: String,
        author: String
    ): CodeGenResult = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(codeGenDir, "kotlin_${projectName}_${timestampSuffix()}")
            projectDir.mkdirs()

            // 创建 src 目录
            val srcDir = File(projectDir, "src/main/kotlin/${packageName.replace('.', '/')}")
            srcDir.mkdirs()

            val mainKt = File(srcDir, "Main.kt")
            mainKt.writeText(
                """package ${packageName}

/**
 * ${projectName}
 * Author: ${author}
 * Generated by MobileClaw CodeGenerator
 */
fun main() {
    println("Hello from ${projectName}!")
}
"""
            )

            val summary = buildString {
                appendLine("Kotlin 项目已创建: ${projectDir.absolutePath}")
                appendLine("  └── src/main/kotlin/${packageName.replace('.', '/')}/Main.kt")
            }

            CodeGenResult.success(
                filePath = projectDir.absolutePath,
                code = summary
            )
        } catch (e: Exception) {
            CodeGenResult.failure(error = "创建 Kotlin 项目失败: ${e.message}")
        }
    }

    /**
     * 创建 Java 项目模板。
     */
    private suspend fun createJavaProject(
        projectName: String,
        packageName: String,
        author: String
    ): CodeGenResult = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(codeGenDir, "java_${projectName}_${timestampSuffix()}")
            projectDir.mkdirs()

            val srcDir = File(projectDir, "src/${packageName.replace('.', '/')}")
            srcDir.mkdirs()

            val mainJava = File(srcDir, "Main.java")
            mainJava.writeText(
                """package ${packageName};

/**
 * ${projectName}
 * Author: ${author}
 * Generated by MobileClaw CodeGenerator
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("Hello from ${projectName}!");
    }
}
"""
            )

            val summary = buildString {
                appendLine("Java 项目已创建: ${projectDir.absolutePath}")
                appendLine("  └── src/${packageName.replace('.', '/')}/Main.java")
            }

            CodeGenResult.success(
                filePath = projectDir.absolutePath,
                code = summary
            )
        } catch (e: Exception) {
            CodeGenResult.failure(error = "创建 Java 项目失败: ${e.message}")
        }
    }

    /**
     * 创建 Web 前端项目模板（HTML/CSS/JS）。
     */
    private suspend fun createWebProject(
        projectName: String,
        author: String
    ): CodeGenResult = withContext(Dispatchers.IO) {
        try {
            val projectDir = File(codeGenDir, "web_${projectName}_${timestampSuffix()}")
            projectDir.mkdirs()

            // index.html
            val indexHtml = File(projectDir, "index.html")
            indexHtml.writeText(
                """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${projectName}</title>
    <link rel="stylesheet" href="style.css">
</head>
<body>
    <div class="container">
        <h1>${projectName}</h1>
        <p>Generated by MobileClaw CodeGenerator</p>
        <p id="demo"></p>
    </div>
    <script src="script.js"></script>
</body>
</html>
"""
            )

            // style.css
            val styleCss = File(projectDir, "style.css")
            styleCss.writeText(
                """* {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
}

body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
}

.container {
    background: white;
    border-radius: 16px;
    padding: 40px;
    box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
    text-align: center;
    max-width: 480px;
    width: 90%;
}

h1 {
    color: #333;
    margin-bottom: 16px;
    font-size: 28px;
}

p {
    color: #666;
    margin-bottom: 12px;
    line-height: 1.6;
}
"""
            )

            // script.js
            val scriptJs = File(projectDir, "script.js")
            scriptJs.writeText(
                """/**
 * ${projectName}
 * Author: ${author}
 * Generated by MobileClaw CodeGenerator
 */
document.addEventListener('DOMContentLoaded', () => {
    const demo = document.getElementById('demo');
    if (demo) {
        demo.textContent = 'Hello from ${projectName}!';
        demo.style.color = '#667eea';
        demo.style.fontWeight = 'bold';
    }
});
"""
            )

            val summary = buildString {
                appendLine("Web 项目已创建: ${projectDir.absolutePath}")
                appendLine("  ├── index.html")
                appendLine("  ├── style.css")
                appendLine("  └── script.js")
            }

            CodeGenResult.success(
                filePath = projectDir.absolutePath,
                code = summary
            )
        } catch (e: Exception) {
            CodeGenResult.failure(error = "创建 Web 项目失败: ${e.message}")
        }
    }

    // =========================================================================
    //  内部：工具方法
    // =========================================================================

    /**
     * 将 [ShellResult] 格式化为可读字符串。
     */
    private fun formatShellResult(result: ShellResult): String {
        return buildString {
            if (result.isSuccess) {
                append("执行成功 (exitCode=0)")
            } else {
                append("执行失败 (exitCode=${result.exitCode})")
            }
            if (result.stdout.isNotBlank()) {
                appendLine()
                append("输出:")
                appendLine()
                append(result.stdout.trimEnd())
            }
            if (result.stderr.isNotBlank()) {
                appendLine()
                append("错误:")
                appendLine()
                append(result.stderr.trimEnd())
            }
        }
    }

    /**
     * 获取代码生成目录路径。
     */
    fun getCodeGenDirectory(): String = codeGenDir.absolutePath

    /**
     * 获取各类型代码的统计信息。
     *
     * @return 包含各类型代码文件数量和磁盘占用（字节）的 Map
     */
    fun getCodeStatistics(): Map<String, Pair<Int, Long>> {
        val stats = mutableMapOf<String, Pair<Int, Long>>()
        val subDirs = listOf(PYTHON_DIR, SHELL_DIR, KOTLIN_DIR, JAVA_DIR, APK_DIR)

        for (subDir in subDirs) {
            val dir = File(codeGenDir, subDir)
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
                val fileCount = files.size
                val totalSize = files.sumOf { it.length() }
                stats[subDir] = Pair(fileCount, totalSize)
            }
        }

        return stats
    }

    /**
     * 清空所有生成的代码文件。
     *
     * @return 删除的文件数量
     */
    fun clearGeneratedCode(): Int {
        var deletedCount = 0
        val subDirs = listOf(PYTHON_DIR, SHELL_DIR, KOTLIN_DIR, JAVA_DIR, APK_DIR)

        for (subDir in subDirs) {
            val dir = File(codeGenDir, subDir)
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles() ?: continue
                for (file in files) {
                    if (file.isFile && file.delete()) {
                        deletedCount++
                    }
                }
            }
        }

        return deletedCount
    }
}