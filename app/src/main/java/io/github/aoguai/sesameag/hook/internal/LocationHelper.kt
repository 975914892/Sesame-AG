package io.github.aoguai.sesameag.hook.internal

import android.location.Geocoder
import io.github.aoguai.sesameag.entity.AreaCode
import io.github.aoguai.sesameag.hook.ApplicationHook
import io.github.aoguai.sesameag.util.DataStore
import io.github.aoguai.sesameag.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale
import org.json.JSONObject

object LocationHelper {

    private const val TAG = "LocationInfoHelper"
    private var classLoader: ClassLoader? = null
    private const val LOCATION_KEY = "cached_location"

    fun init(loader: ClassLoader) {
        classLoader = loader
    }

    /**
     * 同步获取缓存的位置信息
     */
    fun getLocation(): JSONObject? {
        return try {
            val map = DataStore.get(LOCATION_KEY, Map::class.java)
            map?.let { JSONObject(it) }
        } catch (e: Exception) {
            Log.error(TAG, "读取位置缓存失败: ${e.message}")
            null
        }
    }

    /**
     * ? 新增：挂起函数版本 (推荐 Kotlin 使用)
     * 在后台线程获取位置并返回结果，自动切回原线程
     */
    suspend fun requestLocationSuspend(): JSONObject = withContext(Dispatchers.Default) {
        try {
            val loader = classLoader
            if (loader == null) {
                return@withContext createAndSaveError("ClassLoader 未初始化")
            }

            val lnsctrUtilsClass = Class.forName(
                "com.alipay.mobile.common.lnsctr.LnsctrUtils",
                false,
                loader
            )
            val latitude = lnsctrUtilsClass.getDeclaredMethod("getLatitude").apply {
                isAccessible = true
            }.invoke(null) as? Double
            val longitude = lnsctrUtilsClass.getDeclaredMethod("getLongitude").apply {
                isAccessible = true
            }.invoke(null) as? Double

            if (latitude != null && longitude != null) {
                val locationMap = mutableMapOf<String, Any>(
                    "latitude" to latitude,
                    "longitude" to longitude
                )
                val previous = getLocation()
                if (previous?.optDouble("latitude") == latitude && previous.optDouble("longitude") == longitude) {
                    previous.optString("cityCode").takeIf { it.isNotBlank() }?.let { locationMap["cityCode"] = it }
                }
                saveLocationToDataStore(locationMap)
                JSONObject(locationMap)
            } else {
                createAndSaveError("等待目标应用初始化中...")
            }
        } catch (e: Throwable) {
            Log.error(TAG, "获取经纬度异常: ${e.message}")
            createAndSaveError("获取失败: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    fun requireCityCode(): String = runBlocking(Dispatchers.IO) {
        val location = requestLocationSuspend()
        location.optString("cityCode").takeIf { it.isNotBlank() }?.let { return@runBlocking it }
        val latitude = location.optDouble("latitude", Double.NaN)
        val longitude = location.optDouble("longitude", Double.NaN)
        check(latitude.isFinite() && longitude.isFinite()) { "缺少宿主当前位置，城市相关请求保留待处理" }
        val context = checkNotNull(ApplicationHook.appContext) { "宿主 Context 未初始化" }
        check(Geocoder.isPresent()) { "系统地理编码不可用，城市相关请求保留待处理" }
        val address = Geocoder(context, Locale.CHINA).getFromLocation(latitude, longitude, 1)?.firstOrNull()
        val cityName = address?.locality?.takeIf { it.isNotBlank() } ?: address?.adminArea
        val cityCode = AreaCode.getList().firstOrNull { it.name == cityName }?.id
        check(!cityCode.isNullOrBlank()) { "系统地理编码与城市目录未能确定当前城市，保留待处理" }
        saveLocationToDataStore(mapOf("latitude" to latitude, "longitude" to longitude, "cityCode" to cityCode))
        cityCode
    }

    private fun createAndSaveError(msg: String): JSONObject {
        val map = mapOf("status" to msg)
        saveLocationToDataStore(map)
        return JSONObject(map)
    }

    private fun saveLocationToDataStore(locationMap: Map<String, Any>) {
        try {
            DataStore.put(LOCATION_KEY, locationMap)
        } catch (e: Exception) {
            Log.error(TAG, "保存位置缓存失败: ${e.message}")
        }
    }
}

