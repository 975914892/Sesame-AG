package io.github.aoguai.sesameag.task.antFarm

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.antFarm.AntFarm.Companion.TAG
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.TimeUtil
import io.github.aoguai.sesameag.util.maps.UserMap
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 捐蛋排位赛管理子模块：支持单次蹲点和轮询蹲点
 */

internal fun AntFarm.handleDonationCompetition() {
    if (donationCompetition?.value != true) return

    if (receiveDonationCompetitionAward?.value == true) {
        receiveCompetitionAwards()
    }

    val endTimeStr = "2000"
    val endCal = TimeUtil.getTodayCalendarByTimeStr(endTimeStr) ?: return
    val now = System.currentTimeMillis()
    val checkIntervalMs = BaseModel.checkInterval.value!!.toLong()
    val creationWindowMs = 2 * checkIntervalMs
    val creationStartTime = endCal.timeInMillis - creationWindowMs

    if (now < creationStartTime) {
        Log.record(TAG, "当前不在排位赛任务调度时间内")
        return
    }
    if (now > endCal.timeInMillis) return

    try {
        val res = AntFarmRpcCall.enterDonationCompetitionRank()
        val jo = JSONObject(res)
        if (!ResChecker.checkRes(TAG, jo)) {
            Log.record(TAG, "进入捐蛋排位赛失败: ${jo.optString("memo")}")
            return
        }

        scheduleDonationCompetitionTask(endCal.timeInMillis)

    } catch (e: Exception) {
        Log.printStackTrace(TAG, "handleDonationCompetition err:", e)
    }
}

/**
 * 遍历奖励列表并领取
 */
private fun receiveCompetitionAwards(): Int {
    try {
        val res = AntFarmRpcCall.enterCompetitionAwardPage()
        val jo = JSONObject(res)
        if (!ResChecker.checkRes(TAG, jo)) return 0

        val userLevelInfo = jo.optJSONObject("userDonationLevelInfo") ?: return 0
        val currentLevelId = userLevelInfo.optInt("levelId")
        val currentLevelName = userLevelInfo.optString("levelName")
        val lightStars = userLevelInfo.optInt("levelLightStarNum", 0)

        val awardList = jo.optJSONArray("levelAwardInfoList") ?: return 0

        var highestLevelName = "未知"
        var starsToHighest = 0
        var nextLevelName = "已达顶峰"

        for (i in 0 until awardList.length()) {
            val levelItem = awardList.getJSONObject(i)
            val lId = levelItem.optInt("levelId")

            if (i == 0) highestLevelName = levelItem.optString("levelName")

            if (lId == currentLevelId + 1) {
                nextLevelName = levelItem.optString("levelName")
            }

            if (lId >= currentLevelId) {
                val upNum = levelItem.optInt("levelStarUpNum")
                if (upNum < 10000) {
                    starsToHighest += upNum
                }
            }
        }

        val starsToNext = userLevelInfo.optInt("levelStarUpNum") - lightStars
        starsToHighest -= lightStars

        val endTime = jo.optJSONObject("donationCompetitionActivityConf")?.optLong("endTime") ?: 0L

        Log.record(TAG, "--- 🏆 排位赛赛季简报 ---")
        Log.record(TAG, "📅 赛季结束：${TimeUtil.getCommonDate(endTime)}")
        Log.record(TAG, "📈 当前段位：$currentLevelName")
        Log.record(TAG, "🏹 下一段位：$nextLevelName (差 ${starsToNext}🌟)")
        Log.record(TAG, "👑 最高段位：$highestLevelName (总差距 ${starsToHighest}🌟)")
        Log.record(TAG, "------------------------")

        var claimableCount = 0
        var receivedCount = 0
        for (i in 0 until awardList.length()) {
            val award = awardList.getJSONObject(i)
            if (!award.optString("status").equals("unreceived", ignoreCase = true)) continue

            claimableCount++
            val rightsId = award.optString("rightsId")
            val levelName = award.optString("levelName", "未知段位")
            if (rightsId.isBlank()) {
                Log.record(TAG, "发现可领取排位奖励但缺少 rightsId：$levelName")
                continue
            }

            Log.record(TAG, "发现可领取排位奖励：$levelName")
            val receiveRes = AntFarmRpcCall.receiveDonationLevelReward(rightsId)
            val receiveJo = JSONObject(receiveRes)

            if (ResChecker.checkRes(TAG, receiveJo)) {
                receivedCount++
                Log.record(TAG, "🎉 成功领取 $levelName 段位奖励")
            } else {
                Log.record(TAG, "领取 $levelName 段位奖励失败：${formatDonationAwardFailure(receiveJo)}")
            }
        }

        if (claimableCount == 0) {
            Log.record(TAG, "当前没有可领取的排位赛段位奖励")
        }
        return receivedCount
    } catch (e: Exception) {
        Log.printStackTrace(TAG, "receiveCompetitionAwards err:", e)
    }
    return 0
}

