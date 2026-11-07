package fr.boubou.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.widget.RemoteViews

class OrbitalWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.orbital_widget)
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.tiktok.com/@alerte.orbitale"))
            val pending = PendingIntent.getActivity(context, 200, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.orbital_root, pending)
            manager.updateAppWidget(id, views)
        }
    }
}
