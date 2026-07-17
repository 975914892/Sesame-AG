package io.github.aoguai.sesameag.task.reserve

import io.github.aoguai.sesameag.data.Status
import io.github.aoguai.sesameag.entity.ReserveEntity
import io.github.aoguai.sesameag.model.BaseModel
import io.github.aoguai.sesameag.model.ModelFields
import io.github.aoguai.sesameag.model.ModelGroup
import io.github.aoguai.sesameag.model.modelFieldExt.SelectAndCountModelField
import io.github.aoguai.sesameag.model.withDesc
import io.github.aoguai.sesameag.task.ModelTask
import io.github.aoguai.sesameag.task.TaskCommon
import io.github.aoguai.sesameag.util.GlobalThreadPools
import io.github.aoguai.sesameag.util.Log
import io.github.aoguai.sesameag.util.ResChecker
import io.github.aoguai.sesameag.util.maps.IdMapManager
import io.github.aoguai.sesameag.util.maps.ReserveaMap
import org.json.JSONException
import org.json.JSONObject

class Reserve : ModelTask() {
    private var reserveList: SelectAndCountModelField? = null

    override fun getName(): String = "保护地"

    override fun getGroup(): ModelGroup = ModelGroup.FOREST

    override fun getIcon(): String = "Reserve.png"

    override fun getFields(): ModelFields {
        val modelFields = ModelFields()
        modelFields.addField(
            SelectAndCountModelField(
                "reserveList",
                "保护地 | 申请列表",
                LinkedHashMap(),
                ReserveEntity::getListAsMapperEntity,
            ).withDesc("选择要自动申请的保护地及每日申请次数；数量大于 0 才会执行，对应条目填 0 或不选则跳过。").also {
                reserveList = it
            },
        )
        return modelFields
    }

    override fun check(): Boolean =
        when {
            TaskCommon.IS_ENERGY_TIME -> {
                Log.forest("⏸ 当前为只收能量时间【${BaseModel.energyTime.value}】，停止执行${getName()}任务！")
                false
            }

            TaskCommon.IS_MODULE_SLEEP_TIME -> {
                Log.forest("💤 模块休眠时间【${BaseModel.modelSleepTime.value}】停止执行${getName()}任务！")
                false
            }

            else -> {
                true
            }
        }

    override fun runJava() {
        GlobalThreadPools.execute {
            runSuspend()
        }
    }

    override suspend fun runSuspend() {
        try {
            Log.forest("开始保护地任务")
            initReserve()
            animalReserve()
        } catch (t: Throwable) {
            Log.runtime(TAG, "start.run err:")
            Log.printStackTrace(TAG, t)
        } finally {
            Log.forest("保护地任务")
        }
    }

