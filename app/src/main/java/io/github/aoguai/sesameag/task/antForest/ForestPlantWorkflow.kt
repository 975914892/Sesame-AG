package io.github.aoguai.sesameag.task.antForest

import io.github.aoguai.sesameag.hook.ApplicationHookConstants
import io.github.aoguai.sesameag.util.Log
import org.json.JSONArray
import org.json.JSONObject

internal fun AntForest.waterMemberPlant() {
    try {
        var response = JSONObject(AntForestRpcCall.memberForestSignin())
        while (!ApplicationHookConstants.isOffline()) {
            if (!response.optBoolean("success") || response.optString("resultCode") != "SUCCESS") {
                Log.error("ForestPlant", "绿植状态查询失败 raw=$response")
                return
            }
            val data = response.optJSONObject("resultData") ?: return
            if (data.optJSONObject("plantChannelResponse")?.optBoolean("access") != true) return
            val plant = data.optJSONObject("plantInfo") ?: return
            if (plant.optString("status") != "in_progress") return
            val info = plant.optJSONObject("plantInfoMap") ?: return
            val water = info.optJSONObject("WATER") ?: return
            val pot = info.optJSONObject("WATERING_POT") ?: return
            val ext = pot.optJSONObject("extInfo") ?: return
            val available = JSONArray(ext.optString("availablePots").ifBlank { "[]" })
            val speeds = (0 until available.length()).map { available.getString(it) }
            val costPerWater = ext.optLong("costPerWater", 0L)
            val balance = water.optLong("value", 0L)
            val speed = when {
                "five" in speeds && costPerWater > 0 && balance >= costPerWater * 5L -> "five"
                "one" in speeds && costPerWater > 0 && balance >= costPerWater -> "one"
                else -> return
            }
            val progress = info.optJSONObject("PLANT_PROGRESS_V2")?.optJSONObject("extInfo")
            val beforeTotal = progress?.optLong("totalWaterG", -1L) ?: -1L
            val beforeBar = progress?.optLong("currentBarProgressG", -1L) ?: -1L
            if (beforeTotal < 0L) {
                Log.error("ForestPlant", "绿植缺少累计浇水进度 raw=$response")
                return
            }
            val award = JSONObject(AntForestRpcCall.plantAward(speed))
            if (!award.optBoolean("success") || award.optString("resultCode") != "SUCCESS") {
                Log.error("ForestPlant", "绿植浇水失败 speed=$speed raw=$award")
            }
            response = JSONObject(AntForestRpcCall.memberForestSignin(water.optString("darwinVersion", "plant_black_v2")))
            val afterInfo = response.optJSONObject("resultData")?.optJSONObject("plantInfo")?.optJSONObject("plantInfoMap")
            val afterProgress = afterInfo?.optJSONObject("PLANT_PROGRESS_V2")?.optJSONObject("extInfo")
            val afterTotal = afterProgress?.optLong("totalWaterG", -1L) ?: -1L
            val afterBalance = afterInfo?.optJSONObject("WATER")?.optLong("value", -1L) ?: -1L
            if (!response.optBoolean("success") || afterTotal <= beforeTotal || afterBalance < 0L || afterBalance >= balance) {
                Log.error("ForestPlant", "绿植浇水进度未确认 beforeTotal=$beforeTotal beforeWater=$balance raw=$response")
                return
            }
            Log.forest("绿植浇水🌱累计${beforeTotal}→${afterTotal}，水滴${balance}→${afterBalance}，段进度${beforeBar}→${afterProgress?.optLong("currentBarProgressG")}")
        }
    } catch (t: Throwable) {
        Log.printStackTrace("ForestPlant", "绿植浇水处理异常", t)
    }
}
