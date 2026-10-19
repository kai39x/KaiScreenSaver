package com.chelseasaver.dream.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** أوقات صلوات يوم واحد بصيغة "HH:mm" بتوقيت الرياض. */
data class PrayerDay(
    val date: String, // yyyy-MM-dd
    val fajr: String,
    val dhuhr: String,
    val asr: String,
    val maghrib: String,
    val isha: String
)

/** صلاة واحدة بوقتها المطلق (epoch بالميلي ثانية) — هكذا لا يهم توقيت الجهاز ولا تغيّر اليوم. */
data class PrayerEvent(
    val key: String, // fajr | dhuhr | asr | maghrib | isha
    val epochMillis: Long
)

/**
 * أوقات الصلاة لمدينة [ApiConfig.CITY_NAME] (إحداثياتها ومنطقتها الزمنية وطريقة الحساب من [ApiConfig])،
 * بدون أي مفتاح API. المصدر: واجهة AlAdhan العامة المجانية:
 *   GET https://api.aladhan.com/v1/calendar/{year}/{month}?latitude=..&longitude=..&method=..&timezonestring=..
 * (تُرجع الشهر كاملًا في طلب واحد، وأوقاتها بصيغة "05:03 (+03)" وتاريخ اليوم بصيغة DD-MM-YYYY).
 *
 * العمل أوفلاين: كل ما يُجلب يُحفظ في SharedPreferences الخاصة بالتطبيق (لا يحتاج صلاحية).
 * لا نطلب الشبكة إلا إذا نقص من الكاش اليوم أو أحد الأيام الثلاثة التالية، أي غالبًا مرة واحدة كل
 * بضعة أيام فقط، وإن فشل الطلب نكمل بما هو محفوظ ولا نعيد المحاولة قبل 5 دقائق.
 *
 * الكاش يحمل «توقيعًا» (الإحداثيات + طريقة الحساب + المنطقة الزمنية). إذا غيّرت أيًّا منها في ApiConfig
 * يُتجاهل الكاش القديم تلقائيًا ويُجلب من جديد، فلا تظهر أوقات مدينة سابقة أبدًا.
 */
object PrayerRepository {

    private const val BASE_URL = "https://api.aladhan.com/v1"
    private const val PREFS_NAME = "prayer_cache"
    private const val KEY_DAYS = "days"
    private const val KEY_SIGNATURE = "signature"

    // نضمن وجود اليوم + 3 أيام قادمة في الكاش (فجر بكرة مطلوب بعد العشاء حتى لو انقطع النت)
    private const val DAYS_AHEAD_REQUIRED = 3
    private const val RETRY_AFTER_FAILURE_MS = 5 * 60_000L

    private val CITY_TZ: TimeZone = TimeZone.getTimeZone(ApiConfig.TIMEZONE)
    private val TIME_REGEX = Regex("""(\d{1,2}):(\d{2})""")

    @Volatile
    private var lastFailureAtMillis: Long = 0L

    /**
     * يرجع صلوات اليوم وبكرة مرتبة زمنيًا. إن كان [allowNetwork] = false لا يلمس الشبكة أبدًا
     * (يقرأ الكاش فقط) — نستخدمه أولًا ليظهر العداد فورًا حتى لو الشبكة بطيئة أو مقطوعة.
     * القائمة فارغة إن لم يوجد أي كاش ولا اتصال.
     */
    fun getSchedule(
        context: Context,
        lat: Double,
        lon: Double,
        allowNetwork: Boolean
    ): List<PrayerEvent> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val signature = cacheSignature(lat, lon)
        val days = loadCache(prefs, signature)
        val now = System.currentTimeMillis()

