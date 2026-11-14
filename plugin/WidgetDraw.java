package app.treckit.mobile;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Zeichnet die Track.it-Widgets (schwarz, feine weiße Linien) als Bild. */
public class WidgetDraw {
    static final String PREFS = "treckit_widget";
    static final int INK = 0xFFF5F5F7;

    static JSONObject data(Context c) {
        try {
            SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONObject(sp.getString("data", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m.getAppWidgetIds(new ComponentName(c, TreckitWidgetFlame.class))) update(c, m, id, false);
        for (int id : m.getAppWidgetIds(new ComponentName(c, TreckitWidgetChart.class))) update(c, m, id, true);
    }

    static void update(Context c, AppWidgetManager m, int id, boolean chart) {
        Bundle o = m.getAppWidgetOptions(id);
        float dens = c.getResources().getDisplayMetrics().density;
        int wDp = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) : 0;
        int hDp = o != null ? o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) : 0;
        if (wDp <= 0) wDp = chart ? 250 : 110;
        if (hDp <= 0) hDp = chart ? 110 : 110;
        float s = Math.min(dens, 2.5f);
        int w = Math.max(80, Math.min(700, (int) (wDp * s))), h = Math.max(80, Math.min(500, (int) (hDp * s)));
        Bitmap b = chart ? drawChart(data(c), w, h, s) : drawFlame(data(c), w, h, s);
        RemoteViews rv = new RemoteViews(c.getPackageName(), c.getResources().getIdentifier("treckit_widget", "layout", c.getPackageName()));
        int img = c.getResources().getIdentifier("widget_img", "id", c.getPackageName());
        rv.setImageViewBitmap(img, b);
        Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (i != null) {
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            rv.setOnClickPendingIntent(img, PendingIntent.getActivity(c, chart ? 2 : 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        m.updateAppWidget(id, rv);
    }

    static Paint paint(int color, float size, boolean light) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(Typeface.create(light ? "sans-serif-light" : "sans-serif", Typeface.NORMAL));
        return p;
    }

    static Bitmap base(int w, int h, float s) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF000000);
        cv.drawRoundRect(new RectF(0, 0, w, h), 22 * s, 22 * s, bg);
        Paint br = new Paint(Paint.ANTI_ALIAS_FLAG);
        br.setStyle(Paint.Style.STROKE);
        br.setStrokeWidth(Math.max(1, 0.6f * s));
        br.setColor(0x24FFFFFF);
        cv.drawRoundRect(new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), 22 * s, 22 * s, br);
        return b;
    }

    /** Flamme aus der App, Größe sz, oben links bei x/y */
    static void flame(Canvas cv, float x, float y, float sz, boolean on) {
        float k = sz / 72f;
        Path o = new Path();
        o.moveTo(32, 4);
        o.cubicTo(36, 18, 52, 28, 52, 48);
        o.cubicTo(52, 59, 43, 68, 32, 68);
        o.cubicTo(21, 68, 12, 59, 12, 48);
        o.cubicTo(12, 39, 16, 33, 21, 28);
        o.cubicTo(22, 35, 25, 39, 29, 40);
        o.cubicTo(27, 28, 26, 15, 32, 4);
        o.close();
        Path in = new Path();
        in.moveTo(32, 40);
        in.cubicTo(34, 46, 41, 49, 41, 56);
        in.cubicTo(41, 61, 37, 65, 32, 65);
        in.cubicTo(27, 65, 23, 61, 23, 56);
        in.cubicTo(23, 52, 25, 49, 27, 47);
        in.cubicTo(28, 50, 29, 51, 31, 52);
        in.cubicTo(30, 47, 30, 43, 32, 40);
        in.close();
        cv.save();
        cv.translate(x, y);
        cv.scale(k, k);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(1.4f);
        p.setColor(on ? 0xCCF5F5F7 : 0x55F5F5F7);
        cv.drawPath(o, p);
        p.setStrokeWidth(1.1f);
        p.setColor(on ? 0x99F5F5F7 : 0x33F5F5F7);
        cv.drawPath(in, p);
        cv.restore();
    }

    static int[] todayProgress(JSONObject d) {
        boolean same = today().equals(d.optString("day"));
        return new int[] { same ? d.optInt("done", 0) : 0, d.optInt("total", 0) };
    }

    static void progressBar(Canvas cv, float x, float y, float w, float s, int done, int total) {
        Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setStrokeWidth(Math.max(1, 1.2f * s));
        t.setStrokeCap(Paint.Cap.ROUND);
        t.setColor(0x26FFFFFF);
        cv.drawLine(x, y, x + w, y, t);
        if (total > 0 && done > 0) {
            t.setColor(INK);
            cv.drawLine(x, y, x + w * Math.min(1f, done / (float) total), y, t);
        }
    }

    static Bitmap drawFlame(JSONObject d, int w, int h, float s) {
        Bitmap b = base(w, h, s);
        Canvas cv = new Canvas(b);
        int streak = d.optInt("streak", 0);
        int[] pr = todayProgress(d);
        float fs = Math.min(w, h) * 0.34f;
        flame(cv, w / 2f - fs * 0.45f, h * 0.1f, fs, streak > 0);
        Paint num = paint(INK, Math.min(w, h) * 0.26f, true);
        num.setTextAlign(Paint.Align.CENTER);
        cv.drawText(String.valueOf(streak), w / 2f, h * 0.1f + fs + num.getTextSize() * 0.95f, num);
        Paint lab = paint(0x80F5F5F7, Math.max(9 * s, Math.min(w, h) * 0.075f), false);
        lab.setTextAlign(Paint.Align.CENTER);
        lab.setLetterSpacing(0.14f);
        String txt = pr[1] > 0 ? (pr[0] >= pr[1] ? "HEUTE ERLEDIGT" : "HEUTE " + pr[0] + "/" + pr[1]) : "TAGE IN FOLGE";
        float ty = h - h * 0.14f;
        cv.drawText(txt, w / 2f, ty - 8 * s, lab);
        progressBar(cv, w * 0.22f, ty, w * 0.56f, s, pr[0], pr[1]);
        return b;
    }

    static Bitmap drawChart(JSONObject d, int w, int h, float s) {
        Bitmap b = base(w, h, s);
        Canvas cv = new Canvas(b);
        float pad = 14 * s;
        int streak = d.optInt("streak", 0);
        int[] pr = todayProgress(d);
        Paint title = paint(0x80F5F5F7, 10 * s, false);
        title.setLetterSpacing(0.16f);
        cv.drawText("TRACK.IT", pad, pad + 10 * s, title);
        Paint big = paint(INK, Math.min(30 * s, h * 0.26f), true);
        String sc = d.has("score") && !d.isNull("score") ? String.valueOf(d.optInt("score")) : "–";
        cv.drawText(sc, pad, pad + 10 * s + big.getTextSize() + 4 * s, big);
        Paint small = paint(0x80F5F5F7, 10 * s, false);
        cv.drawText("\u00D8 Score 30 Tage", pad, pad + 10 * s + big.getTextSize() + 18 * s, small);
        // Flamme und Tage rechts oben
        float fs = 22 * s;
        Paint n2 = paint(INK, 18 * s, true);
        n2.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(String.valueOf(streak), w - pad, pad + 16 * s, n2);
        flame(cv, w - pad - n2.measureText(String.valueOf(streak)) - fs - 2 * s, pad - 2 * s, fs, streak > 0);
        Paint st = paint(0x80F5F5F7, 10 * s, false);
        st.setTextAlign(Paint.Align.RIGHT);
        st.setLetterSpacing(0.1f);
        cv.drawText(pr[1] > 0 ? (pr[0] >= pr[1] ? "HEUTE ERLEDIGT" : "HEUTE " + pr[0] + "/" + pr[1]) : "", w - pad, pad + 32 * s, st);
        // Verlauf
        JSONArray a = d.optJSONArray("scores");
        float gx = w * 0.42f, gy = pad + 40 * s, gw = w - gx - pad, gh = h - gy - pad;
        if (w < 200 * s) { gx = pad; gw = w - 2 * pad; gy = h * 0.55f; gh = h - gy - pad; }
        if (a != null && a.length() > 1 && gh > 10) {
            int n = a.length();
            Path line = new Path(), fill = new Path();
            boolean started = false;
            float lastX = gx, firstX = gx;
            for (int i = 0; i < n; i++) {
                if (a.isNull(i)) continue;
                double v = a.optDouble(i, -1);
                if (v < 0) continue;
                float x = gx + gw * i / (n - 1f), y = gy + gh * (1f - (float) Math.max(0, Math.min(100, v)) / 100f);
                if (!started) { line.moveTo(x, y); fill.moveTo(x, gy + gh); fill.lineTo(x, y); started = true; firstX = x; }
                else { line.lineTo(x, y); fill.lineTo(x, y); }
                lastX = x;
            }
            if (started) {
                fill.lineTo(lastX, gy + gh);
                fill.close();
                Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
                f.setShader(new LinearGradient(0, gy, 0, gy + gh, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
                cv.drawPath(fill, f);
                Paint l = new Paint(Paint.ANTI_ALIAS_FLAG);
                l.setStyle(Paint.Style.STROKE);
                l.setStrokeWidth(1.4f * s);
                l.setStrokeJoin(Paint.Join.ROUND);
                l.setColor(INK);
                cv.drawPath(line, l);
            }
            Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
            base.setColor(0x1FFFFFFF);
            base.setStrokeWidth(Math.max(1, 0.6f * s));
            cv.drawLine(gx, gy + gh, gx + gw, gy + gh, base);
        }
        progressBar(cv, pad, h - pad, w * 0.42f - 2 * pad, s, pr[0], pr[1]);
        return b;
    }
}
