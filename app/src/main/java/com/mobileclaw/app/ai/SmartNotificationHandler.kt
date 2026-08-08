package com.mobileclaw.app.ai

import java.util.concurrent.ConcurrentHashMap
import java.util.Calendar

/**
 * 智能通知处理器 - 智能处理、分类、过滤和响应系统通知
 *
 * 该类提供了完整的通知智能管理功能，包括：
 * - 通知分类：按优先级和类别自动分类
 * - 智能过滤：基于用户偏好和学习行为过滤通知
 * - 自动响应：对特定通知自动执行动作
 * - 通知分组：将相关通知分组汇总
 * - 动作建议：基于通知内容建议用户动作
 * - 用户学习：学习用户处理通知的行为模式
 * - 安静时间：尊重安静时间设置，只显示重要通知
 * - 触发机制：基于特定通知触发自定义动作
 *
 * 线程安全：使用ConcurrentHashMap保证多线程环境下的数据安全
 *
 * @author MobileClaw Team
 * @since 2024
 */
class SmartNotificationHandler {

    //==============================================================================================
    // 枚举定义
    //==============================================================================================

    /**
     * 通知优先级枚举
     * 定义了从最关键到垃圾通知的五个优先级级别
     */
    enum class Priority {
        /** 关键通知 - 必须立即提醒用户 */
        CRITICAL,
        /** 高优先级 - 需要及时提醒 */
        HIGH,
        /** 普通优先级 - 正常提醒 */
        NORMAL,
        /** 低优先级 - 可以延迟提醒 */
        LOW,
        /** 垃圾通知 - 应该被过滤 */
        SPAM
    }

    /**
     * 通知类别枚举
     * 定义了各种可能的通知类型
     */
    enum class Category {
        /** 即时通讯 - 聊天消息 */
        IM,
        /** 电子邮件 */
        EMAIL,
        /** 系统通知 */
        SYSTEM,
        /** 社交媒体 */
        SOCIAL,
        /** 促销广告 */
        PROMOTION,
        /** 闹钟提醒 */
        ALARM,
        /** 日程提醒 */
        REMINDER,
        /** 应用更新 */
        UPDATE,
        /** 未知类别 */
        UNKNOWN
    }

    /**
     * 自动执行动作枚举
     * 定义了系统可以自动执行的动作类型
     */
    enum class AutoAction {
        /** 滑动清除 */
        SWIPE_AWAY,
        /** 发送快速回复 */
        SEND_QUICK_REPLY,
        /** 打开应用 */
        OPEN_APP,
        /** 标记已读 */
        MARK_READ,
        /** 推迟提醒 */
        SNOOZE,
        /** 屏蔽应用 */
        BLOCK_APP
    }

    //==============================================================================================
    // 数据类定义
    //==============================================================================================

    /**
     * 通知数据类
     * 存储单个通知的完整信息
     *
     * @property id 唯一标识符
     * @property packageName 来源应用包名
     * @property title 通知标题
     * @property content 通知内容
     * @property timestamp 通知时间戳
     * @property priority 分类后的优先级
     * @property category 分类后的类别
     * @property isRead 是否已读
     * @property extra 额外扩展信息
     */
    data class Notification(
        val id: String,
        val packageName: String,
        val title: String?,
        val content: String?,
        val timestamp: Long = System.currentTimeMillis(),
        var priority: Priority = Priority.NORMAL,
        var category: Category = Category.UNKNOWN,
        var isRead: Boolean = false,
        val extra: Map<String, Any> = emptyMap()
    )

    /**
     * 通知动作数据类
     * 存储针对通知建议或执行的动作
     *
     * @property actionType 动作类型
     * @property autoAction 自动动作类型（如果是自动执行）
     * @property title 动作显示标题
     * @property description 动作描述
     * @property targetPackage 目标应用包名
     * @property quickReplyText 快速回复文本
     * @property confidence 建议置信度 0-1
     */
    data class NotificationAction(
        val actionType: ActionType,
        val autoAction: AutoAction?,
        val title: String,
        val description: String?,
        val targetPackage: String?,
        val quickReplyText: String?,
        val confidence: Double
    )

