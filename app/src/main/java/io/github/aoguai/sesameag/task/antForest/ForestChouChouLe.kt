package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.task.TaskStatus
import io.github.aoguai.sesameag.task.common.TaskFlowAction
import io.github.aoguai.sesameag.task.common.TaskFlowActionResult
import io.github.aoguai.sesameag.task.common.TaskFlowAdapter
import io.github.aoguai.sesameag.task.common.TaskFlowEngine
import io.github.aoguai.sesameag.task.common.TaskFlowItem
import io.github.aoguai.sesameag.task.common.TaskFlowPhase
import io.github.aoguai.sesameag.task.common.TaskFlowSnapshot
import io.github.aoguai.sesameag.task.common.TaskRpcFailureType
import io.github.aoguai.sesameag.util.GlobalThreadPools.sleepCompat
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.maps.UserMap
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 森林寻宝任务处理类 (每天自动执行, 完成后标记)
 */
class ForestChouChouLe {
    companion object {
        private const val TAG = "ForestChouChouLe"
        private const val SOURCE = "task_entry"
        private const val FOREST_BLACKLIST_MODULE = "蚂蚁森林"

        // 场景代码常量
        private const val SCENE_NORMAL = "ANTFOREST_NORMAL_DRAW"
        private const val SCENE_ACTIVITY = "ANTFOREST_ACTIVITY_DRAW"
        private const val DEFAULT_NORMAL_ACTIVITY_ID = "2026051801"
        private const val DEFAULT_ACTIVITY_DRAW_ACTIVITY_ID = "20260607"
        private const val TASK_AWARD_ALREADY_FINISHED_CODE = "400000030"
        private const val TASK_ALREADY_FINISHED_CODE = "2600000016"
        private const val TASK_RIGHTS_LIMIT_CODE = "400000012"
        private const val RPC_UNSUPPORTED_CODE = "400000040"

        /**
         * 抽奖场景数据类
         */
        private data class Scene(
            val id: String,
            val code: String,
            val name: String,
            val flag: String,
        ) {
            val taskCode get() = "${code}_TASK"
        }

        // 扩展函数：简化 JSON 解析和检查
        private fun String.toJson(): JSONObject? = runCatching { JSONObject(this) }.getOrNull()

        private fun JSONObject.check(): Boolean = ResChecker.checkRes(TAG, this)

        private fun JSONObject.taskResultCode(): String = optString("code").ifBlank { optString("resultCode") }

        private fun JSONObject.taskResultDesc(): String =
            sequenceOf(
                optString("desc"),
                optString("resultDesc"),
                optString("resultMessage"),
                optString("memo"),
            ).firstOrNull { it.isNotBlank() }.orEmpty()

        private fun JSONObject.isTaskAwardAlreadyFinished(): Boolean =
            taskResultCode() == TASK_AWARD_ALREADY_FINISHED_CODE || taskResultDesc().contains("任务已完结")

        private fun JSONObject.isTaskAlreadyFinished(): Boolean =
            taskResultCode() == TASK_ALREADY_FINISHED_CODE ||
                taskResultDesc().contains("任务已完成") || taskResultDesc().contains("任务已完结")

        private fun JSONObject.isTaskRightsLimitReached(): Boolean =
            taskResultCode() == TASK_RIGHTS_LIMIT_CODE || taskResultDesc().contains("权益获取次数超过上限")

        private fun JSONObject.isRpcUnsupported(): Boolean =
            taskResultCode() == RPC_UNSUPPORTED_CODE || taskResultDesc().contains("不支持rpc调用", ignoreCase = true)

        private fun extractTaskName(
            bizInfo: JSONObject,
            fallback: String,
        ): String =
            bizInfo
                .optString("title")
                .ifBlank { bizInfo.optString("taskTitle") }
                .ifBlank { bizInfo.optString("taskContent") }
                .ifBlank { fallback }

        // 动态获取抽奖场景配置
        private fun getScenes(): List<Scene> {
            val defaultScenes =
                listOf(
                    Scene(DEFAULT_NORMAL_ACTIVITY_ID, SCENE_NORMAL, "森林抽抽乐普通版", StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_NORMAL_COMPLETED),
                    Scene(
                        DEFAULT_ACTIVITY_DRAW_ACTIVITY_ID,
                        SCENE_ACTIVITY,
                        "森林抽抽乐活动版",
                        StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_ACTIVITY_COMPLETED,
                    ),
                )

            return runCatching {
                val scenes = mutableListOf<Scene>()
                // 使用普通场景查询
                val response =
                    AntForestRpcCall.enterDrawActivityopengreen("", SCENE_NORMAL, SOURCE).toJson() ?: return@runCatching defaultScenes

                if (response.optBoolean("success", false)) {
                    val drawSceneGroups = response.optJSONArray("drawSceneGroups") ?: return@runCatching defaultScenes

                    for (i in 0 until drawSceneGroups.length()) {
                        val sceneGroup = drawSceneGroups.optJSONObject(i) ?: continue
                        val drawActivity = sceneGroup.optJSONObject("drawActivity") ?: continue

                        val sceneCode = drawActivity.optString("sceneCode")
                        if (sceneCode.isBlank()) {
                            continue
                        }
                        val activityId =
                            drawActivity
                                .optString("activityId")
                                .ifBlank { fallbackActivityId(sceneCode) }
                        if (activityId.isBlank()) {
                            continue
                        }
                        val name = sceneGroup.optString("name", "未知活动")

                        val flag =
                            when (sceneCode) {
                                SCENE_NORMAL -> {
                                    StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_NORMAL_COMPLETED
                                }

                                SCENE_ACTIVITY -> {
                                    StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_ACTIVITY_COMPLETED
                                }

                                else -> {
                                    StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_COMPLETED_PREFIX +
                                        sceneCode.lowercase(Locale.getDefault()) +
                                        StatusFlags.FLAG_ANTFOREST_CHOUCHOULE_COMPLETED_SUFFIX
                                }
                            }
                        scenes.add(Scene(activityId, sceneCode, name, flag))
                    }
                }
                mergeDefaultScenes(scenes, defaultScenes)
            }.getOrElse {
                Log.printStackTrace(TAG, "获取抽奖场景配置失败, 使用默认配置", it)
                defaultScenes
            }
        }

        private fun mergeDefaultScenes(
            scenes: List<Scene>,
            defaultScenes: List<Scene>,
        ): List<Scene> {
            if (scenes.isEmpty()) {
                return defaultScenes
            }
            val merged = linkedMapOf<String, Scene>()
            for (scene in defaultScenes) {
                merged[scene.code] = scene
            }
            for (scene in scenes) {
                merged[scene.code] = scene
            }
            return merged.values.toList()
        }

        private fun fallbackActivityId(sceneCode: String): String =
            when (sceneCode) {
                SCENE_NORMAL -> DEFAULT_NORMAL_ACTIVITY_ID
                SCENE_ACTIVITY -> DEFAULT_ACTIVITY_DRAW_ACTIVITY_ID
                else -> ""
            }

        private fun normalizeTaskInfoList(response: JSONObject): JSONObject {
            if (response.optJSONArray("taskInfoList") != null) {
                return response
            }
            val taskGroupList = response.optJSONArray("taskGroupInfoList") ?: return response
            val flattened = JSONArray()
            for (i in 0 until taskGroupList.length()) {
                val group = taskGroupList.optJSONObject(i) ?: continue
                val groupTaskList = group.optJSONArray("taskInfoList") ?: continue
                for (j in 0 until groupTaskList.length()) {
                    groupTaskList.optJSONObject(j)?.let { flattened.put(it) }
                }
            }
            if (flattened.length() > 0) {
                response.put("taskInfoList", flattened)
            }
            return response
        }
    }

