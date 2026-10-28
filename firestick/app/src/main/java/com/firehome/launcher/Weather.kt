package com.firehome.launcher

import android.content.Context

/** Current temperature for the user's approximate location (IP-based), cached for 30 minutes. */
object Weather {
    class Now(val tempF: Int, val icon: String, val place: String)

    private var cached: Now? = null
    private var cachedAt = 0L

    fun load(ctx: Context, done: (Now?) -> Unit) {
        cached?.takeIf { System.currentTimeMillis() - cachedAt < 30 * 60_000 }?.let { done(it); return }
        Net.background({ fetch(ctx) }) { now ->
            if (now != null) {
                cached = now
                cachedAt = System.currentTimeMillis()
            }
            done(now ?: cached)
        }
    }

    private fun fetch(ctx: Context): Now {
        val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
        // Location rarely changes; look it up once a day.
        if (System.currentTimeMillis() - prefs.getLong("locAt", 0) > 24 * 3600_000) {
            locate()?.let { (lat, lon, place) ->
                prefs.edit()
                    .putFloat("lat", lat.toFloat())
                    .putFloat("lon", lon.toFloat())
                    .putString("place", place)
                    .putLong("locAt", System.currentTimeMillis())
                    .apply()
            }
        }
        val lat = prefs.getFloat("lat", Float.NaN)
        val lon = prefs.getFloat("lon", Float.NaN)
        if (lat.isNaN()) throw IllegalStateException("no location")
        val w = Net.getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code,is_day&temperature_unit=fahrenheit"
        ).getJSONObject("current")
        return Now(
            tempF = Math.round(w.getDouble("temperature_2m")).toInt(),
            icon = icon(w.getInt("weather_code"), w.optInt("is_day", 1) == 1),
            place = prefs.getString("place", "")!!,
        )
    }

    /**
     * Approximate location from the public IP, over HTTPS only (no key needed): ipwho.is, else GeoJS.
     * Latitude, longitude and the region (or city) name; null when neither answers.
     */
    private fun locate(): Triple<Double, Double, String>? {
        try {
            val j = Net.getJson("https://ipwho.is/?fields=success,latitude,longitude,city,region")
            if (j.optBoolean("success") && j.has("latitude")) return Triple(j.getDouble("latitude"), j.getDouble("longitude"), j.optString("region").ifEmpty { j.optString("city") })
        } catch (e: Exception) {}
        try {
            val j = Net.getJson("https://get.geojs.io/v1/ip/geo.json")
            val lat = j.optString("latitude").toDoubleOrNull()
            val lon = j.optString("longitude").toDoubleOrNull()
            if (lat != null && lon != null) return Triple(lat, lon, j.optString("region").ifEmpty { j.optString("city") })
        } catch (e: Exception) {}
        return null
    }

    class Hour(val label: String, val tempF: Int, val icon: String)
    class Day(val label: String, val highF: Int, val lowF: Int, val icon: String, val rainPct: Int, val summary: String)
    class Forecast(val place: String, val nowF: Int, val nowIcon: String, val nowSummary: String, val hours: List<Hour>, val days: List<Day>)

    /** Hourly + 5-day forecast for the weather page (fetched on demand, not cached). */
    fun forecast(ctx: Context, done: (Forecast?) -> Unit) {
        Net.background({
            val prefs = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)
            val lat = prefs.getFloat("lat", Float.NaN)
            val lon = prefs.getFloat("lon", Float.NaN)
            if (lat.isNaN()) throw IllegalStateException("no location")
            val j = Net.getJson(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&temperature_unit=fahrenheit&timezone=auto" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&hourly=temperature_2m,weather_code,is_day&forecast_hours=13" +
                    "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max&forecast_days=6"
            )
            val cur = j.getJSONObject("current")
            val h = j.getJSONObject("hourly")
            val hTimes = h.getJSONArray("time"); val hTemps = h.getJSONArray("temperature_2m")
            val hCodes = h.getJSONArray("weather_code"); val hDay = h.getJSONArray("is_day")
            val hours = (1 until minOf(13, hTimes.length())).map { i ->
                val hour = hTimes.getString(i).substring(11, 13).toInt()
                val label = if (DreamTime.use24h()) "%02d:00".format(hour)
                    else when { hour == 0 -> "12 AM"; hour < 12 -> "$hour AM"; hour == 12 -> "12 PM"; else -> "${hour - 12} PM" }
                Hour(label, Math.round(hTemps.getDouble(i)).toInt(), icon(hCodes.getInt(i), hDay.optInt(i, 1) == 1))
            }
            val d = j.getJSONObject("daily")
            val dTimes = d.getJSONArray("time")
            val days = (0 until minOf(6, dTimes.length())).map { i ->
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(dTimes.getString(i))!!
                val label = if (i == 0) "Today" else java.text.SimpleDateFormat("EEEE", java.util.Locale.US).format(date)
                val code = d.getJSONArray("weather_code").getInt(i)
                Day(label, Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                    Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                    icon(code, true), d.getJSONArray("precipitation_probability_max").optInt(i, 0), summary(code))
            }
            Forecast(prefs.getString("place", "")!!, Math.round(cur.getDouble("temperature_2m")).toInt(),
                icon(cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1), summary(cur.getInt("weather_code")), hours, days)
        }, done)
    }

    private fun summary(code: Int) = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67, in 80..82 -> "Rain"
        in 71..77, 85, 86 -> "Snow"
        in 95..99 -> "Thunderstorms"
        else -> "Cloudy"
    }

    // WMO weather codes, as used by open-meteo.
    private fun icon(code: Int, day: Boolean) = when (code) {
        0 -> if (day) "☀" else "☾"
        1, 2 -> if (day) "⛅" else "☁"
        3 -> "☁"
        45, 48 -> "🌫"
        in 51..67, in 80..82 -> "🌧"
        in 71..77, 85, 86 -> "❄"
        in 95..99 -> "⛈"
        else -> "☁"
    }
}
