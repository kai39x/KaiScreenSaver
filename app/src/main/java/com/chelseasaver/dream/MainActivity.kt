package com.chelseasaver.dream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.chelseasaver.dream.data.ApiConfig

/**
 * ليست إلزامية لعمل الشاشة كـ Screen Saver، لكنها تسهّل أول استخدام:
 * تشرح للمستخدم كيف يفعّلها، وتوفر زرًا يفتح إعدادات شاشة التوقف مباشرة.
 */
class MainActivity : Activity() {

    // نفس خط لوحة الـ WebView (Thmanyah Sans) يُحمَّل هنا أصليًا كي لا تختلف شاشة
    // الإعدادات هذه عن تصميم الشاشة الرئيسية. في حال تعذّر تحميل أي قصّة (نادر جدًا
    // لأن الملفات مرفقة داخل التطبيق نفسه) يعود تلقائيًا إلى خط النظام الافتراضي.
    private val regularFont: Typeface? by lazy { loadFont("ThmanyahSans-Regular.otf") }
    private val mediumFont: Typeface? by lazy { loadFont("ThmanyahSans-Medium.otf") }
    private val boldFont: Typeface? by lazy { loadFont("ThmanyahSans-Bold.otf") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTextColor(Color.parseColor("#F3F6FC"))
            typeface = boldFont ?: Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "شاشة توقف رسمية (Screen Saver) تعرض الساعة، الصلاة القادمة، عداد رمضان، أقرب مباريات ${ApiConfig.TEAM_NAME}، " +
                "وطقس ${ApiConfig.CITY_NAME}.\n\n" +
                "لتفعيلها:\n" +
                "١) اضغط الزر أدناه لفتح إعدادات شاشة التوقف.\n" +
                "٢) اختر \"${getString(R.string.app_name)}\" من القائمة.\n" +
                "٣) إن لم يظهر خيار شاشة التوقف في جهازك، راجع تعليمات ADB في ملف README المرفق مع المشروع."
            textSize = 16f
            setTextColor(Color.parseColor("#8DA0CC"))
            typeface = regularFont ?: Typeface.DEFAULT
            setPadding(0, padding, 0, padding)
        }

        val openSettingsButton = Button(this).apply {
            text = "فتح إعدادات شاشة التوقف"
            typeface = mediumFont ?: Typeface.DEFAULT
            setOnClickListener {
                openDreamSettings()
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070B1A"))
            setPadding(padding, padding * 3, padding, padding)
            addView(title)
            addView(subtitle)
            addView(openSettingsButton)
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun loadFont(assetFileName: String): Typeface? {
        return try {
            Typeface.createFromAsset(assets, "fonts/$assetFileName")
        } catch (e: Exception) {
            null
        }
    }

    private fun openDreamSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "تعذّر فتح إعدادات شاشة التوقف تلقائيًا على هذا الجهاز — افتحها يدويًا من الإعدادات، " +
                    "أو استخدم أوامر ADB الموجودة في README",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