private fun formatDonationAwardFailure(jo: JSONObject): String {
    return jo.optString("memo")
        .ifBlank { jo.optString("resultDesc") }
        .ifBlank { jo.optString("errorMsg") }
        .ifBlank { jo.optString("desc") }
        .ifBlank { jo.optString("resultCode") }
        .ifBlank { jo.toString() }
}

/**
 * 统一调度蹲点任务
 * 逻辑：轮询蹲点开启时忽略单次蹲点，否则按单次蹲点时间运行
 */
private fun AntFarm.scheduleDonationCompetitionTask(endTimeMs: Long) {
    val taskId = "DR|$ownerFarmId"
    if (hasChildTask(taskId)) return

    val uid = UserMap.currentUid
    val maxDonation = maxDailyDonationCompetitionCount?.value ?: 10
    val currentDonated = Status.getDonationCount(uid)
    if (currentDonated >= maxDonation) {
        Log.record(TAG, "今日排位赛捐蛋数($currentDonated)已达上限($maxDonation)，跳过任务调度")
        return
    }

    val isPollingMode = watchDonationRank?.value == true
    val execTime: Long

    if (isPollingMode) {
        val advanceMin = watchDonationAdvanceTime?.value ?: 2
        execTime = endTimeMs - advanceMin * 60 * 1000L
    } else {
        val timeCfg = donationCompetitionTime?.value ?: "1958"
        execTime = if (timeCfg.length >= 3) {
            TimeUtil.getTodayCalendarByTimeStr(timeCfg)?.timeInMillis ?: 0L
        } else {
            val min = timeCfg.toIntOrNull() ?: 2
            endTimeMs - min * 60 * 1000L
        }
    }

    val now = System.currentTimeMillis()
    if (execTime <= now) return

    val modeName = if (isPollingMode) "轮询蹲点" else "单次蹲点"

    val task = ModelTask.ChildModelTask(
        id = taskId,
        group = "DR",
        suspendRunnable = {
            if (isPollingMode) {
                runDonationRankWatchLoop(endTimeMs)
            } else {
                Log.record(TAG, "🔔 执行单次排位赛捐赠检查")
                val uid = UserMap.currentUid ?: return@ChildModelTask
                val donationsMadeToday = Status.getDonationCount(uid)
                val maxDonation = maxDailyDonationCompetitionCount?.value ?: 10
                if (donationsMadeToday < maxDonation) {
                    checkRankAndDonate(maxDonation - donationsMadeToday)
                }
            }
        },
        execTime = execTime,
        useSmartScheduler = useSmartSchedulerManager?.value == true
    )

    addChildTask(task)
    Log.record(TAG, "✅ 已创建${modeName}任务，执行时间: ${TimeUtil.getCommonDate(execTime)}")
}

private suspend fun AntFarm.runDonationRankWatchLoop(endTimeMs: Long) {
    val refreshSec = watchDonationRefreshInterval?.value ?: 10
    Log.record(TAG, "🚀 开始排位赛轮询蹲点，间隔 ${refreshSec}s")

    while (System.currentTimeMillis() < endTimeMs) {
        val uid = UserMap.currentUid ?: break
        val currentDonated = Status.getDonationCount(uid)
        val maxDonation = maxDailyDonationCompetitionCount?.value ?: 10

        if (currentDonated >= maxDonation) {
            Log.record(TAG, "已达到每日捐蛋排位限额($maxDonation)，结束蹲点。")
            break
        }

        checkRankAndDonate(maxDonation - currentDonated)

        delay(refreshSec * 1000L)
    }
    Log.record(TAG, "🏁 轮询蹲点结束")
}

