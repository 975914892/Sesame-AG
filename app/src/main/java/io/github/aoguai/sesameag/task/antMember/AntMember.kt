package io.github.aoguai.sesameag.task.antMember

import android.annotation.SuppressLint
import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.data.Status.Companion.canMemberPointExchangeBenefitToday
import io.github.aoguai.sesameag.data.Status.Companion.canMemberSignInToday
import io.github.aoguai.sesameag.data.Status.Companion.hasFlagToday
import io.github.aoguai.sesameag.data.Status.Companion.memberPointExchangeBenefitToday
import io.github.aoguai.sesameag.data.Status.Companion.memberSignInToday
import io.github.aoguai.sesameag.data.Status.Companion.setFlagToday
import io.github.aoguai.sesameag.data.StatusFlags
import io.github.aoguai.sesameag.entity.MemberBenefit
import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.hook.internal.LocationHelper.requestLocationSuspend
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.model.modelFieldExt.BooleanModelField
import io.github.aoguai.sesameag.model.modelFieldExt.SelectModelField
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.antOrchard.UrlUtil
import io.github.aoguai.sesameag.util.CoroutineUtils
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.Log.record
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TaskBlacklist
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.MemberBenefitsMap
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.max

class AntMember : ModelTask() {
    override fun getName(): String {
        return "会员"
    }

    override fun getGroup(): ModelGroup {
        return ModelGroup.MEMBER
    }

    override fun getIcon(): String {
        return "AntMember.png"
    }

    internal var memberSign: BooleanModelField? = null
    internal var memberTask: BooleanModelField? = null
    internal var memberPointExchangeBenefit: BooleanModelField? = null
    private var memberPointExchangeBenefitList: SelectModelField? = null
    private var collectInsuredGold: BooleanModelField? = null
    private var enableGameCenter: BooleanModelField? = null
    internal var merchantSign: BooleanModelField? = null
    internal var merchantKmdk: BooleanModelField? = null
    internal var merchantMoreTask: BooleanModelField? = null
    internal var beanSignIn: BooleanModelField? = null
    internal var beanExchangeBubbleBoost: BooleanModelField? = null


    /*//年度回顾
    private var annualReview: BooleanModelField? = null*/

    // 黄金票配置 - 签到
    internal var enableGoldTicket: BooleanModelField? = null

    // 黄金票配置 - 提取/兑换
    internal var enableGoldTicketConsume: BooleanModelField? = null

    /** 账单 贴纸 功能开关 */
    private var collectStickers: BooleanModelField? = null

    private val goldTicketTaskBlacklistModule = "黄金票"


    private data class CurrentMemberTask(
        val taskConfigId: String,
        val taskProcessId: String,
        val title: String,
        val awardPoint: String,
        val targetBusiness: String,
        val simpleTaskConfig: JSONObject,
        val adBizId: String
    )

    private data class MemberTaskProcessAward(
        val taskProcessId: String,
        val awardRelatedOutBizNo: String,
        val title: String,
        val awardPoint: Int,
        val stageIndex: Int
    )

    private enum class CurrentMemberTaskVerifyState {
        CONFIRMED,
        UNCONFIRMED
    }

    private data class MemberFloatingBallTaskRef(
        val bizNo: String,
        val taskType: String,
        val taskStatus: String,
        val endDt: Long,
        val executeTimeSeconds: Long
    )

    private enum class MemberFloatingBallTaskProcessState {
        PROCESSED,
        NO_TASK,
        RETRY_LATER,
        UNKNOWN
    }


    private data class StickerFollowUpResult(
        val success: Boolean = true,
        val handled: Boolean = false
    )

    private enum class StickerRpcFailureType {
        BUSINESS_LIMIT,
        DUPLICATE_REWARD,
        NON_RETRYABLE
    }

    private enum class InsuredGoldRpcFailureType {
        BUSINESS_LIMIT,
        DUPLICATE_REWARD,
        NON_RETRYABLE
    }

    private enum class GuardianBeanAwardRpcFailureType {
        BUSINESS_LIMIT,
        DUPLICATE_REWARD,
        NON_RETRYABLE
    }

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(BooleanModelField("memberSign", "会员签到", false).withDesc(
            "执行会员中心每日签到并领取会员积分。"
        ).also {
            memberSign = it
        })
        modelFields.addField(BooleanModelField("memberTask", "会员任务", false).withDesc(
            "执行会员中心每日任务，完成后统一领取会员积分。"
        ).also {
            memberTask = it
        })



        modelFields.addField(
            BooleanModelField(
                "memberPointExchangeBenefit", "会员积分 | 兑换权益", false
            ).withDesc("按下方兑换列表自动尝试兑换会员权益或道具。").also { memberPointExchangeBenefit = it })
        modelFields.addField(
            SelectModelField(
                "memberPointExchangeBenefitList",
                "会员积分 | 兑换列表",
                LinkedHashSet<String?>()
            ) {
                MemberBenefit.getList()
            }.withDesc("勾选允许自动兑换的会员权益，需同时开启上方兑换开关才会处理。").also { memberPointExchangeBenefitList = it })




        modelFields.addField(
            BooleanModelField(
                "collectInsuredGold", "蚂蚁保|保障金领取", false
            ).withDesc("领取蚂蚁保页面可收取的签到保障金和活动保障金。").also { collectInsuredGold = it })

        // 黄金票配置
        modelFields.addField(
            BooleanModelField(
                "enableGoldTicket", "黄金票签到", false
            ).withDesc("执行黄金票首页签到与日常收取，持续累积黄金票。").also { enableGoldTicket = it })
        modelFields.addField(
            BooleanModelField(
                "enableGoldTicketConsume", "黄金票提取(兑换黄金)", false
            ).withDesc("黄金票达到提取条件后自动兑换或提取黄金。").also { enableGoldTicketConsume = it })
        modelFields.addField(BooleanModelField("enableGameCenter", "游戏中心签到", false).withDesc(
            "执行游戏中心签到、平台任务，并领取可收取的玩乐豆奖励。"
        ).also {
            enableGameCenter = it
        })
        modelFields.addField(
            BooleanModelField(
                "merchantSign", "商家服务|签到", false
            ).withDesc("执行商家服务每日签到，包含可领取时会顺带处理招财金签到积分。").also { merchantSign = it })
        modelFields.addField(
            BooleanModelField(
                "merchantKmdk", "商家服务|开门打卡", false
            ).withDesc("执行商家服务开门打卡的报名与上午签到，需在可用时段内运行。").also { merchantKmdk = it })
        modelFields.addField(
            BooleanModelField(
                "merchantMoreTask", "商家服务|积分任务", false
            ).withDesc("执行商家服务积分任务，并顺带领取任务产出的积分球奖励。").also {
                merchantMoreTask = it
            })
        modelFields.addField(
            BooleanModelField(
                "beanSignIn", "安心豆签到", false
            ).withDesc("执行安心豆每日签到，领取当天可得的安心豆奖励。").also { beanSignIn = it })
        modelFields.addField(
            BooleanModelField(
                "beanExchangeBubbleBoost", "安心豆兑换时光加速器", false
            ).withDesc("在安心豆余额足够时自动兑换时光加速器。").also { beanExchangeBubbleBoost = it })
       /* modelFields.addField(
            BooleanModelField(
                "annualReview", "年度回顾", false
            ).also { annualReview = it })*/


        modelFields.addField(
            BooleanModelField("CollectStickers", "领取贴纸", false).withDesc(
                "扫描并领取当前账单周期内可领取的贴纸奖励。"
            ).also { collectStickers = it }
        )



