# MobileClaw (灵爪) — AI 手机操控助手

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B-brightgreen)](https://developer.android.com/about/versions/11)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-purple)](https://kotlinlang.org)

**MobileClaw（灵爪）** 是一款基于 Android 无障碍服务的 AI 手机操控助手。你只需用自然语言说出指令，它就能帮你完成点击、滑动、输入、截屏等操作，还能执行 Shell 命令、运行 Python 代码、生成并安装 APK。

## 功能

### 核心能力

- **自然语言操控** — 说「打开微信」就打开微信，说「截个屏」就截屏
- **多步任务** — 「打开抖音并搜索猫咪」「先打开微信再打开支付宝」
- **智能意图推断** — 「帮我查天气」「打电话给张三」「导航到天安门」
- **定时命令** — 「5分钟后打开支付宝」「半小时后打开微信」
- **序列命令** — 「先打开A再打开B」「依次打开A、B、C」
- **300+ 应用别名** — 绿泡泡、小而美、狗东、拼夕夕、Insta、TG、奈飞
- **拼音首字母匹配** — wx→微信、zfb→支付宝、dy→抖音
- **同音纠错** — 为信→微信、抖印→抖音、支负宝→支付宝

### AI Agent 引擎

- **ReAct 推理循环** — 自动分析任务、调用工具、观察结果、调整策略
- **15+ 内置工具** — Shell 命令、Python 执行、文件操作、应用管理、屏幕操控
- **代码生成** — 说「写个 Python 爬虫」直接生成并执行
- **APK 项目生成** — 说「创建一个计算器 APP」自动生成完整 Android 项目
- **智能上下文感知** — 打开豆包后说「给他发你好」自动识别
- **智能纠错学习** — 你说「不是A是B」，下次自动纠正
- **任务分解器** — 复杂任务自动拆分为子任务

### 关于 & 命令历史

- **「关于」对话框** — 集成版本信息、功能陈列、更新日志、一键跳转 GitHub
- **命令历史记录** — 自动保存最近 50 条指令，点击即可快速复用
- **更新日志查看** — 内置完整版本变更记录，追忆每个版本的新功能

### 检查更新 & 赞助

- **自动检查更新** — 启动时自动检测 GitHub 新版本，支持更新日志查看
- **应用内下载** — 检测到新版本可直接在应用内下载并安装 APK
- **通知栏下载进度** — 大文件更新时显示通知栏实时下载进度
- **赞助开发者** — 支持微信支付和支付宝两种方式，在应用内扫码即可赞助

### 权限与安全

- **一键快捷配置** — 有 Shizuku/STELLAR 即可一键开启无障碍服务，无需手动操作
- **逐个设置** — 列出所有权限，点击跳转到对应系统设置页，手动开关
- **分级权限策略** — 核心功能仅需无障碍服务，高级功能可选 Shizuku/STELLAR
- **权限缓存优化** — 避免频繁检测系统设置导致卡顿

## 快速开始

### 前置条件

- Android 11 (API 29) 及以上设备
- 开启**无障碍服务**（核心必需）

### 安装

1. 下载 [最新 APK](https://github.com/19923421354/MobileClaw/releases/latest)
2. 安装到设备
3. 打开应用，点击「⚡ 一键快捷配置」开启无障碍服务

### 配置 AI 模型

1. 点击右上角「设置」按钮
2. 输入 API Key 和 Base URL（支持所有 OpenAI 兼容接口）
3. 默认使用**智谱 GLM-4.7-Flash**（免费模型）

### 使用示例

| 指令 | 效果 |
|------|------|
| 打开微信 | 自动打开微信应用 |
| 打开抖音并搜索猫咪 | 打开抖音，在搜索框输入「猫咪」 |
| 截个屏 | 截取当前屏幕 |
| 发消息给张三说我晚点到 | 打开微信，找到张三，发送消息 |
| 打电话给张三 | 打开电话应用，拨打张三 |
| 5分钟后打开支付宝 | 定时5分钟后打开支付宝 |
| 写一个 Python 爬虫 | 生成并执行 Python 爬虫代码 |
| 创建一个计算器 APP | 生成完整 Android 项目 |

## 权限说明

| 权限 | 必需 | 说明 |
|------|------|------|
| 无障碍服务 | 是 | 核心操控能力（点击、滑动、输入、截屏） |
| 悬浮窗 | 否 | 后台启动其他应用 |
| 存储管理 | 否 | 截图保存、文件读写 |
| 电池优化白名单 | 否 | 防止后台被杀 |
| Shizuku/STELLAR | 否 | 高级系统操作（Shell 命令、安装应用、一键开启无障碍） |

## 技术架构

```
┌─────────────────────────────────────────────────────┐
│                    MainActivity                       │
│  (聊天界面 + 权限管理 + 设置)                         │
└──────────────┬──────────────────────────────────────┘
               │
┌──────────────▼──────────────────────────────────────┐
│                  ClawController                       │
│  (任务编排 + 执行调度 + 状态管理)                     │
├──────────────┬──────────────────┬───────────────────┤
│  AIGateway   │  AgentEngine     │  ActionTranslator  │
│  (AI 通信)   │  (ReAct 循环)    │  (指令解析)        │
├──────────────┼──────────────────┼───────────────────┤
│ ToolRegistry │  CodeGenerator   │  TaskDecomposer    │
│  (15+ 工具)  │  (代码生成)      │  (任务分解)        │
├──────────────┼──────────────────┼───────────────────┤
│ ScreenCtrl   │  ShellExecutor   │  ShizukuService    │
│ (无障碍操控)  │  (Shell 执行)    │  (特权服务)        │
└──────────────┴──────────────────┴───────────────────┘
```

## 项目结构

```
MobileClaw/
├── app/
│   ├── src/main/
│   │   ├── java/com/mobileclaw/app/
│   │   │   ├── accessibility/     # 无障碍服务与屏幕操控
│   │   │   ├── adapter/           # 适配器（Shell、应用管理、回收视图）
│   │   │   ├── ai/                # AI 核心引擎
│   │   │   │   ├── AIGateway.kt         # AI API 通信
│   │   │   │   ├── ClawController.kt    # 核心控制器
│   │   │   │   ├── AgentEngine.kt       # ReAct 推理引擎
│   │   │   │   ├── ToolRegistry.kt      # 工具注册表
│   │   │   │   ├── ActionTranslator.kt  # 指令解析
│   │   │   │   ├── CodeGenerator.kt     # 代码生成
│   │   │   │   ├── TaskDecomposer.kt    # 任务分解
│   │   │   │   ├── LocalInferenceEngine.kt  # 本地推理
│   │   │   │   ├── LocalModelManager.kt     # 本地模型管理
│   │   │   │   ├── TermuxBridge.kt      # Termux 集成
│   │   │   │   └── ... (60+ 文件)
│   │   │   ├── debug/              # Shell 执行器
│   │   │   ├── model/              # 数据模型
│   │   │   ├── service/            # 后台服务
│   │   │   ├── shizuku/            # Shizuku 特权服务
│   │   │   ├── system/             # 系统信息采集
│   │   │   ├── util/               # 工具类
│   │   │   ├── MainActivity.kt     # 主界面
│   │   │   └── MobileClawApp.kt    # 应用入口
│   │   ├── res/                    # 资源文件
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

## 构建

```bash
# 克隆仓库
git clone https://github.com/19923421354/MobileClaw.git

# 使用 Gradle 构建
cd MobileClaw
./gradlew assembleDebug

# 签名（可选，通过环境变量配置）
export KEYSTORE_PATH=/path/to/your.keystore
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=your_alias
export KEY_PASSWORD=your_password
./gradlew assembleRelease
```

## 致谢

- [Shizuku](https://shizuku.rikka.app/) — 提供系统级 API 访问能力
- [STELLAR](https://github.com/roro-studio/STELLAR) — Shizuku 兼容增强版
- 所有开源依赖库

## 许可证

[Apache License 2.0](LICENSE)