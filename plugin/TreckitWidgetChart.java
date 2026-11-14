package app.treckit.mobile;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Breites Widget: Verlauf des Gesamtscores, Flamme, Fortschritt heute. */
public class TreckitWidgetChart extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) WidgetDraw.update(c, m, id, true); }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle o) { WidgetDraw.update(c, m, id, true); }
}