        return modelFields
    }

    override fun runJava() {
        runBlocking {
            try {
                Log.member("执行开始-${getName()}")
                requestLocationSuspend()

                val deferredTasks = mutableListOf<Deferred<Unit>>()
                val memberPointPlan = prepareMemberPointWorkflows(this, deferredTasks)

                if (collectInsuredGold?.value == true) {
                    deferredTasks.add(async(Dispatchers.IO) { collectInsuredGold() })
                }

                scheduleGoldTicketWorkflows(this, deferredTasks)

                if (enableGameCenter?.value == true) {
                    deferredTasks.add(async(Dispatchers.IO) { enableGameCenter() })
                }

               /* if (annualReview!!.value) {   //年度回顾已下线
                    deferredTasks.add(async(Dispatchers.IO) { doAnnualReview() })
                }*/

                scheduleBeanWorkflows(this, deferredTasks)
                scheduleMerchantWorkflows(this, deferredTasks)

                if (collectStickers?.value == true) {
                    queryAndCollectStickers()
                }

                deferredTasks.awaitAll()
                finishMemberPointWorkflows(memberPointPlan)

            } catch (t: Throwable) {
                Log.printStackTrace(TAG, t)
            } finally {
                Log.member("执行结束-${getName()}")
            }
        }
    }

    internal suspend fun runMerchantWorkflow() {
        val needKmdkSignIn =
            merchantKmdk?.value == true &&
                !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNIN_DONE) &&
                TimeUtil.isNowAfterTimeStr("0600") &&
                TimeUtil.isNowBeforeTimeStr("1200")
        val needKmdkSignUp =
            merchantKmdk?.value == true &&
                !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNUP_DONE)
        val needMerchantSign =
            merchantSign?.value == true &&
                !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_SIGN_DONE)
        val needMerchantMoreTask =
            merchantMoreTask?.value == true

        if (!(needKmdkSignIn || needKmdkSignUp || needMerchantSign || needMerchantMoreTask)) {
            Log.member("⏭️ 今天已处理过商家服务相关任务，跳过执行")
            return
        }

        if (!canRunMerchantService()) {
            return
        }

        if (needMerchantSign) {
            if (doMerchantSign()) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_SIGN_DONE)
                collectMerchantPointBalls()
            }
        }
        if (needMerchantMoreTask) {
            doMerchantMoreTask()
        }
        if (merchantKmdk?.value == true && (needKmdkSignIn || needKmdkSignUp)) {
            if (needKmdkSignIn) {
                if (kmdkSignIn()) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNIN_DONE)
                }
            } else if (TimeUtil.isNowAfterTimeStr("1200")) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNIN_DONE)
            }
            if (needKmdkSignUp) {
                if (kmdkSignUp()) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_MERCHANT_KMDK_SIGNUP_DONE)
                }
            }
        }
    }


    /*
     * 年度回顾已下线：相关 RPC/组件字段不再维护。
     * 为保证编译通过，暂时整体注释掉这一段实现（含 RPC/组件常量未补齐部分）。
     *
     * 如需恢复：请先补齐 AntMemberRpcCall.annualReview* 与组件常量后再启用。
     *
    /**
     * 年度回顾任务：通过 programInvoke 查询并自动完成任务
     *
     *
     * 1) alipay.imasp.program.programInvoke + ..._task_reward_query 查询 playTaskOrderInfoList
     * 2) 对于 taskStatus = "init" 的任务，使用 ..._task_reward_apply(code) 领取，得到 recordNo
     * 3) 使用 ..._task_reward_process(code, recordNo) 上报完成，服务端自动发放成长值奖励
     */
    private suspend fun doAnnualReview(): Unit = CoroutineUtils.run {
        try {
            Log.member("年度回顾🎞[开始执行]")

            val resp = AntMemberRpcCall.annualReviewQueryTasks()
            if (resp == null || resp.isEmpty()) {
                Log.member("年度回顾[查询返回空]")
                return
            }

            val root: JSONObject?
            try {
                root = JSONObject(resp)
            } catch (e: Throwable) {
                Log.printStackTrace("$TAG.doAnnualReview.parseRoot", e)
                return
            }

            if (!root.optBoolean("isSuccess", false)) {
                Log.member("年度回顾[查询失败]#$resp")
                return
            }

            val components = root.optJSONObject("components")
            if (components == null || components.length() == 0) {
                Log.member("年度回顾[components 为空]")
                return
            }

            var queryComp = components.optJSONObject(AntMemberRpcCall.ANNUAL_REVIEW_QUERY_COMPONENT)
            if (queryComp == null) {
                // 兜底：取第一个组件
                try {
                    val it = components.keys()
                    if (it.hasNext()) {
                        queryComp = components.optJSONObject(it.next())
                    }
                } catch (_: Throwable) {
                }
            }
            if (queryComp == null) {
                Log.member("年度回顾[未找到查询组件]")
                return
            }
            if (!queryComp.optBoolean("isSuccess", true)) {
                Log.member("年度回顾[查询组件返回失败]")
                return
            }

            val content = queryComp.optJSONObject("content")
            if (content == null) {
                Log.member("年度回顾[content 为空]")
                return
            }

            val taskList = content.optJSONArray("playTaskOrderInfoList")
            if (taskList == null || taskList.length() == 0) {
                Log.member("年度回顾[当前无可处理任务]")
                return
            }

            var candidate = 0
            var applied = 0
            var processed = 0
            var failed = 0

            for (i in 0..<taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue

                val taskStatus = task.optString("taskStatus", "")
                if ("init" != taskStatus) {
                    // 已完成/已领奖等状态直接跳过
                    continue
                }
                candidate++

                var code = task.optString("code", "")
                if (code.isEmpty()) {
                    val extInfo = task.optJSONObject("extInfo")
                    if (extInfo != null) {
                        code = extInfo.optString("taskId", "")
                    }
                }
                if (code.isEmpty()) {
                    failed++
                    continue
                }

                var taskName = code
                val displayInfo = task.optJSONObject("displayInfo")
                if (displayInfo != null) {
                    val name = displayInfo.optString(
                        "taskName", displayInfo.optString("activityName", code)
                    )
                    if (!name.isEmpty()) {
                        taskName = name
                    }
                }

                // ========== Step 1: 领取任务 (apply) ==========
                val applyResp = AntMemberRpcCall.annualReviewApplyTask(code)
                if (applyResp == null || applyResp.isEmpty()) {
                    Log.member("年度回顾[领任务失败]$taskName#响应为空")
                    failed++
                    continue
                }

                val applyRoot: JSONObject?
                try {
                    applyRoot = JSONObject(applyResp)
                } catch (e: Throwable) {
                    Log.printStackTrace("$TAG.doAnnualReview.parseApply", e)
                    failed++
                    continue
                }
                if (!applyRoot.optBoolean("isSuccess", false)) {
                    Log.member("年度回顾[领任务失败]$taskName#$applyResp")
                    failed++
                    continue
                }
                val applyComps = applyRoot.optJSONObject("components")
                if (applyComps == null) {
                    failed++
                    continue
                }
                var applyComp = applyComps.optJSONObject(AntMemberRpcCall.ANNUAL_REVIEW_APPLY_COMPONENT)
                if (applyComp == null) {
                    try {
                        val it2 = applyComps.keys()
                        if (it2.hasNext()) {
                            applyComp = applyComps.optJSONObject(it2.next())
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (applyComp == null || !applyComp.optBoolean("isSuccess", true)) {
                    failed++
                    continue
                }
                val applyContent = applyComp.optJSONObject("content")
                if (applyContent == null) {
                    failed++
                    continue
                }
                val claimedTask = applyContent.optJSONObject("claimedTask")
                if (claimedTask == null) {
                    failed++
                    continue
                }
                val recordNo = claimedTask.optString("recordNo", "")
                if (recordNo.isEmpty()) {
                    failed++
                    continue
                }
                applied++

                // ========== Step 2: 提交任务完成 (process) ==========
                val processResp = AntMemberRpcCall.annualReviewProcessTask(code, recordNo)
                if (processResp == null || processResp.isEmpty()) {
                    Log.member("年度回顾[提交任务失败]$taskName#响应为空")
                    failed++
                    continue
                }

                val processRoot: JSONObject?
                try {
                    processRoot = JSONObject(processResp)
                } catch (e: Throwable) {
                    Log.printStackTrace("$TAG.doAnnualReview.parseProcess", e)
                    failed++
                    continue
                }
                if (!processRoot.optBoolean("isSuccess", false)) {
                    Log.member("年度回顾[提交任务失败]$taskName#$processResp")
                    failed++
                    continue
                }
                val processComps = processRoot.optJSONObject("components")
                if (processComps == null) {
                    failed++
                    continue
                }
                var processComp = processComps.optJSONObject(AntMemberRpcCall.ANNUAL_REVIEW_PROCESS_COMPONENT)
                if (processComp == null) {
                    try {
                        val it3 = processComps.keys()
                        if (it3.hasNext()) {
                            processComp = processComps.optJSONObject(it3.next())
                        }
                    } catch (_: Throwable) {
                    }
                }
                if (processComp == null || !processComp.optBoolean("isSuccess", true)) {
                    failed++
                    continue
                }
                val processContent = processComp.optJSONObject("content")
                if (processContent == null) {
                    failed++
                    continue
                }
                val processedTask = processContent.optJSONObject("processedTask")
                if (processedTask == null) {
                    failed++
                    continue
                }
                val newStatus = processedTask.optString("taskStatus", "")
                var rewardStatus = processedTask.optString("rewardStatus", "")

                // ========== Step 3: 如仍未发奖，则调用 get_reward 领取奖励 ==========
                if (!"success".equals(rewardStatus, ignoreCase = true)) {
                    try {
                        val rewardResp = AntMemberRpcCall.annualReviewGetReward(code, recordNo)
                        if (rewardResp != null && !rewardResp.isEmpty()) {
                            val rewardRoot = JSONObject(rewardResp)
                            if (rewardRoot.optBoolean("isSuccess", false)) {
                                val rewardComps = rewardRoot.optJSONObject("components")
                                if (rewardComps != null) {
                                    var rewardComp = rewardComps.optJSONObject(AntMemberRpcCall.ANNUAL_REVIEW_GET_REWARD_COMPONENT)
                                    if (rewardComp == null) {
                                        try {
                                            val it4 = rewardComps.keys()
                                            if (it4.hasNext()) {
                                                rewardComp = rewardComps.optJSONObject(it4.next())
                                            }
                                        } catch (_: Throwable) {
                                        }
                                    }
                                    if (rewardComp != null && rewardComp.optBoolean(
                                            "isSuccess", true
                                        )
                                    ) {
                                        val rewardContent = rewardComp.optJSONObject("content")
                                        if (rewardContent != null) {
                                            var rewardTask = rewardContent.optJSONObject("processedTask")
                                            if (rewardTask == null) {
                                                rewardTask = rewardContent.optJSONObject("claimedTask")
                                            }
                                            if (rewardTask != null) {
                                                val rs = rewardTask.optString("rewardStatus", "")
                                                if (!rs.isEmpty()) {
                                                    rewardStatus = rs
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        Log.printStackTrace("$TAG.doAnnualReview.getReward", e)
                    }
                }

                processed++
                Log.member("年度回顾🎞[任务完成]$taskName#状态=$newStatus 奖励状态=$rewardStatus")
            }

            Log.member("年度回顾🎞[执行结束] 待处理=$candidate 已领取=$applied 已提交=$processed 失败=$failed"
            )
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.doAnnualReview", t)
        }
    }

    */

    /**
     * 会员积分0元兑，权益道具兑换
     */
    internal fun memberPointExchangeBenefit() {
        if (hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_BENEFIT_REFRESH_DONE)) {
            return
        }
        val whiteList: Set<String> = memberPointExchangeBenefitList?.value
            ?.filterNotNull()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
        if (whiteList.isNotEmpty() && whiteList.all { !canMemberPointExchangeBenefitToday(it) }) {
            Log.member("会员积分🎐兑换列表今日已全部处理，跳过执行")
            setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_BENEFIT_REFRESH_DONE)
            return
        }
        try {
            val userId = UserMap.currentUid
            Log.member("会员积分商品加载..")
            val remainingWhiteList: MutableSet<String>? = if (whiteList.isNotEmpty()) whiteList.toMutableSet() else null
            // 1. 分类配置直接放在函数内部
            val categoryMap = mapOf(
                "公益道具" to listOf("94000SR2025022012011004"),
                "出行旅游" to listOf("94000SR2025010611441006", "94000SR2025010611458001"),
                "餐饮" to listOf("94000SR2025110315351006"),
                "皮肤藏品" to listOf("94000SR2025110315357001", "94000SR2025111015444005"),
                "理财还款" to listOf("94000SR2025011411575008", "94000SR2025091814834002"),
                "红包神券" to listOf("94000SR2025092414916001"),
                "充值缴费" to listOf("94000SR2025011611640002", "94000SR2025091814821018")
            )
            // 3. 遍历分类
            categoryMap.forEach { (catName, ids) ->
                var currentPage = 1
                var hasNextPage = true
                while (hasNextPage) {//此处请求过载，容易风控，循环频繁请求会炸
                    GlobalThreadPools.sleepCompat(1000L)
                    val responseStr = AntMemberRpcCall.queryDeliveryZoneDetail(ids, currentPage, 48)
                    if (responseStr.isNullOrEmpty()) {
                        Log.error(TAG, "分类[$catName] 接口返回空字符串")
                        break
                    }
                    val jo = JSONObject(responseStr)
                    if (!ResChecker.checkRes(TAG, jo)) {
                        Log.error(TAG, "分类[$catName] 校验失败: $responseStr")
                        break
                    }
                    val benefits = jo.optJSONArray("briefConfigInfos")
                    if (benefits == null || benefits.length() == 0) {
                        Log.error(TAG, "分类[$catName] 第 $currentPage 页没有权益数据")
                        break
                    }
                    for (i in 0 until benefits.length()) {
                        val rawItem = benefits.getJSONObject(i)
                        // 兼容 benefitInfo 嵌套结构
                        val benefit = if (rawItem.has("benefitInfo")) rawItem.getJSONObject("benefitInfo") else rawItem
                        val name = benefit.optString("name", "未知")
                        val benefitId = benefit.optString("benefitId")
                        val itemId = benefit.optString("itemId")
                        val pointNeeded = benefit.optJSONObject("pricePresentation")?.optString("point") ?: "0"
                        if (benefitId.isEmpty()) {
                            Log.member("商品[$name] 没有 benefitId，跳过")
                            continue
                        }
                        // 记录 benefitId 映射关系
                        IdMapManager.getInstance(MemberBenefitsMap::class.java).add(benefitId, name)
                        // 校验是否在白名单
                        val inWhiteList = whiteList.contains(benefitId)
                        if (!inWhiteList) {
                            // 如果不在白名单，保持安静，不刷 record 日志，或者你可以按需开启
                            continue
                        }
                        remainingWhiteList?.remove(benefitId)
                        // 校验频率限制
                        if (!canMemberPointExchangeBenefitToday(benefitId)) {
                            Log.member("跳过[$name]: 今日已兑换过")
                            continue
                        }
                        // 5. 执行兑换
                        Log.member("准备兑换[$name], ID: $benefitId, 需积分: $pointNeeded")
                        if (exchangeBenefit(benefitId, itemId, userId)) {
                            Log.member("会员积分🎐兑换[$name]#花费[$pointNeeded 积分]")
                        } else {
                            Log.member("兑换失败: $name (ItemId: $itemId)")
                        }
                    }
                    val nextPageNum = jo.optInt("nextPageNum", 0)
                    if (nextPageNum > 0 && nextPageNum > currentPage) {
                        currentPage = nextPageNum
                    } else {
                        hasNextPage = false
                    }

                    if (remainingWhiteList != null && remainingWhiteList.isEmpty()) {
                        IdMapManager.getInstance(MemberBenefitsMap::class.java).save(userId)
                        Log.member("会员积分🎐兑换列表已全部扫描到，提前结束")
                        setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_BENEFIT_REFRESH_DONE)
                        return
                    }
                }
                IdMapManager.getInstance(MemberBenefitsMap::class.java).save(userId)
                Log.member("分类[$catName]处理完毕，已执行中间保存")
            }
            // 7. 保存映射表
            IdMapManager.getInstance(MemberBenefitsMap::class.java).save(userId)
            Log.member("会员积分🎐全部分类任务处理完毕")
            setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_BENEFIT_REFRESH_DONE)

        } catch (t: Throwable) {
            Log.member("memberPointExchangeBenefit 运行异常: ${t.message}")
            Log.printStackTrace(TAG, t)
        }
    }

    private fun exchangeBenefit(benefitId: String, itemid: String, userid: String?): Boolean {
        try {
            val resString = AntMemberRpcCall.exchangeBenefit(benefitId, itemid, userid)
            val jo = JSONObject(resString)
            val resultCode = jo.optString("resultCode")

            if (resultCode == "BEYOND_BUYING_TIMES") {
                Log.member("会员权益兑换已达上限，标记任务今日完成")
                memberPointExchangeBenefitToday(benefitId)
                return true
            }

            if (ResChecker.checkRes(TAG + "会员权益兑换失败:", jo)) {
                memberPointExchangeBenefitToday(benefitId)
                return true
            }

        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "exchangeBenefit 错误:", t)
        }
        return false
    }

    /**
     * 会员签到
     */
    /**
     * 会员签到
     */
    internal suspend fun doMemberSign(): Unit = CoroutineUtils.run {
        var signDoneToday = hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_SIGN_DONE)
        try {
            val uid = UserMap.currentUid
            if (!signDoneToday) {
                if (!canMemberSignInToday(uid)) {
                    signDoneToday = true
                } else {
                    val s = AntMemberRpcCall.queryMemberSigninCalendar()
                    val jo = JSONObject(s)
                    if (ResChecker.checkRes(TAG + "会员签到失败:", jo)) {
                        val currentSigned = jo.optBoolean("currentSigninStatus") || jo.optBoolean("autoSignInSuccess")
                        if (currentSigned) {
                            val signPoint = jo.optString("signinPoint", "0")
                            val signDays = jo.optString("signinSumDay", "-")
                            val signStatus = if (jo.optBoolean("autoSignInSuccess")) "签到成功" else "已签到"
                            Log.member("会员签到📅[${signPoint}积分]#$signStatus${signDays}天")
                            memberSignInToday(uid)
                            signDoneToday = true
                        } else {
                            Log.member("会员签到📅[今日未自动签到]#$s")
                        }
                    } else {
                        val resultDesc = jo.optString("resultDesc", "")
                        if (resultDesc.contains("已签到") || resultDesc.contains("成功")) {
                            memberSignInToday(uid)
                            signDoneToday = true
                        }
                        Log.member("会员签到📅[$resultDesc]")
                        Log.member(s)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "doMemberSign err:", t)
        } finally {
            if (signDoneToday) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_SIGN_DONE)
            }
        }
    }

    internal suspend fun doAllMemberAvailableTaskCompat(): Unit = CoroutineUtils.run {
        try {
            val floatingBallState = processMemberFloatingBallTaskCompat()
            if (ApplicationHookConstants.isOffline()) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)
                Log.member("会员任务[浮球]#检测到离线模式，今日停止继续刷新")
                return@run
            }

            when (floatingBallState) {
                MemberFloatingBallTaskProcessState.PROCESSED -> Unit

                MemberFloatingBallTaskProcessState.RETRY_LATER -> {
                    Log.member("会员任务[浮球]#存在进行中任务，本轮结束，后续轮次继续查询")
                }

                MemberFloatingBallTaskProcessState.UNKNOWN -> {
                    if (!hasFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)) {
                        Log.member("会员任务[浮球]#当前链路状态未确认，本轮结束，后续轮次继续查询")
                    }
                }

                MemberFloatingBallTaskProcessState.NO_TASK -> {
                    markMemberTaskEmptyToday("会员任务[浮球]#未发现可执行任务，今日停止继续刷新")
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "doAllMemberAvailableTaskCompat err:", t)
        }
    }

    private fun buildMemberTaskProcessAwards(jsonObject: JSONObject): List<MemberTaskProcessAward> {
        val taskProcessList = jsonObject.optJSONArray("availableTaskProcessList") ?: return emptyList()
        val awardList = mutableListOf<MemberTaskProcessAward>()
        val dedupKeys = LinkedHashSet<String>()
        for (i in 0 until taskProcessList.length()) {
            val taskProcess = taskProcessList.optJSONObject(i) ?: continue
            val taskProcessId = taskProcess.optString("taskProcessId")
            if (taskProcessId.isEmpty()) {
                continue
            }
            val taskConfig = taskProcess.optJSONObject("taskConfig")
            val taskTitle = taskConfig?.optString("title").orEmpty().ifEmpty {
                taskProcess.optString("title", "会员任务")
            }
            val stageProcessList = taskProcess.optJSONArray("stageProcessList") ?: continue
            for (stageIndexInList in 0 until stageProcessList.length()) {
                val stageProcess = stageProcessList.optJSONObject(stageIndexInList) ?: continue
                val stageStatus = stageProcess.optString("stageStatus")
                val awardRelatedOutBizNo = stageProcess.optString("awardRelatedOutBizNo")
                if (!stageStatus.equals("COMPLETE", true) || awardRelatedOutBizNo.isEmpty()) {
                    continue
                }
                val stageIndex = stageProcess.optInt("stageIndex", stageIndexInList + 1)
                val dedupKey = "$taskProcessId#$awardRelatedOutBizNo"
                if (!dedupKeys.add(dedupKey)) {
                    continue
                }
                awardList.add(
                    MemberTaskProcessAward(
                        taskProcessId = taskProcessId,
                        awardRelatedOutBizNo = awardRelatedOutBizNo,
                        title = taskTitle,
                        awardPoint = stageProcess.optInt("awardPoint", 0),
                        stageIndex = stageIndex
                    )
                )
            }
        }
        return awardList
    }

    internal suspend fun collectMemberTaskProcessAwards(): Int = CoroutineUtils.run {
        try {
            val response = AntMemberRpcCall.queryMemberTaskProcessList()
            val taskListObject = JSONObject(response)
            if (!ResChecker.checkRes(TAG + "查询会员阶段奖励失败:", taskListObject)) {
                Log.member("会员任务[阶段奖励]#查询失败:" + taskListObject.optString("resultDesc", response)
                )
                return@run 0
            }

            val awardList = buildMemberTaskProcessAwards(taskListObject)
            var claimedCount = 0
            for (award in awardList) {
                val awardResponse = AntMemberRpcCall.awardMemberTaskProcess(
                    award.awardRelatedOutBizNo,
                    award.taskProcessId
                )
                val awardObject = JSONObject(awardResponse)
                if (!ResChecker.checkRes(TAG + "领取会员阶段奖励失败:", awardObject)) {
                    Log.member("会员任务[${award.title}]#阶段奖励领取失败:" + awardObject.optString("resultDesc", awardResponse)
                    )
                    continue
                }
                val stageSuffix = if (award.stageIndex > 0) "-阶段${award.stageIndex}" else ""
                if (award.awardPoint > 0) {
                    Log.member("会员任务[${award.title}$stageSuffix]#获得积分${award.awardPoint}")
                } else {
                    Log.member("会员任务[${award.title}$stageSuffix]#领取阶段奖励")
                }
                claimedCount++
                delay(300)
            }
            return@run claimedCount
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "collectMemberTaskProcessAwards err:", t)
            return@run 0
        }
    }

    /**
     * 会员任务-逛一逛
     * 单次执行 1
     */
    private suspend fun doAllMemberAvailableTask(): Unit = CoroutineUtils.run {
        try {
            val legacyTaskResponse = AntMemberRpcCall.queryLegacyAllStatusTaskList()
            val legacyTaskObject = JSONObject(legacyTaskResponse)
            val stopReason = resolveMemberTaskQueryStopReason(legacyTaskObject)
            if (stopReason != null) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)
                Log.member("会员任务🎖️[legacy]#${buildMemberTaskQueryStopMessage(stopReason, legacyTaskObject)}"
                )
                return@run
            }
            if (!ResChecker.checkRes(TAG, legacyTaskObject)) {
                Log.error(
                    "$TAG.doAllMemberAvailableTask",
                    "会员任务响应失败: " + legacyTaskObject.optString("resultDesc")
                )
                return@run
            }
            val taskList = legacyTaskObject.optJSONArray("availableTaskList") ?: JSONArray()
            if (taskList.length() <= 0) {
                Log.member("会员任务🎖️[legacy]#任务列表数量为0，本轮结束，后续轮次继续查询")
                return@run
            }

            var processedCount = 0
            var safeCandidateCount = 0
            for (i in 0 until taskList.length()) {
                val task = taskList.optJSONObject(i) ?: continue
                val taskConfigInfo = task.optJSONObject("taskConfigInfo")
                val taskId = taskConfigInfo?.optLong("id", 0L)?.takeIf { it > 0 }?.toString().orEmpty()
                if (taskConfigInfo != null && shouldKeepMemberTaskConfigId(taskId)) {
                    safeCandidateCount++
                }
                if (processLegacyMemberTask(task)) {
                    processedCount++
                }
                if (ApplicationHookConstants.isOffline()) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)
                    Log.member("会员任务🎖️[legacy]#检测到离线模式，今日停止继续刷新")
                    return@run
                }
            }

            if (processedCount == 0) {
                Log.member(if (safeCandidateCount > 0) {
                        "会员任务🎖️[legacy]#当前列表无可安全执行任务，本轮结束，后续轮次继续查询"
                    } else {
                        "会员任务🎖️[legacy]#列表仅含不兼容任务，本轮结束，后续轮次继续查询"
                    }
                )
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "doAllMemberAvailableTask err:", t)
        }
    }

    private fun resolveMemberTaskQueryStopReason(jsonObject: JSONObject): String? {
        if (ApplicationHookConstants.isOffline()) {
            return "OFFLINE_MODE"
        }
        val code = sequenceOf(
            jsonObject.opt("resultCode")?.toString(),
            jsonObject.opt("errorCode")?.toString(),
            jsonObject.opt("error")?.toString(),
            jsonObject.opt("errorTip")?.toString()
        ).filterNotNull()
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val desc = sequenceOf(
            jsonObject.opt("resultDesc")?.toString(),
            jsonObject.opt("memo")?.toString(),
            jsonObject.opt("desc")?.toString(),
            jsonObject.opt("errorMsg")?.toString(),
            jsonObject.opt("errorMessage")?.toString()
        ).filter { !it.isNullOrBlank() }
            .joinToString(" | ")
        val authLikeKeywords = listOf(
            "需要验证",
            "伺服器繁忙",
            "服务器繁忙",
            "請稍後再試",
            "请稍后再试",
            "稍后重试",
            "稍候再试",
            "操作太频繁",
            "过于频繁",
            "系统繁忙",
            "活动太火爆",
            "訪問異常",
            "访问异常"
        )
        if (
            code == "1009" ||
            authLikeKeywords.any { keyword -> desc.contains(keyword, ignoreCase = true) }
        ) {
            return "AUTH_LIKE"
        }
        if (code == "I07" || desc.contains("离线模式")) {
            return "OFFLINE_MODE"
        }
        return null
    }

    private fun buildMemberTaskQueryStopMessage(stopReason: String, jsonObject: JSONObject): String {
        val detail = sequenceOf(
            jsonObject.optString("resultDesc"),
            jsonObject.optString("memo"),
            jsonObject.optString("desc"),
            jsonObject.optString("errorMessage"),
            jsonObject.optString("errorMsg")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        return when (stopReason) {
            "AUTH_LIKE" -> "检测到验证/服务器繁忙($detail)，停止今日继续刷新"
            "OFFLINE_MODE" -> "检测到离线模式($detail)，停止今日继续刷新"
            else -> "检测到异常($detail)，停止今日继续刷新"
        }
    }

    private fun buildCurrentMemberTasks(jsonObject: JSONObject): List<CurrentMemberTask> {
        val resultData = jsonObject.optJSONObject("resultData") ?: return emptyList()
        val taskProcessObjects = collectCurrentMemberTaskProcessObjects(resultData)
        if (taskProcessObjects.isEmpty()) {
            return emptyList()
        }
        val taskList = mutableListOf<CurrentMemberTask>()
        val dedupKeys = LinkedHashSet<String>()
        for (taskProcessObject in taskProcessObjects) {
            if (isMemberTaskProcessFinished(taskProcessObject)) {
                continue
            }
            val simpleTaskConfig = resolveCurrentMemberTaskConfigObject(taskProcessObject) ?: continue
            val unsupportedAdTaskReason = resolveUnsupportedMemberAdTaskReason(taskProcessObject, simpleTaskConfig)
            if (unsupportedAdTaskReason != null) {
                logSkippedMemberAdTask(
                    simpleTaskConfig.optString("title").ifEmpty {
                        simpleTaskConfig.optString("name").ifEmpty { "会员任务" }
                    },
                    unsupportedAdTaskReason
                )
                continue
            }
            val adBizId = resolveMemberAdTaskBizId(taskProcessObject, simpleTaskConfig)
            val taskConfigId = resolveCurrentMemberTaskConfigId(taskProcessObject)
                ?.takeIf { shouldKeepMemberTaskConfigId(it) || adBizId.isNotEmpty() }
                ?: continue
            val targetBusiness = resolveSupportedMemberTaskTargetBusiness(
                taskProcessObject.optJSONArray("targetBusiness") ?: simpleTaskConfig.optJSONArray("targetBusiness")
            )
            if (targetBusiness.isEmpty() && adBizId.isEmpty()) {
                continue
            }
            val taskProcessId = taskProcessObject.optString("processId").ifEmpty {
                taskProcessObject.optString("taskProcessId")
            }
            val dedupKey = when {
                taskProcessId.isNotEmpty() -> taskProcessId
                adBizId.isNotEmpty() -> "$taskConfigId#$adBizId"
                else -> taskConfigId
            }
            if (!dedupKeys.add(dedupKey)) {
                continue
            }
            taskList.add(
                CurrentMemberTask(
                    taskConfigId = taskConfigId,
                    taskProcessId = taskProcessId,
                    title = simpleTaskConfig.optString("title").ifEmpty {
                        simpleTaskConfig.optString("name").ifEmpty { "任务$taskConfigId" }
                    },
                    awardPoint = extractMemberTaskAwardPoint(simpleTaskConfig),
                    targetBusiness = targetBusiness,
                    simpleTaskConfig = simpleTaskConfig,
                    adBizId = adBizId
                )
            )
        }
        return taskList
    }

    private fun collectCurrentMemberTaskProcessObjects(resultData: JSONObject): List<JSONObject> {
        val taskProcessObjects = mutableListOf<JSONObject>()
        appendCurrentMemberTaskProcessObjects(taskProcessObjects, resultData.optJSONArray("taskProcessVOList"))
        appendCurrentMemberTaskProcessObjects(taskProcessObjects, resultData.optJSONArray("taskHistoryVOList"))
        return taskProcessObjects
    }

    private fun appendCurrentMemberTaskProcessObjects(target: MutableList<JSONObject>, taskArray: JSONArray?) {
        if (taskArray == null) {
            return
        }
        for (i in 0 until taskArray.length()) {
            taskArray.optJSONObject(i)?.let(target::add)
        }
    }

    private fun resolveCurrentMemberTaskConfigObject(taskProcessObject: JSONObject): JSONObject? {
        return taskProcessObject.optJSONObject("simpleTaskConfig")
            ?: taskProcessObject.optJSONObject("taskConfigInfo")
            ?: taskProcessObject.optJSONObject("taskConfig")
    }

    private fun hasCurrentMemberTaskSnapshot(jsonObject: JSONObject): Boolean {
        val resultData = jsonObject.optJSONObject("resultData") ?: return false
        return resultData.has("taskProcessVOList") ||
            resultData.has("taskHistoryVOList") ||
            resultData.has("categoryTaskVOList") ||
            resultData.optString("playInstanceId").isNotBlank()
    }

    private fun markMemberTaskEmptyToday(message: String) {
        setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_EMPTY_TODAY)
        Log.member(message)
    }

    private suspend fun processMemberFloatingBallTaskCompat(): MemberFloatingBallTaskProcessState = CoroutineUtils.run {
        try {
            val floatingBallResponse = AntMemberRpcCall.querySignFloatingBall()
            val floatingBallObject = JSONObject(floatingBallResponse)
            val stopReason = resolveMemberTaskQueryStopReason(floatingBallObject)
            if (stopReason != null) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)
                Log.member("会员任务[浮球]#${buildMemberTaskQueryStopMessage(stopReason, floatingBallObject)}"
                )
                return@run MemberFloatingBallTaskProcessState.UNKNOWN
            }
            if (!ResChecker.checkRes(TAG, floatingBallObject)) {
                Log.error(
                    "$TAG.processMemberFloatingBallTaskCompat",
                    "会员浮球查询失败: " + floatingBallObject.optString("resultDesc", floatingBallResponse)
                )
                return@run MemberFloatingBallTaskProcessState.UNKNOWN
            }
            if (floatingBallObject.optBoolean("allTaskCompleted")) {
                Log.member("会员任务[浮球]#今日浮球任务已全部完成")
                return@run MemberFloatingBallTaskProcessState.NO_TASK
            }
            val taskRef = buildMemberFloatingBallTaskRef(floatingBallObject)
                ?: return@run MemberFloatingBallTaskProcessState.NO_TASK
            if (isMemberTaskProcessFinishedStatus(taskRef.taskStatus)) {
                Log.member("会员任务[浮球]#当前浮球任务已完成，停止本轮继续刷新")
                return@run MemberFloatingBallTaskProcessState.NO_TASK
            }
            if (!taskRef.taskType.equals("MULTIPLE_TIMER_TASK", true)) {
                Log.member("会员任务[浮球]#未适配任务类型${taskRef.taskType}，停止本轮继续刷新")
                return@run MemberFloatingBallTaskProcessState.UNKNOWN
            }

            val remainingMillis = when {
                taskRef.endDt > 0L -> taskRef.endDt - System.currentTimeMillis()
                taskRef.executeTimeSeconds > 0L -> taskRef.executeTimeSeconds * 1000L
                else -> 0L
            }
            if (remainingMillis > 20_000L) {
                val remainingSeconds = ((remainingMillis + 999L) / 1000L).coerceAtLeast(1L)
                Log.member("会员任务[浮球]#倒计时任务进行中，剩余${remainingSeconds}秒，停止本轮继续刷新"
                )
                return@run MemberFloatingBallTaskProcessState.RETRY_LATER
            }
            val triggerResponse = AntMemberRpcCall.triggerSignFloatingBall(taskRef.bizNo, taskRef.taskType)
            val triggerObject = JSONObject(triggerResponse)
            val triggerStopReason = resolveMemberTaskQueryStopReason(triggerObject)
            if (triggerStopReason != null) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_MEMBER_TASK_RISK_STOP_TODAY)
                Log.member("会员任务[浮球]#${buildMemberTaskQueryStopMessage(triggerStopReason, triggerObject)}"
                )
                return@run MemberFloatingBallTaskProcessState.UNKNOWN
            }
            if (isMemberFloatingBallTaskNotEnded(triggerObject)) {
                Log.member("会员任务[浮球]#倒计时任务未结束，本轮结束，后续轮次继续查询")
                return@run MemberFloatingBallTaskProcessState.RETRY_LATER
            }
            if (!ResChecker.checkRes(TAG, triggerObject)) {
                Log.error(
                    "$TAG.processMemberFloatingBallTaskCompat",
                    "会员浮球触发失败: " + triggerObject.optString("resultDesc", triggerResponse)
                )
                return@run MemberFloatingBallTaskProcessState.UNKNOWN
            }

            val triggerStatus = triggerObject.optJSONObject("currentTaskInfo")?.optString("taskStatus").orEmpty()
            if (!isMemberTaskProcessFinishedStatus(triggerStatus)) {
                Log.member("会员任务[浮球]#触发完成后状态未终态，停止本轮继续刷新")
                return@run MemberFloatingBallTaskProcessState.RETRY_LATER
            }

            Log.member("会员任务[浮球]#完成倒计时浮球任务")
            if (!tryProcessMemberFloatingBallAdTask(taskRef)) {
                Log.member("会员任务[浮球]#后续广告任务未返回可直接上报字段，停止本轮继续刷新")
            }
            return@run MemberFloatingBallTaskProcessState.PROCESSED
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "processMemberFloatingBallTaskCompat err:", t)
            return@run MemberFloatingBallTaskProcessState.UNKNOWN
        }
    }

    private fun isMemberFloatingBallTaskNotEnded(jsonObject: JSONObject): Boolean {
        return jsonObject.optString("resultCode") == "SIGN_FLOATING_BALL_TASK_NOT_END" ||
            jsonObject.optString("resultDesc").contains("任务未结束")
    }

    private fun buildMemberFloatingBallTaskRef(jsonObject: JSONObject): MemberFloatingBallTaskRef? {
        val currentTaskInfo = jsonObject.optJSONObject("currentTaskInfo")
        val nextTaskInfo = jsonObject.optJSONObject("nextTaskInfo")
        val activeTaskInfo = when {
            currentTaskInfo == null -> nextTaskInfo
            isMemberTaskProcessFinishedStatus(currentTaskInfo.optString("taskStatus")) &&
                nextTaskInfo != null &&
                !isMemberTaskProcessFinishedStatus(nextTaskInfo.optString("taskStatus")) -> nextTaskInfo

            else -> currentTaskInfo
        } ?: return null
        val bizNo = activeTaskInfo.optString("bizNo").ifEmpty { jsonObject.optString("bizNo") }
        val taskType = jsonObject.optString("taskType")
        if (bizNo.isBlank() || taskType.isBlank()) {
            return null
        }
        return MemberFloatingBallTaskRef(
            bizNo = bizNo,
            taskType = taskType,
            taskStatus = activeTaskInfo.optString("taskStatus"),
            endDt = activeTaskInfo.optLong("endDt", 0L),
            executeTimeSeconds = activeTaskInfo.optLong("executeTime", 0L)
        )
    }

    private suspend fun tryProcessMemberFloatingBallAdTask(taskRef: MemberFloatingBallTaskRef): Boolean = CoroutineUtils.run {
        try {
            if (TaskBlacklist.isTaskInBlacklist(memberTaskBlacklistModule, memberFloatingBallAdTaskTitle)) {
                Log.member("会员任务[浮球]#$memberFloatingBallAdTaskTitle 已在黑名单，跳过后续广告任务")
                return@run true
            }
            val adTaskResponse = AntMemberRpcCall.querySignFloatingBallAdTask(taskRef.bizNo)
            val adTaskObject = JSONObject(adTaskResponse)
            if (!ResChecker.checkRes(TAG, adTaskObject)) {
                Log.error(
                    "$TAG.tryProcessMemberFloatingBallAdTask",
                    "会员浮球广告任务查询失败: " + adTaskObject.optString("resultDesc", adTaskResponse)
                )
                return@run false
            }
            val floatingBallAdTask = buildCurrentMemberTaskFromFloatingBallAdResponse(adTaskObject)
            if (floatingBallAdTask == null) {
                val videoTaskInfo = adTaskObject.optJSONObject("videoTaskInfo")
                if (videoTaskInfo != null) {
                    Log.member("会员任务[浮球]#已识别后续广告任务，但当前响应缺少adBizId/configId，保留后续刷新"
                    )
                }
                return@run false
            }
            return@run finishMemberAdTask(
                floatingBallAdTask.taskConfigId,
                floatingBallAdTask.title,
                floatingBallAdTask.awardPoint,
                floatingBallAdTask.adBizId
            )
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "tryProcessMemberFloatingBallAdTask err:", t)
            return@run false
        }
    }

    private fun buildCurrentMemberTaskFromFloatingBallAdResponse(responseObject: JSONObject): CurrentMemberTask? {
        val taskConfigObject = responseObject.optJSONObject("taskInfo")
            ?: responseObject.optJSONObject("currentTaskInfo")
            ?: responseObject.optJSONObject("nextTaskInfo")
            ?: responseObject.optJSONObject("videoTaskInfo")
            ?: responseObject
        val adBizId = resolveMemberAdTaskBizId(responseObject, taskConfigObject)
            .ifEmpty { resolveMemberAdTaskBizId(taskConfigObject, taskConfigObject) }
        if (adBizId.isBlank()) {
            return null
        }
        val taskConfigId = resolveCurrentMemberTaskConfigId(taskConfigObject)
            ?: resolveFallbackMemberTaskConfigId(responseObject, taskConfigObject)
            ?: return null
        val title = sequenceOf(
            taskConfigObject.optString("title"),
            taskConfigObject.optString("name"),
            responseObject.optJSONObject("extendInfo")?.optJSONObject("taskInfo")?.optString("taskTitle")
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty().ifEmpty { "会员任务$taskConfigId" }
        val awardPoint = sequenceOf(
            taskConfigObject.optString("awardNum"),
            responseObject.optJSONObject("extendInfo")?.optJSONObject("rewardInfo")?.optString("rewardAmount")
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        return CurrentMemberTask(
            taskConfigId = taskConfigId,
            taskProcessId = "",
            title = title,
            awardPoint = awardPoint,
            targetBusiness = "",
            simpleTaskConfig = taskConfigObject,
            adBizId = adBizId
        )
    }

    private fun resolveFallbackMemberTaskConfigId(responseObject: JSONObject, taskConfigObject: JSONObject): String? {
        val directCandidate = sequenceOf(
            responseObject.optString("configId"),
            taskConfigObject.optString("configId"),
            responseObject.optString("taskConfigId"),
            taskConfigObject.optString("taskConfigId")
        ).firstOrNull { it.isNotBlank() }
        if (!directCandidate.isNullOrBlank()) {
            return directCandidate
        }
        val taskId = taskConfigObject.optLong("id", 0L)
        return if (taskId > 0) taskId.toString() else null
    }

    @Throws(JSONException::class)
    private suspend fun processCurrentMemberTask(task: CurrentMemberTask): Boolean = CoroutineUtils.run {
        if (isMemberTaskInBlacklist(task.taskConfigId, task.title)) {
            Log.member("会员任务[${task.title}]#黑名单任务，停止执行")
            return@run false
        }
        if (task.adBizId.isNotEmpty()) {
            return@run finishMemberAdTask(task.taskConfigId, task.title, task.awardPoint, task.adBizId)
        }
        val targetBusinessArray = task.targetBusiness.split("#".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        if (targetBusinessArray.size < 3) {
            return@run false
        }
        val bizType = targetBusinessArray[0]
        val bizSubType = targetBusinessArray[1]
        val bizParam = targetBusinessArray[2]
        val executeResponse = AntMemberRpcCall.executeMemberTask(bizParam, bizSubType, bizType)
        val executeObject = JSONObject(executeResponse)
        if (isSkippableMemberTaskRejection(executeObject)) {
            Log.member("会员任务[${task.title}]#不满足营销规则，跳过执行")
            return@run false
        }
        if (!ResChecker.checkRes(TAG + "执行会员任务失败:", executeObject)) {
            Log.error(TAG, "执行任务失败:" + executeObject.optString("resultDesc", executeResponse))
            return@run false
        }
        when (checkCurrentMemberTaskFinished(task)) {
            CurrentMemberTaskVerifyState.CONFIRMED -> {
                if (task.awardPoint.isNotEmpty()) {
                    Log.member("会员任务[${task.title}]#获得积分${task.awardPoint}")
                } else {
                    Log.member("会员任务[${task.title}]#任务完成")
                }
                true
            }

            CurrentMemberTaskVerifyState.UNCONFIRMED -> {
                Log.member(if (task.taskProcessId.isNotEmpty()) {
                        "会员任务[${task.title}]#执行成功，跳过高风险全量刷新确认，状态待后续页面确认"
                    } else {
                        "会员任务[${task.title}]#执行成功，但当前缺少processId，跳过高风险二次确认"
                    }
                )
                true
            }
        }
    }

    private suspend fun checkCurrentMemberTaskFinished(task: CurrentMemberTask): CurrentMemberTaskVerifyState {
        return try {
            if (task.taskProcessId.isEmpty()) {
                return CurrentMemberTaskVerifyState.UNCONFIRMED
            }

            val detailResponse = AntMemberRpcCall.querySingleTaskProcessDetail(task.taskProcessId)
            val detailObject = JSONObject(detailResponse)
            if (!ResChecker.checkRes(TAG + "查询会员任务详情失败:", detailObject)) {
                Log.error(
                    "$TAG.checkCurrentMemberTaskFinished",
                    "会员任务详情响应失败: " + detailObject.optString("resultDesc", detailResponse)
                )
                return CurrentMemberTaskVerifyState.UNCONFIRMED
            }

            val taskProcessObject = detailObject.optJSONObject("resultData")?.optJSONObject("taskProcessVO")
                ?: detailObject.optJSONObject("taskProcessVO")
            if (isMemberTaskProcessFinished(taskProcessObject)) {
                CurrentMemberTaskVerifyState.CONFIRMED
            } else {
                CurrentMemberTaskVerifyState.UNCONFIRMED
            }
        } catch (_: JSONException) {
            CurrentMemberTaskVerifyState.UNCONFIRMED
        }
    }

    private fun resolveCurrentMemberTaskConfigId(taskObject: JSONObject): String? {
        val directValue = taskObject.optString("taskConfigId")
        if (directValue.isNotEmpty()) {
            return directValue
        }
        val simpleTaskConfig = taskObject.optJSONObject("simpleTaskConfig")
            ?: taskObject.optJSONObject("taskConfigInfo")
            ?: taskObject.optJSONObject("taskConfig")
        if (simpleTaskConfig != null) {
            val configId = simpleTaskConfig.optString("configId")
            if (configId.isNotEmpty()) {
                return configId
            }
            val taskConfigId = simpleTaskConfig.optString("taskConfigId")
            if (taskConfigId.isNotEmpty()) {
                return taskConfigId
            }
            val id = simpleTaskConfig.optLong("id", 0L)
            if (id > 0) {
                return id.toString()
            }
        }
        val id = taskObject.optLong("id", 0L)
        return if (id > 0) id.toString() else null
    }

    private fun isMemberTaskProcessFinished(taskProcessObject: JSONObject?): Boolean {
        if (taskProcessObject == null) {
            return false
        }
        val status = taskProcessObject.optString("status")
        if (isMemberTaskProcessFinishedStatus(status)) {
            return true
        }
        val subStatus = taskProcessObject.optString("subStatus")
        if (isMemberTaskProcessFinishedStatus(subStatus)) {
            return true
        }
        val currentCount = taskProcessObject.optLong("currentCount", -1L)
        val targetCount = taskProcessObject.optLong("targetCount", -1L)
        if (targetCount > 0 && currentCount >= targetCount) {
            return true
        }
        val extInfo = taskProcessObject.optJSONObject("extInfo")
        if (extInfo != null) {
            if (extInfo.optString("awardCurrentPoint").isNotEmpty() || extInfo.optString("awardSuccessTime").isNotEmpty()) {
                return true
            }
        }
        return false
    }

    private fun isMemberTaskProcessFinishedStatus(status: String): Boolean {
        return status.equals("AWARDED", true) ||
            status.equals("SUCCESS", true) ||
            status.equals("COMPLETE", true) ||
            status.equals("DONE", true) ||
            status.equals("FINISHED", true) ||
            status.equals("EXPIRED", true)
    }

    private fun extractMemberTaskAwardPoint(simpleTaskConfig: JSONObject): String {
        val stageVOList = simpleTaskConfig.optJSONArray("stageVOList")
        if (stageVOList != null && stageVOList.length() > 0) {
            val stageObject = stageVOList.optJSONObject(0)
            val awardParam = stageObject?.optJSONObject("awardParam")
            val awardPoint = awardParam?.optString("awardParamPoint").orEmpty()
            if (awardPoint.isNotEmpty()) {
                return awardPoint
            }
        }
        return simpleTaskConfig.optJSONObject("awardParam")?.optString("awardParamPoint").orEmpty()
    }

    private fun isSkippableMemberTaskRejection(response: JSONObject): Boolean {
        val resultCode = response.optString("resultCode").ifEmpty {
            response.optString("errorCode")
        }
        val resultDesc = response.optString("resultDesc").ifEmpty {
            response.optString("errorMsg")
        }
        return resultCode == "NOT_PROMO_RULE_QUALIFIED" ||
            resultDesc.contains("不满足任务的营销规则条件")
    }

    /**
     * 保障金领取
     */
    private suspend fun collectInsuredGold(): Unit = CoroutineUtils.run {
        try {
            var s = AntMemberRpcCall.queryAvailableCollectInsuredGold()
            var jo = JSONObject(s)
            if (!ResChecker.checkRes(TAG, jo)) {
                Log.error("$TAG.collectInsuredGold.queryInsuredHome", "保障金🏥[响应失败]#$s")
                return@run
            }
            val data = jo.optJSONObject("data")
            if (data == null) {
                Log.error("$TAG.collectInsuredGold.queryInsuredHome", "保障金🏥[响应缺少data]#$s")
                return@run
            }
            val signInBall = data.optJSONObject("signInDTO")
            if (signInBall != null &&
                1 == signInBall.optInt("sendFlowStatus") &&
                1 == signInBall.optInt("sendType")
            ) {
                collectSingleInsuredGold(signInBall, true)
            }
            val otherBallList = data.optJSONArray("eventToWaitDTOList")
            if (otherBallList != null) {
                for (i in 0 until otherBallList.length()) {
                    val anotherBall = otherBallList.optJSONObject(i) ?: continue
                    if (anotherBall.optInt("sendType") != 1) {
                        continue
                    }
                    collectSingleInsuredGold(anotherBall, false)
                }
            }
        } catch (t: Throwable) {
            Log.printStackTrace("$TAG.collectInsuredGold", t)
        }
    }

    private fun collectSingleInsuredGold(goldBall: JSONObject, isSignIn: Boolean): Boolean {
        val title = resolveInsuredGoldTitle(goldBall, isSignIn)
        if (goldBall.optString("sendFlowNo").isBlank()) {
            Log.member("保障金🏥[$title]#缺少sendFlowNo，跳过")
            return false
        }
        val requestObject = buildInsuredGoldGainRequest(goldBall, isSignIn)
        val response = AntMemberRpcCall.collectInsuredGold(requestObject)
        val responseObject = JSONObject(response)
        if (!ResChecker.checkRes(TAG, responseObject)) {
            logInsuredGoldFailure(title, responseObject, response)
            return false
        }
        val gainGold = extractInsuredGoldGainYuan(responseObject)
        if (gainGold.isBlank()) {
            Log.member("保障金🏥[$title]#领取成功，返回未包含金额")
        } else {
            Log.member("保障金🏥[$title]#+" + gainGold + "元")
        }
        return true
    }

    private fun buildInsuredGoldGainRequest(goldBall: JSONObject, isSignIn: Boolean): JSONObject {
        val requestObject = JSONObject(goldBall.toString())
        requestObject.put("entrance", "cfsy")
        requestObject.put("helpGain", false)
        val showYuan = requestObject.optString("sendSumInsuredYuan").ifBlank {
            requestObject.optString("realSendSumInsuredYuan")
        }
        if (showYuan.isNotBlank()) {
            requestObject.put("showYuan", showYuan)
        }
        val title = resolveInsuredGoldTitle(requestObject, isSignIn)
        if (title.isNotBlank()) {
            requestObject.put("title", title)
        }
        if (isSignIn) {
            requestObject.put("disabled", false)
            requestObject.put("isSignIn", true)
            if (!requestObject.has("isTodayContinuousSignIn")) {
                requestObject.put("isTodayContinuousSignIn", false)
            }
        }
        return requestObject
    }

    private fun resolveInsuredGoldTitle(goldBall: JSONObject, isSignIn: Boolean): String {
        if (isSignIn || goldBall.optString("channel") == "DAILY_SIGN_IN") {
            return "签到"
        }
        return when (goldBall.optString("channel")) {
            "ALIPAY_LOGIN" -> "登录奖励"
            "ANT_COVERAGE_LOGIN" -> "访问蚂蚁保"
            else -> goldBall.optString("title").ifBlank { "领取保证金" }
        }
    }

    private fun extractInsuredGoldGainYuan(responseObject: JSONObject): String {
        val data = responseObject.optJSONObject("data") ?: return ""
        val gainDto = data.optJSONObject("gainSumInsuredDTO")
        return gainDto?.optString("gainSumInsuredYuan").orEmpty().ifBlank {
            data.optString("gainSumInsuredYuan").ifBlank {
                data.optString("sendSumInsuredYuan")
            }
        }
    }

    private fun logInsuredGoldFailure(title: String, responseObject: JSONObject, rawResponse: String) {
        val code = sequenceOf(
            responseObject.optString("resultCode"),
            responseObject.optString("code"),
            responseObject.optString("errorCode")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val message = sequenceOf(
            responseObject.optString("resultDesc"),
            responseObject.optString("resultMsg"),
            responseObject.optString("memo"),
            responseObject.optString("errorMessage"),
            responseObject.optString("errorMsg"),
            responseObject.optString("desc")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val detail = when {
            code.isNotBlank() && message.isNotBlank() -> "$code/$message"
            code.isNotBlank() -> code
            message.isNotBlank() -> message
            else -> rawResponse
        }
        when (classifyInsuredGoldFailure(code, message)) {
            InsuredGoldRpcFailureType.DUPLICATE_REWARD ->
                Log.member("保障金🏥[$title]#已领取或重复领取，跳过:$detail")

            InsuredGoldRpcFailureType.BUSINESS_LIMIT ->
                Log.member("保障金🏥[$title]#业务受限，本轮跳过:$detail")

            InsuredGoldRpcFailureType.NON_RETRYABLE ->
                Log.error("$TAG.collectInsuredGold.collectInsuredGold", "保障金🏥[$title]#响应失败:$detail")
        }
    }

    private fun classifyInsuredGoldFailure(code: String, message: String): InsuredGoldRpcFailureType {
        return when {
            message.contains("已领取") ||
                message.contains("重复") ||
                message.contains("已经领取") -> InsuredGoldRpcFailureType.DUPLICATE_REWARD

            code.contains("LIMIT", ignoreCase = true) ||
                message.contains("上限") ||
                message.contains("限制") ||
                message.contains("受限") ||
                message.contains("不可领取") ||
                message.contains("频繁") ||
                message.contains("稍后") -> InsuredGoldRpcFailureType.BUSINESS_LIMIT

            else -> InsuredGoldRpcFailureType.NON_RETRYABLE
        }
    }

    /**
     * 执行会员任务 类型1
     * @param task 单个任务对象
     */
    @Throws(JSONException::class)
    private suspend fun processLegacyMemberTask(task: JSONObject): Boolean = CoroutineUtils.run {
        val taskConfigInfo = task.getJSONObject("taskConfigInfo")
        val name = taskConfigInfo.getString("name")
        val id = taskConfigInfo.getLong("id")
        val awardParamPoint = extractMemberTaskAwardPoint(taskConfigInfo)
        if (isMemberTaskInBlacklist(id.toString(), name)) {
            Log.member("会员任务🎖️[$name]#黑名单任务，停止执行")
            return@run false
        }
        val unsupportedAdTaskReason = resolveUnsupportedMemberAdTaskReason(task, taskConfigInfo)
        if (unsupportedAdTaskReason != null) {
            logSkippedMemberAdTask(name, unsupportedAdTaskReason, "会员任务🎖️")
            return@run false
        }
        val adBizId = resolveMemberAdTaskBizId(task, taskConfigInfo)
        val targetBusiness = resolveSupportedMemberTaskTargetBusiness(
            taskConfigInfo.optJSONArray("targetBusiness")
        )
        if (targetBusiness.isEmpty() && adBizId.isEmpty()) {
            return@run false
        }
        if (adBizId.isNotEmpty()) {
            return@run finishMemberAdTask(id.toString(), name, awardParamPoint, adBizId)
        }
        val targetBusinessArray = targetBusiness.split("#".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        val bizType = targetBusinessArray[0]
        val bizSubType = targetBusinessArray[1]
        val bizParam = targetBusinessArray[2]
        val str = AntMemberRpcCall.executeTask(bizParam, bizSubType, bizType, id)
        val jo = JSONObject(str)
        if (!ResChecker.checkRes(TAG + "执行会员任务失败:", jo)) {
            Log.error(TAG, "执行任务失败:" + jo.optString("resultDesc"))
            return@run false
        }
        if (checkMemberTaskFinished(id)) {
            Log.member("会员任务🎖️[$name]#获得积分$awardParamPoint")
            return@run true
        }
        false
    }

    private suspend fun checkMemberTaskFinished(taskId: Long): Boolean {
        return try {
            val str = AntMemberRpcCall.queryLegacyAllStatusTaskList()
            val jsonObject = JSONObject(str)
            if (!ResChecker.checkRes(TAG + "查询会员任务状态失败:", jsonObject)) {
                Log.error(
                    "$TAG.checkMemberTaskFinished", "会员任务响应失败: " + jsonObject.getString("resultDesc")
                )
            }
            if (!jsonObject.has("availableTaskList")) {
                return true
            }
            val taskList = jsonObject.getJSONArray("availableTaskList")
            for (i in 0..<taskList.length()) {
                val taskConfigInfo = taskList.getJSONObject(i).getJSONObject("taskConfigInfo")
                val id = taskConfigInfo.getLong("id")
                if (taskId == id) {
                    return false
                }
            }
            true
        } catch (_: JSONException) {
            false
        }
    }

    private fun resolveSupportedMemberTaskTargetBusiness(targetBusinessArray: JSONArray?): String {
        if (targetBusinessArray == null || targetBusinessArray.length() <= 0) {
            return ""
        }
        for (i in 0 until targetBusinessArray.length()) {
            val targetBusiness = targetBusinessArray.optString(i)
            if (isSupportedMemberTaskTargetBusiness(targetBusiness)) {
                return targetBusiness
            }
        }
        return ""
    }

    private fun isSupportedMemberTaskTargetBusiness(targetBusiness: String): Boolean {
        if (targetBusiness.isBlank()) {
            return false
        }
        val targetParts = targetBusiness.split("#")
        if (targetParts.size < 3) {
            return false
        }
        val bizType = targetParts[0]
        val bizSubType = targetParts[1]
        val bizParam = targetParts[2]
        return bizType.equals("BROWSE", true) && bizSubType.isNotBlank() && bizParam.isNotBlank()
    }

    private fun shouldKeepMemberTaskConfigId(taskConfigId: String): Boolean {
        if (taskConfigId.length >= 12 && taskConfigId.startsWith("6")) {
            return true
        }
        return taskConfigId.length == 8 && taskConfigId.startsWith("3200")
    }

    private fun isMemberTaskInBlacklist(taskConfigId: String, taskTitle: String): Boolean {
        return TaskBlacklist.isTaskInBlacklist(memberTaskBlacklistModule, taskTitle)
            || TaskBlacklist.isTaskInBlacklist(memberTaskBlacklistModule, taskConfigId)
    }

    private fun resolveMemberAdTaskBizId(
        taskObject: JSONObject?,
        taskConfigInfo: JSONObject? = null
    ): String {
        if (isUnsupportedMemberAdTaskType(taskObject, taskConfigInfo)) {
            return ""
        }

        val urlCandidates = listOfNotNull(
            taskObject?.optString("actionUrl"),
            taskObject?.optString("targetUrl"),
            taskObject?.optString("jumpUrl"),
            taskObject?.optString("pageUrl"),
            taskObject?.optString("clickThroughUrl"),
            taskObject?.optString("halfClickThroughUrl"),
            taskConfigInfo?.optString("actionUrl"),
            taskConfigInfo?.optString("targetUrl"),
            taskConfigInfo?.optString("jumpUrl"),
            taskConfigInfo?.optString("pageUrl"),
            taskConfigInfo?.optString("clickThroughUrl"),
            taskConfigInfo?.optString("halfClickThroughUrl"),
            taskConfigInfo?.optString("schemaJson")
        ).filter { it.isNotBlank() }
        val hasMemberAdUrlMarker = urlCandidates.any { looksLikeMemberAdTaskUrl(it) }

        if (hasExplicitMemberAdTaskMarker(taskObject, taskConfigInfo, hasMemberAdUrlMarker)) {
            val directBizId = sequenceOf(
                taskObject?.optString("adBizId"),
                taskObject?.optJSONObject("logExtMap")?.optString("bizId"),
                taskObject?.optJSONObject("extInfo")?.optString("adBizId"),
                taskObject?.optJSONObject("extInfo")?.optString("bizId"),
                taskConfigInfo?.optString("adBizId"),
                taskConfigInfo?.optJSONObject("logExtMap")?.optString("bizId"),
                taskConfigInfo?.optJSONObject("extInfo")?.optString("adBizId"),
                taskConfigInfo?.optJSONObject("extInfo")?.optString("bizId")
            ).filterNotNull().firstOrNull { it.isNotBlank() }
            if (!directBizId.isNullOrBlank()) {
                return directBizId
            }
        }

        for (urlCandidate in urlCandidates) {
            if (!hasMemberAdUrlMarker || !looksLikeMemberAdTaskUrl(urlCandidate)) {
                continue
            }
            extractMemberAdBizIdFromText(urlCandidate)?.let { return it }
            val nestedUrl = UrlUtil.getFullNestedUrl(urlCandidate, "url")
            if (!nestedUrl.isNullOrBlank()) {
                extractMemberAdBizIdFromText(nestedUrl)?.let { return it }
            }
        }
        return ""
    }

    private fun resolveUnsupportedMemberAdTaskReason(
        taskObject: JSONObject?,
        taskConfigInfo: JSONObject?
    ): String? {
        val taskTypeCandidates = sequenceOf(
            taskObject?.optString("taskType"),
            taskConfigInfo?.optString("taskType")
        ).filterNotNull()
        if (taskTypeCandidates.any { it.equals("MULTIPLE_TIMER_TASK", true) }) {
            return "MULTIPLE_TIMER_TASK"
        }
        if (taskObject?.has("videoTaskInfo") == true || taskConfigInfo?.has("videoTaskInfo") == true) {
            return "VIDEO_TASK"
        }
        if (taskObject?.optBoolean("adVideoTask") == true || taskConfigInfo?.optBoolean("adVideoTask") == true) {
            return "AD_VIDEO_TASK"
        }
        if (hasMemberAdVideoSchema(taskObject, taskConfigInfo)) {
            return "VIDEO_TASK"
        }
        return null
    }

    private fun isUnsupportedMemberAdTaskType(
        taskObject: JSONObject?,
        taskConfigInfo: JSONObject?
    ): Boolean {
        return resolveUnsupportedMemberAdTaskReason(taskObject, taskConfigInfo) != null
    }

    private fun logSkippedMemberAdTask(
        taskTitle: String,
        skipReason: String,
        logPrefix: String = "会员任务"
    ) {
        val detail = when (skipReason) {
            "MULTIPLE_TIMER_TASK" -> "多阶段倒计时任务"
            "VIDEO_TASK", "AD_VIDEO_TASK" -> "视频广告任务"
            else -> skipReason
        }
        Log.member("$logPrefix[$taskTitle]#识别到$detail，阶段6按计划跳过")
    }

    private fun hasExplicitMemberAdTaskMarker(
        taskObject: JSONObject?,
        taskConfigInfo: JSONObject?,
        hasMemberAdUrlMarker: Boolean
    ): Boolean {
        if (hasMemberAdUrlMarker) {
            return true
        }
        val configIds = linkedSetOf<String>().apply {
            taskObject?.let(::resolveCurrentMemberTaskConfigId)?.takeIf { it.isNotBlank() }?.let(::add)
            taskObject?.optString("configId")?.takeIf { it.isNotBlank() }?.let(::add)
            taskConfigInfo?.optString("configId")?.takeIf { it.isNotBlank() }?.let(::add)
            taskConfigInfo?.optLong("id", 0L)?.takeIf { it > 0 }?.toString()?.let(::add)
        }
        if (configIds.any { it.length == 8 && it.startsWith("3200") }) {
            return true
        }
        return taskObject?.optBoolean("adTaskFlag") == true ||
            taskConfigInfo?.optBoolean("adTaskFlag") == true ||
            taskObject?.optBoolean("adTask") == true ||
            taskConfigInfo?.optBoolean("adTask") == true
    }

    private fun hasMemberAdVideoSchema(
        taskObject: JSONObject?,
        taskConfigInfo: JSONObject?
    ): Boolean {
        return sequenceOf(
            taskObject?.optString("schemaJson"),
            taskConfigInfo?.optString("schemaJson")
        ).filterNotNull()
            .any { schemaJson ->
                if (schemaJson.isBlank()) {
                    false
                } else {
                    runCatching {
                        JSONObject(schemaJson).optString("videoUrl").isNotBlank()
                    }.getOrDefault(false)
                }
            }
    }

    private fun extractMemberAdBizIdFromText(text: String): String? {
        if (text.isBlank()) {
            return null
        }
        UrlUtil.getParamValue(text, "bizId")?.takeIf { it.isNotBlank() }?.let { return it }
        UrlUtil.getParamValue(text, "opParam")
            ?.takeIf { it.isNotBlank() }
            ?.let { opParam ->
                runCatching { JSONObject(opParam).optString("bizId") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }
            }
        val jsonMatcher = Pattern.compile("\"bizId\"\\s*:\\s*\"([^\"]+)\"").matcher(text)
        if (jsonMatcher.find()) {
            return jsonMatcher.group(1)
        }
        val queryMatcher = Pattern.compile("bizId=([^&#\"]+)").matcher(text)
        if (queryMatcher.find()) {
            return queryMatcher.group(1)
        }
        return null
    }

    private fun looksLikeMemberAdTaskUrl(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        val normalized = text.lowercase()
        return normalized.contains("com.alipay.adtask.biz.mobilegw.service.task.finish") ||
            normalized.contains("spacecode#ant_member_xlight_task") ||
            normalized.contains("spacecode=ant_member_xlight_task") ||
            (normalized.contains("renderconfigkey=") && normalized.contains("ant_member_xlight_task"))
    }

    private suspend fun finishMemberAdTask(
        taskConfigId: String,
        taskTitle: String,
        fallbackAwardPoint: String,
        bizId: String
    ): Boolean = CoroutineUtils.run {
        val response = AntMemberRpcCall.taskFinish(bizId)
        val responseObject = JSONObject(response)
        val success = responseObject.optBoolean("success") ||
            responseObject.optString("errCode") == "0" ||
            responseObject.optString("resultCode").equals("SUCCESS", true)
        if (!success) {
            val message = sequenceOf(
                responseObject.optString("errMsg"),
                responseObject.optString("resultDesc"),
                responseObject.optString("errorMessage"),
                response
            ).firstOrNull { it.isNotBlank() }.orEmpty()
            Log.member("会员任务[$taskTitle]#广告任务上报失败:$message")
            return@run false
        }
        val verifyState = checkMemberAdTaskFinished(taskConfigId, bizId)
        val rewardPoint = responseObject.optJSONObject("extendInfo")
            ?.optJSONObject("rewardInfo")
            ?.optString("rewardAmount")
            .orEmpty()
            .ifEmpty { fallbackAwardPoint }
        if (verifyState == CurrentMemberTaskVerifyState.CONFIRMED) {
            if (rewardPoint.isNotBlank()) {
                Log.member("会员任务[$taskTitle]#获得积分$rewardPoint")
            } else {
                Log.member("会员任务[$taskTitle]#广告任务完成")
            }
        } else {
            Log.member("会员任务[$taskTitle]#广告任务上报成功，状态待后续页面确认")
        }
        return@run true
    }

    private suspend fun checkMemberAdTaskFinished(
        taskConfigId: String,
        bizId: String
    ): CurrentMemberTaskVerifyState {
        if (taskConfigId.isBlank() || bizId.isBlank()) {
            return CurrentMemberTaskVerifyState.UNCONFIRMED
        }
        return try {
            val detailResponse = AntMemberRpcCall.querySingleAdTaskProcessDetail(taskConfigId, bizId)
            val detailObject = JSONObject(detailResponse)
            if (!ResChecker.checkRes(TAG, detailObject)) {
                return CurrentMemberTaskVerifyState.UNCONFIRMED
            }
            val taskProcessObject = detailObject.optJSONObject("resultData")?.optJSONObject("taskProcessVO")
            if (isMemberTaskProcessFinished(taskProcessObject)) {
                CurrentMemberTaskVerifyState.CONFIRMED
            } else {
                CurrentMemberTaskVerifyState.UNCONFIRMED
            }
        } catch (_: JSONException) {
            CurrentMemberTaskVerifyState.UNCONFIRMED
        }
    }

    /**
     * 黄金票任务入口（首页签到/收取/任务扫描 + 提取）
     * @param doSignIn 是否执行签到
     * @param doConsume 是否执行提取
     */
    internal fun doGoldTicketTask(doSignIn: Boolean, doConsume: Boolean) {
        val needSignIn = doSignIn && !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_SIGN_DONE)
        val needHomeCheck = doSignIn && !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_HOME_DONE)
        val needWelfareCheck = doSignIn && !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_WELFARE_DONE)
        val needConsume = doConsume && !hasFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_CONSUME_DONE)

        if (!needSignIn && !needHomeCheck && !needWelfareCheck && !needConsume) {
            Log.member("黄金票🎫[今日已处理] 跳过执行")
            return
        }

        try {
            Log.member("开始执行黄金票...")

            var homeUpsertData: JSONObject? = null
            if (needSignIn || needHomeCheck) {
                homeUpsertData = queryGoldTicketHomeUpsert()
            }

            if (needSignIn) {
                if (homeUpsertData == null) {
                    Log.error("黄金票🎫[首页查询失败] 无法判断签到状态")
                } else if (doGoldTicketSignIn(homeUpsertData)) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_SIGN_DONE)
                    homeUpsertData = queryGoldTicketHomeUpsert() ?: homeUpsertData
                }
            }

            if (needHomeCheck) {
                if (homeUpsertData == null) {
                    Log.error("黄金票🎫[首页查询失败] 跳过收取与任务扫描")
                } else {
                    doGoldTicketCollect(homeUpsertData)
                    handleGoldTicketTasks(homeUpsertData)
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_HOME_DONE)
                }
            }

            if (needWelfareCheck) {
                val welfareHandleResult = handleGoldTicketWelfareTasks()
                if (!welfareHandleResult.querySuccess) {
                    Log.error("黄金票🎫[福利中心任务查询失败]")
                } else if (welfareHandleResult.canMarkDone) {
                    setFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_WELFARE_DONE)
                }
            }

            if (needConsume) {
                doGoldTicketConsume()
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 黄金票首页数据
     */
    private fun queryGoldTicketHomeUpsert(taskId: String = ""): JSONObject? {
        return try {
            val homeRes = AntMemberRpcCall.queryGoldTicketHome(taskId) ?: return null
            val homeJson = JSONObject(homeRes)
            if (!ResChecker.checkRes(TAG, homeJson)) {
                return null
            }
            homeJson.optJSONObject("result")?.optJSONObject("upsertData")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            null
        }
    }

    private fun isGoldTicketCanSign(homeUpsertData: JSONObject?): Boolean {
        return homeUpsertData?.optJSONObject("assetInfo")?.optBoolean("canSign", false) == true
    }

    private fun doGoldTicketIndexCollect(source: String): Int {
        val needleResponse = AntMemberRpcCall.goldTicketIndexCollect()
        if (!needleResponse.isNullOrBlank()) {
            return logGoldTicketCollectResponse(needleResponse, source)
        }
        return logGoldTicketCollectResponse(
            AntMemberRpcCall.goldBillCollect(),
            "$source-旧版兼容"
        )
    }

    /**
     * 黄金票签到逻辑
     *
     * 真实首页日志来自 `com.alipay.wealthgoldtwa.needle.v2.index`，
     * 抓包显示收取接口已切到 `com.alipay.wealthgoldtwa.needle.index.collect`，
     * 因此先用首页 `canSign` 判定，再尝试新版首页收取；
     * 若仍未落库，再回退到已有的 welfareCenter 触发链路。
     */
    private fun doGoldTicketSignIn(homeUpsertData: JSONObject): Boolean {
        return try {
            if (!isGoldTicketCanSign(homeUpsertData)) {
                Log.member("黄金票🎫[今日已签到]")
                return true
            }

            Log.member("黄金票🎫[准备签到]")

            var signSuccess = false
            val collectCount = doGoldTicketIndexCollect("签到尝试")
            var refreshedHome = queryGoldTicketHomeUpsert()
            if (refreshedHome != null && !isGoldTicketCanSign(refreshedHome)) {
                Log.member(
                    if (collectCount > 0) "黄金票🎫[签到成功]#通过首页收取完成签到"
                    else "黄金票🎫[签到成功]"
                )
                signSuccess = true
            }

            if (!signSuccess) {
                val signRes = AntMemberRpcCall.welfareCenterTrigger("SIGN")
                if (signRes.isNotBlank()) {
                    val signJson = JSONObject(signRes)
                    if (ResChecker.checkRes(TAG, signJson)) {
                        val signResult = signJson.optJSONObject("result")
                        val amount = signResult?.optJSONObject("prize")?.optString("amount").orEmpty()
                        refreshedHome = queryGoldTicketHomeUpsert()
                        signSuccess = refreshedHome != null && !isGoldTicketCanSign(refreshedHome)
                        if (signSuccess || amount.isNotBlank()) {
                            Log.member(
                                if (amount.isNotBlank()) "黄金票🎫[签到成功]#获得: $amount"
                                else "黄金票🎫[签到成功]"
                            )
                            signSuccess = true
                        }
                    }
                }
            }

            if (!signSuccess) {
                Log.error("黄金票🎫[签到失败] 未找到可用签到返回")
            }
            signSuccess
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            false
        }
    }

    /**
     * 黄金票首页场景收取
     */
    private fun doGoldTicketCollect(homeUpsertData: JSONObject) {
        try {
            val toBeCollectInfo = homeUpsertData.optJSONObject("assetInfo")?.optJSONObject("toBeCollectInfo")
            val totalProfitValue = toBeCollectInfo?.optInt("totalProfitValue", 0) ?: 0
            if (totalProfitValue <= 0) {
                return
            }

            val collectCount = doGoldTicketIndexCollect("场景收取")
            if (collectCount == 0) {
                Log.member("黄金票🎫[场景收取] 暂无可领取奖励")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    private fun logGoldTicketCollectResponse(response: String?, source: String): Int {
        if (response.isNullOrBlank()) {
            return 0
        }
        return try {
            val collectJson = JSONObject(response)
            if (!ResChecker.checkRes(TAG, collectJson)) {
                val message = collectJson.optString("resultDesc", collectJson.optString("memo"))
                if (message.isNotBlank()) {
                    Log.member("黄金票🎫[$source] $message")
                }
                return 0
            }

            val result = collectJson.optJSONObject("result") ?: return 0
            val collectedList = result.optJSONArray("collectedList") ?: return 0
            var count = 0
            for (i in 0 until collectedList.length()) {
                val item = collectedList.optString(i)
                if (item.isBlank()) {
                    continue
                }
                count++
                Log.member("黄金票🎫[$source]#$item")
            }

            if (count > 0) {
                val totalAmount = result.optJSONObject("collectedCamp")?.optString("amount").orEmpty()
                if (totalAmount.isNotBlank()) {
                    Log.member("黄金票🎫[$source]#本次共得${totalAmount}份")
                }
            }
            count
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            0
        }
    }

    private data class GoldTicketWelfareHandleResult(
        val querySuccess: Boolean,
        val canMarkDone: Boolean
    )

    private fun isGoldTicketEggSignTask(task: JSONObject): Boolean {
        val taskId = task.optString("taskId")
        if (taskId == "AP11249033") {
            return true
        }
        return task.optString("title").contains("蛋定生财")
    }

    private fun isGoldTicketKnownWelfareAutoTask(task: JSONObject): Boolean {
        return when (task.optString("taskId")) {
            "AP11249033", // 逛蛋定生财去签到
            "AP10247402", // 逛逛稳健理财领红包
            "AP13250426", // 逛定期市场领红包
            "AP15280470", // 逛蚂蚁投教基地
            "AP16338809"  // 去芝麻攒粒兑权益
            -> true

            else -> false
        }
    }

    private fun queryGoldTicketWelfareResult(): JSONObject? {
        return try {
            val welfareResponse = AntMemberRpcCall.queryWelfareHome() ?: return null
            val welfareJson = JSONObject(welfareResponse)
            if (!ResChecker.checkRes(TAG, welfareJson)) {
                return null
            }
            welfareJson.optJSONObject("result")
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            null
        }
    }

    private fun queryGoldTicketWelfareTodoTasks(): JSONArray? {
        val welfareResult = queryGoldTicketWelfareResult() ?: return null
        return welfareResult.optJSONObject("goldbillTasks")
            ?.optJSONArray("todo")
            ?: JSONArray()
    }

    private fun countGoldTicketPendingWelfareAutoTasks(todoTasks: JSONArray?): Int {
        if (todoTasks == null || todoTasks.length() == 0) {
            return 0
        }
        var pendingCount = 0
        for (i in 0 until todoTasks.length()) {
            val task = todoTasks.optJSONObject(i) ?: continue
            if (isGoldTicketKnownWelfareAutoTask(task)) {
                pendingCount++
            }
        }
        return pendingCount
    }

    /**
     * 黄金票任务扫描
     *
     * 首页里已确认的攒粒浏览任务会以
     * `SIGNUP_EXPIRED -> goldbill.v4.task.trigger -> needle.taskQueryPush`
     * 闭环完成，其余首页任务仍保守记录为手动任务。
     */
    private fun handleGoldTicketTasks(homeUpsertData: JSONObject) {
        try {
            val todoTasks = homeUpsertData.optJSONObject("task")
                ?.optJSONObject("tasks")
                ?.optJSONArray("todo") ?: return

            if (todoTasks.length() == 0) {
                return
            }

            var autoReceivedCount = 0
            var manualCount = 0
            for (i in 0 until todoTasks.length()) {
                val task = todoTasks.optJSONObject(i) ?: continue
                val status = task.optString("taskProcessStatus")
                when (status) {
                    "TO_RECEIVE" -> {
                        if (tryReceiveGoldTicketTask(task)) {
                            autoReceivedCount++
                        }
                    }

                    "NONE_SIGNUP", "SIGNUP_EXPIRED" -> {
                        val link = task.optString("link")
                        val canAccess = task.optBoolean("canAccess", false)
                        if (link.isNotBlank() || canAccess) {
                            manualCount++
                        }
                    }

                    "SIGNUP_COMPLETE" -> {
                        if (isGoldTicketEggSignTask(task)) {
                            continue
                        }
                        val link = task.optString("link")
                        val canAccess = task.optBoolean("canAccess", false)
                        if (link.isNotBlank() || canAccess) {
                            manualCount++
                        }
                    }
                }
            }

            if (autoReceivedCount > 0) {
                Log.member("黄金票🎫[任务自动领取] ${autoReceivedCount}项")
            }
            if (manualCount > 0) {
                Log.member("黄金票🎫[任务待手动处理] ${manualCount}项")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }

    /**
     * 福利中心已确认的浏览类任务会走：
     * `goldbill.v4.task.trigger -> needle.taskQueryPush -> welfareCenter.index`
     * 这里仅放开抓包已确认的 taskId，避免把未知福利任务误判成可自动完成。
     */
    private fun handleGoldTicketWelfareTasks(): GoldTicketWelfareHandleResult {
        try {
            val todoTasks = queryGoldTicketWelfareTodoTasks()
                ?: return GoldTicketWelfareHandleResult(querySuccess = false, canMarkDone = false)

            val trackedAutoTaskCount = countGoldTicketPendingWelfareAutoTasks(todoTasks)
            if (trackedAutoTaskCount == 0) {
                return GoldTicketWelfareHandleResult(querySuccess = true, canMarkDone = true)
            }

            var autoReceivedCount = 0
            var manualCount = 0
            for (i in 0 until todoTasks.length()) {
                val task = todoTasks.optJSONObject(i) ?: continue
                if (!isGoldTicketKnownWelfareAutoTask(task)) {
                    continue
                }

                when (task.optString("taskProcessStatus")) {
                    "TO_RECEIVE", "NONE_SIGNUP", "SIGNUP_EXPIRED", "SIGNUP_COMPLETE" -> {
                        if (tryReceiveGoldTicketTask(task, "福利中心")) {
                            autoReceivedCount++
                        } else {
                            manualCount++
                        }
                    }

                    "RECEIVE_SUCCESS" -> Unit
                    else -> manualCount++
                }
            }

            if (manualCount > 0) {
                Log.member("黄金票🎫[福利中心任务待手动处理] ${manualCount}项")
            }

            val refreshedTodoTasks = queryGoldTicketWelfareTodoTasks()
            if (refreshedTodoTasks == null) {
                Log.member("黄金票🎫[福利中心任务复查失败] 暂不写入今日完成")
                return GoldTicketWelfareHandleResult(
                    querySuccess = true,
                    canMarkDone = false
                )
            }

            val pendingRetryCount = countGoldTicketPendingWelfareAutoTasks(refreshedTodoTasks)
            val confirmedAutoReceivedCount = (trackedAutoTaskCount - pendingRetryCount).coerceAtLeast(0)
            if (confirmedAutoReceivedCount > 0) {
                Log.member("黄金票🎫[福利中心任务自动领取] ${confirmedAutoReceivedCount}项")
            }
            if (pendingRetryCount > 0) {
                Log.member("黄金票🎫[福利中心任务保留下次重试] ${pendingRetryCount}项")
            }
            return GoldTicketWelfareHandleResult(
                querySuccess = true,
                canMarkDone = pendingRetryCount == 0
            )
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            return GoldTicketWelfareHandleResult(querySuccess = false, canMarkDone = false)
        }
    }

    private fun tryReceiveGoldTicketTask(task: JSONObject, source: String = "首页"): Boolean {
        val taskId = task.optString("taskId")
        if (taskId.isBlank()) {
            return false
        }
        val title = task.optString("title", taskId)
        val status = task.optString("taskProcessStatus")
        val isBlacklisted =
            TaskBlacklist.isTaskInBlacklist(goldTicketTaskBlacklistModule, title) ||
                TaskBlacklist.isTaskInBlacklist(goldTicketTaskBlacklistModule, taskId)
        if (isBlacklisted && status != "TO_RECEIVE") {
            Log.member("黄金票🎫[黑名单跳过]#$source#$title#$taskId#$status")
            return false
        }
        if (isBlacklisted) {
            Log.member("黄金票🎫[黑名单放行领奖]#$source#$title#$taskId#$status")
        }
        return try {
            if (status != "SIGNUP_COMPLETE") {
                val triggerRes = AntMemberRpcCall.goldBillTaskTrigger(taskId) ?: return false
                val triggerJson = JSONObject(triggerRes)
                if (!ResChecker.checkRes(TAG, triggerJson)) {
                    val triggerCode = triggerJson.optString("resultCode", triggerJson.optString("errorCode", ""))
                    val triggerDesc = triggerJson.optString("resultDesc", triggerJson.optString("memo"))
                    if (triggerCode.isNotBlank()) {
                        TaskBlacklist.autoAddToBlacklist(goldTicketTaskBlacklistModule, taskId, title, triggerCode)
                    }
                    if (triggerDesc.isNotBlank()) {
                        Log.error("黄金票🎫[${source}任务领取失败] $title#$taskId#$status#$triggerDesc")
                    }
                    return false
                }
            }

            val pushRes = AntMemberRpcCall.taskQueryPush(taskId)
            if (pushRes.isNullOrBlank()) {
                Log.member("黄金票🎫[${source}任务推送无返回] $title#$taskId#$status")
                return false
            }
            val pushJson = JSONObject(pushRes)
            if (!ResChecker.checkRes(TAG, pushJson)) {
                val pushCode = pushJson.optString("resultCode", pushJson.optString("errorCode", ""))
                val pushDesc = pushJson.optString("resultDesc", pushJson.optString("memo"))
                if (pushCode.isNotBlank()) {
                    TaskBlacklist.autoAddToBlacklist(goldTicketTaskBlacklistModule, taskId, title, pushCode)
                }
                if (pushDesc.isNotBlank()) {
                    Log.member("黄金票🎫[${source}任务推送提示] $title#$taskId#$status#$pushDesc")
                }
                return false
            }
            val pushDone = pushJson.optJSONObject("result")
                ?.optJSONObject("pushResult")
                ?.optBoolean("done", true)
            if (pushDone == false) {
                Log.member("黄金票🎫[${source}任务推送未完成] $title#$taskId#$status")
                return false
            }

            val amount = task.optString("amount")
            if (amount.isNotBlank()) {
                Log.member("黄金票🎫[${source}任务领取成功]#$title#+${amount}份")
            } else {
                Log.member("黄金票🎫[${source}任务领取成功]#$title")
            }
            true
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            false
        }
    }

    /**
     * 黄金票提取逻辑（`queryConsumeHome` + `submitConsume`）
     */
    private fun doGoldTicketConsume() {
        var consumeDone = false
        try {
            Log.member("黄金票🎫[准备检查余额及提取]")

            // 1. 调用新接口 queryConsumeHome 获取最新的资产信息
            val queryRes = AntMemberRpcCall.queryConsumeHome() ?: return
            val queryJson = JSONObject(queryRes)
            if (!ResChecker.checkRes(TAG, queryJson)) return

            val result = queryJson.optJSONObject("result") ?: return

            // 2. 获取余额
            val assetInfo = result.optJSONObject("assetInfo") ?: return

            val availableAmount = assetInfo.optInt("availableAmount", 0)
            val minExchangeAmount = assetInfo.optInt("minExchangeAmount", 100)
            val exchangeAmountUnit = assetInfo.optInt("exchangeAmountUnit", minExchangeAmount).coerceAtLeast(1)

            // 3. 按接口返回的门槛与步长计算提取数量
            val extractAmount = (availableAmount / exchangeAmountUnit) * exchangeAmountUnit

            if (extractAmount < minExchangeAmount) {
                Log.member("黄金票🎫[余额不足] 当前: $availableAmount，最低需$minExchangeAmount")
                consumeDone = true
                return
            }

            // 4. 获取必要参数 productId 和 bonusAmount
            var productId = ""
            val product = result.optJSONObject("product")
            if (product != null) {
                productId = product.optString("productId")
            } else if (result.has("productList") && result.optJSONArray("productList") != null && (result.optJSONArray("productList")?.length()
                    ?: 0) > 0
            ) {
                productId = result.optJSONArray("productList")?.optJSONObject(0)?.optString("productId") ?: ""
            } else if (assetInfo.optJSONArray("mainExchangePrizeList")?.length() ?: 0 > 0) {
                productId = assetInfo.optJSONArray("mainExchangePrizeList")?.optJSONObject(0)?.optString("bizNo") ?: ""
            } else if (assetInfo.optJSONArray("footerExchangePrizeList")?.length() ?: 0 > 0) {
                productId = assetInfo.optJSONArray("footerExchangePrizeList")?.optJSONObject(0)?.optString("bizNo") ?: ""
            } else {
                val backupPrize = assetInfo.optJSONObject("backupPrize")
                if (backupPrize != null && "GOLD".equals(backupPrize.optString("prizeType"), true)) {
                    productId = backupPrize.optString("bizNo")
                }
            }

            if (productId.isEmpty()) {
                Log.error("黄金票🎫[提取异常] 未找到有效的基金ID")
                return
            }

            var bonusAmount = 0
            val bonusInfo = result.optJSONObject("bonusInfo")
            if (bonusInfo != null) {
                bonusAmount = bonusInfo.optInt("bonusAmount", 0)
            }

            // 5. 提交提取
            val exchangeMoney = result.optJSONObject("calcInfo")?.optString("exchangeMoney")
                ?.takeIf { it.isNotBlank() } ?: String.format(Locale.US, "%.2f", extractAmount / 1000.0)
            Log.member("黄金票🎫[开始提取] 计划: $extractAmount 份 => $exchangeMoney 元 (持有: $availableAmount)")
            val submitRes = AntMemberRpcCall.submitConsume(extractAmount, productId, bonusAmount)

            if (submitRes.isNullOrBlank()) {
                Log.error("黄金票🎫[提取失败] 接口无返回")
                return
            }

            val submitJson = JSONObject(submitRes)
            if (!ResChecker.checkRes(TAG, submitJson)) {
                val submitDesc = submitJson.optString("resultDesc", submitJson.optString("memo"))
                if (submitDesc.isNotBlank()) {
                    Log.error("黄金票🎫[提取失败] $submitDesc")
                }
                return
            }

            val submitResult = submitJson.optJSONObject("result")
            val writeOffNo = submitResult?.optString("writeOffNo").orEmpty()
            val successTitle = submitResult?.optString("successTitle").orEmpty()
            if (writeOffNo.isNotBlank() || successTitle.contains("成功")) {
                Log.member("黄金票🎫[提取成功]#$exchangeMoney 元#$extractAmount 份")
                consumeDone = true
            } else {
                Log.error("黄金票🎫[提取失败] 未返回核销码")
            }
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        } finally {
            if (consumeDone) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_GOLD_TICKET_CONSUME_DONE)
            }
        }
    }

    private suspend fun enableGameCenter() {
        try {
            // 1. 查询签到状态并尝试签到
            try {
                val resp = AntMemberRpcCall.querySignInBall()
                val root = JSONObject(resp)
                if (!ResChecker.checkRes(TAG, root)) {
                    val msg = root.optString("errorMsg", root.optString("resultView", resp))
                    Log.error("$TAG.enableGameCenter.signIn", "游戏中心🎮[签到查询失败]#$msg")
                } else {
                    val data = root.optJSONObject("data")

                    if (data == null || data.length() == 0) {
                        Log.member("游戏中心🎮[签到状态为空，跳过签到]")
                    } else {
                        val signModule = data.optJSONObject("signInBallModule")
                        val signed = signModule != null && signModule.optBoolean("signInStatus", false)
                        if (signed) {
                            Log.member("游戏中心🎮[今日已签到]")
                        } else {
                            val signResp = AntMemberRpcCall.continueSignIn()
                            val signJo = JSONObject(signResp)
                            if (!ResChecker.checkRes(TAG, signJo)) {
                                val msg = signJo.optString(
                                    "errorMsg", signJo.optString("resultView", signResp)
                                )
                                Log.error("$TAG.enableGameCenter.signIn", "游戏中心🎮[签到失败]#$msg")
                            } else {
                                val signData = signJo.optJSONObject("data")
                                var title = ""
                                var desc = ""
                                var type = ""
                                if (signData != null) {
                                    val toast = signData.optJSONObject("autoSignInToastModule")
                                    if (toast != null) {
                                        title = toast.optString("title", "")
                                        desc = toast.optString("desc", "")
                                        type = toast.optString("type", "")
                                    }
                                }
                                val toastSuccess = "SUCCESS".equals(type, ignoreCase = true) && !title.contains("失败") && !desc.contains("失败")
                                if (toastSuccess) {
                                    val sb = StringBuilder()
                                    sb.append("游戏中心🎮[每日签到成功]")
                                    if (!title.isEmpty()) {
                                        sb.append("#").append(title)
                                    }
                                    if (!desc.isEmpty()) {
                                        sb.append("#").append(desc)
                                    }
                                    Log.member(sb.toString())
                                } else {
                                    val sb = StringBuilder()
                                    if (!title.isEmpty()) {
                                        sb.append(title)
                                    }
                                    if (!desc.isEmpty()) {
                                        if (sb.isNotEmpty()) sb.append(" ")
                                        sb.append(desc)
                                    }
                                    Log.error(
                                        "$TAG.enableGameCenter.signIn", "游戏中心🎮[签到失败]#" + (if (sb.isNotEmpty()) sb.toString() else signResp)
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "enableGameCenter.signIn err:", th)
            }

            // 2. 查询任务列表,完成平台任务
            try {
                val resp = AntMemberRpcCall.queryGameCenterTaskList()
                val root = JSONObject(resp)
                if (!ResChecker.checkRes(TAG, root)) {
                    val msg = root.optString("errorMsg", root.optString("resultView", resp))
                    Log.error("$TAG.enableGameCenter.tasks", "游戏中心🎮[任务列表查询失败]#$msg")
                } else {
                    val data = root.optJSONObject("data")
                    if (data != null) {
                        val platformTaskModule = data.optJSONObject("gameTaskModule")
                            ?: data.optJSONObject("platformTaskModule")
                        if (platformTaskModule != null) {
                            val platformTaskList = platformTaskModule.optJSONArray("gameTaskList")
                                ?: platformTaskModule.optJSONArray("platformTaskList")
                            if (platformTaskList != null && platformTaskList.length() > 0) {
                                var total = 0
                                var finished = 0
                                var failed = 0
                                var lastFailedTaskId = ""
                                var lastFailedCount = 0

                                for (i in 0..<platformTaskList.length()) {
                                    val task = platformTaskList.optJSONObject(i) ?: continue

                                    val taskId = task.optString("taskId")
                                    val status = task.optString("taskStatus")

                                    if (taskId.isEmpty()) continue
                                    if ("NOT_DONE" != status && "SIGNUP_COMPLETE" != status) {
                                        continue
                                    }

                                    // 如果是上次失败的任务,计数加1
                                    if (taskId == lastFailedTaskId) {
                                        lastFailedCount++
                                        if (lastFailedCount >= 2) {
                                            Log.member("游戏中心🎮任务[" + task.optString("title") + "]连续失败2次,跳过"
                                            )
                                            continue
                                        }
                                    } else {
                                        // 新任务,重置计数
                                        lastFailedTaskId = taskId
                                        lastFailedCount = 0
                                    }

                                    total++
                                    val title = task.optString("title")
                                    val subTitle = task.optString("subTitle")
                                    val needSignUp = task.optBoolean("needSignUp", false)
                                    val pointAmount = task.optInt("pointAmount", 0)

                                    try {
                                        // needSignUp 为 true 且是首次状态 NOT_DONE:先报名
                                        if (needSignUp && "NOT_DONE" == status) {
                                            val signUpResp = AntMemberRpcCall.doTaskSignup(taskId)
                                            val signUpJo = JSONObject(signUpResp)
                                            if (!ResChecker.checkRes(TAG, signUpJo)) {
                                                val msg = signUpJo.optString(
                                                    "errorMsg", signUpJo.optString("resultView", signUpResp)
                                                )
                                                Log.error(
                                                    "$TAG.enableGameCenter.tasks", "游戏中心🎮任务[$title]报名失败#$msg"
                                                )
                                                failed++
                                                continue
                                            }
                                        }

                                        // 完成任务
                                        val doResp = AntMemberRpcCall.doTaskSend(taskId)
                                        val doJo = JSONObject(doResp)

                                        if (ResChecker.checkRes(TAG, doJo)) {
                                            // 检查返回的任务状态
                                            val doData = doJo.optJSONObject("data")
                                            val resultStatus = if (doData != null) doData.optString(
                                                "taskStatus", ""
                                            ) else ""

                                            if ("SIGNUP_COMPLETE" == resultStatus || "NOT_DONE" == resultStatus) {
                                                // 状态未变更,记为失败
                                                Log.error(
                                                    "$TAG.enableGameCenter.tasks", "游戏中心🎮任务[$title]状态未变更,可能无法完成"
                                                )
                                                failed++
                                            } else {
                                                // 真正完成,重置失败计数
                                                Log.member(
                                                    "游戏中心🎮任务[" + (subTitle.ifEmpty { title }) + "]#完成,奖励" + pointAmount + "玩乐豆" + (if (needSignUp) "(签到任务)" else "")
                                                )
                                                finished++
                                                lastFailedTaskId = ""
                                                lastFailedCount = 0
                                            }
                                        } else {
                                            val msg = doJo.optString(
                                                "errorMsg", doJo.optString("resultView", doResp)
                                            )
                                            Log.error(
                                                "$TAG.enableGameCenter.tasks", "游戏中心🎮任务[$title]完成失败#$msg"
                                            )
                                            failed++
                                        }
                                    } catch (e: Throwable) {
                                        Log.printStackTrace("$TAG.enableGameCenter.tasks.doTask", e)
                                        failed++
                                    }
                                }

                                if (total > 0) {
                                    Log.member("游戏中心🎮[平台任务处理完成]#待做:$total 完成:$finished 失败:$failed"
                                    )
                                } else {
                                    Log.member("游戏中心🎮[无待处理的平台任务]"
                                    )
                                }
                            } else {
                                Log.member("游戏中心🎮[平台任务列表为空]")
                            }
                        }
                    }
                }
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "enableGameCenter.tasks err:", th)
            }

            // 3. 查询待收乐豆并使用一键收取接口
            try {
                val resp = AntMemberRpcCall.queryPointBallList()
                val root = JSONObject(resp)
                if (!ResChecker.checkRes(TAG, root)) {
                    val msg = root.optString("errorMsg", root.optString("resultView", resp))
                    Log.error("$TAG.enableGameCenter.point", "游戏中心🎮[查询待收乐豆失败]#$msg")
                } else {
                    val data = root.optJSONObject("data")
                    val pointBallList = data?.optJSONArray("pointBallList")
                    if (pointBallList == null || pointBallList.length() == 0) {
                        Log.member("游戏中心🎮[暂无可领取乐豆]")
                    } else {
                        val batchResp = AntMemberRpcCall.batchReceivePointBall()
                        val batchJo = JSONObject(batchResp)
                        if (ResChecker.checkRes(TAG, batchJo)) {
                            val batchData = batchJo.optJSONObject("data")
                            val receiveAmount = batchData?.optInt("receiveAmount", 0) ?: 0
                            val totalAmount = batchData?.optInt("totalAmount", receiveAmount) ?: receiveAmount
                            if (receiveAmount > 0) {
                                Log.member("游戏中心🎮[一键领取乐豆成功]#本次领取" + receiveAmount + " | 当前累计" + totalAmount + "玩乐豆")
                            } else {
                                Log.member("游戏中心🎮[暂无可领取乐豆]")
                            }
                        } else {
                            val msg = batchJo.optString(
                                "errorMsg", batchJo.optString("resultView", batchResp)
                            )
                            Log.error(
                                "$TAG.enableGameCenter.point", "游戏中心🎮[一键领取乐豆失败]#$msg"
                            )
                        }
                    }
                }
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "enableGameCenter.point err:", th)
            }

            // 4. 游戏中心赚现金签到
            try {
                doGameCenterP2eSignIn()
            } catch (th: Throwable) {
                Log.printStackTrace(TAG, "enableGameCenter.p2eSignIn err:", th)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, t)
        }
    }

    private suspend fun doGameCenterP2eSignIn() {
        val resp = AntMemberRpcCall.queryGameCenterP2eHomePage()
        val root = JSONObject(resp)
        if (!ResChecker.checkRes(TAG, root)) {
            logGameCenterP2eFailure("赚现金签到查询", root, resp)
            return
        }

        val data = root.optJSONObject("data")
        val signUpModule = data?.optJSONObject("signUpModuleVO")
        if (signUpModule == null) {
            val riskMsg = data?.optString("hitRiskControlMsg").orEmpty()
            if (data?.optBoolean("hitRiskControl", false) == true || riskMsg.isNotBlank()) {
                Log.member("游戏中心🎮[赚现金签到业务受限，跳过]#$riskMsg")
            } else {
                Log.member("游戏中心🎮[赚现金暂无签到模块]")
            }
            return
        }

        val todayRecord = findGameCenterP2eTodaySignRecord(signUpModule)
        val todayStatus = todayRecord?.optString("signUpStatus").orEmpty()
        if ("SIGNED".equals(todayStatus, ignoreCase = true)) {
            val amount = todayRecord?.optString("todayGoldCoinAmount").orEmpty()
            Log.member("游戏中心🎮[赚现金今日已签到]" + if (amount.isNotBlank()) "#金币+$amount" else "")
            return
        }

        val date = signUpModule.optString("date")
        val index = signUpModule.optInt("index", 0)
        val signSequenceId = signUpModule.optString("signSequenceId")
        if (date.isBlank() || signSequenceId.isBlank()) {
            Log.error(
                "$TAG.enableGameCenter.p2eSignIn",
                "游戏中心🎮[赚现金签到配置缺失]#date=$date index=$index signSequenceId=$signSequenceId"
            )
            return
        }

        val signResp = AntMemberRpcCall.gameCenterP2eSignIn(date, index, signSequenceId)
        val signObject = JSONObject(signResp)
        if (!ResChecker.checkRes(TAG, signObject)) {
            logGameCenterP2eFailure("赚现金签到", signObject, signResp)
            return
        }

        val signedRecord = findGameCenterP2eTodaySignRecord(
            signObject.optJSONObject("data")?.optJSONObject("signUpPopupModuleVO")
        )
        val signedStatus = signedRecord?.optString("signUpStatus").orEmpty()
        val amount = signedRecord?.optString("todayGoldCoinAmount").orEmpty()
        if ("SIGNED".equals(signedStatus, ignoreCase = true) || signObject.optBoolean("success", false)) {
            Log.member("游戏中心🎮[赚现金签到成功]" + if (amount.isNotBlank()) "#金币+$amount" else "")
        } else {
            Log.error(
                "$TAG.enableGameCenter.p2eSignIn",
                "游戏中心🎮[赚现金签到状态未确认]#" + buildGameCenterRpcMessage(signObject, signResp)
            )
        }
    }

    private fun findGameCenterP2eTodaySignRecord(signUpModule: JSONObject?): JSONObject? {
        if (signUpModule == null) {
            return null
        }
        val signDate = signUpModule.optString("date")
        val records = signUpModule.optJSONArray("signRecordVOList") ?: return null
        var dateMatchedRecord: JSONObject? = null
        for (i in 0 until records.length()) {
            val record = records.optJSONObject(i) ?: continue
            if (record.optBoolean("isToday", false)) {
                return record
            }
            if (signDate.isNotBlank() && signDate == record.optString("signDate")) {
                dateMatchedRecord = record
            }
        }
        return dateMatchedRecord
    }

    private fun logGameCenterP2eFailure(scene: String, response: JSONObject, rawResponse: String) {
        val message = buildGameCenterRpcMessage(response, rawResponse)
        when {
            isGameCenterBusinessLimited(response, message) ->
                Log.member("游戏中心🎮[$scene]#业务受限，本轮跳过:$message")

            isGameCenterDuplicateOrAlreadyDone(message) ->
                Log.member("游戏中心🎮[$scene]#已处理过，跳过重复处理:$message")

            !response.optBoolean("retryable", true) ->
                Log.error("$TAG.enableGameCenter.p2eSignIn", "游戏中心🎮[$scene]#非重试失败:$message")

            else ->
                Log.error("$TAG.enableGameCenter.p2eSignIn", "游戏中心🎮[$scene]#失败:$message")
        }
    }

    private fun buildGameCenterRpcMessage(response: JSONObject, rawResponse: String): String {
        return sequenceOf(
            response.optString("errorMsg"),
            response.optString("errorMessage"),
            response.optString("resultView"),
            response.optString("resultDesc"),
            response.optString("memo"),
            response.optString("desc")
        ).firstOrNull { it.isNotBlank() } ?: rawResponse
    }

    private fun isGameCenterBusinessLimited(response: JSONObject, message: String): Boolean {
        val errorCode = response.optString("errorCode", response.optString("resultCode"))
        return errorCode.equals("PROMO_RISK_ERROR", ignoreCase = true) ||
            message.contains("不在活动邀请范围") ||
            message.contains("风险") ||
            message.contains("风控") ||
            message.contains("受限")
    }

    private fun isGameCenterDuplicateOrAlreadyDone(message: String): Boolean {
        return message.contains("已签到") ||
            message.contains("已领取") ||
            message.contains("重复") ||
            message.contains("already", ignoreCase = true)
    }

    internal fun beanSignIn() {
        try {
            try {
                val signInProcessStr = AntMemberRpcCall.querySignInProcess("AP16242232", "INS_BLUE_BEAN_SIGN")

                var jo = JSONObject(signInProcessStr)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.member(jo.toString())
                    return
                }

                val signInResult = jo.optJSONObject("result")
                if (signInResult?.optBoolean("canPush") == true) {
                    val signInTriggerStr = AntMemberRpcCall.signInTrigger("AP16242232", "INS_BLUE_BEAN_SIGN")

                    jo = JSONObject(signInTriggerStr)
                    if (ResChecker.checkRes(TAG, jo)) {
                        val prizeName = extractBeanSignInPrizeName(jo)
                        if (prizeName.isBlank()) {
                            Log.member("安心豆🫘[签到成功]")
                        } else {
                            Log.member("安心豆🫘[$prizeName]")
                        }
                    } else {
                        Log.member(jo.toString())
                    }
                }
                collectGuardianBeanAward()
            } catch (e: NullPointerException) {
                Log.printStackTrace(TAG, "安心豆🫘[RPC桥接失败]#可能是RpcBridge未初始化", e)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "beanSignIn err:", t)
        }
    }

    private fun extractBeanSignInPrizeName(responseObject: JSONObject): String {
        val prizeList = responseObject.optJSONObject("result")?.optJSONArray("prizeSendOrderDTOList") ?: return ""
        for (i in 0 until prizeList.length()) {
            val prizeName = prizeList.optJSONObject(i)?.optString("prizeName").orEmpty()
            if (prizeName.isNotBlank()) {
                return prizeName
            }
        }
        return ""
    }

    private fun collectGuardianBeanAward() {
        try {
            val awardsResponse = AntMemberRpcCall.queryGuardianGradeAwards()
            val awardsObject = JSONObject(awardsResponse)
            if (!ResChecker.checkRes(TAG, awardsObject)) {
                Log.member("安心豆🫘[守护者奖励查询失败]#$awardsResponse")
                return
            }
            val award = findAvailableGuardianBeanAward(awardsObject)
            if (award == null) {
                logUnavailableGuardianBeanAward(awardsObject)
                return
            }
            val skuId = award.optString("skuId")
            val beanQuantity = award.optInt("beanQuantity", 0)
            if (skuId.isBlank() || beanQuantity <= 0) {
                Log.error("$TAG.collectGuardianBeanAward", "安心豆🫘[守护者奖励配置异常]#$award")
                return
            }
            val sendResponse = AntMemberRpcCall.guardianAwardSend(skuId)
            val sendObject = JSONObject(sendResponse)
            if (ResChecker.checkRes(TAG, sendObject)) {
                Log.member("安心豆🫘[守护者等级奖励]#${beanQuantity}豆")
            } else {
                logGuardianBeanAwardSendFailure(sendObject, sendResponse)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "collectGuardianBeanAward err:", t)
        }
    }

    private fun findAvailableGuardianBeanAward(responseObject: JSONObject): JSONObject? {
        val gradeAwardsList = responseObject.optJSONObject("result")?.optJSONArray("gradeSkuAwardsList") ?: return null
        for (i in 0 until gradeAwardsList.length()) {
            val skuAwardList = gradeAwardsList.optJSONObject(i)?.optJSONArray("skuAwardList") ?: continue
            for (j in 0 until skuAwardList.length()) {
                val award = skuAwardList.optJSONObject(j) ?: continue
                if (award.optString("status") == "AVAILABLE" &&
                    award.optString("spuType") == "MARKETING_PRIZE" &&
                    award.optInt("beanQuantity", 0) > 0
                ) {
                    return award
                }
            }
        }
        return null
    }

    private fun logUnavailableGuardianBeanAward(responseObject: JSONObject) {
        val gradeAwardsList = responseObject.optJSONObject("result")?.optJSONArray("gradeSkuAwardsList") ?: return
        for (i in 0 until gradeAwardsList.length()) {
            val skuAwardList = gradeAwardsList.optJSONObject(i)?.optJSONArray("skuAwardList") ?: continue
            for (j in 0 until skuAwardList.length()) {
                val award = skuAwardList.optJSONObject(j) ?: continue
                val beanQuantity = award.optInt("beanQuantity", 0)
                val status = award.optString("status")
                if (award.optString("spuType") == "MARKETING_PRIZE" &&
                    beanQuantity > 0 &&
                    status == "MONTH_COUNT_LIMIT"
                ) {
                    Log.member("安心豆🫘[守护者等级奖励]#${beanQuantity}豆，业务受限($status)，跳过")
                    return
                }
            }
        }
    }

    private fun logGuardianBeanAwardSendFailure(responseObject: JSONObject, rawResponse: String) {
        val code = sequenceOf(
            responseObject.optString("resultCode"),
            responseObject.optString("code"),
            responseObject.optString("errorCode")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val message = sequenceOf(
            responseObject.optString("resultDesc"),
            responseObject.optString("resultMsg"),
            responseObject.optString("memo"),
            responseObject.optString("errorMessage"),
            responseObject.optString("errorMsg"),
            responseObject.optString("desc")
        ).firstOrNull { it.isNotBlank() }.orEmpty()
        val detail = when {
            code.isNotBlank() && message.isNotBlank() -> "$code/$message"
            code.isNotBlank() -> code
            message.isNotBlank() -> message
            else -> rawResponse
        }
        when (classifyGuardianBeanAwardFailure(code, message)) {
            GuardianBeanAwardRpcFailureType.DUPLICATE_REWARD ->
                Log.member("安心豆🫘[守护者等级奖励]#已领取或重复领取，跳过:$detail")

            GuardianBeanAwardRpcFailureType.BUSINESS_LIMIT ->
                Log.member("安心豆🫘[守护者等级奖励]#业务受限，本轮跳过:$detail")

            GuardianBeanAwardRpcFailureType.NON_RETRYABLE ->
                Log.error("$TAG.collectGuardianBeanAward", "安心豆🫘[守护者奖励领取失败]#$detail")
        }
    }

    private fun classifyGuardianBeanAwardFailure(code: String, message: String): GuardianBeanAwardRpcFailureType {
        return when {
            message.contains("已领取") ||
                message.contains("重复") ||
                message.contains("已经领取") -> GuardianBeanAwardRpcFailureType.DUPLICATE_REWARD

            code.contains("LIMIT", ignoreCase = true) ||
                message.contains("上限") ||
                message.contains("限制") ||
                message.contains("受限") ||
                message.contains("不可领取") ||
                message.contains("频繁") ||
                message.contains("稍后") -> GuardianBeanAwardRpcFailureType.BUSINESS_LIMIT

            else -> GuardianBeanAwardRpcFailureType.NON_RETRYABLE
        }
    }

    internal fun beanExchangeBubbleBoost() {
        try {
            // 检查RPC调用是否可用
            try {
                val accountInfo = AntMemberRpcCall.queryUserAccountInfo("INS_BLUE_BEAN")

                var jo = JSONObject(accountInfo)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.member(jo.toString())
                    return
                }

                val userCurrentPoint = jo.getJSONObject("result").getInt("userCurrentPoint")

                // 检查beanExchangeDetail调用
                val exchangeDetailStr = AntMemberRpcCall.beanExchangeDetail("IT20230214000700069722")

                jo = JSONObject(exchangeDetailStr)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.member(jo.toString())
                    return
                }

                jo = jo.getJSONObject("result").getJSONObject("rspContext").getJSONObject("params").getJSONObject("exchangeDetail")
                val itemId = jo.getString("itemId")
                val itemName = jo.getString("itemName")
                jo = jo.getJSONObject("itemExchangeConsultDTO")
                val realConsumePointAmount = jo.getInt("realConsumePointAmount")

                if (!jo.getBoolean("canExchange") || realConsumePointAmount > userCurrentPoint) {
                    return
                }

                val exchangeResult = AntMemberRpcCall.beanExchange(itemId, realConsumePointAmount)

                jo = JSONObject(exchangeResult)
                if (ResChecker.checkRes(TAG, jo)) {
                    Log.member("安心豆🫘[兑换:$itemName]")
                } else {
                    Log.member(jo.toString())
                }
            } catch (e: NullPointerException) {
                Log.printStackTrace(TAG, "安心豆🫘[RPC桥接失败]#可能是RpcBridge未初始化", e)
            }
        } catch (t: Throwable) {
            Log.printStackTrace(TAG, "beanExchangeBubbleBoost err:", t)
        }
    }



    /**
     * 查询 + 自动领取贴纸
     */
    @SuppressLint("DefaultLocale")
    fun queryAndCollectStickers() {
        try {
            if (hasFlagToday(StatusFlags.FLAG_ANTMEMBER_STICKER)) {
                Log.member("今日已兑换贴纸，跳过")
                return
            }
            val now = Date()
            val year = SimpleDateFormat("yyyy", Locale.ENGLISH).format(now)
            val month = SimpleDateFormat("MM", Locale.ENGLISH).format(now)
            val day = SimpleDateFormat("dd", Locale.ENGLISH).format(now)

            val queryResp = AntMemberRpcCall.queryStickerCanReceive(year, month)

            val queryJson = JSONObject(queryResp)
            if (!ResChecker.checkRes(TAG, queryJson)) {
                logStickerRpcFailure("查询可领取列表", queryJson)
                return
            }

            val canReceivePageList = queryJson.optJSONArray("canReceivePageList") ?: JSONArray()

            // 用于存储 ID -> Name 的映射
            val stickerNameMap = mutableMapOf<String, String>()
            val allStickerIds = mutableListOf<String>()

            for (i in 0 until canReceivePageList.length()) {
                val page = canReceivePageList.optJSONObject(i)
                val stickerList = page?.optJSONArray("stickerCanReceiveList") ?: continue
                for (j in 0 until stickerList.length()) {
                    val stickerObj = stickerList.optJSONObject(j) ?: continue
                    val id = stickerObj.optString("id")
                    val name = stickerObj.optString("name")
                    if (id.isNotEmpty()) {
                        allStickerIds.add(id)
                        stickerNameMap[id] = name.ifEmpty { "未知贴纸" }
                    }
                }
            }

            if (allStickerIds.isEmpty()) {
                Log.member("贴纸扫描：暂无可领取的贴纸")
            } else {
                // 2. 领取阶段
                val collectResp = AntMemberRpcCall.receiveSticker(year, month, allStickerIds)

                val collectJson = JSONObject(collectResp)
                if (!ResChecker.checkRes(TAG, collectJson)) {
                    logStickerRpcFailure("领取贴纸", collectJson)
                    return
                }

                // 3. 结果解析与比对输出
                val specialList = collectJson.optJSONArray("specialStickerList")
                val obtainedIds = collectJson.optJSONArray("obtainedConfigId")

                Log.member("贴纸领取成功，总数：${obtainedIds?.length() ?: 0}")

                if (specialList != null && specialList.length() > 0) {
                    for (i in 0 until specialList.length()) {
                        val special = specialList.optJSONObject(i) ?: continue

                        // 获取领取结果中的 recordId
                        val recordId = special.optString("stickerRecordId")
                        // 从我们之前的 Map 中根据 ID 找到对应的 Name
                        val stickerName = stickerNameMap[recordId] ?: "特殊贴纸"

                        val ranking = special.optString("rankingText")

                        // 仅对特殊贴纸输出会员日志，显示真实的贴纸名称
                        Log.member("获得特殊贴纸 → $stickerName ($ranking)")
                    }
                }
            }

            val followUpResult = handleStickerFollowUps(year, month, day)
            if (!followUpResult.success) {
                Log.member("贴纸后续处理存在失败，保留后续重试机会")
                return
            }

            if (allStickerIds.isNotEmpty() || followUpResult.handled) {
                setFlagToday(StatusFlags.FLAG_ANTMEMBER_STICKER)
            }

        } catch (e: Exception) {
            Log.printStackTrace("$TAG stickerAutoCollect err", e)
        }
    }

    private fun handleStickerFollowUps(year: String, month: String, day: String): StickerFollowUpResult {
        val upgradeResult = upgradeAndCollectStickerBenefits(year, month, day)
        val drawingResult = collectStickerDrawingPrizes()
        return StickerFollowUpResult(
            success = upgradeResult.success && drawingResult.success,
            handled = upgradeResult.handled || drawingResult.handled
        )
    }

    private fun upgradeAndCollectStickerBenefits(year: String, month: String, day: String): StickerFollowUpResult {
        var success = true
        var handled = false
        val benefitCandidates = linkedMapOf<String, String>()
        val upgradeReqList = JSONArray()

        try {
            val homeJson = JSONObject(AntMemberRpcCall.queryStickerHomePage(year, month, day))
            if (!ResChecker.checkRes(TAG, homeJson)) {
                logStickerRpcFailure("查询贴纸首页", homeJson)
                return StickerFollowUpResult(success = false)
            }

            val stickerList = homeJson.optJSONObject("commonStickerRes")
                ?.optJSONArray("stickerDetailList")
                ?: JSONArray()

            for (i in 0 until stickerList.length()) {
                val sticker = stickerList.optJSONObject(i) ?: continue
                val stickerConfigId = sticker.optString("stickerConfigId")
                if (stickerConfigId.isBlank()) continue

                val stickerName = sticker.optString("name", stickerConfigId)
                val status = sticker.optString("status")
                if (sticker.optBoolean("hasBenefit") && !"notReceived".equals(status, ignoreCase = true)) {
                    benefitCandidates[stickerConfigId] = stickerName
                }

                if ("upgradable".equals(status, ignoreCase = true)) {
                    val currentLevelCode = sticker.optJSONObject("currentLevel")
                        ?.optString("levelCode")
                        .orEmpty()
                    val upgradableLevelCode = sticker.optJSONObject("upgradableLevel")
                        ?.optString("levelCode")
                        .orEmpty()
                    if (currentLevelCode.isNotBlank() && upgradableLevelCode.isNotBlank()) {
                        upgradeReqList.put(JSONObject().apply {
                            put("currentLevelCode", currentLevelCode)
                            put("month", month)
                            put("stickerConfigId", stickerConfigId)
                            put("upgradableLevelCode", upgradableLevelCode)
                            put("year", year)
                        })
                    }
                }
            }

            if (upgradeReqList.length() > 0) {
                handled = true
                val upgradeJson = JSONObject(AntMemberRpcCall.upgradeStickerBatch(upgradeReqList))
                if (!ResChecker.checkRes(TAG, upgradeJson)) {
                    logStickerRpcFailure("贴纸升级", upgradeJson)
                    success = false
                } else {
                    val failedList = upgradeJson.optJSONArray("failStickerCfgIdList")
                    if (failedList != null && failedList.length() > 0) {
                        Log.error(TAG, "贴纸升级部分失败：$failedList")
                        success = false
                    } else {
                        Log.member("贴纸升级成功，数量：${upgradeReqList.length()}")
                    }
                }
            }

            for ((stickerConfigId, stickerName) in benefitCandidates) {
                val benefitResult = collectStickerUpgradeBenefit(year, month, stickerConfigId, stickerName)
                handled = handled || benefitResult.handled
                success = success && benefitResult.success
            }
        } catch (e: Exception) {
            Log.printStackTrace("$TAG stickerUpgradeAndBenefit err", e)
            return StickerFollowUpResult(success = false, handled = handled)
        }

        return StickerFollowUpResult(success = success, handled = handled)
    }

    private fun collectStickerUpgradeBenefit(
        year: String,
        month: String,
        stickerConfigId: String,
        stickerName: String
    ): StickerFollowUpResult {
        try {
            val detailJson = JSONObject(AntMemberRpcCall.queryStickerDetailPage(year, month, stickerConfigId))
            if (!ResChecker.checkRes(TAG, detailJson)) {
                logStickerRpcFailure("查询权益详情[$stickerName]", detailJson)
                return StickerFollowUpResult(success = false)
            }

            if (!hasReceivableStickerUpgradeBenefit(detailJson)) {
                return StickerFollowUpResult()
            }

            val triggerJson = JSONObject(AntMemberRpcCall.triggerStickerUpgradePrize(stickerConfigId))
            if (!ResChecker.checkRes(TAG, triggerJson)) {
                logStickerRpcFailure("领取升级权益[$stickerName]", triggerJson)
                return StickerFollowUpResult(success = false, handled = true)
            }

            logStickerPrizeResults("贴纸权益[$stickerName]", triggerJson)
            return StickerFollowUpResult(handled = true)
        } catch (e: Exception) {
            Log.printStackTrace("$TAG collectStickerUpgradeBenefit err", e)
            return StickerFollowUpResult(success = false)
        }
    }

    private fun hasReceivableStickerUpgradeBenefit(detailJson: JSONObject): Boolean {
        val detailList = detailJson.optJSONObject("stickerDetailRes")
            ?.optJSONArray("stickerDetailList")
            ?: return false
        for (i in 0 until detailList.length()) {
            val benefitStatus = detailList.optJSONObject(i)
                ?.optJSONObject("upgradeBenefitModel")
                ?.optString("status")
                .orEmpty()
            if ("can_receive".equals(benefitStatus, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun collectStickerDrawingPrizes(): StickerFollowUpResult {
        var success = true
        var handled = false
        try {
            val prizeHomeJson = JSONObject(AntMemberRpcCall.queryStickerPrizeHomePage())
            if (!ResChecker.checkRes(TAG, prizeHomeJson)) {
                logStickerRpcFailure("查询抽奖机会", prizeHomeJson)
                return StickerFollowUpResult(success = false)
            }

            val prizeConsumerIdList = prizeHomeJson.optJSONArray("prizeConsumerIdList") ?: return StickerFollowUpResult()
            if (prizeConsumerIdList.length() == 0) {
                return StickerFollowUpResult()
            }

            Log.member("贴纸抽奖机会：${prizeConsumerIdList.length()}次")
            for (i in 0 until prizeConsumerIdList.length()) {
                val prizeQuotaRecordId = prizeConsumerIdList.optString(i)
                if (prizeQuotaRecordId.isBlank()) continue

                handled = true
                val drawJson = JSONObject(AntMemberRpcCall.triggerStickerDrawing(prizeQuotaRecordId))
                if (!ResChecker.checkRes(TAG, drawJson)) {
                    logStickerRpcFailure("抽奖[$prizeQuotaRecordId]", drawJson)
                    success = false
                    continue
                }
                logStickerPrizeResults("贴纸抽奖", drawJson)
            }
        } catch (e: Exception) {
            Log.printStackTrace("$TAG collectStickerDrawingPrizes err", e)
            return StickerFollowUpResult(success = false, handled = handled)
        }

        return StickerFollowUpResult(success = success, handled = handled)
    }

    private fun logStickerRpcFailure(scene: String, response: JSONObject) {
        val code = response.optString("resultCode").ifBlank {
            response.optString("code").ifBlank {
                response.optString("errorCode")
            }
        }
        val message = response.optString("message").ifBlank {
            response.optString("resultDesc").ifBlank {
                response.optString("memo").ifBlank {
                    response.optString("errorMsg").ifBlank {
                        response.optString("resultView")
                    }
                }
            }
        }
        val combined = "$code $message ${response.optString("desc")}"
        val failureType = when {
            containsAny(combined, "已领取", "重复", "已兑换", "已经抽过") ->
                StickerRpcFailureType.DUPLICATE_REWARD

            containsAny(combined, "上限", "频繁", "手速", "稍后", "库存不足", "名额", "资格", "机会不足", "额度不足", "活动太火爆") ->
                StickerRpcFailureType.BUSINESS_LIMIT

            else -> StickerRpcFailureType.NON_RETRYABLE
        }
        val label = when (failureType) {
            StickerRpcFailureType.BUSINESS_LIMIT -> "业务受限"
            StickerRpcFailureType.DUPLICATE_REWARD -> "重复领取"
            StickerRpcFailureType.NON_RETRYABLE -> "接口失败"
        }
        val detail = when {
            code.isNotBlank() && message.isNotBlank() -> "$code/$message"
            code.isNotBlank() -> code
            message.isNotBlank() -> message
            else -> response.toString()
        }
        Log.error(TAG, "贴纸[$scene]#$label:$detail")
    }

    private fun containsAny(value: String, vararg keywords: String): Boolean {
        return keywords.any { value.contains(it, ignoreCase = true) }
    }

    private fun logStickerPrizeResults(scene: String, prizeJson: JSONObject) {
        val prizeResultList = prizeJson.optJSONArray("prizeResultList")
        if (prizeResultList != null && prizeResultList.length() > 0) {
            for (i in 0 until prizeResultList.length()) {
                val prize = prizeResultList.optJSONObject(i) ?: continue
                if (!ResChecker.checkRes(TAG, prize)) {
                    logStickerRpcFailure("$scene 部分奖励", prize)
                    continue
                }
                Log.member("$scene#${resolveStickerPrizeName(prize)}")
            }
            return
        }

        Log.member("$scene#${resolveStickerPrizeName(prizeJson)}")
    }

    private fun resolveStickerPrizeName(prize: JSONObject): String {
        val couponPrize = prize.optJSONObject("couponPrizeRes")
        if (couponPrize != null) {
            val price = couponPrize.optString("price")
            val unit = couponPrize.optString("unit")
            val name = couponPrize.optString("name", couponPrize.optString("title", "优惠券"))
            val condition = couponPrize.optString("condition")
            return buildString {
                if (price.isNotBlank() && unit.isNotBlank() && !name.contains(price)) {
                    append(price).append(unit)
                }
                append(name)
                if (condition.isNotBlank()) {
                    append(" ").append(condition)
                }
            }
        }

        val virtualPrize = prize.optJSONObject("virtualPrizeRes")
        if (virtualPrize != null) {
            val title = virtualPrize.optString("title", "虚拟奖励")
            val count = virtualPrize.optString("count")
            val unit = virtualPrize.optString("unit")
            return buildString {
                append(title)
                if (count.isNotBlank()) {
                    append("*").append(count)
                    if (unit.isNotBlank()) {
                        append(unit)
                    }
                }
            }
        }

        return prize.optString("prizeId", "未知奖励")
    }

    companion object {
        private val TAG: String = AntMember::class.java.getSimpleName()
        private const val memberTaskBlacklistModule = "支付宝会员"
        private const val memberFloatingBallAdTaskTitle = "会员浮球广告浏览任务"


        /**
         * 会员积分收取
         * @param page 第几页
         * @param pageSize 每页数据条数
         */
        internal suspend fun queryPointCert(page: Int, pageSize: Int) {
            try {
                var s = AntMemberRpcCall.queryPointCertV2(page, pageSize)
                var jo = JSONObject(s)
                if (ResChecker.checkRes(TAG + "查询会员积分证书失败:", jo) && jo.has("pointToClaim")) {
                    val pointToClaim = jo.optInt("pointToClaim", 0)
                    if (pointToClaim > 0 && jo.optBoolean("showReceiveAllPointFunction")) {
                        s = AntMemberRpcCall.receiveAllPointByUser()
                        val receiveAllObject = JSONObject(s)
                        if (ResChecker.checkRes(TAG + "会员积分一键领取失败:", receiveAllObject)) {
                            val receiveSumPoint = receiveAllObject.optInt("receiveSumPoint", 0)
                            val receiveStatus = receiveAllObject.optString("receiveStatus")
                            if ("SUCCESS" == receiveStatus || receiveSumPoint > 0) {
                                Log.member("会员积分🎖️[一键领取]#${receiveSumPoint}积分")
                            } else {
                                Log.member("会员积分🎖️[一键领取]#未返回SUCCESS(receiveStatus=$receiveStatus)")
                            }
                            return
                        }
                        Log.member("会员积分🎖️[一键领取失败，回退逐条领取]")
                    }
                    val hasNextPage = jo.optBoolean("hasNextPage")
                    val jaCertList = jo.optJSONArray("certList") ?: JSONArray()
                    for (i in 0 until jaCertList.length()) {
                        jo = jaCertList.getJSONObject(i)
                        val bizTitle = jo.optString("bizTitle").ifEmpty { jo.optString("title", "会员积分") }
                        val id = jo.optString("id").ifEmpty { jo.optString("certId") }
                        if (id.isEmpty()) {
                            continue
                        }
                        val pointAmount = jo.optInt("pointAmount", jo.optInt("point", 0))
                        s = AntMemberRpcCall.receivePointByUser(id)
                        val receiveObject = JSONObject(s)
                        if (ResChecker.checkRes(TAG + "会员积分领取失败:", receiveObject)) {
                            Log.member("会员积分🎖️[领取$bizTitle]#${pointAmount}积分")
                        } else {
                            Log.member(receiveObject.optString("resultDesc"))
                            Log.member(s)
                        }
                    }
                    if (hasNextPage) {
                        queryPointCert(page + 1, pageSize)
                    }
                    return
                }

                s = AntMemberRpcCall.queryPointCert(page, pageSize)
                jo = JSONObject(s)
                if (ResChecker.checkRes(TAG + "查询会员积分证书失败:", jo)) {
                    val hasNextPage = jo.optBoolean("hasNextPage")
                    val jaCertList = jo.optJSONArray("certList") ?: JSONArray()
                    for (i in 0 until jaCertList.length()) {
                        jo = jaCertList.getJSONObject(i)
                        val bizTitle = jo.optString("bizTitle").ifEmpty { jo.optString("title", "会员积分") }
                        val id = jo.optString("id").ifEmpty { jo.optString("certId") }
                        if (id.isEmpty()) {
                            continue
                        }
                        val pointAmount = jo.optInt("pointAmount", jo.optInt("point", 0))
                        s = AntMemberRpcCall.receivePointByUser(id)
                        val receiveObject = JSONObject(s)
                        if (ResChecker.checkRes(TAG + "会员积分领取失败:", receiveObject)) {
                            Log.member("会员积分🎖️[领取$bizTitle]#${pointAmount}积分")
                        } else {
                            Log.member(receiveObject.optString("resultDesc"))
                            Log.member(s)
                        }
                    }
                    if (hasNextPage) {
                        queryPointCert(page + 1, pageSize)
                    }
                } else {
                    Log.member(jo.getString("resultDesc"))
                    Log.member(s)
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "queryPointCert err:", t)
            }
        }


        /**
         * 商家开门打卡签到
         */
        private fun kmdkSignIn(): Boolean = CoroutineUtils.run {
            try {
                val s = AntMemberRpcCall.queryActivity()
                val jo = JSONObject(s)
                if (!ResChecker.checkRes(TAG, jo)) {
                    Log.member("queryActivity $s")
                    return@run false
                }

                when (jo.optString("signInStatus")) {
                    "SIGN_IN_ENABLE" -> {
                        val activityNo = jo.optString("activityNo")
                        if (activityNo.isEmpty()) return@run false
                        val joSignIn = JSONObject(AntMemberRpcCall.signIn(activityNo))
                        if (ResChecker.checkRes(TAG, joSignIn)) {
                            Log.member("商家服务🏬[开门打卡签到成功]")
                            return@run true
                        }
                        Log.member(joSignIn.optString("errorMsg"))
                        Log.member(joSignIn.toString())
                        return@run false
                    }

                    "SIGN_IN_DISABLE" -> return@run true // 通常表示已签到
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "kmdkSignIn err:", t)
            }
            false
        }

        /**
         * 商家开门打卡报名
         */
        private suspend fun kmdkSignUp(): Boolean = CoroutineUtils.run {
            try {
                for (i in 0..4) {
                    val jo = JSONObject(AntMemberRpcCall.queryActivity())
                    if (ResChecker.checkRes(TAG, jo)) {
                        val activityNo = jo.optString("activityNo")
                        if (activityNo.isEmpty()) {
                            continue
                        }
                        if (TimeUtil.getFormatDate().replace("-", "") != activityNo.split("_".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()[2]) {
                            break
                        }
                        if ("SIGN_UP" == jo.getString("signUpStatus")) {
                            return@run true
                        }
                        if ("UN_SIGN_UP" == jo.getString("signUpStatus")) {
                            val activityPeriodName = jo.getString("activityPeriodName")
                            val joSignUp = JSONObject(AntMemberRpcCall.signUp(activityNo))
                            if (ResChecker.checkRes(TAG, joSignUp)) {
                                Log.member("商家服务🏬[" + activityPeriodName + "开门打卡报名]")
                                return@run true
                            } else {
                                Log.member(joSignUp.getString("errorMsg"))
                                Log.member(joSignUp.toString())
                            }
                        }
                    } else {
                        Log.member("queryActivity")
                        Log.member(jo.toString())
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "kmdkSignUp err:", t)
            }
            false
        }

        /**
         * 商家积分签到
         */
        private fun doMerchantSign(): Boolean = CoroutineUtils.run {
            var handled = false
            try {
                if (doMerchantZcjSignIn()) {
                    handled = true
                }
                val s = AntMemberRpcCall.merchantSign()
                var jo = JSONObject(s)
                if (!ResChecker.checkRes(TAG, jo)) {
                    if (!handled) {
                        Log.member("doMerchantSign err:$s")
                    }
                    return@run handled
                }
                jo = jo.getJSONObject("data")
                val signResult = jo.optString("signInResult")
                val reward = jo.optString("todayReward")
                if ("SUCCESS" == signResult) {
                    Log.member("商家服务🏬[每日签到]#获得积分$reward")
                    return@run true
                } else {
                    // 对于「已签到 / 不可签到」等情况，直接视为今日已处理，避免反复请求触发风控
                    Log.member("商家服务🏬[每日签到]#未返回SUCCESS(signInResult=$signResult,todayReward=$reward)")
                    Log.member(s)
                    return@run true
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "kmdkSignIn err:", t)
            }
            handled
        }

        /**
         * 商家积分任务
         */
        private suspend fun doMerchantMoreTask(): Unit = CoroutineUtils.run {
            try {
                repeat(3) { roundIndex ->
                    var taskStateChanged = false
                    val taskGroups = queryMerchantTaskGroups()
                    if (taskGroups.isEmpty()) {
                        if (roundIndex == 0) {
                            Log.member("商家服务🏬[积分任务]#未查询到任务列表")
                        }
                        return@run
                    }
                    for (taskList in taskGroups) {
                        for (i in 0..<taskList.length()) {
                            val task = taskList.optJSONObject(i) ?: continue
                            if (processMerchantTask(task)) {
                                taskStateChanged = true
                            }
                        }
                    }
                    if (collectMerchantPointBalls()) {
                        taskStateChanged = true
                    }
                    if (!taskStateChanged) {
                        return@run
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "taskListQuery err:", t)
            }
        }

        /**
         * 完成商家积分任务
         * @param taskCode 任务代码
         * @param actionCodes 行为代码候选
         * @param title 标题
         */
        private fun receiveMerchantTask(taskCode: String): Boolean = CoroutineUtils.run {
            try {
                val jo = JSONObject(AntMemberRpcCall.taskReceive(taskCode))
                val evaluation = evaluateMerchantRpc(jo)
                if (!evaluation.success) {
                    logMerchantRpcFailure("领取任务[$taskCode]", jo, evaluation)
                    return@run evaluation.failureType == MerchantRpcFailureType.DUPLICATE_REWARD
                }
                return@run true
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "receiveMerchantTask err:", t)
            }
            false
        }

        private suspend fun executeMerchantBrowseTask(
            taskCode: String,
            actionCodes: List<String>,
            title: String,
            taskStatus: String,
            targetCount: Int = 1
        ): Boolean = CoroutineUtils.run {
            try {
                if ("UNRECEIVED" == taskStatus && !receiveMerchantTask(taskCode)) {
                    return@run false
                }

                for (actionCode in actionCodes) {
                    var jo = JSONObject(AntMemberRpcCall.actioncode(actionCode))
                    var evaluation = evaluateMerchantRpc(jo)
                    if (!evaluation.success) {
                        logMerchantRpcFailure("查询任务活动[$title/$actionCode]", jo, evaluation)
                        if (evaluation.failureType == MerchantRpcFailureType.AUTH_LIMIT) {
                            return@run false
                        }
                        continue
                    }

                    var produceSuccess = false
                    var remainingCount = max(1, targetCount)
                    for (index in 0 until max(1, targetCount)) {
                        jo = JSONObject(AntMemberRpcCall.produce(actionCode))
                        evaluation = evaluateMerchantRpc(jo)
                        if (!evaluation.success) {
                            logMerchantRpcFailure("任务打点[$title/$actionCode]", jo, evaluation)
                            if (evaluation.failureType == MerchantRpcFailureType.AUTH_LIMIT) {
                                return@run false
                            }
                            break
                        }
                        produceSuccess = true

                        val refreshedTask = queryMerchantTaskByCode(taskCode) ?: break
                        val refreshedStatus = refreshedTask.optString("status")
                        if ("NEED_RECEIVE" == refreshedStatus || ("PROCESSING" != refreshedStatus && "UNRECEIVED" != refreshedStatus)) {
                            break
                        }

                        val refreshedRemainingCount = resolveMerchantTaskRemainingCount(refreshedTask) ?: break
                        if (refreshedRemainingCount <= 0 || refreshedRemainingCount >= remainingCount) {
                            break
                        }
                        remainingCount = refreshedRemainingCount
                    }

                    if (produceSuccess) {
                        Log.member("商家服务🏬[完成任务$title]")
                        return@run true
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "executeMerchantBrowseTask err:", t)
            }
            false
        }

        private fun queryMerchantTaskByCode(taskCode: String): JSONObject? {
            if (taskCode.isEmpty()) {
                return null
            }
            val taskGroups = queryMerchantTaskGroups()
            for (taskList in taskGroups) {
                for (i in 0..<taskList.length()) {
                    val task = taskList.optJSONObject(i) ?: continue
                    if (taskCode == task.optString("taskCode")) {
                        return task
                    }
                }
            }
            return null
        }

        private enum class MerchantRpcFailureType {
            AUTH_LIMIT,
            NO_ACTIVITY,
            DUPLICATE_REWARD,
            DEPRECATED_SOURCE,
            NON_RETRYABLE
        }

        private data class MerchantRpcEvaluation(
            val success: Boolean,
            val code: String,
            val message: String,
            val failureType: MerchantRpcFailureType? = null
        )

        private fun evaluateMerchantRpc(response: JSONObject): MerchantRpcEvaluation {
            val success = response.optBoolean("success") ||
                response.optString("resultCode").equals("SUCCESS", true) ||
                response.optString("errCode") == "0"
            val code = sequenceOf(
                response.optString("errorCode"),
                response.optString("resultCode"),
                response.opt("error")?.toString(),
                response.optString("errorTip"),
                response.optString("errCode"),
                response.opt("errorNo")?.toString()
            ).firstOrNull { !it.isNullOrBlank() && it != "0" }
                .orEmpty()
            val message = sequenceOf(
                response.optString("errorMsg"),
                response.optString("errorMessage"),
                response.optString("resultDesc"),
                response.optString("memo"),
                response.optString("desc")
            ).firstOrNull { it.isNotBlank() }
                .orEmpty()
            if (success) {
                return MerchantRpcEvaluation(
                    success = true,
                    code = code,
                    message = message
                )
            }
            val failureType = when {
                code == "1009" ||
                    message.contains("伺服器繁忙") ||
                    message.contains("服务器繁忙") ||
                    message.contains("請稍後再試") ||
                    message.contains("请稍后再试") ||
                    message.contains("訪問被拒絕") ||
                    message.contains("访问被拒绝") -> MerchantRpcFailureType.AUTH_LIMIT

                code.equals("RESULT_IS_NULL", true) ||
                    message.contains("通过actionCode查询的任务活动为空") -> MerchantRpcFailureType.NO_ACTIVITY

                code == "392" ||
                    message == "任务已领取,无法重复领取" ||
                    message == "宝箱奖励已领取" -> MerchantRpcFailureType.DUPLICATE_REWARD

                code == "3000" ||
                    message.contains("系統出錯，正在排查") ||
                    message.contains("系统出错，正在排查") -> MerchantRpcFailureType.DEPRECATED_SOURCE

                else -> MerchantRpcFailureType.NON_RETRYABLE
            }
            return MerchantRpcEvaluation(
                success = false,
                code = code,
                message = message,
                failureType = failureType
            )
        }

        private fun buildMerchantRpcFailureDetail(evaluation: MerchantRpcEvaluation, response: JSONObject): String {
            return when {
                evaluation.code.isNotBlank() && evaluation.message.isNotBlank() -> "${evaluation.code}/${evaluation.message}"
                evaluation.code.isNotBlank() -> evaluation.code
                evaluation.message.isNotBlank() -> evaluation.message
                else -> response.toString()
            }
        }

        private fun logMerchantRpcFailure(
            scene: String,
            response: JSONObject,
            evaluation: MerchantRpcEvaluation = evaluateMerchantRpc(response)
        ) {
            val detail = buildMerchantRpcFailureDetail(evaluation, response)
            when (evaluation.failureType) {
                MerchantRpcFailureType.AUTH_LIMIT ->
                    Log.member("商家服务🏬[$scene]#业务受限，本轮跳过:$detail")

                MerchantRpcFailureType.NO_ACTIVITY ->
                    Log.member("商家服务🏬[$scene]#当前无可执行活动，跳过:$detail")

                MerchantRpcFailureType.DUPLICATE_REWARD ->
                    Log.member("商家服务🏬[$scene]#奖励已领取，跳过重复领取:$detail")

                MerchantRpcFailureType.DEPRECATED_SOURCE ->
                    Log.member("商家服务🏬[$scene]#旧链路不可用，已停止使用:$detail")

                MerchantRpcFailureType.NON_RETRYABLE, null ->
                    Log.member("商家服务🏬[$scene]#接口失败:$detail")
            }
        }

        private fun canRunMerchantService(): Boolean = CoroutineUtils.run {
            try {
                val jo = JSONObject(AntMemberRpcCall.transcodeCheck())
                val evaluation = evaluateMerchantRpc(jo)
                if (evaluation.success) {
                    val data = jo.optJSONObject("data")
                    if (data?.optBoolean("isOpened") == true) {
                        return@run true
                    }
                    Log.member("商家服务🏬[未开通，本轮跳过]")
                    return@run false
                }
                logMerchantRpcFailure("开通检查", jo, evaluation)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "canRunMerchantService err:", t)
            }
            false
        }

        private fun doMerchantZcjSignIn(): Boolean = CoroutineUtils.run {
            try {
                val queryResp = JSONObject(AntMemberRpcCall.zcjSignInQuery())
                if (!ResChecker.checkRes(TAG, queryResp)) {
                    return@run false
                }
                val button = queryResp.optJSONObject("data")?.optJSONObject("button") ?: return@run false
                when (button.optString("status")) {
                    "RECEIVED" -> return@run true
                    "UNRECEIVED" -> {
                        val executeResp = JSONObject(AntMemberRpcCall.zcjSignInExecute())
                        if (!ResChecker.checkRes(TAG, executeResp)) {
                            Log.member("doMerchantZcjSignIn err:$executeResp")
                            return@run false
                        }
                        val data = executeResp.optJSONObject("data")
                        val reward = data?.optString("todayReward").orEmpty()
                        val widgetName = data?.optString("widgetName").orEmpty().ifEmpty { "招财金签到" }
                        if (reward.isNotEmpty()) {
                            Log.member("商家服务🏬[$widgetName]#获得积分$reward")
                        } else {
                            Log.member("商家服务🏬[$widgetName]")
                        }
                        return@run true
                    }
                }
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "doMerchantZcjSignIn err:", t)
            }
            false
        }

        private fun queryMerchantTaskGroups(): List<JSONArray> {
            try {
                val response = JSONObject(AntMemberRpcCall.taskListQuery())
                val evaluation = evaluateMerchantRpc(response)
                if (!evaluation.success) {
                    logMerchantRpcFailure("积分任务列表", response, evaluation)
                    return emptyList()
                }
                val data = response.optJSONObject("data")
                val planCode = data?.optString("planCode").orEmpty()
                if (planCode.isNotBlank() && !planCode.equals("MORE", true)) {
                    Log.member("商家服务🏬[积分任务列表]#返回计划$planCode，本轮跳过")
                    return emptyList()
                }
                val taskList = data?.optJSONArray("taskList") ?: return emptyList()
                if (taskList.length() <= 0) {
                    return emptyList()
                }
                return listOf(taskList)
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "queryMerchantTaskGroups taskListQuery err:", t)
            }
            return emptyList()
        }

        private suspend fun processMerchantTask(task: JSONObject): Boolean = CoroutineUtils.run {
            val taskStatus = task.optString("status")
            if (taskStatus.isEmpty()) {
                return@run false
            }

            val title = task.optString("title", task.optString("taskName", "商家任务"))
            val reward = task.optString("reward", task.optString("point"))
            val taskCode = task.optString("taskCode")

            if (TaskBlacklist.isTaskInBlacklist(memberTaskBlacklistModule, title) ||
                TaskBlacklist.isTaskInBlacklist(memberTaskBlacklistModule, taskCode)
            ) {
                Log.member("商家服务🏬[$title]#黑名单任务，停止执行")
                return@run false
            }

            if ("NEED_RECEIVE" == taskStatus) {
                val pointBallId = task.optString("pointBallId")
                if (pointBallId.isNotEmpty()) {
                    return@run receiveMerchantPointBall(pointBallId, title, reward)
                }
                return@run false
            }

            if ("PROCESSING" != taskStatus && "UNRECEIVED" != taskStatus) {
                return@run false
            }

            val bizId = resolveMerchantBizId(task)
            if ("PROCESSING" == taskStatus && bizId.isNotEmpty()) {
                val jo = JSONObject(AntMemberRpcCall.taskFinish(bizId))
                val evaluation = evaluateMerchantRpc(jo)
                if (evaluation.success) {
                    Log.member("商家服务🏬[$title]#领取积分$reward")
                    return@run true
                }
                logMerchantRpcFailure("领取积分[$title]", jo, evaluation)
                return@run evaluation.failureType == MerchantRpcFailureType.DUPLICATE_REWARD
            }

            val actionCodes = resolveMerchantActionCodes(task)
            if (taskCode.isEmpty() || actionCodes.isEmpty()) {
                return@run false
            }

            return@run executeMerchantBrowseTask(taskCode, actionCodes, title, taskStatus, resolveMerchantTaskTargetCount(task))
        }

        private fun resolveMerchantBizId(task: JSONObject): String {
            return task.optJSONObject("extendLog")
                ?.optJSONObject("bizExtMap")
                ?.optString("bizId")
                .orEmpty()
        }

        private fun resolveMerchantActionCodes(task: JSONObject): List<String> {
            val candidates = LinkedHashSet<String>()
            val buttonActionCode = task.optJSONObject("button")
                ?.optJSONObject("extInfo")
                ?.optString("actionCode")
                .orEmpty()
            addMerchantActionCodeCandidates(candidates, buttonActionCode)

            val taskActionCode = task.optString("actionCode")
            addMerchantActionCodeCandidates(candidates, taskActionCode)

            val taskCode = task.optString("taskCode")
            if (task.has("sendPointImmediately") && taskCode.isNotEmpty()) {
                addMerchantActionCodeCandidate(candidates, "${taskCode}_VIEWED")
            }
            addMerchantActionCodeCandidate(candidates, when (taskCode) {
                "SYH_CPC_DYNAMIC" -> "SYH_CPC_DYNAMIC_VIEWED"
                "JFLLRW_TASK" -> "JFLL_VIEWED"
                "ZFBHYLLRW_TASK" -> "ZFBHYLL_VIEWED"
                "QQKLLRW_TASK" -> "QQKLL_VIEWED"
                "RCR_RWZX_LLRW_TASK" -> "rcr_llrw_VIEWED"
                "SSLLRW_TASK" -> "SSLL_VIEWED"
                "CYLLRW_TASK" -> "CYLLRW_VIEWED"
                "ELMGYLLRW2_TASK" -> "ELMGYLL_VIEWED"
                "ZMXYLLRW_TASK" -> "ZMXYLL_VIEWED"
                "GXYKPDDYH_TASK" -> "xykhkzd_VIEWED"
                "HHKLLRW_TASK" -> "HHKLLX_VIEWED"
                "TBNCLLRW_TASK" -> "TBNCLLRW_TASK_VIEWED"
                else -> null
            })
            return candidates.toList()
        }

        private fun addMerchantActionCodeCandidates(candidates: LinkedHashSet<String>, actionCode: String?) {
            val normalizedActionCode = actionCode.orEmpty().trim()
            if (normalizedActionCode.isEmpty()) {
                return
            }
            candidates.add(normalizedActionCode)
            if (!normalizedActionCode.endsWith("_VIEWED")) {
                candidates.add("${normalizedActionCode}_VIEWED")
            }
        }

        private fun addMerchantActionCodeCandidate(candidates: LinkedHashSet<String>, actionCode: String?) {
            val normalizedActionCode = actionCode.orEmpty().trim()
            if (normalizedActionCode.isNotEmpty()) {
                candidates.add(normalizedActionCode)
            }
        }

        private fun resolveMerchantTaskTargetCount(task: JSONObject): Int {
            return max(1, resolveMerchantTaskRemainingCount(task) ?: 1)
        }

        private fun resolveMerchantTaskRemainingCount(task: JSONObject): Int? {
            val target = task.optInt("target", Int.MIN_VALUE)
            val current = task.optInt("current", 0)
            if (target != Int.MIN_VALUE) {
                return (target - current).coerceAtLeast(0)
            }

            val targetCount = task.optInt("targetCount", Int.MIN_VALUE)
            val currentCount = task.optInt("currentCount", 0)
            if (targetCount != Int.MIN_VALUE) {
                return (targetCount - currentCount).coerceAtLeast(0)
            }

            return null
        }

        private suspend fun collectMerchantPointBalls(): Boolean = CoroutineUtils.run {
            try {
                val jo = JSONObject(AntMemberRpcCall.merchantBallQuery())
                val evaluation = evaluateMerchantRpc(jo)
                if (!evaluation.success) {
                    logMerchantRpcFailure("查询积分球", jo, evaluation)
                    return@run false
                }
                val pointBalls = jo.optJSONObject("data")?.optJSONArray("pointBalls") ?: return@run false
                var received = false
                for (i in 0..<pointBalls.length()) {
                    val pointBall = pointBalls.optJSONObject(i) ?: continue
                    val ballId = pointBall.optString("id")
                    if (ballId.isEmpty()) {
                        continue
                    }
                    val ballName = pointBall.optString("name", "积分球")
                    val receiveResp = JSONObject(AntMemberRpcCall.ballReceive(ballId))
                    val receiveEvaluation = evaluateMerchantRpc(receiveResp)
                    if (!receiveEvaluation.success) {
                        logMerchantRpcFailure("领取积分球[$ballName]", receiveResp, receiveEvaluation)
                        continue
                    }
                    val pointReceived = receiveResp.optJSONObject("data")?.optString("pointReceived").orEmpty()
                    if (pointReceived.isNotEmpty()) {
                        Log.member("商家服务🏬领取[$ballName]#获得积分$pointReceived")
                    } else {
                        Log.member("商家服务🏬领取[$ballName]")
                    }
                    received = true
                }
                return@run received
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "collectMerchantPointBalls err:", t)
            }
            false
        }

        private suspend fun receiveMerchantPointBall(
            pointBallId: String,
            title: String,
            reward: String
        ): Boolean = CoroutineUtils.run {
            try {
                val jo = JSONObject(AntMemberRpcCall.ballReceive(pointBallId))
                if (!ResChecker.checkRes(TAG, jo)) {
                    return@run false
                }
                val pointReceived = jo.optJSONObject("data")?.optString("pointReceived").orEmpty()
                if (pointReceived.isNotEmpty()) {
                    Log.member("商家服务🏬[$title]#领取积分$pointReceived")
                } else if (reward.isNotEmpty()) {
                    Log.member("商家服务🏬[$title]#领取积分$reward")
                } else {
                    Log.member("商家服务🏬[$title]#领取积分")
                }
                return@run true
            } catch (t: Throwable) {
                Log.printStackTrace(TAG, "receiveMerchantPointBall err:", t)
            }
            false
        }
    }

}

