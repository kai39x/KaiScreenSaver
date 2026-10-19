package com.chelseasaver.dream

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.webkit.WebView
import android.webkit.WebViewClient
import com.chelseasaver.dream.data.ApiConfig
import com.chelseasaver.dream.data.TeamMatch
import com.chelseasaver.dream.data.FootballRepository
import com.chelseasaver.dream.data.PrayerEvent
import com.chelseasaver.dream.data.PrayerRepository
import com.chelseasaver.dream.data.RamadanCalculator
import com.chelseasaver.dream.data.WeatherRepository
import com.chelseasaver.dream.data.WeatherSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * الشاشة الرسمية (Screen Saver / DreamService). كل عملها هنا هو:
 *  1) عرض WebView بملء الشاشة يحمّل dashboard.html من الأصول (assets).
 *  2) عند تحميل الصفحة: دفع إعدادات ApiConfig (المدينة/الفريق/المنطقة الزمنية) وقائمة صور الخلفية
 *     الموجودة في assets/backgrounds (الصفحة هي من تبدّلها).
 *  3) كل دقيقة: حساب عداد رمضان محليًا + أوقات الصلاة (من الكاش أولًا) + جلب الطقس والمباريات
 *     من الشبكة في خيط خلفي، ثم دفعها إلى صفحة الويب عبر evaluateJavascript.
 * الساعة وعدّاد الصلاة ودوران المباريات والخلفيات كلها تُحدَّث داخل JavaScript محليًا
 * (لا تحتاج اتصال شبكة).
 */
class ChelseaDreamService : DreamService() {

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundExecutor: ExecutorService? = null

    private val periodicRefresh = object : Runnable {
        override fun run() {
            refreshData()
            mainHandler.postDelayed(this, ApiConfig.REFRESH_INTERVAL_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        // إعدادات شاشة التوقف: غير تفاعلية، ملء الشاشة، وبسطوع طبيعي (وليس وضع Doze خافت)
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        backgroundExecutor = Executors.newSingleThreadExecutor()

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(Color.parseColor("#070B1A"))
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // إعدادات المدينة والفريق والتوقيت من ApiConfig تُدفع أولًا ومرة واحدة،
                    // فتأخذ الصفحة اسم المدينة واسم الفريق والمنطقة الزمنية قبل أي بيانات
                    pushConfig()
                    // الخلفيات تُدفع مرة واحدة فقط (لا تتغير أثناء التشغيل)
                    pushBackgrounds()
                    // أول تحديث فور انتهاء تحميل الصفحة مباشرة (بدون انتظار الدقيقة الأولى)
                    refreshData()
                }
            }
            loadUrl("file:///android_asset/dashboard.html")
        }

