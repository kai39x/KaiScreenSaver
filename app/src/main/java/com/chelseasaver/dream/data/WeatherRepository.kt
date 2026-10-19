package com.chelseasaver.dream.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

data class DailyForecast(
    val dayLabel: String,
    val minTemp: Int,
    val maxTemp: Int,
    val iconCode: String
)

data class WeatherSnapshot(
    val currentTemp: Int,
    val description: String,
    val iconCode: String,
    val daily: List<DailyForecast>
)

/**
 * يستخدم واجهتي OpenWeatherMap المجانيتين القياسيتين:
 *  - /data/2.5/weather   → الطقس الحالي
 *  - /data/2.5/forecast  → توقعات كل 3 ساعات لمدة 5 أيام (يتم تجميعها هنا يوميًا)
 * كلتاهما ضمن الخطة المجانية (60 طلب/دقيقة) ولا تحتاج بطاقة ائتمان.
 */
object WeatherRepository {

    private const val BASE_URL = "https://api.openweathermap.org/data/2.5"
    private val CITY_TZ = TimeZone.getTimeZone(ApiConfig.TIMEZONE)

    fun fetchWeather(lat: Double, lon: Double, apiKey: String): WeatherSnapshot? {
        if (apiKey.isBlank()) return null
        val current = fetchCurrent(lat, lon, apiKey) ?: return null
        val daily = fetchForecastDaily(lat, lon, apiKey)
        return current.copy(daily = daily)
    }

    private fun fetchCurrent(lat: Double, lon: Double, apiKey: String): WeatherSnapshot? {
        val url = URL("$BASE_URL/weather?lat=$lat&lon=$lon&units=metric&lang=ar&appid=$apiKey")
        val body = httpGet(url) ?: return null
        val root = JSONObject(body)
        val main = root.optJSONObject("main") ?: return null
        val weather0 = root.optJSONArray("weather")?.optJSONObject(0)
        return WeatherSnapshot(
            currentTemp = main.optDouble("temp").toInt(),
            description = weather0?.optString("description").orEmpty(),
            iconCode = weather0?.optString("icon") ?: "01d",
            daily = emptyList()
        )
    }

    private fun fetchForecastDaily(lat: Double, lon: Double, apiKey: String): List<DailyForecast> {
        val url = URL("$BASE_URL/forecast?lat=$lat&lon=$lon&units=metric&lang=ar&appid=$apiKey")
        val body = httpGet(url) ?: return emptyList()
        val root = JSONObject(body)
        val list: JSONArray = root.optJSONArray("list") ?: return emptyList()

        val utcParse = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = CITY_TZ }
        val dayNameFormat = SimpleDateFormat("EEEE", Locale("ar")).apply { timeZone = CITY_TZ }

        val byDay = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until list.length()) {
            val entry = list.getJSONObject(i)
            val dtTxt = entry.optString("dt_txt")
            val parsed = try { utcParse.parse(dtTxt) } catch (e: Exception) { null } ?: continue
            val dayKey = dayKeyFormat.format(parsed)
            byDay.getOrPut(dayKey) { mutableListOf() }.add(entry)
        }

        val result = mutableListOf<DailyForecast>()
        for ((dayKey, entries) in byDay) {
            var minT = Double.MAX_VALUE
            var maxT = -Double.MAX_VALUE
            var repIcon = "01d"
            var bestHourDiff = Int.MAX_VALUE

            for (entry in entries) {
                val main = entry.optJSONObject("main") ?: continue
                val t = main.optDouble("temp")
                if (t < minT) minT = t
                if (t > maxT) maxT = t

                val hour = entry.optString("dt_txt").substringAfter(" ").substringBefore(":").toIntOrNull() ?: 12
                val diff = abs(hour - 13) // أقرب نقطة بيانات لمنتصف النهار تمثل حالة اليوم العامة في الأيقونة
                if (diff < bestHourDiff) {
                    bestHourDiff = diff
                    repIcon = entry.optJSONArray("weather")?.optJSONObject(0)?.optString("icon") ?: repIcon
                }
            }

            val dayDate = try { dayKeyFormat.parse(dayKey) } catch (e: Exception) { null }
            val label = if (dayDate != null) dayNameFormat.format(dayDate) else dayKey
            result.add(DailyForecast(label, minT.toInt(), maxT.toInt(), repIcon))
        }
        return result.take(5)
    }

    private fun httpGet(url: URL): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