        if (allowNetwork) {
            val missing = (0..DAYS_AHEAD_REQUIRED)
                .map { dateKeyAt(now, it) }
                .filter { !days.containsKey(it) }

            if (missing.isNotEmpty() && now - lastFailureAtMillis >= RETRY_AFTER_FAILURE_MS) {
                // الأشهر التي ينقصها يوم مطلوب (شهر واحد غالبًا، وشهران في آخر أيام الشهر)
                val months = missing.map { it.substring(0, 7) }.distinct()
                var changed = false
                for (ym in months) {
                    val year = ym.substring(0, 4).toInt()
                    val month = ym.substring(5, 7).toInt()
                    val fetched = fetchMonth(lat, lon, year, month)
                    if (fetched.isNotEmpty()) {
                        days.putAll(fetched)
                        changed = true
                    }
                }

                val stillMissing = (0..DAYS_AHEAD_REQUIRED).any { !days.containsKey(dateKeyAt(now, it)) }
                if (stillMissing) lastFailureAtMillis = now

                // نحذف الأيام القديمة (قبل أمس) حتى لا يكبر الكاش
                val oldestKept = dateKeyAt(now, -1)
                if (days.keys.removeAll { it < oldestKept }) changed = true
                if (changed) saveCache(prefs, days, signature)
            }
        }

