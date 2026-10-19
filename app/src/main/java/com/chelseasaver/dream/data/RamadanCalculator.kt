package com.chelseasaver.dream.data

import java.util.Calendar
import java.util.TimeZone

/**
 * يحسب الوقت المتبقي على أول أيام رمضان القادم بتوقيت أم القرى (المعتمد رسميًا في السعودية).
 *
 * التواريخ أدناه هي تواريخ 1 رمضان الرسمية المؤكدة من تقويم أم القرى:
 *   - 1447هـ → 18 فبراير 2026م
 *   - 1448هـ → 8 فبراير 2027م (مؤكد من عدة مصادر لتقويم أم القرى)
 *
 * بعد آخر تاريخ مؤكد في الجدول، يقدّر الكود تلقائيًا التاريخ التالي بإضافة طول سنة هجرية
 * تقريبي (354 يومًا)، وهو تقريب قد يختلف يومًا واحدًا عن الإعلان الرسمي لرؤية الهلال.
 * حدّث الجدول أدناه سنويًا للحصول على أعلى دقة (ابحث عن "تقويم أم القرى" + السنة الهجرية).
 */
object RamadanCalculator {

    private val CITY_TZ: TimeZone = TimeZone.getTimeZone(ApiConfig.TIMEZONE)

    private val knownRamadanStarts: List<Calendar> = listOf(
        makeDate(2026, Calendar.FEBRUARY, 18), // 1 رمضان 1447هـ
        makeDate(2027, Calendar.FEBRUARY, 8)   // 1 رمضان 1448هـ
    )

    private fun makeDate(year: Int, month: Int, day: Int): Calendar {
        val cal = Calendar.getInstance(CITY_TZ)
        cal.set(year, month, day, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    private fun nextRamadanStart(now: Calendar): Calendar {
        val fromTable = knownRamadanStarts.firstOrNull { it.after(now) }
        if (fromTable != null) return fromTable

        // تجاوزنا الجدول المعروف: نقدّر تقريبيًا بإضافة ~354 يومًا حتى نتجاوز اللحظة الحالية
        var estimate = knownRamadanStarts.last()
        while (!estimate.after(now)) {
            val next = estimate.clone() as Calendar
            next.add(Calendar.DAY_OF_YEAR, 354)
            estimate = next
        }
        return estimate
    }

    // targetMillis = لحظة بداية 1 رمضان (منتصف ليل الرياض) — تُدفع للصفحة لتعرض تاريخ البداية وتعدّ الأيام محليًا
    data class Countdown(val days: Long, val hours: Long, val minutes: Long, val targetMillis: Long)

    fun countdownToRamadan(): Countdown {
        val now = Calendar.getInstance(CITY_TZ)
        val target = nextRamadanStart(now)

        val diffMillis = (target.timeInMillis - now.timeInMillis).coerceAtLeast(0)
        val totalMinutes = diffMillis / 60_000L
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return Countdown(days, hours, minutes, target.timeInMillis)
    }
}
