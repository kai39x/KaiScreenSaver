package com.chelseasaver.dream.data

import com.chelseasaver.dream.BuildConfig

/**
 * كل الثوابت والإعدادات القابلة للتعديل في مكان واحد.
 *
 * ⭐ لتغيير الفريق أو المدينة عدّل قسم «إعداداتك» أدناه فقط — لا تحتاج لمس أي ملف آخر:
 *    الطقس وأوقات الصلاة والتوقيت وأسماء الفريق والمدينة على الشاشة كلها تتبع هذه القيم.
 *    (التفاصيل في README القسم 6)
 */
object ApiConfig {

    // تُقرأ من local.properties عبر BuildConfig — راجع README لشرح الإعداد
    val OPENWEATHER_API_KEY: String = BuildConfig.OPENWEATHER_API_KEY
    val FOOTBALL_DATA_API_KEY: String = BuildConfig.FOOTBALL_DATA_API_KEY

    // ═══════════════════════ إعداداتك ═══════════════════════

    // ── المدينة: الطقس + أوقات الصلاة + المنطقة الزمنية ──

    // الاسم الذي يظهر على الشاشة فوق الطقس («طقس المدينة المنورة»)
    const val CITY_NAME: String = "المدينة المنورة"

    // إحداثيات المدينة (من خرائط جوجل: زر أيمن على الموقع ثم انسخ الرقمين). الغرب والجنوب بالسالب.
    // تُستخدم للطقس ولأوقات الصلاة معًا، فتغييرهما يغيّر الاثنين.
    const val CITY_LAT: Double = 24.5247
    const val CITY_LON: Double = 39.5692

    // المنطقة الزمنية للمدينة بصيغة IANA (مثل Asia/Riyadh أو Asia/Dubai أو Africa/Cairo أو Europe/London).
    // تحدد الساعة والتاريخ وأوقات الصلاة والمباريات، وتدعم التوقيت الصيفي تلقائيًا.
    const val TIMEZONE: String = "Asia/Riyadh"

    // طريقة حساب الصلاة في AlAdhan: 4 أم القرى · 3 رابطة العالم الإسلامي · 5 الهيئة المصرية
    // 2 أمريكا الشمالية (ISNA) · 1 كراتشي — القائمة الكاملة: https://aladhan.com/prayer-times-api
    const val PRAYER_METHOD: Int = 4

    // ── الفريق: المباريات القادمة ──

    // رقم الفريق في football-data.org (API v4). تحقق منه عبر: https://api.football-data.org/v4/teams/61
    // (مع ترويسة X-Auth-Token) — إن أعاد اسمًا غير فريقك فابحث عن الرقم الصحيح كما في README.
    const val TEAM_ID: Int = 61

    // اسم الفريق كما تريده على الشاشة («تشيلسي × توتنهام»)
    const val TEAM_NAME: String = "تشيلسي"

    // ═══════════════════════ إعدادات متقدمة ═══════════════════════

    // مجلد صور الخلفية داخل assets — ضع صورك (jpg / png / webp) في app/src/main/assets/backgrounds/
    const val BACKGROUNDS_ASSET_DIR: String = "backgrounds"

    // كل كم ميلي ثانية تُحدَّث بيانات الطقس/المباريات/عداد رمضان (دقيقة واحدة كما طُلب)
    const val REFRESH_INTERVAL_MS: Long = 60_000L
}
