package com.craneai.findr.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.craneai.findr.R

/** The home-screen search bar. It shows no results itself; a tap anywhere on it opens the quick search panel. */
class QuickSearchWidget : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
    val open = PendingIntent.getActivity(
      context,
      0,
      QuickSearchActivity.intent(context, QuickSearchActivity.SOURCE_WIDGET),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    for (id in appWidgetIds) {
      val views = RemoteViews(context.packageName, R.layout.widget_quick_search)
      views.setOnClickPendingIntent(R.id.widget_root, open)
      manager.updateAppWidget(id, views)
    }
  }
}