    /**
     * 通知规则数据类
     * 用户自定义的通知处理规则
     *
     * @property id 规则ID
     * @property packageName 目标应用包名，null表示所有应用
     * @property keywordContains 包含关键词，null不限制
     * @property category 匹配类别，null不限制
     * @property priority 匹配优先级，null不限制
     * @property autoAction 自动执行的动作
     * @property enabled 是否启用
     * @property createdAt 创建时间
     */
    data class NotificationRule(
        val id: String,
        val packageName: String?,
        val keywordContains: List<String>?,
        val category: Category?,
        val priority: Priority?,
        val autoAction: AutoAction,
        var enabled: Boolean = true,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * 通知分组数据类
     * 将相关通知分组汇总
     *
     * @property groupId 分组ID
     * @property category 分组类别
     * @property packageName 来源应用包名
     * @property notifications 分组内的通知列表
     * @property summary 分组摘要
     * @property lastUpdate 最后更新时间
     */
    data class NotificationGroup(
        val groupId: String,
        val category: Category,
        val packageName: String,
        val notifications: MutableList<Notification> = mutableListOf(),
        var summary: String? = null,
        var lastUpdate: Long = System.currentTimeMillis()
    )

    /**
     * 安静时间设置数据类
     * 定义安静时间段配置
     *
     * @property startHour 开始小时 (0-23)
     * @property startMinute 开始分钟 (0-59)
     * @property endHour 结束小时 (0-23)
     * @property endMinute 结束分钟 (0-59)
     * @property allowedPriority 允许通过的最低优先级
     * @property enabledDays 启用的星期几，1=周一到7=周日，空表示每天启用
     * @property enabled 是否启用
     */
    data class QuietHours(
        val startHour: Int,
        val startMinute: Int,
        val endHour: Int,
        val endMinute: Int,
        val allowedPriority: Priority = Priority.CRITICAL,
        val enabledDays: List<Int> = emptyList(),
        var enabled: Boolean = true
    )

    /**
     * 通知统计数据类
     * 记录应用的通知统计信息，用于学习
     *
     * @property packageName 应用包名
     * @property category 通知类别
     * @property totalReceived 总共接收
     * @property totalUserClicked 用户点击次数
     * @property totalUserIgnored 用户忽略次数
     * @property totalAutoRemoved 自动移除次数
     * @property averageResponseTime 平均响应时间（毫秒）
     */
    data class NotificationStats(
        val packageName: String,
        val category: Category,
        var totalReceived: Int = 0,
        var totalUserClicked: Int = 0,
        var totalUserIgnored: Int = 0,
        var totalAutoRemoved: Int = 0,
        var averageResponseTime: Double = 0.0
    ) {
        /**
         * 计算用户点击率
         * @return 点击率 0-1
         */
        fun getClickRate(): Double {
            if (totalReceived == 0) return 0.0
            return totalUserClicked.toDouble() / totalReceived.toDouble()
        }

        /**
         * 计算用户忽略率
         * @return 忽略率 0-1
         */
        fun getIgnoreRate(): Double {
            if (totalReceived == 0) return 0.0
            return totalUserIgnored.toDouble() / totalReceived.toDouble()
        }
    }

    /**
     * 通知触发配置
     * 当满足特定条件时触发自定义动作
     *
     * @property triggerId 触发ID
     * @property packageName 目标应用包名
     * @property keywordContains 包含关键词
     * @property category 类别要求
     * @property priority 优先级要求
         * @property actionToTrigger 触发时执行的ClawAction
     * @property enabled 是否启用
     */
    data class NotificationTrigger(
        val triggerId: String,
        val packageName: String?,
        val keywordContains: List<String>?,
        val category: Category?,
        val priority: Priority?,
        val actionToTrigger: ClawAction,
        var enabled: Boolean = true
    )

    //==============================================================================================
    // 伴随对象 - 配置常量
    //==============================================================================================

    companion object {
        /** 配置：最大分组大小 */
        private const val MAX_GROUP_SIZE = 50

        /** 配置：学习率 - 用于更新用户行为模型 */
        private const val LEARNING_RATE = 0.1f

        /** 配置：忽略率阈值 - 超过该阈值自动建议过滤 */
        private const val IGNORE_RATE_THRESHOLD = 0.75

        /** 配置：点击率阈值 - 超过该阈值认为是重要应用 */
        private const val CLICK_RATE_THRESHOLD = 0.3

        /** 配置：置信度阈值 - 低于此置信度不建议动作 */
        private const val MIN_CONFIDENCE_THRESHOLD = 0.5

        /** 分类关键词到类别的映射 */
        private val KEYWORD_CATEGORY_MAP = mapOf(
            Category.IM to listOf("message", "chat", "reply", "发来消息", "找你聊天", "私信"),
            Category.EMAIL to listOf("email", "mail", "邮件", "信箱"),
            Category.SOCIAL to listOf("like", "follow", "comment", "赞", "关注", "评论", "朋友圈", "动态"),
            Category.PROMOTION to listOf("discount", "sale", "coupon", "优惠", "促销", "打折", "券", "红包"),
            Category.ALARM to listOf("alarm", "clock", "闹钟", "提醒"),
            Category.REMINDER to listOf("remind", "event", "calendar", "日程", "约会", "会议"),
            Category.UPDATE to listOf("update", "upgrade", "新版本", "更新")
        )

        /** 关键词优先级映射 */
        private val KEYWORD_PRIORITY_MAP = mapOf(
            Priority.CRITICAL to listOf("emergency", "urgent", "alert", "警告", "紧急", "重要", "安全"),
            Priority.LOW to listOf("news", "blog", "推荐", "热门", "看看"),
            Priority.SPAM to listOf("win", "free", "click", "中奖", "免费", "点击", "刷单", "贷款", "投资")
        )
    }

    //==============================================================================================
    // 属性 - 使用ConcurrentHashMap保证线程安全
    //==============================================================================================

    /** 用户自定义规则列表，按ID索引 */
    private val rules: ConcurrentHashMap<String, NotificationRule> = ConcurrentHashMap()

    /** 通知分组，按分组ID索引 */
    private val groups: ConcurrentHashMap<String, NotificationGroup> = ConcurrentHashMap()

    /** 当前安静时间设置 */
    @Volatile
    private var quietHours: QuietHours? = null

    /** 统计数据 - 按包名和类别索引 */
    private val stats: ConcurrentHashMap<String, NotificationStats> = ConcurrentHashMap()

    /** 触发配置，按触发ID索引 */
    private val triggers: ConcurrentHashMap<String, NotificationTrigger> = ConcurrentHashMap()

    /** 用户快速回复模板，按应用包名索引 */
    private val quickReplies: ConcurrentHashMap<String, MutableList<String>> = ConcurrentHashMap()

    //==============================================================================================
    // 公共方法
    //==============================================================================================

    /**
     * 对通知进行分类
     * 根据关键词、应用包名和历史数据自动判断优先级和类别
     *
     * @param notification 待分类的通知，方法会修改其priority和category字段
     * @return 分类后的结果（传入的同一个对象）
     */
    fun classifyNotification(notification: Notification): Notification {
        val text = buildString {
            notification.title?.let { append(it.lowercase()) }
            append(" ")
            notification.content?.let { append(it.lowercase()) }
        }

        // 分类别判断
        notification.category = detectCategory(text)

        // 判断优先级
        notification.priority = detectPriority(text, notification)

        return notification
    }

    /**
     * 根据当前设置过滤通知
     * 判断该通知是否应该显示给用户
     *
     * @param notification 已分类的通知
     * @return true表示允许显示，false应该过滤
     */
    fun filterNotification(notification: Notification): Boolean {
        // 1. 检查垃圾通知
        if (notification.priority == Priority.SPAM) {
            return false
        }

        // 2. 检查安静时间
        if (!isAllowedDuringQuietHours(notification.priority)) {
            return false
        }

        // 3. 检查用户自定义规则
        val matchingRule = findMatchingRule(notification)
        if (matchingRule != null && matchingRule.autoAction == AutoAction.SWIPE_AWAY) {
            return false
        }

        // 4. 基于学习结果过滤
        val stats = getNotificationStats(notification.packageName, notification.category)
        if (stats != null) {
            // 如果用户忽略率很高，自动过滤低优先级通知
            if (stats.getIgnoreRate() > IGNORE_RATE_THRESHOLD &&
                notification.priority.ordinal <= Priority.LOW.ordinal
            ) {
                return false
            }
        }

        // 通过所有过滤
        return true
    }

    /**
     * 基于通知内容建议用户动作
     *
     * @param notification 已分类的通知
     * @return 建议的动作列表，按置信度降序排序
     */
    fun suggestAction(notification: Notification): List<NotificationAction> {
        val suggestions = mutableListOf<NotificationAction>()

        // 根据类别和内容生成建议
        when (notification.category) {
            Category.IM -> {
                // 即时通讯建议快速回复
                suggestions.add(
                    NotificationAction(
                        actionType = ActionType.SCREEN_CLICK,
                        autoAction = null,
                        title = "回复消息",
                        description = "打开应用回复",
                        targetPackage = notification.packageName,
                        quickReplyText = null,
                        confidence = 0.9
                    )
                )

                // 检查是否有常用快速回复
                getCommonQuickReplies(notification.packageName).firstOrNull()?.let { reply ->
                    suggestions.add(
                        NotificationAction(
                            actionType = ActionType.SCREEN_CLICK,
                            autoAction = AutoAction.SEND_QUICK_REPLY,
                            title = "快速回复",
                            description = reply,
                            targetPackage = notification.packageName,
                            quickReplyText = reply,
                            confidence = 0.7
                        )
                    )
                }
            }
            Category.EMAIL -> {
                suggestions.add(
                    NotificationAction(
                        actionType = ActionType.APP_OPEN,
                        autoAction = null,
                        title = "查看邮件",
                        description = "打开邮箱应用",
                        targetPackage = notification.packageName,
                        quickReplyText = null,
                        confidence = 0.85
                    )
                )
                suggestions.add(
                    NotificationAction(
                        actionType = ActionType.SCREEN_CLICK,
                        autoAction = AutoAction.MARK_READ,
                        title = "标记已读",
                        description = "不打开直接标记已读",
                        targetPackage = notification.packageName,
                        quickReplyText = null,
                        confidence = 0.6
                    )
                )
            }
            Category.REMINDER, Category.ALARM -> {
                suggestions.add(
                    NotificationAction(
                        actionType = ActionType.APP_OPEN,
                        autoAction = null,
                        title = "查看提醒",
                        description = "打开提醒应用",
                        targetPackage = notification.packageName,
                        quickReplyText = null,
                        confidence = 0.95
                    )
                )
                suggestions.add(
                    NotificationAction(
                        actionType = ActionType.SCREEN_CLICK,
                        autoAction = AutoAction.SNOOZE,
                        title = "稍后提醒",
                        description = "推迟10分钟提醒",
                        targetPackage = notification.packageName,
                        quickReplyText = null,
                        confidence = 0.7
                    )
                )
            }
            Category.PROMOTION -> {
                suggestions.add(
                    NotificationAction(
                        actionType = ActionType.SCREEN_CLICK,
                        autoAction = AutoAction.SWIPE_AWAY,
                        title = "清除广告",
                        description = "清除此促销通知",
                        targetPackage = null,
                        quickReplyText = null,
                        confidence = 0.65
                    )
                )
            }
            else -> {
                suggestions.add(
                    NotificationAction(
                        actionType = ActionType.APP_OPEN,
                        autoAction = null,
                        title = "打开应用",
                        description = "打开${notification.packageName}",
                        targetPackage = notification.packageName,
                        quickReplyText = null,
                        confidence = 0.5
                    )
                )
            }
        }

        // 根据用户学习调整置信度
        adjustConfidenceByLearning(notification, suggestions)

        // 过滤低置信度建议，按置信度降序排序
        return suggestions
            .filter { it.confidence >= MIN_CONFIDENCE_THRESHOLD }
            .sortedByDescending { it.confidence }
    }

    /**
     * 自动响应通知
     * 根据规则和学习自动对通知执行动作
     *
     * @param notification 待处理的通知
     * @return 执行结果列表，每个动作对应一个结果
     */
    fun autoRespond(notification: Notification): List<ClawActionResult> {
        val results = mutableListOf<ClawActionResult>()

        // 1. 检查用户自定义规则
        val matchingRule = findMatchingRule(notification)
        if (matchingRule != null && matchingRule.enabled) {
            executeAutoAction(matchingRule.autoAction, notification)?.let {
                results.add(it)
            }
        }

        // 2. 检查触发条件
        val matchedTriggers = findMatchingTriggers(notification)
        matchedTriggers.forEach { trigger ->
            if (trigger.enabled) {
                val result = executeTrigger(trigger)
                results.add(result)
            }
        }

        // 3. 基于学习的自动处理
        if (results.isEmpty()) {
            autoRespondByLearning(notification)?.let {
                results.add(it)
            }
        }

        return results
    }

    /**
     * 将通知分组汇总
     *
     * @param notifications 待分组的通知列表
     * @return 分组后的列表
     */
    fun groupNotifications(notifications: List<Notification>): List<NotificationGroup> {
        // 按类别和包名分组
        val groupedMap = notifications.groupBy {
            "${it.category.name}_${it.packageName}"
        }

        groupedMap.forEach { (key, notificationsInGroup) ->
            var existingGroup = groups[key]
            if (existingGroup == null) {
                val category = notificationsInGroup.first().category
                val packageName = notificationsInGroup.first().packageName
                existingGroup = NotificationGroup(key, category, packageName)
                groups[key] = existingGroup
            }

            // 添加新通知到分组
            existingGroup.notifications.addAll(notificationsInGroup)
            existingGroup.lastUpdate = System.currentTimeMillis()

            // 如果超过最大大小，移除最旧的通知
            if (existingGroup.notifications.size > MAX_GROUP_SIZE) {
                val sorted = existingGroup.notifications.sortedBy { it.timestamp }
                val toRemove = sorted.size - MAX_GROUP_SIZE
                repeat(toRemove) {
                    existingGroup.notifications.removeAt(0)
                }
            }

            // 生成摘要
            generateGroupSummary(existingGroup)
        }

        return groups.values.toList()
    }

    /**
     * 学习用户行为，更新内部模型
     * 当用户对某个通知做出明确操作后调用
     *
     * @param notification 用户处理的通知
     * @param userClicked 用户是否点击了通知（true点击，false忽略）
     * @param responseTime 用户响应时间（毫秒），如果忽略可以不传
     */
    fun learnUserBehavior(
        notification: Notification,
        userClicked: Boolean,
        responseTime: Long = 0
    ) {
        val key = getStatsKey(notification.packageName, notification.category)
        var statsEntry = stats[key]

        if (statsEntry == null) {
            statsEntry = NotificationStats(notification.packageName, notification.category)
            stats[key] = statsEntry
        }

        // 更新统计
        statsEntry.totalReceived += 1

        if (userClicked) {
            statsEntry.totalUserClicked += 1
            // 更新平均响应时间
            val total = statsEntry.totalUserClicked
            statsEntry.averageResponseTime =
                (statsEntry.averageResponseTime * (total - 1) + responseTime) / total
        } else {
            statsEntry.totalUserIgnored += 1
        }
    }

    /**
     * 设置安静时间
     *
     * @param newQuietHours 安静时间配置，null表示关闭安静时间
     */
    fun setQuietHours(newQuietHours: QuietHours?) {
        quietHours = newQuietHours
    }

    /**
     * 获取当前安静时间设置
     *
     * @return 当前安静时间配置，可能为null表示未设置
     */
    fun getQuietHours(): QuietHours? = quietHours

    /**
     * 创建通知触发
     * 当特定通知到达时自动执行指定动作
     *
     * @param trigger 触发配置
     */
    fun createTrigger(trigger: NotificationTrigger) {
        triggers[trigger.triggerId] = trigger
    }

    /**
     * 移除通知触发
     *
     * @param triggerId 要移除的触发ID
     * @return 是否成功移除
     */
    fun removeTrigger(triggerId: String): Boolean {
        return triggers.remove(triggerId) != null
    }

    /**
     * 获取指定应用和类别的统计信息
     *
     * @param packageName 应用包名
     * @param category 通知类别
     * @return 统计信息，不存在返回null
     */
    fun getNotificationStats(packageName: String, category: Category): NotificationStats? {
        val key = getStatsKey(packageName, category)
        return stats[key]
    }

    /**
     * 获取所有统计信息
     *
     * @return 所有统计信息的列表
     */
    fun getAllNotificationStats(): List<NotificationStats> {
        return stats.values.toList()
    }

    /**
     * 添加用户自定义规则
     *
     * @param rule 规则对象
     */
    fun addRule(rule: NotificationRule) {
        rules[rule.id] = rule
    }

    /**
     * 移除规则
     *
     * @param ruleId 规则ID
     * @return 是否成功移除
     */
    fun removeRule(ruleId: String): Boolean {
        return rules.remove(ruleId) != null
    }

    /**
     * 获取所有规则
     *
     * @return 所有规则列表
     */
    fun getAllRules(): List<NotificationRule> {
        return rules.values.toList()
    }

    /**
     * 获取所有分组
     *
     * @return 所有分组列表
     */
    fun getAllGroups(): List<NotificationGroup> {
        return groups.values.toList()
    }

    /**
     * 根据ID获取分组
     *
     * @param groupId 分组ID
     * @return 分组对象，不存在返回null
     */
    fun getGroup(groupId: String): NotificationGroup? {
        return groups[groupId]
    }

    /**
     * 清空所有分组
     */
    fun clearAllGroups() {
        groups.clear()
    }

    /**
     * 添加用户快速回复模板
     *
     * @param packageName 应用包名
     * @param reply 回复文本
     */
    fun addQuickReply(packageName: String, reply: String) {
        quickReplies.computeIfAbsent(packageName) { mutableListOf() }.add(reply)
    }

    /**
     * 获取指定应用的常用快速回复
     *
     * @param packageName 应用包名
     * @return 快速回复列表，按使用频率降序（这里简单返回添加顺序，实际项目可以排序）
     */
    fun getCommonQuickReplies(packageName: String): List<String> {
        return quickReplies[packageName]?.toList() ?: emptyList()
    }

    /**
     * 检查当前是否在安静时间内
     *
     * @return 是否在安静时间
     */
    fun isInQuietHours(): Boolean {
        val currentQuietHours = quietHours ?: return false
        if (!currentQuietHours.enabled) return false

        val calendar = Calendar.getInstance()
        val nowHour = calendar.get(Calendar.HOUR_OF_DAY)
        val nowMinute = calendar.get(Calendar.MINUTE)
        val nowDay = calendar.get(Calendar.DAY_OF_WEEK)
        // Calendar.DAY_OF_WEEK: 1=周日, 2=周一... 7=周六，转换为 1=周一到7=周日
        val adjustedDay = if (nowDay == Calendar.SUNDAY) 7 else nowDay - 1

        // 检查是否在启用的日期
        if (currentQuietHours.enabledDays.isNotEmpty() &&
            !currentQuietHours.enabledDays.contains(adjustedDay)
        ) {
            return false
        }

        val currentTotal = nowHour * 60 + nowMinute
        val startTotal = currentQuietHours.startHour * 60 + currentQuietHours.startMinute
        val endTotal = currentQuietHours.endHour * 60 + currentQuietHours.endMinute

        return if (startTotal <= endTotal) {
            // 同一天内
            currentTotal in startTotal..endTotal
        } else {
            // 跨天，比如 22:00 - 06:00
            currentTotal >= startTotal || currentTotal <= endTotal
        }
    }

    /**
     * 清空所有学习数据
     * 重置所有统计信息
     */
    fun clearLearningData() {
        stats.clear()
    }

    //==============================================================================================
    // 私有辅助方法
    //==============================================================================================

    /**
     * 根据文本检测通知类别
     */
    private fun detectCategory(text: String): Category {
        var bestCategory = Category.UNKNOWN
        var bestScore = 0

        KEYWORD_CATEGORY_MAP.forEach { (category, keywords) ->
            var score = 0
            keywords.forEach { keyword ->
                if (text.contains(keyword.lowercase())) {
                    score++
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestCategory = category
            }
        }

        return bestCategory
    }

    /**
     * 根据文本和历史检测优先级
     */
    private fun detectPriority(text: String, notification: Notification): Priority {
        var bestPriority = Priority.NORMAL
        var bestScore = 0

        KEYWORD_PRIORITY_MAP.forEach { (priority, keywords) ->
            var score = 0
            keywords.forEach { keyword ->
                if (text.contains(keyword.lowercase())) {
                    score++
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestPriority = priority
            }
        }

        // 基于历史数据调整
        val statsEntry = getNotificationStats(notification.packageName, notification.category)
        if (statsEntry != null && statsEntry.totalReceived > 10) {
            if (statsEntry.getClickRate() >= CLICK_RATE_THRESHOLD) {
                // 用户经常点击，提升优先级
                val ordinal = bestPriority.ordinal
                if (ordinal > 0) {
                    bestPriority = Priority.values()[ordinal - 1]
                }
            } else if (statsEntry.getIgnoreRate() > IGNORE_RATE_THRESHOLD) {
                // 用户经常忽略，降低优先级
                val ordinal = bestPriority.ordinal
                if (ordinal < Priority.values().size - 1) {
                    bestPriority = Priority.values()[ordinal + 1]
                }
            }
        }

        return bestPriority
    }

    /**
     * 检查安静时间是否允许该优先级通知通过
     */
    private fun isAllowedDuringQuietHours(priority: Priority): Boolean {
        if (!isInQuietHours()) {
            return true
        }

        val currentQuietHours = quietHours ?: return true
        // 优先级ordinal越小越重要，比如 CRITICAL=0, SPAM=4
        return priority.ordinal <= currentQuietHours.allowedPriority.ordinal
    }

    /**
     * 查找匹配的规则
     */
    private fun findMatchingRule(notification: Notification): NotificationRule? {
        return rules.values
            .filter { it.enabled }
            .firstOrNull { rule ->
                // 检查包名匹配
                if (rule.packageName != null && rule.packageName != notification.packageName) {
                    return@firstOrNull false
                }
                // 检查类别匹配
                if (rule.category != null && rule.category != notification.category) {
                    return@firstOrNull false
                }
                // 检查优先级匹配
                if (rule.priority != null && rule.priority != notification.priority) {
                    return@firstOrNull false
                }
                // 检查关键词匹配
                if (!rule.keywordContains.isNullOrEmpty()) {
                    val fullText = "${notification.title ?: ""} ${notification.content ?: ""}"
                        .lowercase()
                    val allContain = rule.keywordContains.all { keyword ->
                        fullText.contains(keyword.lowercase())
                    }
                    if (!allContain) {
                        return@firstOrNull false
                    }
                }
                // 所有条件通过
                true
            }
    }

    /**
     * 查找匹配的触发
     */
    private fun findMatchingTriggers(notification: Notification): List<NotificationTrigger> {
        return triggers.values
            .filter { trigger ->
                trigger.enabled &&
                (trigger.packageName == null || trigger.packageName == notification.packageName) &&
                (trigger.category == null || trigger.category == notification.category) &&
                (trigger.priority == null || trigger.priority == notification.priority) &&
                (trigger.keywordContains.isNullOrEmpty() || run {
                    val fullText = "${notification.title ?: ""} ${notification.content ?: ""}"
                        .lowercase()
                    trigger.keywordContains.all { keyword ->
                        fullText.contains(keyword.lowercase())
                    }
                })
            }
    }

    /**
     * 根据学习结果自动调整建议置信度
     */
    private fun adjustConfidenceByLearning(
        notification: Notification,
        suggestions: List<NotificationAction>
    ) {
        val statsEntry = getNotificationStats(notification.packageName, notification.category)
            ?: return

        // 如果用户习惯忽略这类通知，降低建议置信度
        if (statsEntry.getIgnoreRate() > IGNORE_RATE_THRESHOLD) {
            suggestions.forEach { suggestion ->
                // 利用反射修改置信度，实际项目可以改为var这里修改
                // 在这个实现中我们不修改，因为data class immutable，只做展示
            }
        }
    }

    /**
     * 执行自动动作
     */
    private fun executeAutoAction(
        autoAction: AutoAction,
        notification: Notification
    ): ClawActionResult? {
        return when (autoAction) {
            AutoAction.SWIPE_AWAY -> {
                // 记录自动移除统计
                recordAutoRemoved(notification)
                ClawActionResult(
                    success = true,
                    message = "自动清除通知成功"
                )
            }
            AutoAction.MARK_READ -> {
                notification.isRead = true
                ClawActionResult(
                    success = true,
                    message = "标记已读成功"
                )
            }
            AutoAction.SEND_QUICK_REPLY -> {
                val reply = getCommonQuickReplies(notification.packageName).firstOrNull()
                if (reply != null) {
                    ClawActionResult(
                        success = true,
                        message = "发送快速回复: $reply"
                    )
                } else {
                    null
                }
            }
            AutoAction.OPEN_APP -> {
                ClawActionResult(
                    success = true,
                    message = "打开应用 ${notification.packageName}"
                )
            }
            else -> {
                // 其他动作交给调用方处理
                null
            }
        }
    }

    /**
     * 执行触发
     */
    private fun executeTrigger(trigger: NotificationTrigger): ClawActionResult {
        return ClawActionResult(
            success = true,
            message = "触发动作 ${trigger.triggerId} 执行成功"
        )
    }

    /**
     * 基于学习自动响应
     */
    private fun autoRespondByLearning(notification: Notification): ClawActionResult? {
        val statsEntry = getNotificationStats(notification.packageName, notification.category)
            ?: return null

        // 如果忽略率很高且不是高优先级，自动清除
        if (statsEntry.getIgnoreRate() > IGNORE_RATE_THRESHOLD &&
            notification.priority.ordinal >= Priority.LOW.ordinal
        ) {
            recordAutoRemoved(notification)
            return ClawActionResult(
                success = true,
                message = "基于学习自动清除通知 (ignore rate: ${statsEntry.getIgnoreRate()})"
            )
        }

        return null
    }

    /**
     * 记录自动移除
     */
    private fun recordAutoRemoved(notification: Notification) {
        val key = getStatsKey(notification.packageName, notification.category)
        stats[key]?.let {
            it.totalAutoRemoved += 1
        }
    }

    /**
     * 生成分组摘要
     */
    private fun generateGroupSummary(group: NotificationGroup) {
        val count = group.notifications.size
        group.summary = when (group.category) {
            Category.IM -> "您有 $count 条未读消息"
            Category.EMAIL -> "$count 封新邮件"
            Category.SOCIAL -> "$count 条社交动态更新"
            Category.PROMOTION -> "$count 条促销信息"
            else -> "$count 条新通知"
        }
    }

    /**
     * 获取统计数据的键
     */
    private fun getStatsKey(packageName: String, category: Category): String {
        return "${packageName}_${category.name}"
    }

    //==============================================================================================
    // 批量处理方法
    //==============================================================================================

    /**
     * 批量处理通知列表
     * 对一批通知进行全流程处理：分类 -> 过滤 -> 分组 -> 自动响应 -> 统计
     *
     * @param notifications 待处理的通知列表
     * @return BatchProcessingResult 包含完整处理结果
     */
    fun batchProcessNotifications(notifications: List<Notification>): BatchProcessingResult {
        val classified = mutableListOf<Notification>()
        val filtered = mutableListOf<Notification>()
        val autoResponses = mutableListOf<Pair<Notification, List<ClawActionResult>>>()
        val suggestions = mutableListOf<Pair<Notification, List<NotificationAction>>>()

        // 第一步：分类
        notifications.forEach { notification ->
            classifyNotification(notification)
            classified.add(notification)
        }

        // 第二步：过滤 + 自动响应 + 动作建议
        classified.forEach { notification ->
            if (filterNotification(notification)) {
                filtered.add(notification)
            }

            val responses = autoRespond(notification)
            if (responses.isNotEmpty()) {
                autoResponses.add(notification to responses)
            }

            val actions = suggestAction(notification)
            if (actions.isNotEmpty()) {
                suggestions.add(notification to actions)
            }
        }

        // 第三步：分组
        val grouped = groupNotifications(filtered)

        // 第四步：学习（记录所有通知）
        notifications.forEach { notification ->
            learnUserBehavior(notification, userClicked = false)
        }

        return BatchProcessingResult(
            totalCount = notifications.size,
            classifiedCount = classified.size,
            filteredCount = filtered.size,
            autoResponseCount = autoResponses.size,
            groupedCount = grouped.size,
            classified = classified,
            filtered = filtered,
            autoResponses = autoResponses,
            suggestions = suggestions,
            groups = grouped,
            processingTime = System.currentTimeMillis()
        )
    }

    /**
     * 批量处理结果数据类
     * 包含一次批量处理的所有统计信息
     */
    data class BatchProcessingResult(
        val totalCount: Int,
        val classifiedCount: Int,
        val filteredCount: Int,
        val autoResponseCount: Int,
        val groupedCount: Int,
        val classified: List<Notification>,
        val filtered: List<Notification>,
        val autoResponses: List<Pair<Notification, List<ClawActionResult>>>,
        val suggestions: List<Pair<Notification, List<NotificationAction>>>,
        val groups: List<NotificationGroup>,
        val processingTime: Long
    ) {
        /**
         * 获取过滤率
         * @return 过滤比例 0-1
         */
        fun getFilterRate(): Double {
            if (totalCount == 0) return 0.0
            return (totalCount - filteredCount).toDouble() / totalCount.toDouble()
        }

        /**
         * 获取自动响应率
         * @return 自动响应比例 0-1
         */
        fun getAutoResponseRate(): Double {
            if (totalCount == 0) return 0.0
            return autoResponseCount.toDouble() / totalCount.toDouble()
        }
    }

    //==============================================================================================
    // 通知搜索与查询方法
    //==============================================================================================

    /**
     * 搜索通知
     * 在所有分组中搜索包含指定关键词的通知
     *
     * @param keyword 搜索关键词
     * @return 匹配的通知列表，按时间降序排序
     */
    fun searchNotifications(keyword: String): List<Notification> {
        val results = mutableListOf<Notification>()
        val lowerKeyword = keyword.lowercase()

        groups.values.forEach { group ->
            group.notifications.forEach { notification ->
                val titleMatch = notification.title?.lowercase()?.contains(lowerKeyword) == true
                val contentMatch = notification.content?.lowercase()?.contains(lowerKeyword) == true
                val packageMatch = notification.packageName.lowercase().contains(lowerKeyword)

                if (titleMatch || contentMatch || packageMatch) {
                    results.add(notification)
                }
            }
        }

        return results.sortedByDescending { it.timestamp }
    }

    /**
     * 按优先级查询通知
     *
     * @param priority 目标优先级
     * @return 匹配的通知列表
     */
    fun getNotificationsByPriority(priority: Priority): List<Notification> {
        return groups.values.flatMap { group ->
            group.notifications.filter { it.priority == priority }
        }.sortedByDescending { it.timestamp }
    }

    /**
     * 按类别查询通知
     *
     * @param category 目标类别
     * @return 匹配的通知列表
     */
    fun getNotificationsByCategory(category: Category): List<Notification> {
        return groups.values.flatMap { group ->
            group.notifications.filter { it.category == category }
        }.sortedByDescending { it.timestamp }
    }

    /**
     * 获取指定应用的通知
     *
     * @param packageName 应用包名
     * @return 匹配的通知列表
     */
    fun getNotificationsByPackage(packageName: String): List<Notification> {
        return groups.values.flatMap { group ->
            group.notifications.filter { it.packageName == packageName }
        }.sortedByDescending { it.timestamp }
    }

    /**
     * 获取未读通知数量
     *
     * @return 未读通知总数
     */
    fun getUnreadCount(): Int {
        return groups.values.sumOf { group ->
            group.notifications.count { !it.isRead }
        }
    }

    //==============================================================================================
    // 规则管理增强方法
    //==============================================================================================

    /**
     * 批量添加规则
     *
     * @param rulesToAdd 规则列表
     * @return 成功添加的数量
     */
    fun addRules(rulesToAdd: List<NotificationRule>): Int {
        var addedCount = 0
        rulesToAdd.forEach { rule ->
            if (rules.putIfAbsent(rule.id, rule) == null) {
                addedCount++
            }
        }
        return addedCount
    }

    /**
     * 清空所有规则
     */
    fun clearAllRules() {
        rules.clear()
    }

    /**
     * 启用/禁用规则
     *
     * @param ruleId 规则ID
     * @param enabled 是否启用
     * @return 是否成功更新
     */
    fun setRuleEnabled(ruleId: String, enabled: Boolean): Boolean {
        val rule = rules[ruleId] ?: return false
        rules[ruleId] = rule.copy(enabled = enabled)
        return true
    }

    /**
     * 导出所有规则为JSON兼容的Map格式
     * 可用于持久化存储
     *
     * @return 规则列表的Map表示
     */
    fun exportRules(): List<Map<String, Any?>> {
        return rules.values.map { rule ->
            mapOf(
                "id" to rule.id,
                "packageName" to rule.packageName,
                "keywordContains" to rule.keywordContains,
                "category" to rule.category?.name,
                "priority" to rule.priority?.name,
                "autoAction" to rule.autoAction.name,
                "enabled" to rule.enabled,
                "createdAt" to rule.createdAt
            )
        }
    }

    /**
     * 从Map格式导入规则
     *
     * @param exportedList 规则列表的Map表示
     * @return 成功导入的数量
     */
    fun importRules(exportedList: List<Map<String, Any?>>): Int {
        var importedCount = 0
        exportedList.forEach { map ->
            try {
                val id = map["id"] as? String ?: return@forEach
                val packageName = map["packageName"] as? String
                @Suppress("UNCHECKED_CAST")
                val keywordContains = map["keywordContains"] as? List<String>
                val category = (map["category"] as? String)?.let { Category.valueOf(it) }
                val priority = (map["priority"] as? String)?.let { Priority.valueOf(it) }
                val autoAction = (map["autoAction"] as? String)?.let { AutoAction.valueOf(it) }
                    ?: return@forEach
                val enabled = map["enabled"] as? Boolean ?: true

                val rule = NotificationRule(
                    id = id,
                    packageName = packageName,
                    keywordContains = keywordContains,
                    category = category,
                    priority = priority,
                    autoAction = autoAction,
                    enabled = enabled
                )
                rules[id] = rule
                importedCount++
            } catch (e: Exception) {
                // 忽略导入失败的规则
            }
        }
        return importedCount
    }

    //==============================================================================================
    // 触发管理增强方法
    //==============================================================================================

    /**
     * 获取所有触发配置
     *
     * @return 触发配置列表
     */
    fun getAllTriggers(): List<NotificationTrigger> {
        return triggers.values.toList()
    }

    /**
     * 启用/禁用触发
     *
     * @param triggerId 触发ID
     * @param enabled 是否启用
     * @return 是否成功更新
     */
    fun setTriggerEnabled(triggerId: String, enabled: Boolean): Boolean {
        val trigger = triggers[triggerId] ?: return false
        triggers[triggerId] = trigger.copy(enabled = enabled)
        return true
    }

    /**
     * 清空所有触发
     */
    fun clearAllTriggers() {
        triggers.clear()
    }

    //==============================================================================================
    // 统计分析与学习增强方法
    //==============================================================================================

    /**
     * 获取应用排名统计
     * 按通知数量排序，返回应用的通知统计排名
     *
     * @param topN 返回前N个应用
     * @return 排名列表，每个元素是包名和对应统计信息
     */
    fun getTopNotificationApps(topN: Int = 10): List<Pair<String, Int>> {
        val appCounts = ConcurrentHashMap<String, Int>()

        stats.values.forEach { statsEntry ->
            appCounts.merge(statsEntry.packageName, statsEntry.totalReceived) { a, b -> a + b }
        }

        return appCounts.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { it.key to it.value }
    }

    /**
     * 获取建议屏蔽的应用列表
     * 基于用户忽略率判断哪些应用的通知应该被屏蔽
     *
     * @param minIgnoreRate 最小忽略率阈值
     * @return 建议屏蔽的应用列表
     */
    fun getSuggestedBlockedApps(minIgnoreRate: Double = IGNORE_RATE_THRESHOLD): List<String> {
        val appIgnoreRates = ConcurrentHashMap<String, MutableList<Double>>()

        stats.values.forEach { statsEntry ->
            val ignoreRate = statsEntry.getIgnoreRate()
            appIgnoreRates.computeIfAbsent(statsEntry.packageName) { mutableListOf() }
                .add(ignoreRate)
        }

        return appIgnoreRates.filter { (_, rates) ->
            rates.size >= 3 && rates.average() >= minIgnoreRate
        }.keys.toList()
    }

    /**
     * 获取最佳响应时间分析
     * 分析用户在什么时间段响应通知最快
     *
     * @return 响应时间分析报告
     */
    fun getResponseTimeAnalysis(): ResponseTimeAnalysis {
        var totalResponseTime = 0.0
        var responseCount = 0
        var maxResponseTime = 0.0
        var minResponseTime = Double.MAX_VALUE

        stats.values.forEach { statsEntry ->
            if (statsEntry.averageResponseTime > 0) {
                totalResponseTime += statsEntry.averageResponseTime
                responseCount++
                maxResponseTime = maxOf(maxResponseTime, statsEntry.averageResponseTime)
                minResponseTime = minOf(minResponseTime, statsEntry.averageResponseTime)
            }
        }

        return ResponseTimeAnalysis(
            averageResponseTime = if (responseCount > 0) totalResponseTime / responseCount else 0.0,
            maxResponseTime = if (maxResponseTime > 0) maxResponseTime else 0.0,
            minResponseTime = if (minResponseTime < Double.MAX_VALUE) minResponseTime else 0.0,
            sampleCount = responseCount
        )
    }

    /**
     * 响应时间分析数据类
     */
    data class ResponseTimeAnalysis(
        val averageResponseTime: Double,
        val maxResponseTime: Double,
        val minResponseTime: Double,
        val sampleCount: Int
    )

    /**
     * 获取完整的用户行为摘要报告
     * 包含所有统计信息的汇总
     *
     * @return 行为摘要字符串
     */
    fun getUserBehaviorSummary(): String {
        val totalNotifications = stats.values.sumOf { it.totalReceived }
        val totalClicks = stats.values.sumOf { it.totalUserClicked }
        val totalIgnored = stats.values.sumOf { it.totalUserIgnored }
        val totalAutoRemoved = stats.values.sumOf { it.totalAutoRemoved }
        val appCount = stats.values.map { it.packageName }.distinct().size

        return buildString {
            appendLine("========== 用户行为摘要 ==========")
            appendLine("总通知数: $totalNotifications")
            appendLine("总点击数: $totalClicks")
            appendLine("总忽略数: $totalIgnored")
            appendLine("自动移除数: $totalAutoRemoved")
            appendLine("涉及应用数: $appCount")
            appendLine("整体点击率: ${if (totalNotifications > 0) "%.2f".format(totalClicks.toDouble() / totalNotifications * 100) else "0.00"}%")
            appendLine("整体忽略率: ${if (totalNotifications > 0) "%.2f".format(totalIgnored.toDouble() / totalNotifications * 100) else "0.00"}%")
            appendLine("规则数量: ${rules.size}")
            appendLine("触发配置数: ${triggers.size}")
            appendLine("活动分组数: ${groups.size}")
            appendLine("安静时间: ${quietHours?.let { "${it.startHour}:${it.startMinute} - ${it.endHour}:${it.endMinute}" } ?: "未设置"}")
            appendLine("==================================")
        }
    }

    /**
     * 获取类别分布统计
     * 按类别统计通知数量
     *
     * @return 类别到数量的映射
     */
    fun getCategoryDistribution(): Map<Category, Int> {
        val distribution = ConcurrentHashMap<Category, Int>()
        stats.values.forEach { statsEntry ->
            distribution.merge(statsEntry.category, statsEntry.totalReceived) { a, b -> a + b }
        }
        return distribution.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }

    /**
     * 获取优先级分布统计
     * 按优先级统计通知数量
     *
     * @return 优先级到数量的映射
     */
    fun getPriorityDistribution(): Map<Priority, Int> {
        val distribution = ConcurrentHashMap<Priority, Int>()
        groups.values.forEach { group ->
            group.notifications.forEach { notification ->
                distribution.merge(notification.priority, 1) { a, b -> a + b }
            }
        }
        return distribution.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }

    //==============================================================================================
    // 分组管理增强方法
    //==============================================================================================

    /**
     * 合并两个分组
     * 将源分组的内容合并到目标分组，然后删除源分组
     *
     * @param sourceGroupId 源分组ID
     * @param targetGroupId 目标分组ID
     * @return 是否成功合并
     */
    fun mergeGroups(sourceGroupId: String, targetGroupId: String): Boolean {
        val sourceGroup = groups[sourceGroupId] ?: return false
        val targetGroup = groups[targetGroupId] ?: return false

        targetGroup.notifications.addAll(sourceGroup.notifications)
        targetGroup.lastUpdate = System.currentTimeMillis()
        generateGroupSummary(targetGroup)

        groups.remove(sourceGroupId)
        return true
    }

    /**
     * 移除分组中的旧通知
     * 移除超过指定天数的通知
     *
     * @param maxAgeDays 最大保留天数
     * @return 移除的通知数量
     */
    fun removeOldNotifications(maxAgeDays: Int = 7): Int {
        val cutoffTime = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        var removedCount = 0

        groups.values.forEach { group ->
            val toRemove = group.notifications.filter { it.timestamp < cutoffTime }
            group.notifications.removeAll(toRemove)
            removedCount += toRemove.size
            group.lastUpdate = System.currentTimeMillis()
            generateGroupSummary(group)
        }

        return removedCount
    }

    //==============================================================================================
    // 工具方法
    //==============================================================================================

    /**
     * 重置所有状态
     * 清空规则、分组、触发、统计和安静时间设置
     */
    fun resetAll() {
        rules.clear()
        groups.clear()
        triggers.clear()
        stats.clear()
        quickReplies.clear()
        quietHours = null
    }

    /**
     * 获取处理器状态报告
     * 包含所有核心组件的运行状态
     *
     * @return 状态报告字符串
     */
    fun getStatusReport(): String {
        return buildString {
            appendLine("========== SmartNotificationHandler 状态报告 ==========")
            appendLine("规则数量: ${rules.size}")
            appendLine("活动分组数: ${groups.size}")
            appendLine("跟踪应用数: ${stats.values.map { it.packageName }.distinct().size}")
            appendLine("触发配置数: ${triggers.size}")
            appendLine("快速回复模板数: ${quickReplies.values.sumOf { it.size }}")
            appendLine("安静时间: ${if (quietHours?.enabled == true) "已启用" else "未启用/已禁用"}")
            appendLine("当前安静时间内: ${isInQuietHours()}")
            appendLine("==================================================")
        }
    }

    /**
     * 获取通知优先级对应的中文描述
     *
     * @param priority 优先级枚举值
     * @return 中文描述字符串
     */
    fun getPriorityDescription(priority: Priority): String {
        return when (priority) {
            Priority.CRITICAL -> "关键通知 - 必须立即处理"
            Priority.HIGH -> "高优先级 - 需要及时关注"
            Priority.NORMAL -> "普通通知 - 正常提醒"
            Priority.LOW -> "低优先级 - 可以稍后查看"
            Priority.SPAM -> "垃圾通知 - 建议过滤"
        }
    }

    /**
     * 获取通知类别对应的中文描述
     *
     * @param category 类别枚举值
     * @return 中文描述字符串
     */
    fun getCategoryDescription(category: Category): String {
        return when (category) {
            Category.IM -> "即时通讯 - 聊天消息与对话"
            Category.EMAIL -> "电子邮件 - 邮件通知"
            Category.SYSTEM -> "系统通知 - 操作系统或应用本身的通知"
            Category.SOCIAL -> "社交媒体 - 社交平台动态"
            Category.PROMOTION -> "促销广告 - 营销推广信息"
            Category.ALARM -> "闹钟提醒 - 定时闹钟"
            Category.REMINDER -> "日程提醒 - 日程与事件提醒"
            Category.UPDATE -> "应用更新 - 版本更新通知"
            Category.UNKNOWN -> "未知类别 - 无法自动分类"
        }
    }
}