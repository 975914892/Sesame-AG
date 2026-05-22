package io.github.aoguai.sesameag.task.antSesameCredit

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.Status.Companion.hasFlagToday
import io.github.aoguai.sesameag.data.Status.Companion.setFlagToday
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.SesameGift
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.internal.LocationHelper.requestLocationSuspend
import io.github.aoguai.sesameag.hook.internal.SecurityBodyHelper.getSecurityBodyData
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.antOrchard.AntOrchardRpcCall.orchardSpreadManure
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.TaskBlacklist.autoAddToBlacklist
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.SesameGiftMap
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Objects
import java.util.regex.Pattern
import kotlin.math.max

class AntSesameCredit : ModelTask() {
    override fun getName(): String = "芝麻信用"

    override fun getGroup(): ModelGroup = ModelGroup.SESAME_CREDIT

    override fun getIcon(): String = "AntMember.png"

    internal var collectSesame: BooleanModelField? = null
    internal var collectSesameWithOneClick: BooleanModelField? = null
    internal var sesameTask: BooleanModelField? = null
    internal var sesameAlchemy: BooleanModelField? = null
    internal var enableZhimaTree: BooleanModelField? = null
    internal var sesameGrainExchange: BooleanModelField? = null
    private var sesameGrainExchangeList: SelectModelField? = null

    private val sesameTaskRefreshRoundLimit = 8
    private val sesameAlchemyTaskBlacklistModule = "芝麻炼金"

    private data class SesameFeedbackItem(
        val title: String,
        val creditFeedbackId: String,
        val potentialSize: String
    )

    private data class SesameTaskBatchResult(
        val completedCount: Int = 0,
        val skippedCount: Int = 0,
        val interrupted: Boolean = false
    )

    internal data class SesameTaskRunSummary(
        val finishedAllRounds: Boolean = false,
        val completedCount: Int = 0,
        val skippedCount: Int = 0,
        val interrupted: Boolean = false
    )

    private data class ZhimaTreeTaskRef(
        val title: String,
        val prizeName: String,
        val status: String,
        val taskId: String?,
        val taskIdCandidates: List<String>,
        val needManuallyReceiveAward: Boolean
    ) {
        fun describeCandidates(): String {
            if (taskIdCandidates.isEmpty()) {
                return "<empty>"
            }
            return taskIdCandidates.joinToString(" | ") { it.ifBlank { "<blank>" } }
        }
    }

    private data class ZhimaTreeAdTaskRef(
        val title: String,
        val rewardText: String,
        val bizId: String,
        val spaceCode: String?
    )

    private data class ZhimaTreeActionResult(
        val success: Boolean,
        val response: JSONObject?,
        val rawResponse: String?
    )

    private data class ZhimaTreeTaskRefreshResult(
        val tasks: List<ZhimaTreeTaskRef>,
        val queriedSourceCount: Int
    ) {
        val hasConfirmedSnapshot: Boolean
            get() = queriedSourceCount > 0
    }

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()

        modelFields.addField(
            BooleanModelField(
                "sesameTask", "芝麻信用 | 信用任务", false
            ).withDesc("执行芝麻信用的涨分进度与芝麻粒相关每日任务。").also { sesameTask = it })
        modelFields.addField(BooleanModelField("collectSesame", "芝麻粒 | 领取", false).withDesc(
            "领取芝麻粒、阶段奖励和其他可收取的芝麻相关奖励。"
        ).also {
            collectSesame = it
        })
        modelFields.addField(
            BooleanModelField(
                "collectSesameWithOneClick", "芝麻粒 | 一键收取", false
            ).withDesc("开启后优先走一键收取接口领取芝麻粒。需开启“芝麻粒 | 领取”。").also { collectSesameWithOneClick = it })
        modelFields.addField(
            BooleanModelField(
                "sesameGrainExchange", "芝麻粒 | 兑换道具", false
            ).withDesc("使用芝麻粒兑换已勾选的道具，适合长期清理库存。").also { sesameGrainExchange = it })

