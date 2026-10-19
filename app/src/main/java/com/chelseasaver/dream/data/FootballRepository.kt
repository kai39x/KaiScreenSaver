package com.chelseasaver.dream.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class TeamMatch(
    val opponent: String,
    val isHome: Boolean,
    val competition: String,
    val dateLabel: String,
    val timeLabel: String,
    // لحظة الانطلاق (epoch ms) — تستخدمها الصفحة لعرض "اليوم/بكرة" والوقت بتوقيت الرياض
    val kickoffMillis: Long = 0L
)

/**
 * يستخدم football-data.org API v4 (مجاني — 10 طلبات/دقيقة):
 *   GET /v4/teams/{id}/matches?status=SCHEDULED&limit=N
 *   ترويسة المصادقة: X-Auth-Token
 * التوثيق: https://docs.football-data.org/general/v4/
 */
object FootballRepository {

    private const val BASE_URL = "https://api.football-data.org/v4"

    fun fetchUpcomingMatches(teamId: Int, apiToken: String, limit: Int = 5): List<TeamMatch> {
        if (apiToken.isBlank()) return emptyList()

        val url = URL("$BASE_URL/teams/$teamId/matches?status=SCHEDULED&limit=$limit")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("X-Auth-Token", apiToken)
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return emptyList()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseMatches(body, teamId)
        } catch (e: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseMatches(body: String, myTeamId: Int): List<TeamMatch> {
        val result = mutableListOf<TeamMatch>()
        val matches: JSONArray = JSONObject(body).optJSONArray("matches") ?: return result

        val isoParse = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val riyadhDate = SimpleDateFormat("EEEE d MMMM", Locale("ar")).apply {
            timeZone = TimeZone.getTimeZone(ApiConfig.TIMEZONE)
        }
        val riyadhTime = SimpleDateFormat("hh:mm a", Locale("ar")).apply {
            timeZone = TimeZone.getTimeZone(ApiConfig.TIMEZONE)
        }

        for (i in 0 until matches.length()) {
            val match = matches.getJSONObject(i)
            val homeTeam = match.optJSONObject("homeTeam")
            val awayTeam = match.optJSONObject("awayTeam")
            val isHome = (homeTeam?.optInt("id") ?: -1) == myTeamId
            val opponent = if (isHome) {
                awayTeam?.optString("name").orEmpty()
            } else {
                homeTeam?.optString("name").orEmpty()
            }
            val competition = match.optJSONObject("competition")?.optString("name").orEmpty()

            var dateLabel = ""
            var timeLabel = ""
            var kickoffMillis = 0L
            try {
                val parsed = isoParse.parse(match.optString("utcDate"))
                if (parsed != null) {
                    dateLabel = riyadhDate.format(parsed)
                    timeLabel = riyadhTime.format(parsed)
                    kickoffMillis = parsed.time
                }
            } catch (e: Exception) {
                // تجاهل التنسيق إن تغيّر شكل التاريخ القادم من الـ API؛ يبقى السطر معروضًا بدون وقت دقيق
            }

            result.add(TeamMatch(opponent, isHome, competition, dateLabel, timeLabel, kickoffMillis))
        }
        return result
    }
}