        return buildEvents(days, now)
    }

    // ───────────── الجلب والتحليل ─────────────

    private fun fetchMonth(lat: Double, lon: Double, year: Int, month: Int): Map<String, PrayerDay> {
        val url = URL(
            "$BASE_URL/calendar/$year/$month?latitude=$lat&longitude=$lon" +
                "&method=${ApiConfig.PRAYER_METHOD}&timezonestring=${ApiConfig.TIMEZONE}"
        )
        val body = httpGet(url) ?: return emptyMap()
        return try {
            parseCalendar(body)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** يحلل رد /calendar: data = مصفوفة أيام، لكل يوم timings و date.gregorian.date (DD-MM-YYYY). */
    internal fun parseCalendar(body: String): Map<String, PrayerDay> {
        val result = LinkedHashMap<String, PrayerDay>()
        val data = JSONObject(body).optJSONArray("data") ?: return result

        for (i in 0 until data.length()) {
            val entry = data.optJSONObject(i) ?: continue
            val timings = entry.optJSONObject("timings") ?: continue
            val gregorianDate = entry.optJSONObject("date")
                ?.optJSONObject("gregorian")
                ?.optString("date")
                .orEmpty()

            val key = toDateKey(gregorianDate) ?: continue
            val fajr = cleanTime(timings.optString("Fajr")) ?: continue
            val dhuhr = cleanTime(timings.optString("Dhuhr")) ?: continue
            val asr = cleanTime(timings.optString("Asr")) ?: continue
            val maghrib = cleanTime(timings.optString("Maghrib")) ?: continue
            val isha = cleanTime(timings.optString("Isha")) ?: continue

            result[key] = PrayerDay(key, fajr, dhuhr, asr, maghrib, isha)
        }
        return result
    }

    /** "19-10-2026" → "2026-10-19" (أو null إن كان الشكل غير متوقع). */
    private fun toDateKey(ddMMyyyy: String): String? {
        val parts = ddMMyyyy.split("-")
        if (parts.size != 3 || parts[0].length != 2 || parts[1].length != 2 || parts[2].length != 4) return null
        if (parts.any { p -> p.any { !it.isDigit() } }) return null
        return "${parts[2]}-${parts[1]}-${parts[0]}"
    }

    /** "05:03 (+03)" → "05:03" ؛ يرفض أي وقت خارج 00:00–23:59. */
    private fun cleanTime(raw: String?): String? {
        val m = TIME_REGEX.find(raw.orEmpty()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        return String.format(Locale.US, "%02d:%02d", h, min)
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

    // ───────────── التواريخ والأحداث ─────────────

    /** مفتاح التاريخ yyyy-MM-dd بتوقيت الرياض لوقت [nowMillis] مُزاحًا بـ [offsetDays] يوم. */
    internal fun dateKeyAt(nowMillis: Long, offsetDays: Int): String {
        val cal = Calendar.getInstance(CITY_TZ)
        cal.timeInMillis = nowMillis
        cal.add(Calendar.DAY_OF_YEAR, offsetDays)
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** يحوّل (تاريخ بتوقيت الرياض + "HH:mm") إلى لحظة مطلقة epoch ms. */
    private fun toEpoch(dateKey: String, hhmm: String): Long? {
        val m = TIME_REGEX.find(hhmm) ?: return null
        val year = dateKey.substring(0, 4).toIntOrNull() ?: return null
        val month = dateKey.substring(5, 7).toIntOrNull() ?: return null
        val day = dateKey.substring(8, 10).toIntOrNull() ?: return null
        val cal = Calendar.getInstance(CITY_TZ)
        cal.clear()
        cal.set(year, month - 1, day, m.groupValues[1].toInt(), m.groupValues[2].toInt(), 0)
        return cal.timeInMillis
    }

    /** صلوات اليوم وبكرة (10 أحداث كحد أقصى) مرتبة زمنيًا؛ ما ليس في الكاش يُتجاهل. */
    internal fun buildEvents(days: Map<String, PrayerDay>, nowMillis: Long): List<PrayerEvent> {
        val events = ArrayList<PrayerEvent>()
        for (offset in 0..1) {
            val key = dateKeyAt(nowMillis, offset)
            val day = days[key] ?: continue
            val pairs = listOf(
                "fajr" to day.fajr,
                "dhuhr" to day.dhuhr,
                "asr" to day.asr,
                "maghrib" to day.maghrib,
                "isha" to day.isha
            )
            for ((name, time) in pairs) {
                val epoch = toEpoch(key, time) ?: continue
                events.add(PrayerEvent(name, epoch))
            }
        }
        events.sortBy { it.epochMillis }
        return events
    }

    // ───────────── الكاش المحلي ─────────────

    /** يميّز كاش مدينة/طريقة/منطقة زمنية عن غيرها: أي تغيير في ApiConfig يُنتج توقيعًا مختلفًا. */
    internal fun cacheSignature(lat: Double, lon: Double): String =
        "$lat,$lon,${ApiConfig.PRAYER_METHOD},${ApiConfig.TIMEZONE}"

    /** يقرأ الكاش، لكن يتجاهله كله (يرجع فارغًا) إن كان محفوظًا لمدينة أو طريقة أو منطقة زمنية مختلفة. */
    internal fun loadCache(prefs: SharedPreferences, signature: String): MutableMap<String, PrayerDay> {
        val map = LinkedHashMap<String, PrayerDay>()
        if (prefs.getString(KEY_SIGNATURE, null) != signature) return map
        val raw = prefs.getString(KEY_DAYS, null) ?: return map
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val d = root.optJSONObject(key) ?: continue
                val fajr = cleanTime(d.optString("fajr")) ?: continue
                val dhuhr = cleanTime(d.optString("dhuhr")) ?: continue
                val asr = cleanTime(d.optString("asr")) ?: continue
                val maghrib = cleanTime(d.optString("maghrib")) ?: continue
                val isha = cleanTime(d.optString("isha")) ?: continue
                map[key] = PrayerDay(key, fajr, dhuhr, asr, maghrib, isha)
            }
        } catch (e: Exception) {
            map.clear() // كاش تالف: نتجاهله ونعيد الجلب
        }
        return map
    }

    internal fun saveCache(prefs: SharedPreferences, days: Map<String, PrayerDay>, signature: String) {
        val root = JSONObject()
        for ((key, d) in days) {
            root.put(
                key,
                JSONObject()
                    .put("fajr", d.fajr)
                    .put("dhuhr", d.dhuhr)
                    .put("asr", d.asr)
                    .put("maghrib", d.maghrib)
                    .put("isha", d.isha)
            )
        }
        prefs.edit()
            .putString(KEY_DAYS, root.toString())
            .putString(KEY_SIGNATURE, signature)
            .apply()
    }
}