    fun chouChouLe() {
        runCatching {
            val scenes = getScenes()
            Log.forest("开始处理森林寻宝, 共 ${scenes.size} 个场景")
            scenes.forEach {
                processScene(it)
                sleepCompat(100L)
            }
        }.onFailure { Log.printStackTrace(TAG, "执行异常", it) }
    }

    private fun processScene(s: Scene) =
        runCatching {
            if (Status.hasFlagToday(s.flag)) {
                if (!hasActionableTaskAfterCompletionFlag(s)) {
                    Log.forest("⏭️ ${s.name} 今天已完成, 跳过")
                    return@runCatching
                }
                Status.removeFlag(s.flag)
            }

            Log.forest("👉 开始处理: ${s.name}")

            // 1. 检查活动有效期
            val enterResp = AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE).toJson()
            if (enterResp == null || !enterResp.check()) return@runCatching

            val drawActivity = enterResp.optJSONObject("drawActivity")
            if (drawActivity != null) {
                val now = System.currentTimeMillis()
                val startTime = drawActivity.optLong("startTime")
                val endTime = drawActivity.optLong("endTime")
                if (now !in startTime..endTime) {
                    Log.forest("⛔ ${s.name} 活动不在有效期内, 跳过")
                    return@runCatching
                }
            }