        // 使用 SesameGiftMap 来存储和回显商品名称
        modelFields.addField(
            SelectModelField(
                "sesameGrainExchangeList",
                "芝麻粒 | 兑换列表",
                LinkedHashSet<String?>()
            ) {
                SesameGift.getList()
            }.withDesc("勾选允许自动兑换的芝麻粒商品，需开启“芝麻粒 | 兑换道具”。").also { sesameGrainExchangeList = it })
        // 芝麻炼金
        modelFields.addField(
            BooleanModelField(
                "sesameAlchemy", "芝麻炼金 | 开启", false
            ).withDesc("执行芝麻粒炼金的签到、任务和时段奖励领取。").also { sesameAlchemy = it })
        // 芝麻树
        modelFields.addField(BooleanModelField("enableZhimaTree", "芝麻树 | 开启", false).withDesc(
            "执行芝麻树相关签到、任务和奖励领取。"
        ).also {
            enableZhimaTree = it
        })
        return modelFields
    }

    override fun runJava() {
        runBlocking {
            try {
                Log.sesame("执行开始-${getName()}")
                requestLocationSuspend()

                val deferredTasks = mutableListOf<Deferred<Unit>>()
                val sesamePlan = prepareSesameWorkflows(this, deferredTasks)
                deferredTasks.awaitAll()
                finishSesameWorkflows(sesamePlan)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
            } finally {
                Log.sesame("执行结束-${getName()}")
            }
        }
    }

    internal fun handleGrowthGuideTasks() {
        try {
            if (ApplicationHookConstants.isOffline()) {
                Log.sesame("信誉任务领取因离线模式跳过，保留后续重试机会")
                return
            }
            Log.sesame("开始执行信誉任务领取")
            var resp: String?
            try {
                resp = AntSesameCreditRpcCall.Zmxy.queryGrowthGuideToDoList()
            } catch (e: Throwable) {
                Log.printStackTrace("$TAG.handleGrowthGuideTasks.queryGrowthGuideToDoList", e)
                return
            }

            if (resp.isNullOrEmpty()) {
                Log.sesame("信誉任务列表返回空")
                return
            }

            val root: JSONObject?
            try {
                root = JSONObject(resp)
            } catch (e: Throwable) {
                Log.printStackTrace("$TAG.handleGrowthGuideTasks.parseRootJson", e)
                return
            }

            if (!ResChecker.checkRes(TAG, root)) {
                Log.sesame("信誉任务列表获取失败: " + root.optString("resultView", resp)
                )
                return
            }
            // 成长引导列表（不会用，只做计数）
            val growthGuideList = root.optJSONArray("growthGuideList")
            growthGuideList?.length() ?: 0

            // 待处理任务列表
            val toDoList = root.optJSONArray("toDoList")
            val toDoCount = toDoList?.length() ?: 0
            if (toDoList == null || toDoCount == 0) {
                return
            }

            for (i in 0..<toDoList.length()) {
                var task: JSONObject? = null
                try {
                    task = toDoList.optJSONObject(i)
                } catch (_: Throwable) {
                }

                if (task == null) continue

                val behaviorId = task.optString("behaviorId", "")
                val title = task.optString("title", "")
                val status = task.optString("status", "")
                val subTitle = task.optString("subTitle", "")

                // ===== 2.1 公益类任务 =====
                if ("wait_receive" == status) {
                    val openResp: String?
                    try {
                        openResp = AntSesameCreditRpcCall.Zmxy.openBehaviorCollect(behaviorId)
                    } catch (e: Throwable) {
                        Log.printStackTrace("$TAG.handleGrowthGuideTasks.openBehaviorCollect", e)
                        continue
                    }

                    try {
                        val openJo = JSONObject(openResp)
                        if (ResChecker.checkRes(TAG, openJo)) {
                            Log.sesame("信誉任务[领取成功] $title")
                        } else {
                            Log.sesame(("信誉任务[领取失败] behaviorId=" + behaviorId + " title=" + title + " resp=" + openResp)
                            )
                        }
                    } catch (e: Throwable) {
                        Log.printStackTrace(
                            "$TAG.handleGrowthGuideTasks.parseOpenBehaviorCollect", e
                        )
                    }
                    continue
                }

                // ===== 2.2 每日问答 =====
                if ("meiriwenda" == behaviorId && "wait_doing" == status) { //如果等待去做才执行，一般不会进入下面的今日已参与判断

                    if (subTitle.contains("今日已参与")) {
                        Log.sesame("信誉任务[每日问答] $subTitle（跳过答题）")
                        continue
                    }

                    try {
                        // ① 查询题目
                        val quizResp = AntSesameCreditRpcCall.Zmxy.queryDailyQuiz(behaviorId)
                        val quizJo: JSONObject?
                        try {
                            quizJo = JSONObject(quizResp)
                        } catch (e: Throwable) {
                            Log.printStackTrace(
                                "$TAG.handleGrowthGuideTasks.parseDailyQuiz 每日问答[解析失败]$quizResp", e
                            )
                            continue
                        }

                        if (!ResChecker.checkRes(TAG, quizJo)) {
                            continue
                        }

                        val data = quizJo.optJSONObject("data")
                        if (data == null) {
                            Log.error("$TAG.handleGrowthGuideTasks", "每日问答[返回缺少data]")
                            continue
                        }

                        val qVo = data.optJSONObject("questionVo")
                        if (qVo == null) {
                            Log.error("$TAG.handleGrowthGuideTasks", "每日问答[缺少questionVo]")
                            continue
                        }

                        val rightAnswer = qVo.optJSONObject("rightAnswer")
                        if (rightAnswer == null) {
                            Log.error("$TAG.handleGrowthGuideTasks", "每日问答[缺少rightAnswer]")
                            continue
                        }

                        val bizDate = data.optLong("bizDate", 0L)
                        val questionId = qVo.optString("questionId", "")
                        val questionContent = qVo.optString("questionContent", "")
                        val answerId = rightAnswer.optString("answerId", "")
                        val answerContent = rightAnswer.optString("answerContent", "")

                        if (bizDate <= 0 || questionId.isEmpty() || answerId.isEmpty()) {
                            Log.error("$TAG.handleGrowthGuideTasks", "每日问答[关键字段缺失]")
                            continue
                        }

                        // ② 提交答案
                        val pushResp = AntSesameCreditRpcCall.Zmxy.pushDailyTask(
                            behaviorId, bizDate, answerId, questionId, "RIGHT"
                        )

                        val pushJo: JSONObject?
                        try {
                            pushJo = JSONObject(pushResp)
                        } catch (e: Throwable) {
                            Log.printStackTrace(
                                "$TAG.handleGrowthGuideTasks.parsePushDailyTask 每日问答[提交解析失败]$quizResp", e
                            )
                            continue
                        }

                        if (ResChecker.checkRes(TAG, pushJo)) {
                            Log.sesame(("信誉任务[每日答题成功] " + questionContent + " | 答案=" + answerContent + "(" + answerId + ")" + (if (subTitle.isEmpty()) "" else " | $subTitle"))
                            )
                        } else {
                            Log.error(
                                "$TAG.handleGrowthGuideTasks", "每日问答[提交失败] resp=$pushResp"
                            )
                        }
                    } catch (e: Throwable) {
                        Log.printStackTrace("$TAG.handleGrowthGuideTasks.meiriwenda", e)
                    }
                }

                // ===== 2.3 视频问答 =====
                if ("shipingwenda" == behaviorId && "wait_doing" == status) {
                    val bizDate = System.currentTimeMillis()
                    val questionId = "question3"
                    val answerId = "A"
                    val answerType = "RIGHT"

                    val pushResp = AntSesameCreditRpcCall.Zmxy.pushDailyTask(
                        behaviorId, bizDate, answerId, questionId, answerType
                    )

                    val jo: JSONObject?
                    try {
                        jo = JSONObject(pushResp)
                    } catch (e: Throwable) {
                        Log.printStackTrace(
                            "$TAG.handleGrowthGuideTasks.parsePushDailyTask 视频问答[提交解析失败]$pushResp", e
                        )
                        continue  // 改为continue，避免return影响循环
                    }

                    if (ResChecker.checkRes(TAG, jo)) {
                        Log.sesame("信誉任务[视频问答提交成功] → ")
                    } else {
                        Log.error("$TAG.handleGrowthGuideTasks", "视频问答[提交失败] → $pushResp")
                    }
                }

                // ===== 2.4 芭芭农场施肥 =====
                if ("babanongchang_7d" == behaviorId && "wait_doing" == status) {
                    try {
                        // 假设getWua()方法存在，返回wua（为空即可）
                        val wua = getSecurityBodyData(4) // 传入空字符串
                        val source = "DNHZ_NC_zhimajingnangSF" // 从buttonUrl提取的source
                        Log.debug(TAG, "信誉任务[芭芭农场施肥] set Wua $wua")

                        val spreadManureDataStr = orchardSpreadManure(
                            Objects.requireNonNull(wua).toString(), source, false
                        )
                        val spreadManureData: JSONObject?
                        try {
                            spreadManureData = JSONObject(spreadManureDataStr)
                        } catch (e: Throwable) {
                            Log.printStackTrace(
                                "$TAG.handleGrowthGuideTasks.parsePushDailyTask 芭芭农场[提交解析失败]$spreadManureDataStr", e
                            )
                            continue
                        }

                        if ("100" != spreadManureData.optString("resultCode")) {
                            Log.sesame("农场 orchardSpreadManure 错误：" + spreadManureData.optString("resultDesc")
                            )
                            continue
                        }

                        val taobaoDataStr = spreadManureData.optString("taobaoData", "")
                        if (taobaoDataStr.isEmpty()) {
                            Log.error("$TAG.handleGrowthGuideTasks", "芭芭农场[缺少taobaoData]")
                            continue
                        }

                        val spreadTaobaoData: JSONObject?
                        try {
                            spreadTaobaoData = JSONObject(taobaoDataStr)
                        } catch (e: Throwable) {
                            Log.printStackTrace(
                                "$TAG.handleGrowthGuideTasks.parsePushDailyTask 芭芭农场[taobaoData解析失败]$taobaoDataStr", e
                            )
                            continue
                        }

                        val currentStage = spreadTaobaoData.optJSONObject("currentStage")
                        if (currentStage == null) {
                            Log.error("$TAG.handleGrowthGuideTasks", "芭芭农场[缺少currentStage]")
                            continue
                        }

                        val stageText = currentStage.optString("stageText", "")
                        val statistics = spreadTaobaoData.optJSONObject("statistics")
                        val dailyAppWateringCount = statistics?.optInt("dailyAppWateringCount", 0) ?: 0

                        Log.sesame("今日农场已施肥💩 $dailyAppWateringCount 次 [$stageText]")

                        Log.sesame("信誉任务[芭芭农场施肥成功] $title | 已施肥 $dailyAppWateringCount 次"
                        )
                    } catch (e: Throwable) {
                        Log.printStackTrace("$TAG.handleGrowthGuideTasks.babanongchang", e)
                    }
                }
            }
        } catch (e: Throwable) {
            Log.printStackTrace("$TAG.handleGrowthGuideTasks.Fatal", e)
        }
    }

    /**
     * 芝麻信用任务
     */
    internal suspend fun doAllAvailableSesameTask(): SesameTaskRunSummary = CoroutineUtils.run {
        var overallCompletedTasks = 0
        var overallSkippedTasks = 0
        try {
            var round = 0
            var finishedAllRounds = false
            var interrupted = false
            val transientSkippedTasks = linkedSetOf<String>()
            while (round < sesameTaskRefreshRoundLimit) {
                round++
                val s = AntSesameCreditRpcCall.queryAvailableSesameTask()
                var jo = JSONObject(s)
                if (jo.has("resData")) {
                    jo = jo.getJSONObject("resData")
                }
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.error(
                        "$TAG.doAllAvailableSesameTask.queryAvailableSesameTask",
                        "芝麻信用💳[查询任务响应失败]#$s"
                    )
                    val interrupted = true
                    return@run SesameTaskRunSummary(
                        completedCount = overallCompletedTasks,
                        skippedCount = overallSkippedTasks,
                        interrupted = interrupted
                    )
                }

                val taskObj = jo.optJSONObject("data")
                if (taskObj == null) {
                    Log.sesame("芝麻信用💳[第${round}轮]#任务数据为空，停止刷新")
                    finishedAllRounds = true
                    break
                }

                var roundTotalTasks = 0
                var roundCompletedTasks = 0
                var roundSkippedTasks = 0

                if (taskObj.has("dailyTaskListVO")) {
                    val dailyTaskListVO = taskObj.getJSONObject("dailyTaskListVO")

                    if (dailyTaskListVO.has("waitCompleteTaskVOS")) {
                        val waitCompleteTaskVOS = dailyTaskListVO.getJSONArray("waitCompleteTaskVOS")
                        roundTotalTasks += waitCompleteTaskVOS.length()
                        Log.sesame("芝麻信用💳[第${round}轮待完成任务]#开始处理(" + waitCompleteTaskVOS.length() + "个)"
                        )
                        val results = joinAndFinishSesameTaskWithResult(waitCompleteTaskVOS, transientSkippedTasks)
                        roundCompletedTasks += results.completedCount
                        roundSkippedTasks += results.skippedCount
                        if (results.interrupted) {
                            interrupted = true
                            overallCompletedTasks += roundCompletedTasks
                            overallSkippedTasks += roundSkippedTasks
                            break
                        }
                    }

                    if (dailyTaskListVO.has("waitJoinTaskVOS")) {
                        val waitJoinTaskVOS = dailyTaskListVO.getJSONArray("waitJoinTaskVOS")
                        roundTotalTasks += waitJoinTaskVOS.length()
                        Log.sesame("芝麻信用💳[第${round}轮待加入任务]#开始处理(" + waitJoinTaskVOS.length() + "个)"
                        )
                        val results = joinAndFinishSesameTaskWithResult(waitJoinTaskVOS, transientSkippedTasks)
                        roundCompletedTasks += results.completedCount
                        roundSkippedTasks += results.skippedCount
                        if (results.interrupted) {
                            interrupted = true
                            overallCompletedTasks += roundCompletedTasks
                            overallSkippedTasks += roundSkippedTasks
                            break
                        }
                    }
                }

                if (taskObj.has("toCompleteVOS")) {
                    val toCompleteVOS = taskObj.getJSONArray("toCompleteVOS")
                    roundTotalTasks += toCompleteVOS.length()
                    Log.sesame("芝麻信用💳[第${round}轮toCompleteVOS任务]#开始处理(" + toCompleteVOS.length() + "个)"
                    )
                    val results = joinAndFinishSesameTaskWithResult(toCompleteVOS, transientSkippedTasks)
                    roundCompletedTasks += results.completedCount
                    roundSkippedTasks += results.skippedCount
                    if (results.interrupted) {
                        interrupted = true
                        overallCompletedTasks += roundCompletedTasks
                        overallSkippedTasks += roundSkippedTasks
                        break
                    }
                }

                overallCompletedTasks += roundCompletedTasks
                overallSkippedTasks += roundSkippedTasks
                Log.sesame("芝麻信用💳[第${round}轮处理完成]#总任务:${roundTotalTasks}个, 完成:${roundCompletedTasks}个, 跳过:${roundSkippedTasks}个"
                )

                if (roundTotalTasks == 0) {
                    finishedAllRounds = true
                    Log.sesame("芝麻信用💳[当前轮无可做任务，今日停止刷新]")
                    break
                }

                if (roundCompletedTasks <= 0) {
                    finishedAllRounds = true
                    Log.sesame("芝麻信用💳[当前轮无新增完成任务，今日停止刷新]")
                    break
                }

            }

            Log.sesame("芝麻信用💳[任务总计]#轮次:$round, 完成:${overallCompletedTasks}个, 跳过:${overallSkippedTasks}个"
            )

            if (interrupted || ApplicationHookConstants.isOffline()) {
                return@run SesameTaskRunSummary(
                    completedCount = overallCompletedTasks,
                    skippedCount = overallSkippedTasks,
                    interrupted = true
                )
            }

            if (finishedAllRounds) {
                setFlagToday(StatusFlags.FLAG_SESAME_DO_ALL_AVAILABLE_TASK)
                Log.sesame(if (overallCompletedTasks > 0) {
                        "芝麻信用💳[当前可执行任务已处理完成，今日跳过]"
                    } else {
                        "芝麻信用💳[无新增可执行任务，今日跳过]"
                    }
                )
            } else {
                Log.sesame("芝麻信用💳[达到最大刷新轮次]#$sesameTaskRefreshRoundLimit，保留后续重试机会"
                )
            }
            return@run SesameTaskRunSummary(
                finishedAllRounds = finishedAllRounds,
                completedCount = overallCompletedTasks,
                skippedCount = overallSkippedTasks
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "doAllAvailableSesameTask err", t)
            return@run SesameTaskRunSummary(
                completedCount = overallCompletedTasks,
                skippedCount = overallSkippedTasks,
                interrupted = true
            )
        }
    }

    /**
     * 芝麻粒信用福利签到  与芝麻粒炼金的签到方法都一样 alchemyQueryCheckIn 只不过scenecode不一样
     * 基于 HomeV8RpcManager.queryServiceCard 返回的 serviceCardVOList
     * 通过 itemAttrs.checkInModuleVO.currentDateCheckInTaskVO 判断今日是否可签到
     */
    internal fun doSesameZmlCheckIn() {
        var flagState = Status.TodayFlagState.RETRY_LATER
        try {
            if (ApplicationHookConstants.isOffline()) {
                return
            }
            val checkInRes = AntSesameCreditRpcCall.zmlCheckInQueryTaskLists()
            val checkInJo = JSONObject(checkInRes)
            if (!ResChecker.checkRes(TAG, checkInJo)) {
                return
            }
            val data = checkInJo.optJSONObject("data") ?: return
            val currentDay = data.optJSONObject("currentDateCheckInTaskVO") ?: return

            val status = currentDay.optString("status")
            val checkInDate = currentDay.optString("checkInDate")

            if ("CAN_COMPLETE" != status || checkInDate.isEmpty()) {
                flagState = Status.TodayFlagState.NO_MORE_ACTION_TODAY
                return
            }
            if ("CAN_COMPLETE" == status && checkInDate.isNotEmpty()) {
                // 信誉主页签到
                val completeRes = AntSesameCreditRpcCall.zmCheckInCompleteTask(checkInDate, "zml")
                val completeJo = JSONObject(completeRes)
                val checkInSuccess = ResChecker.checkRes(TAG, completeJo)
                if (checkInSuccess) {
                    val prize = completeJo.optJSONObject("data")
                    val num = if (prize == null) {
                        0
                    } else {
                        val prizeObj = prize.optJSONObject("prize")
                        prize.optInt("zmlNum", prizeObj?.optInt("num", 0) ?: 0)
                    }
                    Log.sesame("芝麻信用💳[芝麻粒福利签到成功]#获得" + num + "粒")
                } else {
                    Log.error("$TAG.doSesameZmlCheckIn", "芝麻粒福利签到失败:$completeRes")
                }
                if (checkInSuccess) {
                    flagState = Status.TodayFlagState.DONE
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.doSesameZmlCheckIn", t)
        } finally {
            setFlagToday(StatusFlags.FLAG_SESAME_ZML_CHECKIN_DONE, flagState)
        }
    }

    internal fun doSesameAlchemyNextDayAward() = CoroutineUtils.run {
        try {
            val entryRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryEntryList()
            val entryJo = JSONObject(entryRes)
            if (!ResChecker.checkRes(TAG, entryJo)) {
                Log.error("芝麻炼金⚗️[次日奖励入口查询失败]：$entryRes")
                return@run
            }

            val entryList = entryJo.optJSONObject("data")?.optJSONArray("entryList")
            var nextDayAward: JSONObject? = null
            if (entryList != null) {
                for (i in 0 until entryList.length()) {
                    val entry = entryList.optJSONObject(i) ?: continue
                    if ("ALCHEMY_STAGE_REWARD" == entry.optString("entryCode")) {
                        nextDayAward = entry.optJSONObject("nextDayAwardDTO")
                        break
                    }
                }
            }
            if (nextDayAward == null) {
                Log.sesame("芝麻炼金⚗️[次日奖励入口缺失] 视为今日无可领奖励")
                setFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_NEXT_DAY_AWARD)
                return@run
            }

            val awardAvailable = nextDayAward.optBoolean("awardAvailable", false)
            val awardId = nextDayAward.optString("awardId")
            val pointValue = nextDayAward.optInt("pointValue", 0)
            if (!awardAvailable) {
                Log.sesame("芝麻炼金⚗️[次日奖励暂无可领] 预计奖励=${pointValue}粒${if (awardId.isNotEmpty()) " awardId=$awardId" else ""}"
                )
                setFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_NEXT_DAY_AWARD)
                return@run
            }

            val awardRes = AntSesameCreditRpcCall.Zmxy.Alchemy.claimAward(awardId)
            val jo = JSONObject(awardRes)

            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error("芝麻炼金⚗️[次日奖励领取失败]：$awardRes")
                return@run
            }

            val data = jo.optJSONObject("data")
            var gotNum = 0

            if (data != null) {
                val arr = data.optJSONArray("alchemyAwardSendResultVOS")
                if (arr != null && arr.length() > 0) {
                    val item = arr.optJSONObject(0)
                    if (item != null) {
                        gotNum = item.optInt("pointNum", item.optInt("pointValue", 0))
                    }
                }
                if (gotNum <= 0) {
                    gotNum = data.optInt("pointNum", data.optInt("pointValue", 0))
                }
            }

            if (gotNum > 0) {
                Log.sesame("芝麻炼金⚗️[次日奖励领取成功]#获得" + gotNum + "粒")
            } else {
                Log.sesame("芝麻炼金⚗️[次日奖励无奖励] 已领取或无可领奖励")
            }

            setFlagToday(StatusFlags.FLAG_SESAME_ALCHEMY_NEXT_DAY_AWARD)
        } catch (t: Throwable) {
            Log.printStackTrace("doSesameAlchemyNextDayAward", t)
        }
    }

    private fun extractSesameFeedbackArray(root: JSONObject): JSONArray? {
        return root.optJSONArray("creditFeedbackVOS")
            ?: root.optJSONObject("data")?.optJSONArray("creditFeedbackVOS")
            ?: root.optJSONObject("resData")?.optJSONArray("creditFeedbackVOS")
    }

    private fun buildUnclaimedSesameFeedbackItems(root: JSONObject): List<SesameFeedbackItem> {
        val feedbackArray = extractSesameFeedbackArray(root) ?: return emptyList()
        val result = mutableListOf<SesameFeedbackItem>()
        for (i in 0 until feedbackArray.length()) {
            val item = feedbackArray.optJSONObject(i) ?: continue
            if ("UNCLAIMED" != item.optString("status")) {
                continue
            }
            result.add(
                SesameFeedbackItem(
                    title = item.optString("title", "未知奖励"),
                    creditFeedbackId = item.optString("creditFeedbackId"),
                    potentialSize = item.optString("potentialSize", "0")
                )
            )
        }
        return result
    }

    private suspend fun queryUnclaimedSesameFeedbackItems(logPrefix: String): List<SesameFeedbackItem>? {
        val resp = AntSesameCreditRpcCall.queryCreditFeedback()
        val jo = JSONObject(resp)
        if (!ResChecker.checkRes(TAG, jo)) {
            Log.error(
                "$TAG.queryUnclaimedSesameFeedbackItems",
                "$logPrefix[查询未领取芝麻粒响应失败]#$jo"
            )
            return null
        }
        return buildUnclaimedSesameFeedbackItems(jo)
    }

    private suspend fun collectSesameFeedbackItems(
        items: List<SesameFeedbackItem>,
        preferOneClick: Boolean,
        logPrefix: String
    ): Int {
        if (items.isEmpty()) {
            return 0
        }
        var collectedCount = 0
        var needFallbackCollect = true

        if (preferOneClick) {
            val collectAllResp = AntSesameCreditRpcCall.collectAllCreditFeedback()
            val collectAllJo = JSONObject(collectAllResp)
            if (AntSesameCreditRpcCall.isRpcSuccess(collectAllResp)) {
                needFallbackCollect = false
                items.forEach { item ->
                    Log.sesame("$logPrefix[" + item.title + "]#" + item.potentialSize + "粒(一键收取)")
                    collectedCount++
                }
            } else {
                val errorCode = collectAllJo.optString(
                    "errorCode",
                    collectAllJo.optString("resultCode", "")
                )
                val msg = buildSesameRpcMessage(collectAllJo, collectAllResp)
                if (isTransientSesameTaskError(errorCode, msg)) {
                    Log.error(
                        TAG,
                        "$logPrefix[一键收取失败，回退逐个收取] classification=RETRYABLE_RPC decision=RETRY_LATER " +
                            "module=$logPrefix taskId=collectAllCreditFeedback taskName=一键收取芝麻粒 " +
                            "action=collectAllCreditFeedback rpc=AntSesameCreditRpcCall.collectAllCreditFeedback " +
                            "code=${errorCode.ifEmpty { "UNKNOWN" }} msg=$msg raw=$collectAllJo"
                    )
                } else {
                    Log.error(
                        "$TAG.collectSesameFeedbackItems",
                        "$logPrefix[一键收取响应失败，回退逐个收取] classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY " +
                            "module=$logPrefix taskId=collectAllCreditFeedback taskName=一键收取芝麻粒 " +
                            "action=collectAllCreditFeedback rpc=AntSesameCreditRpcCall.collectAllCreditFeedback " +
                            "code=${errorCode.ifEmpty { "UNKNOWN" }} msg=$msg raw=$collectAllJo"
                    )
                }
            }
        }

        if (!needFallbackCollect) {
            return collectedCount
        }

        for (item in items) {
            if (item.creditFeedbackId.isEmpty()) {
                continue
            }
            val collectResp = AntSesameCreditRpcCall.collectCreditFeedback(item.creditFeedbackId)
            val collectJo = JSONObject(collectResp)
            if (!ResChecker.checkRes(TAG, collectJo)) {
                val itemErrorCode = collectJo.optString("errorCode", collectJo.optString("resultCode", ""))
                val itemMsg = buildSesameRpcMessage(collectJo, collectResp)
                val itemClassification = if (isTransientSesameTaskError(itemErrorCode, itemMsg)) {
                    "RETRYABLE_RPC"
                } else {
                    "UNKNOWN_NEEDS_REVIEW"
                }
                val itemDecision = if (itemClassification == "RETRYABLE_RPC") "RETRY_LATER" else "LOG_ONLY"
                Log.error(
                    "$TAG.collectSesameFeedbackItems",
                    "$logPrefix[${item.title}] classification=$itemClassification decision=$itemDecision " +
                        "module=$logPrefix taskId=${item.creditFeedbackId} taskName=${item.title} " +
                        "action=collectCreditFeedback rpc=AntSesameCreditRpcCall.collectCreditFeedback " +
                        "code=${itemErrorCode.ifEmpty { "UNKNOWN" }} msg=$itemMsg raw=$collectJo"
                )
                continue
            }
            Log.sesame("$logPrefix[" + item.title + "]#" + item.potentialSize + "粒")
            collectedCount++
        }
        return collectedCount
    }

    /**
     * 芝麻粒收取
     * @param withOneClick 启用一键收取
     */
    internal suspend fun collectSesame(withOneClick: Boolean): Unit = CoroutineUtils.run {
        var flagState = Status.TodayFlagState.RETRY_LATER
        if (ApplicationHookConstants.isOffline()) {
            return@run
        }
        try {
            val items = queryUnclaimedSesameFeedbackItems("芝麻信用💳") ?: return@run
            if (items.isEmpty()) {
                flagState = Status.TodayFlagState.NO_MORE_ACTION_TODAY
                Log.sesame("芝麻信用💳[当前无待收取芝麻粒]")
                return@run
            }
            collectSesameFeedbackItems(items, withOneClick, "芝麻信用💳")
            if (ApplicationHookConstants.isOffline()) {
                return@run
            }
            val remainingItems = queryUnclaimedSesameFeedbackItems("芝麻信用💳[复核]") ?: return@run
            if (remainingItems.isEmpty()) {
                flagState = Status.TodayFlagState.DONE
            } else {
                Log.sesame("芝麻信用💳[仍有${remainingItems.size}项未收取] 保留后续重试机会")
            }
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.collectSesame", t)
        } finally {
            setFlagToday(StatusFlags.FLAG_SESAME_COLLECT_DONE, flagState)
        }
    }

    /**
     * 芝麻炼金
     */
    internal suspend fun doSesameAlchemy(): Unit = CoroutineUtils.run {
        try {
            Log.sesame("开始执行芝麻炼金⚗️")

            // ================= Step 1: 自动炼金 (消耗芝麻粒升级 / 消耗免费炼金次数) =================
            runSesameAlchemyCycles()

            // ================= Step 2: 自动签到 & 时段奖励 =================
            val checkInRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryCheckIn("alchemy")
            val checkInJo = JSONObject(checkInRes)
            if (ResChecker.checkRes(TAG, checkInJo)) {
                val data = checkInJo.optJSONObject("data")
                if (data != null) {
                    val currentDay = data.optJSONObject("currentDateCheckInTaskVO")
                    if (currentDay != null) {
                        val status = currentDay.optString("status")
                        val checkInDate = currentDay.optString("checkInDate")
                        if ("CAN_COMPLETE" == status && !checkInDate.isEmpty()) {
                            // 炼金签到
                            val completeRes = AntSesameCreditRpcCall.zmCheckInCompleteTask(checkInDate, "alchemy")
                            try {
                                val completeJo = JSONObject(completeRes)
                                if (ResChecker.checkRes(TAG, completeJo)) {
                                    val prize = completeJo.optJSONObject("data")
                                    val num = if (prize == null) {
                                        0
                                    } else {
                                        val prizeObj = prize.optJSONObject("prize")
                                        prize.optInt("zmlNum", prizeObj?.optInt("num", 0) ?: 0)
                                    }
                                    Log.sesame("芝麻炼金⚗️[每日签到成功]#获得" + num + "粒")
                                } else {
                                    Log.error("$TAG.doSesameAlchemy", "炼金签到失败:$completeRes")
                                }
                            } catch (e: Throwable) {
                                Log.printStackTrace(
                                    "$TAG.doSesameAlchemy.alchemyCheckInComplete", e
                                )
                            }
                        } // status 为 COMPLETED 时不再重复签到
                    }
                }
            }

            // 1. 查询时段任务
            val queryRespStr = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryTimeLimitedTask()
            Log.sesame("芝麻炼金⚗️[检查时段奖励]")

            val queryResp = JSONObject(queryRespStr)
            val queryData = queryResp.optJSONObject("data")
            if (!ResChecker.checkRes(TAG, "查询时段任务失败:", queryResp) || !ResChecker.checkRes(
                    TAG, queryResp
                ) || queryData == null
            ) {
                Log.error(
                    TAG, "芝麻炼金⚗️[检查时段奖励错误] alchemyQueryTimeLimitedTask raw=$queryResp"
                )
            } else {
                val timeLimitedTaskVO = queryData.optJSONObject("timeLimitedTaskVO")
                if (timeLimitedTaskVO == null) {
                    Log.sesame("芝麻炼金⚗️[当前没有时段奖励任务]")
                } else {
                    // 2. 获取任务信息
                    val taskName = timeLimitedTaskVO.optString("longTitle", "未知任务")
                    val templateId = timeLimitedTaskVO.getString("templateId") // 动态获取
                    val state = timeLimitedTaskVO.optInt("state", 0) // 1: 可领取, 2: 未到时间
                    val tomorrow = timeLimitedTaskVO.optBoolean("tomorrow", false)
                    val rewardAmount = timeLimitedTaskVO.optInt("rewardAmount", 0)

                    Log.sesame("芝麻炼金⚗️[任务检查] 任务=$taskName 状态=$state 奖励=$rewardAmount 明天=$tomorrow"
                    )

                    // 3. 如果是明天任务，跳过时段奖励，但继续处理任务列表
                    if (tomorrow) {
                        Log.sesame("芝麻炼金⚗️[任务跳过] 任务=$taskName 是明天的奖励")
                    } else if (state == 1) { // 可领取
                        Log.sesame("芝麻炼金⚗️[开始领取任务奖励] 任务=$taskName")

                        val collectRespStr = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyCompleteTimeLimitedTask(templateId)
                        val collectResp = JSONObject(collectRespStr)

                        if (!ResChecker.checkRes(
                                TAG, collectResp
                            ) || collectResp.optJSONObject("data") == null
                        ) {
                            Log.error(TAG, "领取任务奖励失败 raw=$collectResp")
                        } else {
                            val data = collectResp.getJSONObject("data")
                            val zmlNum = data.optInt("zmlNum", 0)
                            val toast = data.optString("toast", "")
                            Log.sesame("芝麻炼金⚗️[领取成功] 获得芝麻粒=$zmlNum 提示=$toast")
                        }
                    } else { // 其他状态
                        Log.sesame("芝麻炼金⚗️[当前不可领取] 任务=$taskName")
                    }
                }
            }


            // ================= Step 3: 自动做任务 =================
            val processedTaskCount = processAlchemyTaskListsUntilStable()
            if (processedTaskCount > 0) {
                Log.sesame("芝麻炼金⚗️[任务列表处理完成]#本次处理${processedTaskCount}项")
            }

            // ================= Step 4: [新增] 任务完成后一键收取芝麻粒 =================
            Log.sesame("芝麻炼金⚗️[任务处理完毕，准备收取芝麻粒]")
            delay(2000) // 稍作等待，确保任务奖励到账
            val feedbackItems = queryUnclaimedSesameFeedbackItems("芝麻炼金⚗️")
            if (feedbackItems == null) {
                Log.sesame("芝麻炼金⚗️[查询待收取芝麻粒失败]")
            } else if (feedbackItems.isEmpty()) {
                Log.sesame("芝麻炼金⚗️[当前无待收取芝麻粒]")
            } else {
                Log.sesame("芝麻炼金⚗️[发现" + feedbackItems.size + "个待收取项，执行一键收取]")
                val collectedCount = collectSesameFeedbackItems(feedbackItems, true, "芝麻炼金⚗️")
                if (collectedCount > 0) {
                    Log.sesame("芝麻炼金⚗️[收取完成]#本次处理" + collectedCount + "项")
                }
            }

            // 新增浏览任务可能奖励炼金次数（LJCS），任务后仅补跑免费炼金，避免额外消耗新到账芝麻粒。
            runSesameAlchemyCycles(allowPaidAlchemy = false)
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.doSesameAlchemy", t)
        }
    }

    private suspend fun runSesameAlchemyCycles(allowPaidAlchemy: Boolean = true) {
        val homeRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryHome()
        val homeJo = JSONObject(homeRes)
        if (!ResChecker.checkRes(TAG, homeJo)) {
            Log.error(TAG, "芝麻炼金首页查询失败")
            return
        }
        val data = homeJo.optJSONObject("data") ?: return
        var zmlBalance = data.optInt("zmlBalance", 0)
        val cost = data.optInt("alchemyCostZml", 5).coerceAtLeast(1)
        var capReached = data.optBoolean("capReached", false)
        var currentLevel = data.optInt("currentLevel", 0)
        var freeAlchemyNum = data.optInt("freeAlchemyNum", 0)
        val maxAlchemyAttempts = (freeAlchemyNum + if (allowPaidAlchemy) zmlBalance / cost else 0).coerceAtLeast(1)
        var alchemyAttempts = 0

        while (freeAlchemyNum > 0 || (allowPaidAlchemy && zmlBalance >= cost && !capReached)) {
            if (alchemyAttempts >= maxAlchemyAttempts) {
                Log.sesame("芝麻炼金⚗️[达到本轮炼金安全次数上限]#$maxAlchemyAttempts，停止自动炼金")
                break
            }
            alchemyAttempts++

            val alchemyRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyExecute()
            val alchemyJo = JSONObject(alchemyRes)

            if (isSesameAlchemyCapReached(alchemyJo) && freeAlchemyNum <= 0) {
                Log.sesame("芝麻炼金⚗️[已达盖帽值，停止自动炼金]")
                break
            }
            if (!ResChecker.checkRes(TAG, alchemyJo)) {
                Log.error(TAG, "芝麻炼金失败: " + alchemyJo.optString("resultView", alchemyRes))
                break
            }

            val alData = alchemyJo.optJSONObject("data") ?: break
            val levelUp = alData.optBoolean("levelUp", false)
            val levelFull = alData.optBoolean("levelFull", false)
            val goldNum = alData.optInt("goldNum", 0)
            val usedFreeAlchemy =
                alData.optBoolean("free", false) || (freeAlchemyNum > 0 && (!allowPaidAlchemy || capReached))

            if (levelUp) {
                currentLevel++
            }
            if (levelFull) {
                capReached = true
            }

            val consumeText = if (usedFreeAlchemy) {
                if (freeAlchemyNum > 0) {
                    freeAlchemyNum--
                }
                "消耗免费次数1次"
            } else {
                zmlBalance -= cost
                "消耗${cost}粒"
            }

            Log.sesame(
                "芝麻炼金⚗️[炼金成功]#$consumeText | 获得" + goldNum + "金" +
                    " | 当前等级Lv." + currentLevel +
                    (if (levelUp) "（升级🎉）" else "") +
                    (if (levelFull) "（满级🏆）" else "")
            )
        }
    }

    private suspend fun processAlchemyTaskListsUntilStable(): Int {
        val processedBlacklistTasks = mutableSetOf<String>()
        var totalProcessedCount = 0
        val maxRound = 20

        for (round in 1..maxRound) {
            Log.sesame("芝麻炼金⚗️[开始扫描任务列表]#第${round}轮")
            val listRes = AntSesameCreditRpcCall.Zmxy.Alchemy.alchemyQueryListV3()
            val listJo = JSONObject(listRes)

            if (!ResChecker.checkRes(TAG, listJo)) {
                Log.error(TAG, "芝麻炼金⚗️[任务列表查询失败] raw=$listJo")
                break
            }

            val data = listJo.optJSONObject("data")
            if (data == null) {
                Log.sesame("芝麻炼金⚗️[任务列表为空]")
                break
            }

            var roundProcessedCount = 0
            roundProcessedCount += processAlchemyTasks(data.optJSONArray("toCompleteVOS"), processedBlacklistTasks)

            val dailyTaskVO = data.optJSONObject("dailyTaskListVO")
            if (dailyTaskVO != null) {
                roundProcessedCount += processAlchemyTasks(
                    dailyTaskVO.optJSONArray("waitJoinTaskVOS"), processedBlacklistTasks
                )
                roundProcessedCount += processAlchemyTasks(
                    dailyTaskVO.optJSONArray("waitCompleteTaskVOS"), processedBlacklistTasks
                )
            }

            if (roundProcessedCount <= 0) {
                if (round > 1) {
                    Log.sesame("芝麻炼金⚗️[任务列表已无新增可处理任务]")
                }
                break
            }

            totalProcessedCount += roundProcessedCount
            if (round == maxRound) {
                Log.sesame("芝麻炼金⚗️[任务列表达到安全轮次上限]#已处理${totalProcessedCount}项")
            }
        }

        return totalProcessedCount
    }

    /**
     * 处理芝麻炼金任务列表
     * @param taskList 任务列表
     * @param processedBlacklistTasks 已处理的黑名单任务集合（用于避免重复日志）
     */
    @Throws(JSONException::class)
    private suspend fun processAlchemyTasks(
        taskList: JSONArray?, processedBlacklistTasks: MutableSet<String>
    ): Int {
        if (taskList == null || taskList.length() == 0) return 0

        var processedCount = 0

        for (i in 0..<taskList.length()) {
            val task = taskList.getJSONObject(i)
            val title = task.optString("title")
            val templateId = task.optString("templateId")
            val finishFlag = task.optBoolean("finishFlag", false)
            val bizType = task.optString("bizType", "")

            if (finishFlag) continue

            // 使用TaskBlacklist进行黑名单检查
            if (isTaskInBlacklist(sesameAlchemyTaskBlacklistModule, title)) {
                // 只有在所有任务组中未处理过时才记录日志
                if (!processedBlacklistTasks.contains(title)) {
                    Log.sesame("跳过黑名单任务: $title")
                    processedBlacklistTasks.add(title)
                }
                continue
            }

            if (shouldSkipShareAssistSesameTask(task)) {
                Log.sesame("芝麻炼金任务: 跳过助力型任务 $title")
                continue
            }

            // 特殊处理：广告浏览任务（逛15秒商品橱窗 / 浏览15秒视频广告 等）
            // 这类任务没有有效 templateId，需要用 logExtMap.bizId 走 com.alipay.adtask.biz.mobilegw.service.task.finish
            if ("AD_TASK" == bizType) {
                try {
                    if (handleSesameAdTask(task, title, "芝麻炼金⚗️", sesameAlchemyTaskBlacklistModule)) {
                        processedCount++
                    }
                } catch (e: Throwable) {
                    Log.printStackTrace("$TAG.processAlchemyTasks.adTask", e)
                }
                // 广告任务不再走 templateId / recordId 这套逻辑
                continue
            }

            // 普通任务：仍然使用模板+recordId 的 Promise 流程
            if (templateId.contains("invite") || templateId.contains("upload") || templateId.contains("auth") || templateId.contains("banli")) {
                continue
            }
            val actionUrl = task.optString("actionUrl", "")
            if (actionUrl.startsWith("alipays://") && !actionUrl.contains("chInfo")) {
                // 需要外部 App，无法仅靠 hook 完成
                continue
            }

            Log.sesame("芝麻炼金任务: $title 准备执行")

            var recordId = task.optString("recordId", "")

            if (recordId.isEmpty()) {
                // templateId 为空或无效时，直接跳过，避免 "参数[templateId]不是有效的入参"
                if (templateId == null || templateId.trim { it <= ' ' }.isEmpty()) {
                    Log.sesame("芝麻炼金任务: 模板为空，跳过 $title")
                    continue
                }
                val joinRes = AntSesameCreditRpcCall.joinSesameTask(templateId)
                val joinJo = JSONObject(joinRes)
                if (ResChecker.checkRes(TAG, joinJo)) {
                    val joinData = joinJo.optJSONObject("data")
                    if (joinData != null) {
                        recordId = joinData.optString("recordId")
                    }
                    Log.sesame("任务领取成功: $title")
                } else {
                    Log.error(
                        TAG, "任务领取失败: " + title + " - " + joinJo.optString("resultView", joinRes)
                    )
                    continue
                }
            }

            if (!reportSesameTaskFeedback(
                    task,
                    title,
                    "芝麻炼金⚗️",
                    sesameAlchemyTaskBlacklistModule,
                    version = "alchemy"
                )
            ) {
                continue
            }

            if (!recordId.isEmpty()) {
                val finishRes = AntSesameCreditRpcCall.finishSesameTask(recordId)
                val finishJo = JSONObject(finishRes)
                if (ResChecker.checkRes(TAG, finishJo)) {
                    Log.sesame("芝麻炼金⚗️[任务完成: " + title + "]#获得" + formatSesameAlchemyReward(task))
                    processedCount++
                } else {
                    val errorCode = finishJo.optString("resultCode", "")
                    val resultView = finishJo.optString("resultView", finishRes)
                    //  val errorMsg = finishJo.optString("resultView", finishRes)
                    //  Log.error(TAG, "任务提交失败: $title - $errorMsg")
                    // 自动添加到黑名单
                    if (!errorCode.isEmpty()) {
                        autoBlacklistSesameTaskIfNeeded(sesameAlchemyTaskBlacklistModule, title, errorCode, resultView, "finish")
                    }
                }
            }
        }

        return processedCount
    }

    internal suspend fun doZhimaTree(): Unit = CoroutineUtils.run {
        try {
            // 1. 执行首页的所有任务 (包括浏览任务和复访任务)
            doHomeTasks()

            // 2. 执行常规列表任务 (赚净化值列表)
            doRentGreenTasks()

            // 3. 消耗净化值进行净化
            doPurification()
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        }
    }

    /**
     * 处理首页返回的任务 (含浏览任务和状态列表任务)
     */
    private suspend fun doHomeTasks(): Unit = CoroutineUtils.run {
        try {
            val res = AntSesameCreditRpcCall.zhimaTreeHomePage() ?: return@run

            val json = JSONObject(res)
            if (ResChecker.checkRes(TAG, json)) {
                val result = json.optJSONObject("extInfo") ?: return@run
                val queryResult = result.optJSONObject("zhimaTreeHomePageQueryResult") ?: return@run

                // 1. 处理 browseTaskList (如：芝麻树首页每日_浏览任务)
                val browseList = queryResult.optJSONArray("browseTaskList")
                if (browseList != null) {
                    for (i in 0..<browseList.length()) {
                        processSingleTask(browseList.getJSONObject(i))
                    }
                }

                // 2. 处理 taskStatusList (如：芝麻树复访任务70净化值)
                val statusList = queryResult.optJSONArray("taskStatusList")
                if (statusList != null) {
                    for (i in 0..<statusList.length()) {
                        processSingleTask(statusList.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 处理赚净化值列表任务
     */
    private suspend fun doRentGreenTasks(): Unit = CoroutineUtils.run {
        try {
            val res = AntSesameCreditRpcCall.queryRentGreenTaskList() ?: return@run

            val json = JSONObject(res)
            if (ResChecker.checkRes(TAG, json)) {
                val extInfo = json.optJSONObject("extInfo") ?: return@run

                val taskDetailListObj = extInfo.optJSONObject("taskDetailList") ?: return@run

                val processedAdBizIds = mutableSetOf<String>()
                processZhimaTreeSpaceResultList(
                    taskDetailListObj.optJSONArray("spaceResultList"),
                    processedAdBizIds
                )

                val tasks = taskDetailListObj.optJSONArray("taskDetailList")
                if (tasks != null) {
                    for (i in 0..<tasks.length()) {
                        processSingleTask(tasks.getJSONObject(i))
                    }
                }
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 处理单个任务对象的逻辑
     */
    private suspend fun processSingleTask(task: JSONObject) {
        try {
            val taskRef = buildZhimaTreeTaskRef(task) ?: return
            if ("NOT_DONE" == taskRef.status || "SIGNUP_COMPLETE" == taskRef.status) {
                if (taskRef.taskId == null) {
                    Log.sesame("芝麻树🌳[跳过无有效任务ID] ${taskRef.title} | candidates=${taskRef.describeCandidates()}"
                    )
                    return
                }
                Log.sesame(
                    "芝麻树🌳[开始任务] " + taskRef.title +
                        (if (taskRef.prizeName.isEmpty()) "" else " (${taskRef.prizeName})")
                )
                performTask(taskRef)
            } else if ("TO_RECEIVE" == taskRef.status) {
                receiveZhimaTreeTask(taskRef, "领取奖励")
            } else if ("RECEIVE_SUCCESS" == taskRef.status && taskRef.needManuallyReceiveAward) {
                receiveZhimaTreeTask(taskRef, "领取奖励")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    private suspend fun processZhimaTreeSpaceResultList(
        spaceResultList: JSONArray?,
        processedBizIds: MutableSet<String>
    ): Int {
        if (spaceResultList == null || spaceResultList.length() == 0) {
            return 0
        }
        var processedCount = 0
        for (i in 0..<spaceResultList.length()) {
            val spaceResult = spaceResultList.optJSONObject(i) ?: continue
            val listSpaceCode = spaceResult.optString("spaceCode")
            val spaceObjectList = spaceResult.optJSONArray("spaceObjectList") ?: continue
            for (j in 0..<spaceObjectList.length()) {
                val spaceObject = spaceObjectList.optJSONObject(j) ?: continue
                val adTask = extractZhimaTreeAdTaskContent(spaceObject) ?: continue
                val adTaskRef = buildZhimaTreeAdTaskRef(adTask, listSpaceCode) ?: continue
                if (!processedBizIds.add(adTaskRef.bizId)) {
                    continue
                }
                if (finishZhimaTreeAdTask(adTaskRef)) {
                    processedCount++
                }
            }
        }
        return processedCount
    }

    private fun extractZhimaTreeAdTaskContent(spaceObject: JSONObject): JSONObject? {
        return when (val content = spaceObject.opt("content")) {
            is JSONObject -> content
            is String -> parseJSONObjectOrNull(content) ?: spaceObject
            else -> spaceObject
        }
    }

    private fun buildZhimaTreeAdTaskRef(adTask: JSONObject, listSpaceCode: String): ZhimaTreeAdTaskRef? {
        val logExtMap = adTask.optJSONObject("logExtMap")
        val schemaJson = parseJSONObjectOrNull(adTask.optString("schemaJson"))
        val clickThroughUrl = adTask.optString("clickThroughUrl")
            .ifBlank { schemaJson?.optString("url").orEmpty() }
        val rewardAmount = schemaJson?.optString("taskRewardAmount").orEmpty()
            .ifBlank { adTask.optString("rewardNum") }
            .ifBlank { logExtMap?.optString("rewardNum").orEmpty() }
        val spaceCode = resolveAdTaskSpaceCode(
            logExtMap,
            clickThroughUrl,
            fallbackSpaceCode = listSpaceCode,
            fallbackRewardNum = rewardAmount
        )
        val bizId = logExtMap?.optString("bizId").orEmpty()
            .ifBlank { adTask.optString("xlightBizId") }
            .ifBlank { adTask.optString("bizId") }
            .ifBlank { schemaJson?.optString("adBizId").orEmpty() }
            .ifBlank { extractQueryParam(clickThroughUrl, "bizId").orEmpty() }
            .ifBlank { extractAdRenderConfigValue(spaceCode, "bizId") }
        if (bizId.isBlank()) {
            return null
        }
        val title = schemaJson?.optString("taskMainTitle").orEmpty()
            .ifBlank { schemaJson?.optString("title").orEmpty() }
            .ifBlank { adTask.optString("title") }
            .ifBlank { "芝麻树广告浏览任务" }
        val renderRewardAmount = rewardAmount.ifBlank {
            extractAdRenderConfigValue(spaceCode, "rewardNum")
        }
        val rewardText = if (renderRewardAmount.isBlank()) {
            "奖励已领取"
        } else if (renderRewardAmount.contains("净化") || renderRewardAmount.contains("能量")) {
            renderRewardAmount
        } else {
            renderRewardAmount + "净化值"
        }
        return ZhimaTreeAdTaskRef(
            title = title,
            rewardText = rewardText,
            bizId = bizId,
            spaceCode = spaceCode
        )
    }

    private suspend fun finishZhimaTreeAdTask(taskRef: ZhimaTreeAdTaskRef): Boolean {
        val spaceCode = taskRef.spaceCode
        if (spaceCode.isNullOrBlank()) {
            Log.sesame("芝麻树🌳[广告任务缺少浏览配置] ${taskRef.title} | bizId=${taskRef.bizId}")
            return false
        }
        return try {
            Log.sesame("芝麻树🌳[广告任务准备] ${taskRef.title}")
            val layerRes = AntSesameCreditRpcCall.adTaskApplayerQuery(spaceCode)
            val layerJo = JSONObject(layerRes)
            if (!ResChecker.checkRes(TAG, layerJo) && "0" != layerJo.optString("errCode")) {
                val layerMsg = buildSesameRpcMessage(layerJo, layerRes)
                if (isAdTaskRetryable(layerJo, layerMsg)) {
                    Log.sesame("芝麻树🌳[广告浏览配置暂时不可用] ${taskRef.title} - $layerMsg")
                } else {
                    Log.error(TAG, "芝麻树🌳[广告浏览配置失败] ${taskRef.title} - $layerMsg")
                }
                return false
            }
            val finishRes = AntSesameCreditRpcCall.taskFinish(taskRef.bizId, includeExtendInfo = false)
            val finishJo = JSONObject(finishRes)
            if (isAdTaskFinishSuccess(finishJo, finishRes)) {
                Log.sesame("芝麻树🌳[广告任务完成] ${taskRef.title} #${taskRef.rewardText}")
                return true
            }
            val finishMsg = buildSesameRpcMessage(finishJo, finishRes)
            if (isSesameAdTaskAlreadyFinished(finishJo, finishMsg)) {
                Log.sesame("芝麻树🌳[广告任务已完成，跳过重复上报] ${taskRef.title} - $finishMsg")
                return true
            }
            if (isAdTaskRetryable(finishJo, finishMsg)) {
                Log.sesame("芝麻树🌳[广告任务暂时未完成] ${taskRef.title} - $finishMsg")
            } else {
                Log.error(TAG, "芝麻树🌳[广告任务上报失败] ${taskRef.title} - $finishMsg")
            }
            false
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.finishZhimaTreeAdTask", t)
            false
        }
    }

    private fun buildZhimaTreeTaskRef(task: JSONObject): ZhimaTreeTaskRef? {
        val sendCampTriggerType = task.optString("sendCampTriggerType")
        if ("EVENT_TRIGGER" == sendCampTriggerType) {
            return null
        }
        val taskBaseInfo = task.optJSONObject("taskBaseInfo") ?: return null
        val taskIdCandidates = collectZhimaTreeTaskIdCandidates(task, taskBaseInfo)
        val taskId = taskIdCandidates.mapNotNull { normalizeZhimaTreeTaskId(it) }.firstOrNull()
        var title = taskBaseInfo.optString("appletName")
        if (title.isEmpty()) {
            title = taskBaseInfo.optString("title", taskId ?: "未知任务")
        }
        if (title.contains("邀请") || title.contains("下单") || title.contains("开通")) {
            return null
        }
        return ZhimaTreeTaskRef(
            title = title,
            prizeName = getPrizeName(task),
            status = task.optString("taskProcessStatus"),
            taskId = taskId,
            taskIdCandidates = taskIdCandidates,
            needManuallyReceiveAward = task.optBoolean("needManuallyReceiveAward", true)
        )
    }

    private fun normalizeZhimaTreeTaskId(rawTaskId: String?): String? {
        val normalized = rawTaskId?.trim().orEmpty()
        if (normalized.isBlank() || normalized.equals("null", ignoreCase = true)) {
            return null
        }
        if (normalized == "{}" || normalized == "[]") {
            return null
        }
        if ((normalized.startsWith("{") && normalized.endsWith("}")) ||
            (normalized.startsWith("[") && normalized.endsWith("]"))
        ) {
            return null
        }
        return normalized
    }

    private fun collectZhimaTreeTaskIdCandidates(task: JSONObject, taskBaseInfo: JSONObject): List<String> {
        return sequenceOf(
            taskBaseInfo.opt("appletId"),
            taskBaseInfo.opt("taskId"),
            taskBaseInfo.opt("appId"),
            task.opt("taskId"),
            task.opt("appletId"),
            task.opt("appId")
        ).filterNotNull()
            .map { candidate ->
                when (candidate) {
                    JSONObject.NULL -> ""
                    is String -> candidate
                    else -> candidate.toString()
                }
            }
            .toList()
    }

    private suspend fun queryZhimaTreeTaskRefs(): ZhimaTreeTaskRefreshResult = CoroutineUtils.run {
        val refreshedTasks = mutableListOf<ZhimaTreeTaskRef>()
        var queriedSourceCount = 0
        if (appendZhimaTreeTaskRefsFromHomePage(refreshedTasks)) {
            queriedSourceCount++
        }
        if (appendZhimaTreeTaskRefsFromRentGreenList(refreshedTasks)) {
            queriedSourceCount++
        }
        ZhimaTreeTaskRefreshResult(refreshedTasks, queriedSourceCount)
    }

    private fun appendZhimaTreeTaskRefs(target: MutableList<ZhimaTreeTaskRef>, tasks: JSONArray?) {
        if (tasks == null) {
            return
        }
        for (i in 0..<tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            buildZhimaTreeTaskRef(task)?.let(target::add)
        }
    }

    private fun appendZhimaTreeTaskRefsFromHomePage(target: MutableList<ZhimaTreeTaskRef>): Boolean {
        val res = AntSesameCreditRpcCall.zhimaTreeHomePage() ?: return false
        val json = JSONObject(res)
        if (!ResChecker.checkRes(TAG, json)) {
            return false
        }
        val queryResult = json.optJSONObject("extInfo")
            ?.optJSONObject("zhimaTreeHomePageQueryResult") ?: return true
        appendZhimaTreeTaskRefs(target, queryResult.optJSONArray("browseTaskList"))
        appendZhimaTreeTaskRefs(target, queryResult.optJSONArray("taskStatusList"))
        return true
    }

    private fun appendZhimaTreeTaskRefsFromRentGreenList(target: MutableList<ZhimaTreeTaskRef>): Boolean {
        val res = AntSesameCreditRpcCall.queryRentGreenTaskList() ?: return false
        val json = JSONObject(res)
        if (!ResChecker.checkRes(TAG, json)) {
            return false
        }
        val tasks = json.optJSONObject("extInfo")
            ?.optJSONObject("taskDetailList")
            ?.optJSONArray("taskDetailList") ?: return true
        appendZhimaTreeTaskRefs(target, tasks)
        return true
    }

    private fun findMatchingZhimaTreeTask(
        originalTask: ZhimaTreeTaskRef,
        refreshedTasks: List<ZhimaTreeTaskRef>
    ): ZhimaTreeTaskRef? {
        return refreshedTasks.firstOrNull { refreshedTask ->
            isSameZhimaTreeTask(originalTask, refreshedTask, requireSameTaskId = true)
        } ?: refreshedTasks.firstOrNull { refreshedTask ->
            isSameZhimaTreeTask(originalTask, refreshedTask, requireSameTaskId = false)
        }
    }

    private fun isSameZhimaTreeTask(
        originalTask: ZhimaTreeTaskRef,
        refreshedTask: ZhimaTreeTaskRef,
        requireSameTaskId: Boolean
    ): Boolean {
        if (refreshedTask.title != originalTask.title) {
            return false
        }
        val prizeMatched = originalTask.prizeName.isEmpty() ||
            refreshedTask.prizeName.isEmpty() ||
            refreshedTask.prizeName == originalTask.prizeName
        if (!prizeMatched) {
            return false
        }
        if (!requireSameTaskId || originalTask.taskId == null) {
            return true
        }
        return refreshedTask.taskIdCandidates
            .mapNotNull(::normalizeZhimaTreeTaskId)
            .any { it == originalTask.taskId }
    }

    private fun buildZhimaTreeSuccessLog(action: String, taskRef: ZhimaTreeTaskRef): String {
        return "芝麻树🌳[$action] " + taskRef.title + " #" +
            taskRef.prizeName.ifEmpty { "奖励已领取" }
    }

    private fun classifyZhimaTreeActionFailure(response: JSONObject?): String {
        val code = response?.optString("errorCode")
            .orEmpty()
            .ifBlank { response?.optString("resultCode").orEmpty() }
            .ifBlank { response?.optString("code").orEmpty() }
        val message = response?.let { extractZhimaTreeActionFailureMessage(it) }.orEmpty()
        return when (code) {
            "20020012" -> "parameter_invalid"
            "10001011" -> "business_limited"
            "10000702" -> "business_restricted"
            else -> when {
                message.contains("已领取") ||
                    message.contains("已发放") ||
                    message.contains("已完成") ||
                    message.contains("重复") -> "duplicate_or_already_done"
                message.contains("风控") ||
                    message.contains("风险") ||
                    message.contains("安全") ||
                    message.contains("cheating traffic", ignoreCase = true) -> "risk_limited"
                message.contains("次数超过限制") ||
                    message.contains("达到上限") ||
                    message.contains("当日上限") -> "business_limited"
                message.contains("营销规则验证不通过") ||
                    message.contains("验证不通过") -> "business_restricted"
                response?.optBoolean("canRetry", true) == false -> "non_retryable"
                else -> "rpc_failed"
            }
        }
    }

    private fun extractZhimaTreeActionFailureMessage(response: JSONObject): String {
        return response.optString("errorMsg")
            .ifBlank { response.optString("errorMessage") }
            .ifBlank { response.optString("resultDesc") }
            .ifBlank { response.optString("resultView") }
            .ifBlank { response.optString("desc") }
            .ifBlank { response.optString("memo") }
    }

    private fun logZhimaTreeActionFailure(
        action: String,
        stageCode: String,
        taskRef: ZhimaTreeTaskRef,
        actionResult: ZhimaTreeActionResult
    ) {
        val response = actionResult.response
        val code = response?.optString("errorCode")
            .orEmpty()
            .ifBlank { response?.optString("resultCode").orEmpty() }
            .ifBlank { response?.optString("code").orEmpty() }
            .ifBlank { "<empty>" }
        val message = response?.let { extractZhimaTreeActionFailureMessage(it) }
            .orEmpty()
            .ifBlank { "<empty>" }
        Log.error(
            TAG,
            "芝麻树🌳[${action}失败][${classifyZhimaTreeActionFailure(response)}] " +
                "${taskRef.title} | stage=$stageCode | taskId=${taskRef.taskId ?: "<null>"} " +
                "| candidates=${taskRef.describeCandidates()} | code=$code | msg=$message " +
                "| raw=${actionResult.rawResponse ?: "<empty>"}"
        )
    }

    private suspend fun receiveZhimaTreeTask(taskRef: ZhimaTreeTaskRef, successAction: String): Boolean {
        if (taskRef.taskId == null) {
            Log.sesame("芝麻树🌳[跳过无有效任务ID] ${taskRef.title} | candidates=${taskRef.describeCandidates()}"
            )
            return false
        }
        val receiveResult = doTaskActionResult(taskRef.taskId, "receive")
        if (!receiveResult.success) {
            logZhimaTreeActionFailure("领取奖励", "receive", taskRef, receiveResult)
            return false
        }
        Log.sesame(buildZhimaTreeSuccessLog(successAction, taskRef))
        return true
    }

    private suspend fun tryReceiveZhimaTreeTaskFallback(
        taskRef: ZhimaTreeTaskRef,
        reason: String
    ): Boolean {
        if (!taskRef.needManuallyReceiveAward || taskRef.taskId == null) {
            return false
        }
        Log.sesame("芝麻树🌳[$reason，尝试直接领取] ${taskRef.title} | candidates=${taskRef.describeCandidates()}"
        )
        return receiveZhimaTreeTask(taskRef, "完成任务")
    }

    /**
     * 执行任务动作：去完成 -> 刷新确认 -> 领取（必要时直接回退领取）
     */
    private suspend fun performTask(taskRef: ZhimaTreeTaskRef): Boolean {
        return try {
            val safeTaskId = taskRef.taskId
            if (safeTaskId == null) {
                Log.sesame("芝麻树🌳[跳过执行，无有效任务ID] ${taskRef.title}")
                return false
            }
            val sendResult = doTaskActionResult(safeTaskId, "send")
            if (!sendResult.success) {
                logZhimaTreeActionFailure("开始任务", "send", taskRef, sendResult)
                return false
            }
            val refreshResult = queryZhimaTreeTaskRefs()
            val refreshedTask = findMatchingZhimaTreeTask(taskRef, refreshResult.tasks)
            if (refreshedTask == null) {
                if (!refreshResult.hasConfirmedSnapshot) {
                    Log.sesame("芝麻树🌳[回查失败] ${taskRef.title} | candidates=${taskRef.describeCandidates()}"
                    )
                    return false
                }
                if (!taskRef.needManuallyReceiveAward) {
                    Log.sesame(buildZhimaTreeSuccessLog("完成任务", taskRef))
                    return true
                }
                if (tryReceiveZhimaTreeTaskFallback(taskRef, "回查未找到任务")) {
                    return true
                }
                return false
            }
            val receiveTarget = if (refreshedTask.taskId != null) refreshedTask else taskRef
            when (refreshedTask.status) {
                "TO_RECEIVE" -> return receiveZhimaTreeTask(receiveTarget, "完成任务")
                "RECEIVE_SUCCESS" -> {
                    if (refreshedTask.needManuallyReceiveAward) {
                        return receiveZhimaTreeTask(receiveTarget, "完成任务")
                    }
                    Log.sesame(buildZhimaTreeSuccessLog("完成任务", refreshedTask))
                    return true
                }
                "DONE", "COMPLETE", "FINISHED", "RECEIVED" -> {
                    Log.sesame(buildZhimaTreeSuccessLog("完成任务", refreshedTask))
                    return true
                }
            }
            if (tryReceiveZhimaTreeTaskFallback(receiveTarget, "回查状态未终态")) {
                return true
            }
            Log.sesame("芝麻树🌳[回查未完成] ${taskRef.title} | status=${refreshedTask.status.ifEmpty { "<empty>" }} " +
                    "| candidates=${refreshedTask.describeCandidates()}"
            )
            false
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            false
        }
    }

    /**
     * 获取任务奖励名称
     */
    private fun getPrizeName(task: JSONObject): String {
        var prizeName = ""
        try {
            var prizes = task.optJSONArray("validPrizeDetailDTO")
            if (prizes == null || prizes.length() == 0) {
                prizes = task.optJSONArray("prizeDetailDTOList")
            }

            if (prizes != null && prizes.length() > 0) {
                val prizeBase = prizes.getJSONObject(0).optJSONObject("prizeBaseInfoDTO")
                if (prizeBase != null) {
                    val rawName = prizeBase.optString("prizeName", "")

                    if (rawName.contains("能量")) {
                        val p = Pattern.compile("(森林)?能量(\\d+g?)")
                        val m = p.matcher(rawName)
                        if (m.find()) {
                            prizeName = m.group(0) ?: ""
                        } else {
                            prizeName = rawName
                        }
                    } else if (rawName.contains("净化值")) {
                        val p = Pattern.compile("(\\d+净化值|净化值\\d+)")
                        val m = p.matcher(rawName)
                        if (m.find()) {
                            prizeName = m.group(1) ?: ""
                        } else {
                            prizeName = rawName
                        }
                    } else {
                        prizeName = rawName
                    }
                }
            }

            // 如果没找到 PrizeDTO，尝试从 taskExtProps 解析
            if (prizeName.isEmpty()) {
                val taskExtProps = task.optJSONObject("taskExtProps")
                if (taskExtProps != null && taskExtProps.has("TASK_MORPHO_DETAIL")) {
                    val detail = JSONObject(taskExtProps.getString("TASK_MORPHO_DETAIL"))
                    val `val` = detail.optString("finishOneTaskGetPurificationValue", "")
                    if (!`val`.isEmpty() && "0" != `val`) {
                        prizeName = `val` + "净化值"
                    }
                }
            }
        } catch (_: Exception) {
        }
        return prizeName
    }

    private fun doTaskAction(taskId: String?, stageCode: String?): Boolean {
        return doTaskActionResult(taskId, stageCode).success
    }

    private fun doTaskActionResult(taskId: String?, stageCode: String?): ZhimaTreeActionResult {
        try {
            val safeTaskId = normalizeZhimaTreeTaskId(taskId)
                ?: return ZhimaTreeActionResult(false, null, null)
            val safeStageCode = stageCode?.takeIf { it.isNotBlank() }
                ?: return ZhimaTreeActionResult(false, null, null)
            val rawResponse = AntSesameCreditRpcCall.rentGreenTaskFinish(safeTaskId, safeStageCode)
                ?: return ZhimaTreeActionResult(false, null, null)
            val json = JSONObject(rawResponse)
            return ZhimaTreeActionResult(
                success = ResChecker.checkRes(TAG, json),
                response = json,
                rawResponse = rawResponse
            )
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            return ZhimaTreeActionResult(false, null, null)
        }
    }

    /**
     * 净化逻辑
     */
    private suspend fun doPurification(): Unit = CoroutineUtils.run {
        try {
            val homeRes = AntSesameCreditRpcCall.zhimaTreeHomePage() ?: return@run

            val homeJson = JSONObject(homeRes)
            if (!ResChecker.checkRes(TAG, homeJson)) return@run

            val result = homeJson.optJSONObject("extInfo")?.optJSONObject("zhimaTreeHomePageQueryResult")
            if (result == null) return@run

            // 获取净化分数（兼容 currentCleanNum）
            val score = result.optInt("purificationScore", result.optInt("currentCleanNum", 0))
            var treeCode = "ZHIMA_TREE"

            // 尝试获取 remainPurificationClickNum（新逻辑）
            var clicks = score / 100 // 默认兜底：按分数计算
            if (result.has("trees") && result.getJSONArray("trees").length() > 0) {
                val tree = result.getJSONArray("trees").getJSONObject(0)
                treeCode = tree.optString("treeCode", "ZHIMA_TREE")
                // 若服务端明确提供剩余点击次数，则优先使用
                if (tree.has("remainPurificationClickNum")) {
                    clicks = max(0, tree.optInt("remainPurificationClickNum", clicks))
                }
            }

            if (clicks <= 0) {
                Log.sesame("芝麻树🌳[无需净化] 净化值不足（当前: " + score + "g，可点击: " + clicks + "次）")
                return@run
            }

            Log.sesame("芝麻树🌳[开始净化] 可点击 $clicks 次")

            for (i in 0..<clicks) {
                val res = AntSesameCreditRpcCall.zhimaTreeCleanAndPush(treeCode) ?: break

                val json = JSONObject(res)
                if (!ResChecker.checkRes(TAG, json)) break

                val ext = json.optJSONObject("extInfo") ?: continue

                // 优先从标准路径取分数
                var newScore = ext.optJSONObject("zhimaTreeCleanAndPushResult")?.optInt("purificationScore", -1) ?: -1
                // 兼容旧结构：直接在 extInfo 顶层
                if (newScore == -1) {
                    newScore = ext.optInt("purificationScore", score - (i + 1) * 100)
                }

                val growth = ext.optJSONObject("zhimaTreeCleanAndPushResult")?.optJSONObject("currentTreeInfo")?.optInt("scoreSummary", -1) ?: -1

                var log = "芝麻树🌳[净化]第" + (i + 1) + "次 | 剩:" + newScore + "g"
                if (growth != -1) log += "|成长:$growth"
                Log.sesame("$log ✅")

            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    companion object {
        private val TAG: String = AntSesameCredit::class.java.simpleName
        private const val sesameCreditTaskBlacklistModule = "芝麻信用"

        private enum class SesameTaskFailureType {
            TERMINAL_DONE,
            BUSINESS_LIMIT,
            UNSUPPORTED_NO_CLOSURE,
            NON_RETRYABLE_INVALID,
            RETRYABLE_RPC,
            UNKNOWN_NEEDS_REVIEW
        }

        /**
         * 查询 + 自动领取可领取球（精简一行输出领取信息）
         */
        @SuppressLint("DefaultLocale")
        fun queryAndCollect() {
            try {
                var collectedRounds = 0
                var emptyRetryBeforeCollect = 0
                for (attempt in 0..2) {
                    val queryResp = AntSesameCreditRpcCall.Zmxy.queryScoreProgress()
                    if (queryResp.isEmpty()) {
                        return
                    }

                    val json = JSONObject(queryResp)
                    if (!ResChecker.checkRes(TAG, json)) {
                        if (attempt == 0) {
                            Log.sesame("攒芝麻分🎁[查询进度球失败，1.2秒后重试]")
                            Thread.sleep(1200)
                            continue
                        }
                        return
                    }

                    val totalWait = json.optJSONObject("totalWaitProcessVO") ?: return
                    val idList = totalWait.optJSONArray("totalProgressIdList")
                    if (idList == null || idList.length() == 0) {
                        if (collectedRounds == 0 && emptyRetryBeforeCollect == 0) {
                            emptyRetryBeforeCollect++
                            Thread.sleep(1200)
                            continue
                        }
                        return
                    }

                    val collectResp = AntSesameCreditRpcCall.Zmxy.collectProgressBall(idList) ?: return
                    val collectJson = JSONObject(collectResp)
                    if (isSesameProgressBallEmpty(collectJson)) {
                        Log.sesame("攒芝麻分🎁[暂无可领取进度球]")
                        return
                    }
                    if (!ResChecker.checkRes(TAG, collectJson)) {
                        if (attempt == 0) {
                            Log.sesame("攒芝麻分🎁[领取进度球失败，1.2秒后重试]")
                            Thread.sleep(1200)
                            continue
                        }
                        Log.error(TAG, "攒芝麻分🎁[领取失败]#$collectResp")
                        return
                    }

                    Log.sesame(String.format(
                            "领取完成 → 本次加速进度: %d, 当前加速倍率: %.2f",
                            collectJson.optInt("collectedAccelerateProgress", -1),
                            collectJson.optDouble("currentAccelerateValue", -1.0)
                        )
                    )
                    collectedRounds++
                    Thread.sleep(1200)
                }
            } catch (e: Exception) {
                Log.printStackTrace(TAG, "queryAndCollect err", e)
            }
        }

        /**
         * 检查是否满足运行芝麻信用任务的条件
         * @return bool
         */
        internal fun checkSesameCanRun(): Boolean {
            try {
                val s = AntSesameCreditRpcCall.queryHome()
                val jo = JSONObject(s)
                if (ResChecker.checkRes(TAG, jo)) {
                    val entrance = jo.optJSONObject("entrance") ?: return false
                    if (!entrance.optBoolean("openApp")) {
                        Log.sesame("芝麻信用💳[未开通，本轮跳过]")
                        return false
                    }
                    return true
                }
                Log.sesame("芝麻信用💳[V7首页探活失败，回退V8]")
            } catch (t: Throwable) {
                Log.sesame("芝麻信用💳[V7首页探活异常，回退V8]#${t.message}")
            }

            try {
                val s = AntSesameCreditRpcCall.queryHomeV8()
                val jo = JSONObject(s)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.error("$TAG.checkSesameCanRun.queryHomeV8", "芝麻信用💳[首页响应失败]#$s")
                    return false
                }
                val entrance = jo.optJSONObject("entrance") ?: return false
                if (!entrance.optBoolean("openApp")) {
                    Log.sesame("芝麻信用💳[未开通，本轮跳过]")
                    return false
                }
                return true
            } catch (t: Throwable) {
                Log.printStackTrace("$TAG.checkSesameCanRun", t)
                return false
            }
        }

        /**
         * 检查任务是否在黑名单中
         * @param taskTitle 任务标题
         * @return true表示在黑名单中，应该跳过
         */
        private fun isTaskInBlacklist(moduleName: String, taskTitle: String?): Boolean {
            return TaskBlacklist.isTaskInBlacklist(moduleName, taskTitle)
        }

        private fun isSesameTaskInBlacklist(moduleName: String, task: JSONObject, taskTitle: String): Boolean {
            if (isTaskInBlacklist(moduleName, taskTitle)) {
                return true
            }
            val templateId = task.optString("templateId")
            return templateId.isNotBlank() && isTaskInBlacklist(moduleName, templateId)
        }

        private fun shouldSkipShareAssistSesameTask(task: JSONObject): Boolean {
            return task.optBoolean("shareAssist", false) ||
                task.optString("title").contains("邀请好友") ||
                task.optString("subTitle").contains("邀请成功")
        }

        private fun isTransientSesameTaskError(errorCode: String, resultView: String = ""): Boolean {
            if (errorCode.isEmpty() && resultView.isEmpty()) {
                return false
            }
            return errorCode in setOf(
                "OP_REPEAT_CHECK",
                "SYSTEM_BUSY",
                "NETWORK_ERROR",
                "COLLECT_CREDIT_FEEDBACK_FAILED"
            ) || resultView.contains("请稍后") ||
                resultView.contains("频繁") ||
                resultView.contains("网络不可用")
        }

        private fun isSesameProgressBallEmpty(response: JSONObject): Boolean {
            val resultCode = response.optString("resultCode", response.optString("errorCode", ""))
            val resultView = response.optString("resultView")
            return resultCode == "INIT_SCORE_BALL_EMPTY" ||
                resultCode == "无可领取的信用球" ||
                resultView.contains("无可领取的信用球")
        }

        private fun isSesameAlchemyCapReached(response: JSONObject): Boolean {
            val resultCode = response.optString("resultCode", response.optString("errorCode", ""))
            val resultView = response.optString("resultView")
            return resultCode == "CAP_REACHED" || resultView.contains("盖帽值拦截")
        }

        private fun formatSesameAlchemyReward(task: JSONObject): String {
            val rewardAmount = task.optInt("rewardAmount", 0)
            return when (task.optString("rewardType", "ZML")) {
                "LJCS" -> rewardAmount.toString() + "次炼金次数"
                "ZML" -> rewardAmount.toString() + "粒"
                else -> {
                    val rewardType = task.optString("rewardType")
                    if (rewardType.isEmpty()) {
                        rewardAmount.toString() + "粒"
                    } else {
                        rewardAmount.toString() + rewardType
                    }
                }
            }
        }

        private fun buildSesameRpcMessage(response: JSONObject, rawResponse: String): String {
            return sequenceOf(
                response.optString("resultView"),
                response.optString("resultDesc"),
                response.optString("errMsg"),
                response.optString("errorMessage"),
                response.optString("memo"),
                rawResponse
            ).firstOrNull { it.isNotBlank() }.orEmpty()
        }

        private fun parseJSONObjectOrNull(raw: String?): JSONObject? {
            val value = raw?.trim().orEmpty()
            if (value.isBlank() || !value.startsWith("{")) {
                return null
            }
            return try {
                JSONObject(value)
            } catch (_: Throwable) {
                null
            }
        }

        private fun decodeUrlComponentRepeated(value: String?, maxRounds: Int = 3): String {
            var current = value?.trim().orEmpty()
            if (current.isBlank()) {
                return ""
            }
            repeat(maxRounds) {
                val decoded = try {
                    URLDecoder.decode(current, "UTF-8")
                } catch (_: Throwable) {
                    return current
                }
                if (decoded == current) {
                    return current
                }
                current = decoded
            }
            return current
        }

        private fun extractQueryParam(rawUrl: String?, name: String): String? {
            val url = rawUrl?.takeIf { it.isNotBlank() } ?: return null
            val marker = "$name="
            for (candidate in listOf(url, decodeUrlComponentRepeated(url))) {
                val startIndex = candidate.indexOf(marker)
                if (startIndex < 0) {
                    continue
                }
                val valueStart = startIndex + marker.length
                val valueEnd = candidate.indexOf('&', valueStart).takeIf { it >= 0 } ?: candidate.length
                val rawValue = candidate.substring(valueStart, valueEnd)
                val decodedValue = decodeUrlComponentRepeated(rawValue)
                if (decodedValue.isNotBlank()) {
                    return decodedValue
                }
            }
            return null
        }

        private fun buildAdTaskSpaceCodeFromRenderConfigKey(rawRenderConfigKey: String?): String? {
            val decoded = decodeUrlComponentRepeated(rawRenderConfigKey)
            if (decoded.isBlank()) {
                return null
            }
            return decoded.takeIf { it.contains("adPosId#") || it.contains("_duration=") }
        }

        private fun extractAdTaskSpaceCodeFromCdpQueryParams(rawUrl: String?): String? {
            val rawParams = extractQueryParam(rawUrl, "cdpQueryParams")
                ?: extractQueryParam(rawUrl, "useCdpQueryParams")
                ?: return null
            val params = parseJSONObjectOrNull(rawParams) ?: return null
            return buildAdTaskSpaceCodeFromRenderConfigKey(params.optString("spaceCode"))
                ?: buildAdTaskSpaceCodeFromRenderConfigKey(params.optString("renderConfigKey"))
        }

        private fun extractAdRenderConfigValue(rawRenderConfigKey: String?, key: String): String {
            val renderConfigKey = buildAdTaskSpaceCodeFromRenderConfigKey(rawRenderConfigKey)
                ?: decodeUrlComponentRepeated(rawRenderConfigKey)
            if (renderConfigKey.isBlank()) {
                return ""
            }
            val prefix = "$key#"
            return renderConfigKey.split("##")
                .firstOrNull { it.startsWith(prefix) }
                ?.substring(prefix.length)
                .orEmpty()
        }

        private fun buildAdTaskSpaceCodeFromLogExtMap(
            logExtMap: JSONObject?,
            fallbackSpaceCode: String? = null,
            fallbackRewardNum: String? = null
        ): String? {
            if (logExtMap == null) {
                return null
            }
            val adPositionId = logExtMap.optString("adPositionId")
            val taskType = logExtMap.optString("taskType")
            val mediaScene = logExtMap.optString("mediaScene").ifBlank { logExtMap.optString("ch") }
            val rewardNum = logExtMap.optString("rewardNum").ifBlank { fallbackRewardNum.orEmpty() }
            val spaceCode = logExtMap.optString("spaceCode").ifBlank { fallbackSpaceCode.orEmpty() }
            if (adPositionId.isBlank() || taskType.isBlank() || mediaScene.isBlank() ||
                rewardNum.isBlank() || spaceCode.isBlank()
            ) {
                return null
            }
            val sceneCode = logExtMap.optString("sceneCode")
            val expCode = logExtMap.optString("expCode").ifBlank { "null" }
            return "adPosId#$adPositionId##taskType#$taskType##sceneCode#$sceneCode" +
                "##mediaScene#$mediaScene##rewardNum#$rewardNum##spaceCode#$spaceCode##expCode#$expCode"
        }

        private fun resolveAdTaskSpaceCode(
            logExtMap: JSONObject?,
            actionUrl: String?,
            fallbackSpaceCode: String? = null,
            fallbackRewardNum: String? = null
        ): String? {
            val candidates = listOf(
                logExtMap?.optString("renderConfigKey"),
                extractQueryParam(actionUrl, "renderConfigKey"),
                extractAdTaskSpaceCodeFromCdpQueryParams(actionUrl),
                logExtMap?.optString("spaceCode"),
                fallbackSpaceCode
            )
            for (candidate in candidates) {
                buildAdTaskSpaceCodeFromRenderConfigKey(candidate)?.let {
                    return it
                }
            }
            return buildAdTaskSpaceCodeFromLogExtMap(logExtMap, fallbackSpaceCode, fallbackRewardNum)
        }

        private fun resolveSesameAdTaskSpaceCode(task: JSONObject, logExtMap: JSONObject): String? {
            if ("LJCS" == task.optString("rewardType")) {
                val ch = logExtMap.optString("ch")
                val adPositionId = logExtMap.optString("adPositionId")
                if (ch.isNotBlank() && adPositionId.isNotBlank()) {
                    return "${ch}_${adPositionId}_duration=5"
                }
            }
            resolveAdTaskSpaceCode(
                logExtMap,
                task.optString("actionUrl"),
                fallbackRewardNum = task.optString("rewardAmount")
            )?.let {
                return it
            }
            return null
        }

        private fun isAdTaskFinishSuccess(response: JSONObject, rawResponse: String): Boolean {
            return ResChecker.checkRes(TAG, response) ||
                "0" == response.optString("errCode") ||
                "SUCCESS".equals(response.optString("resultCode"), ignoreCase = true) ||
                "SUCCESS".equals(response.optString("errorCode"), ignoreCase = true) ||
                rawResponse.contains("业务自发奖")
        }

        private fun isAdTaskRetryable(response: JSONObject, message: String): Boolean {
            val code = response.optString(
                "errorCode",
                response.optString("resultCode", response.optString("errCode", ""))
            )
            return response.optBoolean("needRetry", false) || isTransientSesameTaskError(code, message)
        }

        private fun confirmAlchemyAdTaskFinished(
            adTaskBizId: String,
            taskTitle: String,
            logPrefix: String
        ): Boolean? {
            return try {
                val lastOperateRes = AntSesameCreditRpcCall.queryLastOperateTask("alchemy")
                val lastOperateJo = JSONObject(lastOperateRes)
                if (!ResChecker.checkRes(TAG, lastOperateJo)) {
                    Log.sesame("$logPrefix[炼金次数回查失败]#$taskTitle - $lastOperateRes")
                    return null
                }
                val lastTask = lastOperateJo.optJSONObject("data")
                    ?.optJSONObject("lastOperateTaskVO")
                val matched = lastTask?.optBoolean("finishFlag", false) == true &&
                    "LJCS" == lastTask.optString("rewardType") &&
                    (adTaskBizId.isBlank() || adTaskBizId == lastTask.optString("adTaskBizId"))
                if (!matched) {
                    Log.sesame("$logPrefix[炼金次数回查未确认]#$taskTitle | adTaskBizId=$adTaskBizId | last=$lastTask"
                    )
                    return false
                }
                true
            } catch (t: Throwable) {
                Log.printStackTrace("$TAG.confirmAlchemyAdTaskFinished", t)
                null
            }
        }

        private fun isSesameAdTaskAlreadyFinished(response: JSONObject, message: String): Boolean {
            val resultCode = response.optString(
                "resultCode",
                response.optString("errorCode", response.optString("errCode", ""))
            )
            return resultCode in setOf(
                "TASK_ALREADY_FINISHED",
                "TASK_HAS_FINISHED",
                "REPEAT_FINISH",
                "REPEAT_REWARD"
            ) || message.contains("已完成") ||
                message.contains("已领取") ||
                message.contains("重复")
        }

        private fun autoBlacklistSesameTaskIfNeeded(
            moduleName: String,
            taskTitle: String,
            errorCode: String,
            resultView: String = "",
            action: String = "task"
        ) {
            if (taskTitle.isBlank() || (errorCode.isBlank() && resultView.isBlank())) {
                return
            }
            val code = errorCode.ifBlank { "UNKNOWN" }
            val message = resultView.ifBlank { "<empty>" }
            val rpc = when (action) {
                "join" -> "AntSesameCreditRpcCall.joinSesameTask"
                "feedback" -> "AntSesameCreditRpcCall.feedBackSesameTask"
                "finish" -> "AntSesameCreditRpcCall.finishSesameTask"
                "adFinish" -> "AntSesameCreditRpcCall.taskFinish"
                else -> "AntSesameCreditRpcCall.$action"
            }
            val detail = "module=$moduleName taskId=$taskTitle taskName=$taskTitle action=$action rpc=$rpc " +
                "code=$code msg=$message raw=$message"
            when (classifySesameTaskFailure(errorCode, resultView)) {
                SesameTaskFailureType.TERMINAL_DONE -> {
                    Log.sesame("$moduleName[$taskTitle] classification=TERMINAL_DONE decision=MARK_HANDLED $detail")
                }

                SesameTaskFailureType.BUSINESS_LIMIT -> {
                    Log.sesame("$moduleName[$taskTitle] classification=BUSINESS_LIMIT decision=STOP_TODAY_OR_CURRENT_CHAIN $detail")
                }

                SesameTaskFailureType.UNSUPPORTED_NO_CLOSURE -> {
                    blacklistClassifiedSesameTask(moduleName, taskTitle, errorCode)
                    Log.error(TAG, "$moduleName[$taskTitle] classification=UNSUPPORTED_NO_CLOSURE decision=BLACKLIST reason=未抓到稳定完成RPC $detail")
                }

                SesameTaskFailureType.NON_RETRYABLE_INVALID -> {
                    blacklistClassifiedSesameTask(moduleName, taskTitle, errorCode)
                    Log.error(TAG, "$moduleName[$taskTitle] classification=NON_RETRYABLE_INVALID decision=BLACKLIST $detail")
                }

                SesameTaskFailureType.RETRYABLE_RPC -> {
                    Log.error(TAG, "$moduleName[$taskTitle] classification=RETRYABLE_RPC decision=RETRY_LATER $detail")
                }

                SesameTaskFailureType.UNKNOWN_NEEDS_REVIEW -> {
                    Log.error(TAG, "$moduleName[$taskTitle] classification=UNKNOWN_NEEDS_REVIEW decision=LOG_ONLY $detail")
                }
            }
        }

        private fun blacklistClassifiedSesameTask(moduleName: String, taskTitle: String, errorCode: String) {
            if (errorCode.isNotBlank()) {
                autoAddToBlacklist(moduleName, taskTitle, taskTitle, errorCode)
            }
            TaskBlacklist.addToBlacklist(moduleName, taskTitle, taskTitle)
        }

        private fun classifySesameTaskFailure(errorCode: String, resultView: String): SesameTaskFailureType {
            val code = errorCode.trim()
            val message = resultView.trim()
            return when {
                code in setOf("TASK_ALREADY_FINISHED", "TASK_HAS_FINISHED", "REPEAT_FINISH", "REPEAT_REWARD") ||
                    containsAnySesame(message, "已完成", "已领取", "已经领取", "重复领取", "重复领奖", "重复完成") ->
                    SesameTaskFailureType.TERMINAL_DONE

                code in setOf("CAMP_TRIGGER_ERROR", "PROMISE_TODAY_FINISH_TIMES_LIMIT", "PROMISE_HAS_PROCESSING_TEMPLATE", "104") ||
                    code.contains("LIMIT", ignoreCase = true) ||
                    containsAnySesame(message, "上限", "限制", "受限", "不可领取", "资格不足", "风控", "风险", "模板处理中") ->
                    SesameTaskFailureType.BUSINESS_LIMIT

                code == "400000040" ||
                    containsAnySesame(message, "不支持rpc调用", "不支持RPC完成") ->
                    SesameTaskFailureType.UNSUPPORTED_NO_CLOSURE

                code in setOf("20020012", "TASK_ID_INVALID", "ILLEGAL_ARGUMENT", "PROMISE_TEMPLATE_NOT_EXIST") ||
                    containsAnySesame(message, "参数错误", "任务ID非法", "模板不存在", "生活记录模板不存在") ->
                    SesameTaskFailureType.NON_RETRYABLE_INVALID

                code in setOf("3000", "REMOTE_INVOKE_EXCEPTION", "OP_REPEAT_CHECK", "SYSTEM_BUSY", "NETWORK_ERROR", "COLLECT_CREDIT_FEEDBACK_FAILED") ||
                    isTransientSesameTaskError(code, message) ||
                    containsAnySesame(message, "系统出错", "系统繁忙", "稍后", "繁忙", "频繁", "重试", "网络不可用") ->
                    SesameTaskFailureType.RETRYABLE_RPC

                else -> SesameTaskFailureType.UNKNOWN_NEEDS_REVIEW
            }
        }

        private fun containsAnySesame(value: String, vararg keywords: String): Boolean {
            return keywords.any { keyword -> value.contains(keyword, ignoreCase = true) }
        }

        private suspend fun joinSesameTaskWithFallback(
            taskTemplateId: String,
            taskTitle: String,
            logPrefix: String,
            primarySceneCode: String? = null
        ): Pair<String, JSONObject> {
            var joinRes = AntSesameCreditRpcCall.joinSesameTask(taskTemplateId, primarySceneCode)
            var joinJo = JSONObject(joinRes)
            val joinResultCode = joinJo.optString("resultCode", joinJo.optString("errorCode", ""))
            if (!ResChecker.checkRes(TAG, joinJo) &&
                !primarySceneCode.isNullOrBlank() &&
                "PROMISE_TODAY_FINISH_TIMES_LIMIT" != joinResultCode
            ) {
                Log.sesame("$logPrefix[领取任务扩展参数失败，回退简版参数]#$taskTitle")
                joinRes = AntSesameCreditRpcCall.joinSesameTask(taskTemplateId)
                joinJo = JSONObject(joinRes)
            }
            return joinRes to joinJo
        }

        private suspend fun reportSesameTaskFeedback(
            task: JSONObject,
            taskTitle: String,
            logPrefix: String,
            moduleName: String,
            version: String = "new",
            sceneCode: String? = null,
            preferExtended: Boolean = false
        ): Boolean {
            val templateId = task.optString("templateId")
            if (templateId.isBlank()) {
                Log.sesame("$logPrefix[任务回调缺少templateId]#$taskTitle")
                return false
            }

            val bizType = task.optString("bizType")
            val hasExtendedArgs = bizType.isNotBlank() && !sceneCode.isNullOrBlank()
            val feedbackAttempts = mutableListOf<Pair<String, suspend () -> String>>()
            if (preferExtended && hasExtendedArgs) {
                feedbackAttempts.add(
                    "扩展参数" to suspend {
                        AntSesameCreditRpcCall.feedBackSesameTask(templateId, bizType, sceneCode, version)
                    }
                )
            }
            feedbackAttempts.add("简版参数" to suspend { AntSesameCreditRpcCall.feedBackSesameTask(templateId) })
            if (!preferExtended && hasExtendedArgs) {
                feedbackAttempts.add(
                    "扩展参数" to suspend {
                        AntSesameCreditRpcCall.feedBackSesameTask(templateId, bizType, sceneCode, version)
                    }
                )
            }

            var lastErrorCode = ""
            var lastResultView = ""
            var lastFeedbackRes = ""
            for ((index, attempt) in feedbackAttempts.withIndex()) {
                val (attemptLabel, call) = attempt
                val feedbackRes = call()
                lastFeedbackRes = feedbackRes
                val feedbackJo = JSONObject(feedbackRes)
                if (ResChecker.checkRes(TAG, feedbackJo)) {
                    return true
                }
                lastErrorCode = feedbackJo.optString(
                    "errorCode",
                    feedbackJo.optString("resultCode", "")
                )
                lastResultView = feedbackJo.optString("resultView").ifEmpty {
                    feedbackJo.optString("errorMessage", feedbackRes)
                }
                if (index < feedbackAttempts.lastIndex) {
                    Log.sesame("$logPrefix[任务回调${attemptLabel}失败，尝试兼容参数]#$taskTitle - $lastResultView"
                    )
                }
            }
            Log.error(TAG, "$logPrefix[任务回调失败]#$taskTitle - $lastResultView")
            autoBlacklistSesameTaskIfNeeded(
                moduleName,
                taskTitle,
                lastErrorCode,
                lastResultView.ifEmpty { lastFeedbackRes },
                "feedback"
            )
            return false
        }

    private suspend fun handleSesameAdTask(
        task: JSONObject,
        taskTitle: String,
        logPrefix: String,
        moduleName: String
    ): Boolean {
        val logExtMap = task.optJSONObject("logExtMap")
        if (logExtMap == null) {
            Log.sesame("$logPrefix[广告任务缺少logExtMap]#$taskTitle")
            return false
        }
        val bizId = logExtMap.optString("bizId")
        if (bizId.isEmpty()) {
            Log.sesame("$logPrefix[广告任务缺少bizId]#$taskTitle")
            return false
        }
        Log.sesame("$logPrefix[广告任务准备]#$taskTitle")
        val isAlchemyFreeCountTask = "LJCS" == task.optString("rewardType")
        val adTaskBizId = task.optString("adTaskBizId").ifEmpty { bizId }
        if (isAlchemyFreeCountTask) {
            val rewardRes = AntSesameCreditRpcCall.adRewardLjcs(adTaskBizId)
            val rewardJo = JSONObject(rewardRes)
            if (!ResChecker.checkRes(TAG, rewardJo)) {
                val rewardMsg = buildSesameRpcMessage(rewardJo, rewardRes)
                if (isSesameAdTaskAlreadyFinished(rewardJo, rewardMsg)) {
                    Log.sesame("$logPrefix[炼金次数登记已完成，继续浏览上报]#$taskTitle - $rewardMsg")
                } else if (isAdTaskRetryable(rewardJo, rewardMsg)) {
                    Log.sesame("$logPrefix[炼金次数登记暂时不可用]#$taskTitle - $rewardMsg")
                    return false
                } else {
                    Log.error(TAG, "$logPrefix[炼金次数登记失败]#$taskTitle - $rewardMsg")
                    return false
                }
            }
        }
        val spaceCode = resolveSesameAdTaskSpaceCode(task, logExtMap)
        if (!spaceCode.isNullOrBlank()) {
            val layerRes = AntSesameCreditRpcCall.adTaskApplayerQuery(spaceCode)
            val layerResponse = JSONObject(layerRes)
            if (!ResChecker.checkRes(TAG, layerResponse) && "0" != layerResponse.optString("errCode")) {
                val layerMsg = buildSesameRpcMessage(layerResponse, layerRes)
                val layerCode = layerResponse.optString(
                    "errorCode",
                    layerResponse.optString("resultCode", layerResponse.optString("errCode", ""))
                )
                if (isAdTaskRetryable(layerResponse, layerMsg)) {
                    Log.sesame("$logPrefix[广告浏览配置暂时不可用]#$taskTitle - $layerMsg")
                } else {
                    Log.error(TAG, "$logPrefix[广告浏览配置失败]#$taskTitle - code=$layerCode msg=$layerMsg")
                }
                return false
            }
        } else {
            Log.sesame("$logPrefix[广告浏览配置缺失，直接上报]#$taskTitle")
        }
        val adFinishRes = AntSesameCreditRpcCall.taskFinish(bizId, includeExtendInfo = true)
        val adFinishJo = JSONObject(adFinishRes)
        if (isAdTaskFinishSuccess(adFinishJo, adFinishRes)) {
            if (isAlchemyFreeCountTask) {
                confirmAlchemyAdTaskFinished(adTaskBizId, taskTitle, logPrefix)
            }
            Log.sesame("$logPrefix[广告任务完成: " + taskTitle + "]#获得" + formatSesameAlchemyReward(task))
            return true
        }
        val errorCode = adFinishJo.optString(
            "errorCode",
            adFinishJo.optString("resultCode", adFinishJo.optString("errCode", ""))
        )
        val resultView = buildSesameRpcMessage(adFinishJo, adFinishRes)
        if (isSesameAdTaskAlreadyFinished(adFinishJo, resultView)) {
            Log.sesame("$logPrefix[广告任务已完成，跳过重复上报]#$taskTitle - $resultView")
            return true
        }
        autoBlacklistSesameTaskIfNeeded(moduleName, taskTitle, errorCode, resultView, "adFinish")
        return false
    }

        /**
         * 芝麻信用-领取并完成任务（带结果统计）
         * @param taskList 任务列表
         * @return 任务处理结果
         * @throws JSONException JSON解析异常，上抛处理
         */
        @Throws(JSONException::class)
        private suspend fun joinAndFinishSesameTaskWithResult(
            taskList: JSONArray,
            transientSkippedTasks: MutableSet<String>
        ): SesameTaskBatchResult {
            var completedCount = 0
            var skippedCount = 0
            var interrupted = false
            var joinLimitReached = hasFlagToday(StatusFlags.FLAG_SESAME_JOIN_LIMIT_REACHED)
            var joinLimitLogged = false

            for (i in 0..<taskList.length()) {
                val task = taskList.getJSONObject(i)
                val taskTitle = if (task.has("title")) task.getString("title") else "未知任务"

                val finishFlag = task.optBoolean("finishFlag", false)
                val actionText = task.optString("actionText", "")

                if (transientSkippedTasks.contains(taskTitle)) {
                    Log.sesame("芝麻信用💳[跳过本轮频控任务]#$taskTitle")
                    skippedCount++
                    continue
                }

                if (finishFlag || "已完成" == actionText) {
                    Log.sesame("芝麻信用💳[跳过已完成任务]#$taskTitle")
                    skippedCount++
                    continue
                }

                var recordId = task.optString("recordId", "")
                if (recordId.isEmpty() && joinLimitReached) {
                    if (!joinLimitLogged) {
                        Log.sesame("芝麻信用💳[领取任务已达当日上限] 今日不再领取新任务")
                        joinLimitLogged = true
                    }
                    skippedCount++
                    continue
                }

                if (isSesameTaskInBlacklist(sesameCreditTaskBlacklistModule, task, taskTitle)) {
                    Log.sesame("芝麻信用💳[跳过黑名单任务]#$taskTitle")
                    skippedCount++
                    continue
                }

                if (shouldSkipShareAssistSesameTask(task)) {
                    Log.sesame("芝麻信用💳[跳过助力型任务]#$taskTitle")
                    skippedCount++
                    continue
                }

                val bizType = task.optString("bizType", "")
                if ("AD_TASK" == bizType) {
                    if (handleSesameAdTask(task, taskTitle, "芝麻信用💳", sesameCreditTaskBlacklistModule)) {
                        completedCount++
                    } else {
                        skippedCount++
                    }
                    continue
                }

                if (!task.has("templateId")) {
                    Log.sesame("芝麻信用💳[跳过缺少templateId任务]#$taskTitle")
                    skippedCount++
                    continue
                }

                val taskTemplateId = task.getString("templateId")
                val needCompleteNum = if (task.has("needCompleteNum")) task.getInt("needCompleteNum") else 1
                val completedNum = task.optInt("completedNum", 0)
                if (completedNum >= needCompleteNum && needCompleteNum > 0) {
                    Log.sesame("芝麻信用💳[跳过已达完成次数]#$taskTitle")
                    skippedCount++
                    continue
                }
                var s: String?
                var responseObj: JSONObject?

                val actionUrl = task.optString("actionUrl", "")
                if (actionUrl.contains("jumpAction") && !actionUrl.contains("jumpAction=userGrowth")) {
                    Log.sesame("芝麻信用💳[跳过跳转APP任务]#$taskTitle")
                    skippedCount++
                    continue
                }

                var taskCompleted = false
                if (recordId.isEmpty()) {
                    val joinResult = joinSesameTaskWithFallback(
                        taskTemplateId,
                        taskTitle,
                        "芝麻信用💳",
                        "zml"
                    )
                    s = joinResult.first
                    responseObj = joinResult.second
                    val joinResultCode = responseObj.optString("resultCode", responseObj.optString("errorCode", ""))
                    if ("PROMISE_TODAY_FINISH_TIMES_LIMIT" == joinResultCode) {
                        joinLimitReached = true
                        setFlagToday(StatusFlags.FLAG_SESAME_JOIN_LIMIT_REACHED)
                        Log.sesame("芝麻信用💳[领取任务已达当日上限] 今日不再领取新任务")
                        joinLimitLogged = true
                        skippedCount++
                        continue
                    }
                    val joinResultView = responseObj.optString("resultView").ifEmpty {
                        responseObj.optString("errorMessage", s ?: "")
                    }
                    if (isTransientSesameTaskError(joinResultCode, joinResultView)) {
                        transientSkippedTasks.add(taskTitle)
                        Log.error(
                            TAG,
                            "芝麻信用💳[$taskTitle] classification=RETRYABLE_RPC decision=RETRY_LATER " +
                                "module=$sesameCreditTaskBlacklistModule taskId=$taskTemplateId taskName=$taskTitle " +
                                "action=join rpc=AntSesameCreditRpcCall.joinSesameTask " +
                                "code=${joinResultCode.ifEmpty { "UNKNOWN" }} msg=$joinResultView raw=${s ?: responseObj}"
                        )
                        skippedCount++
                        continue
                    }
                    if (!ResChecker.checkRes(TAG, responseObj)) {
                        Log.error(TAG, "芝麻信用💳[领取任务" + taskTitle + "失败]#" + s)
                        val errorCode = responseObj.optString("errorCode", responseObj.optString("resultCode", ""))
                        val resultView = responseObj.optString("resultView", s ?: "")
                        if (!errorCode.isEmpty()) {
                            autoBlacklistSesameTaskIfNeeded(sesameCreditTaskBlacklistModule, taskTitle, errorCode, resultView, "join")
                        }
                        skippedCount++
                        if (isSesameTaskFlowInterrupted(responseObj)) {
                            interrupted = true
                            break
                        }
                        continue
                    }
                    recordId = responseObj.optJSONObject("data")?.optString("recordId").orEmpty()
                    if (recordId.isEmpty()) {
                        Log.error(TAG, "芝麻信用💳[任务" + taskTitle + "未获取到recordId]#" + task)
                        skippedCount++
                        continue
                    }
                }

                if (!reportSesameTaskFeedback(
                        task,
                        taskTitle,
                        "芝麻信用💳",
                        sesameCreditTaskBlacklistModule,
                        sceneCode = "zml",
                        preferExtended = true
                    )
                ) {
                    skippedCount++
                    if (isSesameTaskFlowInterrupted()) {
                        interrupted = true
                        break
                    }
                    continue
                }

                s = AntSesameCreditRpcCall.finishSesameTask(recordId)
                responseObj = JSONObject(s)
                val errorCode = responseObj.optString("errorCode", responseObj.optString("resultCode", ""))
                val resultView = responseObj.optString("resultView").ifEmpty {
                    responseObj.optString("errorMessage", s ?: "")
                }
                if (isTransientSesameTaskError(errorCode, resultView)) {
                    transientSkippedTasks.add(taskTitle)
                    Log.error(
                        TAG,
                        "芝麻信用💳[$taskTitle] classification=RETRYABLE_RPC decision=RETRY_LATER " +
                            "module=$sesameCreditTaskBlacklistModule taskId=$recordId taskName=$taskTitle " +
                            "action=finish rpc=AntSesameCreditRpcCall.finishSesameTask " +
                            "code=${errorCode.ifEmpty { "UNKNOWN" }} msg=$resultView raw=${s ?: responseObj}"
                    )
                    if (isSesameTaskFlowInterrupted(responseObj)) {
                        interrupted = true
                    }
                } else if (ResChecker.checkRes(TAG, responseObj)) {
                    Log.sesame("芝麻信用💳[完成任务" + taskTitle + "]#(" + (completedNum + 1) + "/" + needCompleteNum + "天)"
                    )
                    taskCompleted = true
                } else {
                    Log.error(TAG, "芝麻信用💳[完成任务" + taskTitle + "失败]#" + s)
                    if (!errorCode.isEmpty()) {
                        autoBlacklistSesameTaskIfNeeded(sesameCreditTaskBlacklistModule, taskTitle, errorCode, resultView, "finish")
                    }
                    if (isSesameTaskFlowInterrupted(responseObj)) {
                        interrupted = true
                    }
                }

                if (taskCompleted) {
                    completedCount++
                } else {
                    skippedCount++
                }
                if (interrupted) {
                    break
                }
            }

            return SesameTaskBatchResult(completedCount, skippedCount, interrupted)
        }

        private fun isSesameTaskFlowInterrupted(response: JSONObject? = null): Boolean {
            if (ApplicationHookConstants.isOffline()) {
                return true
            }
            if (response == null) {
                return false
            }
            val resultCode = response.optString("resultCode").ifEmpty {
                response.optString("errorCode").ifEmpty {
                    response.optString("code")
                }
            }
            val resultDesc = response.optString("resultDesc").ifEmpty {
                response.optString("errorMsg")
            }
            val resultView = response.optString("resultView")
            return resultCode == "I07" ||
                resultDesc.contains("需要验证") ||
                resultView.contains("需要验证")
        }

    }

    /**
     * 芝麻粒兑换道具
     * 仿照会员积分兑换逻辑：遍历列表更新Map，同时匹配用户设置进行兑换
     */
    internal suspend fun doSesameGrainExchange(): Unit = CoroutineUtils.run {
        // 每日只运行一次，避免重复请求
        if (hasFlagToday(StatusFlags.FLAG_SESAME_GRAIN_EXCHANGE_DONE)) {
            return@run
        }

        try {
            val userId = UserMap.currentUid
            // 获取用户在配置中选中的商品ID列表（白名单）
            val targetIds = sesameGrainExchangeList?.value ?: emptySet()
            var currentPage = 1
            // 限制最大页数，防止无限循环（抓包看大概也就3-5页）
            val maxPage = 10
            val pageSize = 99 //适当调整pageSize 减少请求
            var hasNextPage = true

            while (hasNextPage && currentPage <= maxPage) {
                // 稍微延时，避免请求过快被风控
                GlobalThreadPools.sleepCompat(1500L)
                // 调用 RPC 获取列表
                val jo = JSONObject(AntSesameCreditRpcCall.queryExchangeList(currentPage, pageSize))
//                所有的请求使用这个类方法检查过滤就行了
                if (!ResChecker.checkRes(TAG, jo)) {//一次失败直接return不要break
                    Log.error(TAG, "芝麻粒商品列表校验失败: $jo")
                    return@run
                }

                val data = jo.optJSONObject("data") ?: return@run //没数据也return
                val list = data.optJSONArray("awardTemplateList") ?: return@run

                // 遍历当前页的商品
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val name = item.optString("awardName", "未知商品")
                    val id = item.optString("awardTemplateId")
                    val pointNeeded = item.optString("point", "0")
                    val remainingBudget = item.optInt("remainingBudget", 0) // 库存
                    if (id.isEmpty()) continue
                    // 1. 核心步骤：记录 ID 和 名称 的映射关系
                    // 这样下次进入设置界面，就能看到中文名称了
                    IdMapManager.getInstance(SesameGiftMap::class.java).add(id, name)
                    // 2. 检查是否在用户的待兑换列表里（白名单）
                    val inWhiteList = targetIds.contains(id)
                    if (!inWhiteList) {
                        // 如果没勾选，就跳过，不做处理
                        continue
                    }
                    // 3. 检查库存
                    if (remainingBudget <= 0) {
                        Log.sesame("跳过[$name]: 库存不足")
                        continue
                    }
                    // 4. 执行兑换 (这里不加每日限制判断了，只要有库存且勾选了就尝试兑换)
                    Log.sesame("准备兑换[$name], ID: $id, 需芝麻粒: $pointNeeded")
                    exchangeSesameGift(id, name, pointNeeded)
                }
                // 判断是否有下一页
                hasNextPage = data.optBoolean("hasNext", false)
                currentPage++
            }

            // 保存映射关系到本地文件 sesame_gift.json
            IdMapManager.getInstance(SesameGiftMap::class.java).save(userId)
            Log.sesame("芝麻粒兑换任务处理完毕，商品列表已更新")
            // 标记今日已完成
            setFlagToday(StatusFlags.FLAG_SESAME_GRAIN_EXCHANGE_DONE)

        } catch (t: Throwable) {//这里
            Log.printStackTrace(TAG, "doSesameGrainExchange 运行异常:", t)
        }
    }

    /**
     * 执行具体的芝麻粒兑换请求
     */
    private fun exchangeSesameGift(templateId: String, name: String, point: String): Boolean {
        try {
            // 调用兑换接口
            val resString = AntSesameCreditRpcCall.obtainAward(templateId)
            val jo = JSONObject(resString)

            // 检查结果
            if (ResChecker.checkRes(TAG, jo)) {
                val recordId = jo.optJSONObject("data")?.optString("awardRecordId", "")
                Log.sesame("芝麻粒兑换🛒[成功] $name #消耗${point}粒")
                return true
            } else {
                val errorMsg = jo.optString("resultView", resString)
                // 如果是“积分不足”等错误，也会在这里打印
                Log.error(TAG, "兑换失败[$name]: $errorMsg")
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "exchangeSesameGift 错误:", t)
        }
        return false
    }

}
