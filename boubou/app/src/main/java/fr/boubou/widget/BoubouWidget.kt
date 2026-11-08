package fr.boubou.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.os.Build
import android.widget.RemoteViews
import androidx.work.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class BoubouWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        super.onUpdate(context, manager, ids)
        schedule(context)
        WeatherWorker.enqueue(context)
    }
    override fun onEnabled(context: Context) {
        schedule(context)
        WeatherWorker.enqueue(context)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) WeatherWorker.enqueue(context)
    }
    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<WeatherWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("boubou_weather", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    companion object {
        const val ACTION_REFRESH = "fr.boubou.widget.REFRESH"
        fun configureRefresh(context: Context, views: RemoteViews) {
            val intent = Intent(context, BoubouWidget::class.java).setAction(ACTION_REFRESH)
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.root, pending)
        }
    }
}

class WeatherWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val views = RemoteViews(context.packageName, R.layout.widget)
        BoubouWidget.configureRefresh(context, views)
        try { BoubouPhoto.bitmap()?.let { views.setImageViewBitmap(R.id.plant, it) } } catch (_: Exception) {}
        try {
            val endpoint = URL("https://api.open-meteo.com/v1/forecast?latitude=43.667&longitude=4.032&hourly=temperature_2m,apparent_temperature,precipitation_probability,wind_speed_10m,wind_gusts_10m,precipitation&timezone=Europe%2FParis&forecast_days=5")
            val connection = (endpoint.openConnection() as HttpURLConnection).apply { connectTimeout = 12000; readTimeout = 12000 }
            val body = try {
                if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally { connection.disconnect() }
            val hourly = JSONObject(body).getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val feels = hourly.getJSONArray("apparent_temperature")
            val rain = hourly.getJSONArray("precipitation_probability")
            val wind = hourly.getJSONArray("wind_speed_10m")
            val gusts = hourly.getJSONArray("wind_gusts_10m")
            val precipitation = hourly.getJSONArray("precipitation")
            val now = LocalDateTime.now(ZoneId.of("Europe/Paris"))
            val nightStart = if (now.hour < 9) now.toLocalDate().minusDays(1).atTime(18, 0) else now.toLocalDate().atTime(18, 0)
            val nightEnd = nightStart.plusHours(15)
            val horizon = nightStart.plusDays(5).plusHours(15)
            var currentTemp: Double? = null
            var nextCold: LocalDateTime? = null
            val tonight = mutableListOf<Double>()
            val nights = mutableListOf<Double>()
            val perNight = Array(5) { mutableListOf<Double>() }
            var maxWind = 0.0
            var maxGust = 0.0
            var maxRain = 0.0
            var rain24 = 0.0
            var rainProb24 = 0.0
            var maxGust24 = 0.0
            var maxTemp24 = Double.NEGATIVE_INFINITY
            var minTemp24 = Double.POSITIVE_INFINITY
            var minFeels = Double.POSITIVE_INFINITY
            for (index in 0 until times.length()) {
                if (temps.isNull(index)) continue
                val hour = LocalDateTime.parse(times.getString(index))
                if (currentTemp == null && !hour.isBefore(now)) currentTemp = temps.getDouble(index)
                if (!hour.isBefore(now) && hour.isBefore(now.plusHours(24))) {
                    if (!precipitation.isNull(index)) rain24 += precipitation.getDouble(index)
                    if (!rain.isNull(index)) rainProb24 = maxOf(rainProb24, rain.getDouble(index))
                    if (!gusts.isNull(index)) maxGust24 = maxOf(maxGust24, gusts.getDouble(index))
                    if (!temps.isNull(index)) { maxTemp24 = maxOf(maxTemp24, temps.getDouble(index)); minTemp24 = minOf(minTemp24, temps.getDouble(index)) }
                }
                if (hour.isBefore(now) || !hour.isBefore(horizon)) continue
                val temp = temps.getDouble(index)
                if (nextCold == null && temp <= 5) nextCold = hour
                if (!hour.isBefore(nightStart) && hour.isBefore(nightEnd)) tonight += temp
                if (hour.hour >= 18 || hour.hour < 9) {
                    nights += temp
                    val date = if (hour.hour < 9) hour.toLocalDate().minusDays(1) else hour.toLocalDate()
                    val indexNight = java.time.temporal.ChronoUnit.DAYS.between(nightStart.toLocalDate(), date).toInt()
                    if (indexNight in 0..4) perNight[indexNight] += temp
                    if (indexNight == 0) {
                        if (!wind.isNull(index)) maxWind = maxOf(maxWind, wind.getDouble(index))
                        if (!gusts.isNull(index)) maxGust = maxOf(maxGust, gusts.getDouble(index))
                        if (!rain.isNull(index)) maxRain = maxOf(maxRain, rain.getDouble(index))
                        if (!feels.isNull(index)) minFeels = minOf(minFeels, feels.getDouble(index))
                    }
                }
            }
            if (tonight.isEmpty() || nights.isEmpty()) error("No night forecast")
            val low = tonight.minOrNull()!!
            val low72 = perNight.take(3).flatMap { it }.minOrNull() ?: low
            val low5 = perNight.flatMap { it }.minOrNull() ?: low
            val status = when {
                low <= 5 -> "🏠 RENTRE BOUBOU CE SOIR"
                low < 8 -> "⚠️ NUIT FRAÎCHE"
                low5 <= 5 -> "⚠️ FROID À VENIR"
                low5 < 8 -> "🌙 SURVEILLE BOUBOU"
                else -> "✓ BOUBOU DEHORS"
            }
            val color = when {
                low <= 5 -> Color.rgb(177,43,66)
                low < 8 || low5 < 8 -> Color.rgb(203,117,28)
                else -> Color.rgb(31,140,93)
            }
            val prefs = context.getSharedPreferences("boubou", Context.MODE_PRIVATE)
            val forecasts = perNight.mapIndexed { i, list ->
                val label = if (i == 0) "Cette nuit" else "Nuit J+$i"
                "$label : " + (list.minOrNull()?.let { format(it) + " °C" } ?: "indisponible")
            }.joinToString("\\n")
            val conditions = "Ressenti indicatif : " + (if(minFeels.isFinite()) format(minFeels) + " °C" else "—") +
                "  • Vent max : " + format(maxWind) + " km/h  • Rafales : " + format(maxGust) +
                " km/h  • Probabilité de pluie max : " + format(maxRain) + " %"
            val previousStatus = prefs.getString("last_status", "")
            val previousWarning = prefs.getBoolean("last_warning", false)
            val shouldWarn = low <= 5 || low5 <= 5 || maxGust >= 65
            prefs.edit().putLong("updated", System.currentTimeMillis())
                .putString("summary", status + " · " + format(low) + " °C cette nuit")
                .putString("forecast", forecasts)
                .putString("conditions", conditions)
                .putString("last_status", status)
                .putBoolean("last_warning", shouldWarn).apply()
            if (shouldWarn && (!previousWarning || previousStatus != status)) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val channel = "boubou_froid"
                if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channel, "Alertes Boubou", NotificationManager.IMPORTANCE_HIGH))
                val message = if (low <= 5) "Nuit à " + format(low) + " °C : rentre Boubou." else if (low5 <= 5) "Froid prévu dans les cinq prochaines nuits : " + format(low5) + " °C." else "Rafales fortes prévues : protège le pot."
                val notification = Notification.Builder(context, channel).setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("🌺 Alerte Boubou").setContentText(message).setAutoCancel(true).build()
                try { manager.notify(3301, notification) } catch (_: SecurityException) {}
                val old = prefs.getString("history", "") ?: ""
                prefs.edit().putString("history", "• " + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm")) + " — " + message + "\\n" + old.take(1800)).apply()
            }
            val hoursToCold = nextCold?.let { java.time.Duration.between(now, it).toHours().coerceAtLeast(0) }
            val advice = when {
                low <= 5 -> "Froid cette nuit : rentre Boubou avant le soir"
                low5 <= 5 -> "Froid dans " + (hoursToCold?.toString() ?: "?") + " h : prépare sa protection"
                maxGust >= 65 -> "Rafales fortes : place le pot à l'abri"
                low < 8 -> "Nuit fraîche : surveille l'exposition"
                else -> "Conditions favorables : Boubou peut rester dehors"
            }
            // Two-hour blocks, each colored for the worst condition in that interval.
            val risk = IntArray(12)
            val hourlyTemps = arrayOfNulls<Double>(12)
            for (i in 0 until times.length()) {
                val hour = LocalDateTime.parse(times.getString(i))
                val slot = (java.time.Duration.between(now, hour).toMinutes() / 120).toInt()
                if (hour.isBefore(now) || slot !in 0..11 || temps.isNull(i)) continue
                val t = temps.getDouble(i)
                if (hourlyTemps[slot] == null) hourlyTemps[slot] = t
                val g = if (gusts.isNull(i)) 0.0 else gusts.getDouble(i)
                val mm = if (precipitation.isNull(i)) 0.0 else precipitation.getDouble(i)
                val probability = if (rain.isNull(i)) 0.0 else rain.getDouble(i)
                val level = when {
                    t <= 5 || g >= 65 || mm >= 8 -> 2
                    t < 8 || g >= 45 || mm >= 3 || (mm >= 1.5 && probability >= 70) -> 1
                    else -> 0
                }
                risk[slot] = maxOf(risk[slot], level)
            }
            val riskIds = intArrayOf(R.id.hour0, R.id.hour1, R.id.hour2, R.id.hour3, R.id.hour4, R.id.hour5, R.id.hour6, R.id.hour7, R.id.hour8, R.id.hour9, R.id.hour10, R.id.hour11)
            riskIds.forEachIndexed { index, id ->
                val hour = now.plusHours((index * 2).toLong())
                val hourLabelIds = intArrayOf(R.id.hourLabel0, R.id.hourLabel1, R.id.hourLabel2, R.id.hourLabel3, R.id.hourLabel4, R.id.hourLabel5, R.id.hourLabel6, R.id.hourLabel7, R.id.hourLabel8, R.id.hourLabel9, R.id.hourLabel10, R.id.hourLabel11)
                val hourTempIds = intArrayOf(R.id.hourTemp0, R.id.hourTemp1, R.id.hourTemp2, R.id.hourTemp3, R.id.hourTemp4, R.id.hourTemp5, R.id.hourTemp6, R.id.hourTemp7, R.id.hourTemp8, R.id.hourTemp9, R.id.hourTemp10, R.id.hourTemp11)
                views.setTextViewText(hourLabelIds[index], if (index == 0) "Mnt" else hour.format(DateTimeFormatter.ofPattern("HH")) + "h")
                views.setTextViewText(hourTempIds[index], hourlyTemps[index]?.let { format(it) + "°" } ?: "—")
                views.setInt(id, "setBackgroundColor", when(risk[index]) {
                    2 -> Color.rgb(226, 74, 88)
                    1 -> Color.rgb(244, 170, 67)
                    else -> Color.rgb(71, 201, 143)
                })
            }
            val firstRisk = risk.indexOfFirst { it == 2 }
            val firstCaution = risk.indexOfFirst { it == 1 }
            val timelineMessage = when {
                firstRisk >= 0 -> "À ABRITER vers " + now.plusHours((firstRisk * 2).toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
                firstCaution >= 0 -> "À SURVEILLER vers " + now.plusHours((firstCaution * 2).toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
                else -> "DEHORS · 24 H FAVORABLES"
            }
            views.setTextViewText(R.id.timeline_label, "24 H · " + timelineMessage)
            views.setTextViewText(R.id.advice, advice)
            views.setTextViewText(R.id.current, "Actuellement " + (currentTemp?.let { format(it) + " °C" } ?: "—"))
            val hourNow = now.hour
            val background = when {
                low <= 5 -> Color.rgb(92, 29, 47)
                low5 <= 5 || low < 8 -> Color.rgb(61, 39, 93)
                hourNow < 7 || hourNow >= 19 -> Color.rgb(12, 25, 47)
                else -> Color.rgb(24, 56, 78)
            }
            // Keep the rounded premium gradient background instead of overriding it.
            views.setTextViewText(R.id.status, status)
            views.setTextViewText(R.id.temperature, "${format(low)} °C")
            views.setTextViewText(R.id.rain_detail, "${format(rain24)} mm")
            views.setTextViewText(R.id.wind_detail, "${format(maxGust24)} km/h")
            views.setTextViewText(R.id.temp_detail, if (maxTemp24.isFinite() && minTemp24.isFinite()) "${format(minTemp24)}–${format(maxTemp24)}°" else "—")
            views.setTextViewText(R.id.weather, "Vent ${format(maxWind)} · Rafales ${format(maxGust)} km/h · Pluie ${format(maxRain)} %")
            listOf(R.id.night0, R.id.night1, R.id.night2, R.id.night3, R.id.night4).forEachIndexed { index, id ->
                val min = perNight[index].minOrNull()
                val label = nightStart.plusDays(index.toLong()).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.FRANCE).replaceFirstChar { it.uppercase() }
                views.setTextViewText(id, "$label\n${min?.let { format(it) + "°" } ?: "—"}")
                views.setTextColor(id, if (min == null) Color.WHITE else if (min <= 5) Color.rgb(255,117,130) else if (min < 8) Color.rgb(255,205,114) else Color.WHITE)
            }
            views.setColorStateList(R.id.status, "setBackgroundTintList", ColorStateList.valueOf(color))
            views.setTextColor(R.id.status, Color.WHITE)
            views.setTextViewText(R.id.updated, "↻ " + java.time.ZonedDateTime.now(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("HH:mm")))
        } catch (exception: Exception) {
            views.setTextViewText(R.id.status, "⚠️ MÉTÉO INDISPONIBLE")
            views.setTextColor(R.id.status, Color.rgb(255,205,114))
            views.setTextViewText(R.id.temperature, "— °C")
            views.setTextViewText(R.id.current, "Actuellement —")
            views.setTextViewText(R.id.advice, "Données indisponibles · touche pour réessayer")
            views.setTextViewText(R.id.night0, "—\n—")
            views.setTextViewText(R.id.night1, "—\n—")
            views.setTextViewText(R.id.night2, "—\n—")
            views.setTextViewText(R.id.night3, "—\n—")
            views.setTextViewText(R.id.night4, "—\n—")
            views.setTextViewText(R.id.weather, "Météo indisponible")
            views.setTextViewText(R.id.rain_detail, "—")
            views.setTextViewText(R.id.wind_detail, "—")
            views.setTextViewText(R.id.temp_detail, "—")
            views.setTextViewText(R.id.updated, "Hors ligne")
            views.setTextViewText(R.id.timeline_label, "24 H · PRÉVISIONS INDISPONIBLES")
        }
        AppWidgetManager.getInstance(context).updateAppWidget(ComponentName(context, BoubouWidget::class.java), views)
        return Result.success()
    }
    private fun format(temp: Double) = String.format(java.util.Locale.FRANCE, "%.0f", temp)
    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WeatherWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("boubou_refresh", ExistingWorkPolicy.REPLACE, request)
        }
    }
}