    private suspend fun animalReserve() {
        try {
            Log.forest("开始执行-${getName()}")
            val configuredReserveMap = getConfiguredReserveMap()
            if (configuredReserveMap.isEmpty()) {
                Log.forest("保护地列表未配置有效申请项，跳过执行")
                return
            }
            var s: String? = ReserveRpcCall.queryTreeItemsForExchange()
            if (s == null) {
                s = ReserveRpcCall.queryTreeItemsForExchange()
            }
            val jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val ja = jo.getJSONArray("treeItems")
                for (i in 0 until ja.length()) {
                    val item = ja.getJSONObject(i)
                    if (!item.has("projectType")) {
                        continue
                    }
                    if (item.getString("projectType") != "RESERVE") {
                        continue
                    }
                    if (item.getString("applyAction") != "AVAILABLE") {
                        continue
                    }
                    val projectId = item.getString("itemId")
                    val itemName = item.getString("itemName")
                    val value = configuredReserveMap[projectId] ?: continue
                    if (Status.canReserveToday(projectId, value)) {
                        exchangeTree(projectId, itemName, value)
                    }
                }
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"))
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "animalReserve err:")
            Log.printStackTrace(TAG, t)
        } finally {
            Log.forest("结束执行-${getName()}")
        }
    }

    private fun getConfiguredReserveMap(): Map<String, Int> {
        val configuredMap = reserveList?.value ?: return emptyMap()
        if (configuredMap.isEmpty()) {
            return emptyMap()
        }
        val enabledReserveMap = LinkedHashMap<String, Int>()
        configuredMap.forEach { (projectId, count) ->
            val enabledCount = count ?: return@forEach
            if (!projectId.isNullOrBlank() && enabledCount > 0) {
                enabledReserveMap[projectId] = enabledCount
            }
        }
        return enabledReserveMap
    }

    private fun queryTreeForExchange(projectId: String): Boolean {
        try {
            val s = ReserveRpcCall.queryTreeForExchange(projectId)
            var jo = JSONObject(s)
            if (ResChecker.checkRes(TAG, jo)) {
                val applyAction = jo.getString("applyAction")
                val currentEnergy = jo.getInt("currentEnergy")
                jo = jo.getJSONObject("exchangeableTree")
                return if (applyAction == "AVAILABLE") {
                    if (currentEnergy >= jo.getInt("energy")) {
                        true
                    } else {
                        Log.forest("领保护地🏕️[${jo.getString("projectName")}]#能量不足停止申请")
                        false
                    }
                } else {
                    Log.forest("领保护地🏕️[${jo.getString("projectName")}]#似乎没有了")
                    false
                }
            } else {
                Log.forest(jo.getString("resultDesc"))
                Log.runtime(s)
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "queryTreeForExchange err:")
            Log.printStackTrace(TAG, t)
        }
        return false
    }

    private suspend fun exchangeTree(
        projectId: String,
        itemName: String,
        count: Int,
    ) {
        try {
            var canApply = queryTreeForExchange(projectId)
            if (!canApply) {
                return
            }
            for (applyCount in 1..count) {
                val s = ReserveRpcCall.exchangeTree(projectId)
                val jo = JSONObject(s)
                if (ResChecker.checkRes(TAG, jo)) {
                    val vitalityAmount = jo.optInt("vitalityAmount", 0)
                    val appliedTimes = Status.getReserveTimes(projectId) + 1
                    val str =
                        "领保护地🏕️[$itemName]#第${appliedTimes}次" +
                            if (vitalityAmount > 0) "-活力值+$vitalityAmount" else ""
                    Log.forest(str)
                    Status.reserveToday(projectId, 1)
                } else {
                    Log.forest(jo.getString("resultDesc"))
                    Log.runtime(jo.toString())
                    Log.forest("领保护地🏕️[$itemName]#发生未知错误，停止申请")
                    break
                }
                canApply = queryTreeForExchange(projectId)
                if (!canApply) {
                    break
                }
                if (!Status.canReserveToday(projectId, count)) {
                    break
                }
            }
        } catch (t: Throwable) {
            Log.runtime(TAG, "exchangeTree err:")
            Log.printStackTrace(TAG, t)
        }
    }

    companion object {
        private val TAG = Reserve::class.java.simpleName

        @JvmStatic
        fun initReserve() {
            val reserveMap = IdMapManager.getInstance(ReserveaMap::class.java)
            try {
                val response = ReserveRpcCall.queryTreeItemsForExchange()
                val jsonResponse = JSONObject(response)
                if (!ResChecker.checkRes(TAG, jsonResponse)) {
                    Log.runtime(TAG, "刷新保护地候选失败: ${jsonResponse.optString("resultDesc", "未知错误")}")
                    return
                }
                val treeItems =
                    jsonResponse.optJSONArray("treeItems") ?: run {
                        Log.runtime(TAG, "刷新保护地候选失败: 缺少treeItems")
                        return
                    }

                reserveMap.clear()
                var availableCount = 0
                for (i in 0 until treeItems.length()) {
                    val item = treeItems.optJSONObject(i) ?: continue
                    if (item.optString("projectType") != "RESERVE" ||
                        item.optString("applyAction") != "AVAILABLE"
                    ) {
                        continue
                    }
                    val itemId = item.optString("itemId").trim()
                    if (itemId.isEmpty()) {
                        Log.runtime(TAG, "跳过缺少itemId的保护地候选")
                        continue
                    }
                    val itemName = item.optString("itemName", itemId)
                    val energy = item.optInt("energy", 0)
                    reserveMap.add(itemId, "$itemName(${energy}g)")
                    availableCount++
                }
                reserveMap.save()
                Log.runtime(TAG, "刷新保护地候选成功[$availableCount]")
            } catch (e: JSONException) {
                Log.runtime(TAG, "刷新保护地候选JSON错误：${e.message}")
                Log.printStackTrace(e)
            } catch (e: Exception) {
                Log.runtime(TAG, "刷新保护地候选出错：${e.message}")
                Log.printStackTrace(e)
            }
        }
    }
}
