package fr.boubou.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
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
        try {
            val endpoint = URL("https://api.open-meteo.com/v1/forecast?latitude=43.667&longitude=4.032&hourly=temperature_2m&timezone=Europe%2FParis&forecast_days=5")
            val connection = (endpoint.openConnection() as HttpURLConnection).apply { connectTimeout = 12000; readTimeout = 12000 }
            val body = try {
                if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally { connection.disconnect() }
            val hourly = JSONObject(body).getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            val now = LocalDateTime.now(ZoneId.of("Europe/Paris"))
            val nightStart = if (now.hour < 9) now.toLocalDate().minusDays(1).atTime(18, 0) else now.toLocalDate().atTime(18, 0)
            val nightEnd = nightStart.plusHours(15)
            val horizon = now.plusHours(72)
            val tonight = mutableListOf<Double>()
            val nights = mutableListOf<Double>()
            val perNight = Array(3) { mutableListOf<Double>() }
            for (index in 0 until times.length()) {
                if (temps.isNull(index)) continue
                val hour = LocalDateTime.parse(times.getString(index))
                if (hour.isBefore(now) || !hour.isBefore(horizon)) continue
                val temp = temps.getDouble(index)
                if (!hour.isBefore(nightStart) && hour.isBefore(nightEnd)) tonight += temp
                if (hour.hour >= 18 || hour.hour < 9) {
                    nights += temp
                    val date = if (hour.hour < 9) hour.toLocalDate().minusDays(1) else hour.toLocalDate()
                    val indexNight = java.time.temporal.ChronoUnit.DAYS.between(nightStart.toLocalDate(), date).toInt()
                    if (indexNight in 0..2) perNight[indexNight] += temp
                }
            }
            if (tonight.isEmpty() || nights.isEmpty()) error("No night forecast")
            val low = tonight.minOrNull()!!
            val low72 = nights.minOrNull()!!
            val status = when {
                low <= 5 -> "🏠 RENTRE BOUBOU"
                low < 8 -> "⚠️ NUIT FRAÎCHE"
                low72 <= 5 -> "⚠️ FROID À VENIR"
                low72 < 8 -> "🌙 SURVEILLE BOUBOU"
                else -> "✓ BOUBOU DEHORS"
            }
            val color = when {
                low <= 5 -> Color.rgb(177,43,66)
                low < 8 || low72 < 8 -> Color.rgb(203,117,28)
                else -> Color.rgb(31,140,93)
            }
            views.setTextViewText(R.id.status, status)
            views.setTextViewText(R.id.temperature, "${format(low)} °C")
            listOf(R.id.night0, R.id.night1, R.id.night2).forEachIndexed { index, id ->
                val min = perNight[index].minOrNull()
                val label = listOf("Ce soir", "Demain", "J+2")[index]
                views.setTextViewText(id, "$label ${min?.let { format(it) + "°" } ?: "—"}")
                views.setTextColor(id, if (min == null) Color.WHITE else if (min <= 5) Color.rgb(255,117,130) else if (min < 8) Color.rgb(255,205,114) else Color.WHITE)
            }
            views.setTextColor(R.id.status, if (color == Color.rgb(31,140,93)) Color.rgb(132,226,176) else if (color == Color.rgb(203,117,28)) Color.rgb(255,205,114) else Color.rgb(255,117,130))
            views.setTextViewText(R.id.updated, "↻ " + java.time.ZonedDateTime.now(ZoneId.of("Europe/Paris")).format(DateTimeFormatter.ofPattern("HH:mm")))
        } catch (exception: Exception) {
            views.setTextViewText(R.id.status, "⚠️ MÉTÉO INDISPONIBLE")
            views.setTextColor(R.id.status, Color.rgb(255,205,114))
            views.setTextViewText(R.id.temperature, "— °C")
            views.setTextViewText(R.id.night0, "Ce soir —")
            views.setTextViewText(R.id.night1, "Demain —")
            views.setTextViewText(R.id.night2, "J+2 —")
            views.setTextViewText(R.id.updated, "Hors ligne")
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