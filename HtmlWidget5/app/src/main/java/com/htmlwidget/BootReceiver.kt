package com.htmlwidget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, HtmlWidgetProvider::class.java))
        ids.forEach { id ->
            HtmlWidgetProvider.update(ctx, mgr, id)
            HtmlWidgetProvider.scheduleAlarm(ctx, id)
        }
    }
}
