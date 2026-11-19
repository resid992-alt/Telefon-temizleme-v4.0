package az.temiz

import android.app.ActivityManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.widget.RemoteViews
import android.widget.Toast
import java.util.Locale

class CleanWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_CLEAN = "az.temiz.ACTION_WIDGET_CLEAN"

        fun updateAllWidgets(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, CleanWidgetProvider::class.java))
                for (id in ids) {
                    updateWidget(context, manager, id)
                }
            } catch (_: Exception) {
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val usedPct = if (mi.totalMem > 0L) {
                (((mi.totalMem - mi.availMem) * 100L) / mi.totalMem).toInt().coerceIn(0, 100)
            } else {
                0
            }

            val sf = StatFs(Environment.getDataDirectory().path)
            val freeGb = sf.availableBytes / 1_000_000_000.0
            val statsText = String.format(Locale.US, "RAM: %d%% · Boş: %.1f GB", usedPct, freeGb)

            val views = RemoteViews(context.packageName, R.layout.widget_clean)
            views.setTextViewText(R.id.widget_stats, statsText)

            val openIntent = Intent(context, MainActivity::class.java)
            val openPi = PendingIntent.getActivity(
                context, 10, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPi)

            val cleanIntent = Intent(context, CleanWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_CLEAN
            }
            val cleanPi = PendingIntent.getBroadcast(
                context, 11, cleanIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_clean, cleanPi)

            manager.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_WIDGET_CLEAN) {
            val res = Cleaner.run(context)
            val mb = res.optLong("mb", 0L)
            val cnt = res.optInt("n", 0)
            Toast.makeText(
                context,
                "Təmiz Telefon: $cnt tətbiq dayandırıldı (~$mb MB RAM azad)",
                Toast.LENGTH_SHORT
            ).show()
            updateAllWidgets(context)
        }
    }
}