            // 2. 查询、完成与领奖统一交给公共任务闭环处理。
            TaskFlowEngine(ChouChouLeTaskFlowAdapter(s), roundSleepMs = 100L).run()

            // 3. 执行抽奖
            processLottery(s)
        }.onFailure { Log.printStackTrace(TAG, "${s.name} 处理异常", it) }

    private fun fetchFreshTaskList(s: Scene): JSONObject? {
        val legacyResp =
            AntForestRpcCall
                .listTaskopengreen(s.taskCode, SOURCE)
                .toJson()
                ?.let { normalizeTaskInfoList(it) } ?: return null
        val taskTypes = extractTaskTypes(legacyResp.optJSONArray("taskInfoList"))
        if (taskTypes.isEmpty()) {
            return legacyResp
        }
        val byIdsResp =
            AntForestRpcCall
                .listTaskByIdsopengreen(s.taskCode, SOURCE, taskTypes)
                .toJson()
                ?.let { normalizeTaskInfoList(it) }
        if (byIdsResp != null && byIdsResp.check() && byIdsResp.optJSONArray("taskInfoList") != null) {
            return byIdsResp
        }
        return legacyResp
    }

    private fun extractTaskTypes(taskList: JSONArray?): List<String> {
        if (taskList == null) return emptyList()
        val taskTypes = linkedSetOf<String>()
        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            val taskType = task.optJSONObject("taskBaseInfo")?.optString("taskType").orEmpty()
            if (taskType.isNotBlank()) {
                taskTypes.add(taskType)
            }
        }
        return taskTypes.toList()
    }

    private fun hasActionableTaskAfterCompletionFlag(s: Scene): Boolean {
        val resp = fetchFreshTaskList(s) ?: return false
        if (!resp.check()) return false

        val taskList = resp.optJSONArray("taskInfoList") ?: return false
        for (i in 0 until taskList.length()) {
            val task = taskList.optJSONObject(i) ?: continue
            val baseInfo = task.optJSONObject("taskBaseInfo") ?: continue
            val taskType = baseInfo.optString("taskType")
            val taskStatus = baseInfo.optString("taskStatus")
            if (taskStatus != TaskStatus.TODO.name &&
                taskStatus != TaskStatus.FINISHED.name &&
                taskStatus != "COMPLETE"
            ) {
                continue
            }
            val bizInfo = baseInfo.optString("bizInfo").toJson() ?: JSONObject()
            val taskName = extractTaskName(bizInfo, taskType.ifBlank { "未知任务" })
            if (!isBlockedTask(taskType, taskName)) {
                Log.forest("${s.name} 已有完成标记但发现待处理任务: $taskName [$taskStatus]")
                return true
            }
        }
        return false
    }

    /**
     * 执行抽奖逻辑
     */
    private fun processLottery(s: Scene) {
        val currentUid = UserMap.currentUid ?: return
        val enterResp = AntForestRpcCall.enterDrawActivityopengreen(s.id, s.code, SOURCE).toJson() ?: return
        if (!enterResp.check()) return

        val drawAsset = enterResp.optJSONObject("drawAsset") ?: return
        var balance = drawAsset.optInt("blance", 0)
        val total = drawAsset.optInt("totalTimes", 0)

        Log.forest("${s.name} 剩余抽奖次数: $balance / $total")

        var retry = 0
        // 最多抽50次，防止死循环
        while (balance > 0 && retry < 50) {
            retry++
            Log.forest("${s.name} 第 $retry 次抽奖")

            val drawResp = AntForestRpcCall.drawopengreen(s.id, s.code, SOURCE, currentUid).toJson()
            if (drawResp == null || !drawResp.check()) {
                break
            }

            balance = drawResp.optJSONObject("drawAsset")?.optInt("blance", 0) ?: 0
            val prize = drawResp.optJSONObject("prizeVO")
            if (prize != null) {
                val name = prize.optString("prizeName", "未知奖品")
                val num = prize.optInt("prizeNum", 1)
                Log.forest("${s.name} 🎁 [获得: $name * $num] 剩余次数: $balance")
            }

            if (balance > 0) sleepCompat(100L)
        }
    }

    /**
     * 寻宝仍保留场景发现和抽奖，但任务推进统一复用公共闭环。
     * 只依据服务端的结构化状态、类型和 RPC 响应分类，不根据标题或跳转地址预判。
     */
    private inner class ChouChouLeTaskFlowAdapter(
        private val scene: Scene,
    ) : TaskFlowAdapter {
        override val moduleName: String = FOREST_BLACKLIST_MODULE
        override val flowName: String = scene.name

        override fun query(): JSONObject = fetchFreshTaskList(scene) ?: JSONObject().put("success", false).put("message", "任务列表返回空")

        override fun isQuerySuccess(response: JSONObject): Boolean = response.check()

        override fun extractItems(response: JSONObject): List<TaskFlowItem> {
            val taskList = response.optJSONArray("taskInfoList") ?: return emptyList()
            val items = mutableListOf<TaskFlowItem>()
            for (index in 0 until taskList.length()) {
                val taskInfo = taskList.optJSONObject(index) ?: continue
                val taskBaseInfo = taskInfo.optJSONObject("taskBaseInfo") ?: continue
                val taskType = taskBaseInfo.optString("taskType")
                val sceneCode = taskBaseInfo.optString("sceneCode")
                if (taskType.isBlank() || sceneCode.isBlank()) {
                    continue
                }
                val bizInfo = taskBaseInfo.optString("bizInfo").toJson() ?: JSONObject()
                val title = extractTaskName(bizInfo, taskType)
                items.add(
                    TaskFlowItem(
                        id = taskType,
                        title = title,
                        status = taskBaseInfo.optString("taskStatus"),
                        type = taskType,
                        sceneCode = sceneCode,
                        blacklistKeys = listOf(taskType, title).filter { it.isNotBlank() },
                        raw =
                            JSONObject()
                                .put("taskInfo", taskInfo)
                                .put("taskBaseInfo", taskBaseInfo)
                                .put("bizInfo", bizInfo),
                    ),
                )
            }
            return items
        }

        override fun mapPhase(item: TaskFlowItem): TaskFlowPhase =
            when (item.status) {
                TaskStatus.FINISHED.name, "COMPLETE" -> TaskFlowPhase.REWARD_READY
                TaskStatus.TODO.name -> TaskFlowPhase.READY_TO_COMPLETE
                TaskStatus.RECEIVED.name, "HAS_RECEIVED", "DONE", "COMPLETED" -> TaskFlowPhase.TERMINAL
                else -> TaskFlowPhase.UNKNOWN
            }

        override fun complete(item: TaskFlowItem): TaskFlowActionResult {
            val taskBaseInfo =
                item.raw?.optJSONObject("taskBaseInfo")
                    ?: return missingTaskData(item, "complete")
            val response =
                if (item.type == "NORMAL_DRAW_EXCHANGE_VITALITY") {
                    AntForestRpcCall
                        .exchangeTimesFromTaskopengreen(
                            scene.id,
                            scene.code,
                            SOURCE,
                            item.sceneCode,
                            item.type,
                        ).toJson()
                } else {
                    val source = taskBaseInfo.optString("source").ifBlank { SOURCE }
                    AntForestRpcCall.finishTaskopengreen(item.type, item.sceneCode, source).toJson()
                }
            return handleActionResponse(
                item = item,
                response = response,
                action = TaskFlowAction.COMPLETE,
                rpc =
                    if (item.type == "NORMAL_DRAW_EXCHANGE_VITALITY") {
                        "AntForestRpcCall.exchangeTimesFromTaskopengreen"
                    } else {
                        "AntForestRpcCall.finishTaskopengreen"
                    },
            )
        }

        override fun receive(item: TaskFlowItem): TaskFlowActionResult {
            val taskBaseInfo =
                item.raw?.optJSONObject("taskBaseInfo")
                    ?: return missingTaskData(item, "receive")
            val rawTask =
                JSONObject(taskBaseInfo.toString()).apply {
                    if (optString("sceneCode").isBlank()) put("sceneCode", item.sceneCode)
                    if (optString("source").isBlank()) put("source", SOURCE)
                    if (optString("taskType").isBlank()) put("taskType", item.type)
                }
            return handleActionResponse(
                item = item,
                response = AntForestRpcCall.receiveTaskAwardopengreen(rawTask).toJson(),
                action = TaskFlowAction.RECEIVE,
                rpc = "AntForestRpcCall.receiveTaskAwardopengreen",
            )
        }

        override fun actionKey(
            item: TaskFlowItem,
            action: TaskFlowAction,
        ): String = "${action.logName}:${item.sceneCode}#${item.type}"

        override fun afterSuccess(
            item: TaskFlowItem,
            action: TaskFlowAction,
            result: TaskFlowActionResult,
        ) {
            if (action == TaskFlowAction.RECEIVE) {
                syncDrawAssetAfterTaskAward(scene)
            }
        }

        override fun onAllTasksDone(snapshot: TaskFlowSnapshot) {
            Status.setFlagToday(scene.flag)
            val summary = if (snapshot.totalTasks == 0) "无有效任务" else "全部完成"
            Log.forest("✅ ${scene.name} $summary (${snapshot.completedTasks}/${snapshot.totalTasks})")
        }

        override fun onQueryFailed(response: JSONObject) {
            Log.error(TAG, "${scene.name} 任务列表查询失败 raw=$response")
        }

        override fun logInfo(message: String) {
            Log.forest(message)
        }

        override fun logError(message: String) {
            Log.error(TAG, message)
        }

        private fun handleActionResponse(
            item: TaskFlowItem,
            response: JSONObject?,
            action: TaskFlowAction,
            rpc: String,
        ): TaskFlowActionResult {
            if (response == null) {
                return TaskFlowActionResult.failure(
                    failureType = TaskRpcFailureType.RETRYABLE_RPC,
                    code = "EMPTY_RESPONSE",
                    message = "${action.logName}返回空",
                    rpc = rpc,
                    detail = actionDetail(item, action),
                )
            }
            when {
                response.isTaskAwardAlreadyFinished() || response.isTaskAlreadyFinished() -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.TERMINAL_DONE,
                        code = response.taskResultCode(),
                        message = response.taskResultDesc(),
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }

                response.isTaskRightsLimitReached() -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.BUSINESS_LIMIT,
                        code = response.taskResultCode(),
                        message = response.taskResultDesc(),
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }

                response.isRpcUnsupported() -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNSUPPORTED_NO_CLOSURE,
                        code = response.taskResultCode(),
                        message = response.taskResultDesc(),
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }

                response.check() -> {
                    val actionName = if (action == TaskFlowAction.RECEIVE) "奖励领取成功" else "任务已提交"
                    Log.forest("${scene.name} $actionName: ${item.title}")
                    return TaskFlowActionResult.success()
                }

                (response.has("retriable") && !response.optBoolean("retriable")) ||
                    (response.has("retryable") && !response.optBoolean("retryable")) -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.NON_RETRYABLE_INVALID,
                        code = response.taskResultCode(),
                        message = response.taskResultDesc(),
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }

                response.optBoolean("retriable") || response.optBoolean("retryable") -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.RETRYABLE_RPC,
                        code = response.taskResultCode(),
                        message = response.taskResultDesc(),
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }

                else -> {
                    return TaskFlowActionResult.failure(
                        failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                        code = response.taskResultCode(),
                        message = response.taskResultDesc(),
                        rpc = rpc,
                        raw = response.toString(),
                        detail = actionDetail(item, action),
                    )
                }
            }
        }

        private fun missingTaskData(
            item: TaskFlowItem,
            action: String,
        ): TaskFlowActionResult =
            TaskFlowActionResult.failure(
                failureType = TaskRpcFailureType.UNKNOWN_NEEDS_REVIEW,
                message = "缺少寻宝任务原始数据",
                rpc = "ChouChouLeTaskFlowAdapter.$action",
                detail = actionDetail(item, null),
            )

        private fun actionDetail(
            item: TaskFlowItem,
            action: TaskFlowAction?,
        ): String = "sceneCode=${item.sceneCode} taskType=${item.type} taskName=${item.title} action=${action?.logName.orEmpty()}"
    }

    /**
     * 判断任务是否在屏蔽列表中
     */
    private fun isBlockedTask(
        taskType: String,
        taskName: String,
    ): Boolean =
        TaskBlacklist.isTaskInBlacklist(FOREST_BLACKLIST_MODULE, taskType) ||
            TaskBlacklist.isTaskInBlacklist(FOREST_BLACKLIST_MODULE, taskName)

    private fun syncDrawAssetAfterTaskAward(s: Scene) {
        runCatching {
            val res = AntForestRpcCall.drawSyncopengreen(s.id, s.code, "taskaward").toJson()
            if (res != null && res.check()) {
                val balance = res.optJSONObject("drawAsset")?.optInt("blance", 0) ?: 0
                Log.forest("${s.name} 奖励后刷新抽奖次数: $balance")
            }
        }.onFailure {
            Log.printStackTrace(TAG, "${s.name} 奖励后刷新抽奖次数失败", it)
        }
    }
}