private fun AntFarm.checkRankAndDonate(remainingQuota: Int): Boolean {
    try {
        val myUid = UserMap.currentUid ?: return false
        val res = AntFarmRpcCall.enterDonationCompetitionRank()
        val jo = JSONObject(res)
        if (ResChecker.checkRes(TAG, jo)) {
            syncAnimalStatus(ownerFarmId)

            val rankInfo = jo.optJSONObject("donationRankHomeInfo") ?: return false
            val rankList = rankInfo.optJSONArray("userDonationRankList") ?: return false

            if (rankList.length() > 0) {
                var myData: JSONObject? = null
                for (i in 0 until rankList.length()) {
                    val user = rankList.getJSONObject(i)
                    if (user.getString("userId") == myUid) {
                        myData = user
                        break
                    }
                }

                if (myData == null) {
                    Log.record(TAG, "未在排行榜中找到个人数据，可能尚未初始化")
                    return false
                }

                val serverDonationCount = myData.getInt("donationNum")
                val localDonationCount = Status.getDonationCount(myUid)
                if (serverDonationCount != localDonationCount) {
                    Status.updateDonationCount(myUid, serverDonationCount, incremental = false)
                }

                val myRank = myData.getInt("rankOrder")
                val myStars = myData.optInt("rewardStarNum", 0)

                // 如果已经是第一名，根据策略不做处理
                if (myRank == 1) {
                    Log.record(TAG, "当前已是第一名(${serverDonationCount}蛋)，无需捐赠")
                    return false
                }

                // 寻找能提升星星奖励的目标
                var targetRank = -1
                var targetEggsNeeded = -1
                var targetStarsExpected = myStars

                // 从第一名开始向下遍历，寻找配额内能达到的最高星级
                for (i in 0 until rankList.length()) {
                    val other = rankList.getJSONObject(i)
                    val oRank = other.getInt("rankOrder")
                    if (oRank >= myRank) break // 只看比我名次高的

                    val oStars = other.optInt("rewardStarNum", 0)
                    val oDonation = other.getInt("donationNum")

                    // 只有当该名次的奖励高于我当前的奖励时才考虑
                    if (oStars > myStars) {
                        val eggsNeeded = oDonation - serverDonationCount + 1
                        if (eggsNeeded <= remainingQuota) {
                            // 如果这个名次的星星比之前发现的目标更高，或者星星一样但更省蛋
                            if (oStars > targetStarsExpected) {
                                targetStarsExpected = oStars
                                targetEggsNeeded = eggsNeeded
                                targetRank = oRank
                            } else if (oStars == targetStarsExpected) {
                                // 同样多的星星，选择更省蛋的名次
                                if (targetEggsNeeded == -1 || eggsNeeded < targetEggsNeeded) {
                                    targetEggsNeeded = eggsNeeded
                                    targetRank = oRank
                                }
                            }
                        }
                    }
                }

                if (targetRank != -1 && targetEggsNeeded > 0) {
                    Log.record(TAG, "精明捐赠：当前奖励${myStars}星，目标排名${targetRank}(奖励${targetStarsExpected}星)，需反超${targetEggsNeeded}个蛋")
                    if (donateForCompetition(targetEggsNeeded)) {
                        // 捐赠成功后立即手动更新一次本地计数
                        Status.updateDonationCount(myUid, targetEggsNeeded, incremental = true)
                        return true
                    }
                } else {
                    Log.record(TAG, "评估结论：剩余配额(${remainingQuota})不足以提升星星奖励，放弃捐赠")
                }
            }
        }
    } catch (e: Exception) {
        Log.printStackTrace(TAG, "checkRankAndDonate err:", e)
    }
    return false
}

private fun AntFarm.donateForCompetition(count: Int): Boolean {
    try {
        if (harvestBenevolenceScore < count) {
            if (harvestEggIfInsufficient?.value == true && benevolenceScore >= 1.0) {
                Log.record(TAG, "排位反超蛋数不足(当前:$harvestBenevolenceScore)，发现有待收取蛋($benevolenceScore)，尝试先收获...")
                harvestProduce(ownerFarmId)
            }

            if (harvestBenevolenceScore < count) {
                Log.record(TAG, "排位反超🥚[鸡蛋不足(当前:$harvestBenevolenceScore)，需要:$count，跳过本次捐赠]")
                return false
            }
        }

        val s = AntFarmRpcCall.listActivityInfo()
        val jo = JSONObject(s)
        if (!ResChecker.checkRes(TAG, jo)) return false

        val jaActivityInfos = jo.optJSONArray("activityInfos") ?: return false
        if (jaActivityInfos.length() == 0) return false

        // 寻找第一个未满的项目进行捐赠
        for (i in 0 until jaActivityInfos.length()) {
            val projectJo = jaActivityInfos.getJSONObject(i)
            val donationTotal = projectJo.optDouble("donationTotal", 0.0)
            val donationLimit = projectJo.optDouble("donationLimit", 0.0)
            if (donationTotal >= donationLimit) continue

            val activityId = projectJo.getString("activityId")
            val activityName = projectJo.optString("projectName", activityId)

            return performDonation(activityId, activityName, count)
        }
    } catch (e: Exception) {
        Log.printStackTrace(TAG, "donateForCompetition err:", e)
    }
    return false
}