        webView = wv
        setContentView(wv)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        mainHandler.postDelayed(periodicRefresh, ApiConfig.REFRESH_INTERVAL_MS)
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        mainHandler.removeCallbacks(periodicRefresh)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(periodicRefresh)
        backgroundExecutor?.shutdownNow()
        backgroundExecutor = null
        webView?.destroy()
        webView = null
    }

    private fun refreshData() {
        // عداد رمضان حساب محلي فوري، لا يحتاج شبكة
        pushRamadanCountdown()

        val executor = backgroundExecutor ?: return
        executor.execute {
            // أوقات الصلاة أولًا: المرحلة 1 من الكاش المحلي فورًا (حتى لو الشبكة بطيئة أو مقطوعة)،
            // والمرحلة 2 تجلب من الشبكة فقط إن نقص الكاش أيامًا قادمة، وتدفع النتيجة إن تغيّرت.
            val cachedPrayers = try {
                PrayerRepository.getSchedule(
                    applicationContext, ApiConfig.CITY_LAT, ApiConfig.CITY_LON, allowNetwork = false
                )
            } catch (e: Exception) {
                emptyList()
            }
            if (cachedPrayers.isNotEmpty()) {
                mainHandler.post { pushPrayers(cachedPrayers) }
            }
            val freshPrayers = try {
                PrayerRepository.getSchedule(
                    applicationContext, ApiConfig.CITY_LAT, ApiConfig.CITY_LON, allowNetwork = true
                )
            } catch (e: Exception) {
                cachedPrayers
            }
            if (freshPrayers != cachedPrayers || freshPrayers.isEmpty()) {
                mainHandler.post { pushPrayers(freshPrayers) }
            }

            val weather = try {
                WeatherRepository.fetchWeather(
                    ApiConfig.CITY_LAT,
                    ApiConfig.CITY_LON,
                    ApiConfig.OPENWEATHER_API_KEY
                )
            } catch (e: Exception) {
                null
            }

            val matches = try {
                FootballRepository.fetchUpcomingMatches(
                    ApiConfig.TEAM_ID,
                    ApiConfig.FOOTBALL_DATA_API_KEY
                )
            } catch (e: Exception) {
                emptyList()
            }

            mainHandler.post {
                weather?.let { pushWeather(it) }
                pushMatches(matches)
            }
        }
    }

    private fun pushRamadanCountdown() {
        val countdown = RamadanCalculator.countdownToRamadan()
        val json = JSONObject().apply {
            put("days", countdown.days)
            put("hours", countdown.hours)
            put("minutes", countdown.minutes)
            put("target", countdown.targetMillis)
        }
        evaluateJs("updateRamadan($json)")
    }

    private fun pushPrayers(events: List<PrayerEvent>) {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("key", e.key)
                    put("ts", e.epochMillis)
                }
            )
        }
        val json = JSONObject().apply {
            put("events", arr)
        }
        evaluateJs("updatePrayers($json)")
    }

    // يدفع إعدادات ApiConfig التي تحتاجها الصفحة: اسم المدينة (فوق الطقس)، اسم الفريق (في المباراة)،
    // والمنطقة الزمنية لحساب الساعة والتواريخ. offsetMs إزاحة المنطقة الآن، احتياط فقط إن لم يدعم
    // الـ WebView أسماء المناطق الزمنية (نادر جدًا).
    private fun pushConfig() {
        val json = JSONObject().apply {
            put("city", ApiConfig.CITY_NAME)
            put("team", ApiConfig.TEAM_NAME)
            put("tz", ApiConfig.TIMEZONE)
            put("offsetMs", TimeZone.getTimeZone(ApiConfig.TIMEZONE).getOffset(System.currentTimeMillis()))
        }
        evaluateJs("setConfig($json)")
    }

    // يقرأ أسماء الصور من assets/backgrounds ويدفعها للصفحة (التبديل والتلاشي يتمان داخل JavaScript)
    private fun pushBackgrounds() {
        val names = try {
            assets.list(ApiConfig.BACKGROUNDS_ASSET_DIR)
                ?.filter { name ->
                    val lower = name.lowercase()
                    lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                        lower.endsWith(".png") || lower.endsWith(".webp")
                }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val arr = JSONArray()
        names.forEach { arr.put(it) }
        evaluateJs("setBackgrounds($arr)")
    }

    private fun pushWeather(weather: WeatherSnapshot) {
        val dailyArray = JSONArray()
        weather.daily.forEach { day ->
            dailyArray.put(
                JSONObject().apply {
                    put("day", day.dayLabel)
                    put("min", day.minTemp)
                    put("max", day.maxTemp)
                    put("icon", day.iconCode)
                }
            )
        }
        val json = JSONObject().apply {
            put("temp", weather.currentTemp)
            put("description", weather.description)
            put("icon", weather.iconCode)
            put("daily", dailyArray)
        }
        evaluateJs("updateWeather($json)")
    }

    private fun pushMatches(matches: List<TeamMatch>) {
        val arr = JSONArray()
        matches.forEach { m ->
            arr.put(
                JSONObject().apply {
                    put("opponent", m.opponent)
                    put("isHome", m.isHome)
                    put("competition", m.competition)
                    put("date", m.dateLabel)
                    put("time", m.timeLabel)
                    put("ts", m.kickoffMillis)
                }
            )
        }
        evaluateJs("updateMatches($arr)")
    }

    private fun evaluateJs(script: String) {
        webView?.evaluateJavascript(script, null)
    }
}
