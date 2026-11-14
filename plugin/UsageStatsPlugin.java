package app.treckit.mobile;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Liest Bildschirmzeit, Zeit pro App und Entsperrungen aus Android (nur lesen, nur auf diesem Gerät). */
@CapacitorPlugin(name = "UsageStats")
public class UsageStatsPlugin extends Plugin {

    /** Ergebnis für einen Tag. */
    private static class Day {
        String date;
        long total;
        int unlocks;
        Map<String, Long> apps = new HashMap<>();
        long dayStart;
        long[] hours = new long[24];
        Map<String, long[]> appHours = new HashMap<>();
    }

    private boolean granted() {
        Context c = getContext();
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    @PluginMethod
    public void hasAccess(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", granted());
        r.put("version", 5);
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    private Set<String> ignored() {
        Set<String> s = new HashSet<>();
        s.add("com.android.systemui");
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getContext().getPackageManager().queryIntentActivities(home, 0)) {
                if (ri.activityInfo != null) s.add(ri.activityInfo.packageName);
            }
        } catch (Exception e) {
            // ohne Liste der Startbildschirm-Apps zählen sie eben mit
        }
        return s;
    }

    private static long overlap(long s, long e, long a, long b) {
        long x = Math.max(s, a), y = Math.min(e, b);
        return y > x ? y - x : 0;
    }

    /** Zählt einen Tag aus: Gesamtzeit, Zeit pro App, Entsperrungen. */
    private Day countDay(UsageStatsManager usm, Set<String> skip, int daysBack, long now) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, -daysBack);
        long start = cal.getTimeInMillis();
        Day day = new Day();
        day.dayStart = start;
        day.date = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        cal.add(Calendar.DAY_OF_YEAR, 1);
        long end = Math.min(cal.getTimeInMillis(), now);
        if (end <= start) return day;
        // 6 Stunden Vorlauf, damit Apps, die über Mitternacht offen waren, richtig gezählt werden
        UsageEvents ev = usm.queryEvents(start - 6L * 3600000L, end);
        UsageEvents.Event e = new UsageEvents.Event();
        // Immer nur eine App ist im Vordergrund: eine neue App beendet die alte, Bildschirm aus beendet alles.
        // Startbildschirm und Systemleiste beenden die Zeit der vorigen App, zählen selbst aber nicht.
        String cur = null, curCls = null;
        long curStart = 0;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            String pkg = e.getPackageName();
            String cls = e.getClassName();
            long ts = e.getTimeStamp();
            if (t == 18) { if (ts >= start) day.unlocks++; continue; } // KEYGUARD_HIDDEN = Bildschirm entsperrt
            if (t == 16 || t == 26) { // Bildschirm aus oder Handy ausgeschaltet
                if (cur != null) addSpan(day, skip, cur, curStart, ts, start, end);
                cur = null; curCls = null;
                continue;
            }
            if (pkg == null) continue;
            if (t == 1) { // ACTIVITY_RESUMED
                if (pkg.equals(cur)) { curCls = cls; continue; }
                if (cur != null) addSpan(day, skip, cur, curStart, ts, start, end);
                cur = pkg; curCls = cls; curStart = ts;
            } else if (t == 2 || t == 23) { // ACTIVITY_PAUSED / ACTIVITY_STOPPED
                if (cur != null && pkg.equals(cur) && (curCls == null || cls == null || cls.equals(curCls))) {
                    addSpan(day, skip, cur, curStart, ts, start, end);
                    cur = null; curCls = null;
                }
            }
        }
        if (cur != null) addSpan(day, skip, cur, curStart, end, start, end);
        return day;
    }

    /** Zählt die Zeit von s bis e (auf den Tag begrenzt) und verteilt sie auf die Stunden des Tages. */
    private static void addSpan(Day day, Set<String> skip, String pkg, long s, long e, long start, long end) {
        if (skip.contains(pkg)) return;
        long x = Math.max(s, start), y = Math.min(e, end);
        if (y <= x) return;
        add(day, pkg, y - x);
        long[] ah = day.appHours.get(pkg);
        if (ah == null) { ah = new long[24]; day.appHours.put(pkg, ah); }
        while (x < y) {
            int h = (int) ((x - day.dayStart) / 3600000L);
            if (h < 0) h = 0;
            if (h > 23) h = 23;
            long next = Math.min(y, day.dayStart + (h + 1) * 3600000L);
            if (h == 23) next = y;
            long part = next - x;
            day.hours[h] += part;
            ah[h] += part;
            x = next;
        }
    }

    private static JSArray hourMinutes(long[] hs) {
        JSArray a = new JSArray();
        for (long v : hs) {
            try { a.put(Math.round(v / 6000.0) / 10.0); } catch (org.json.JSONException ex) { a.put(0); }
        }
        return a;
    }

    private static void add(Day day, String pkg, long ms) {
        if (ms <= 0) return;
        day.total += ms;
        Long old = day.apps.get(pkg);
        day.apps.put(pkg, (old == null ? 0 : old) + ms);
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l == null ? pkg : l.toString();
        } catch (Exception ex) {
            return pkg;
        }
    }

    private int days(PluginCall call) {
        int days = call.getInt("days", 7);
        if (days < 1) days = 1;
        if (days > 10) days = 10; // Android hält Einzelereignisse nur wenige Tage vor
        return days;
    }

    @PluginMethod
    public void getDaily(PluginCall call) {
        if (!granted()) { call.reject("no_access"); return; }
        int days = days(call);
        try {
            UsageStatsManager usm = (UsageStatsManager) getContext().getSystemService(Context.USAGE_STATS_SERVICE);
            Set<String> skip = ignored();
            long now = System.currentTimeMillis();
            JSArray out = new JSArray();
            for (int d = 0; d < days; d++) {
                Day day = countDay(usm, skip, d, now);
                JSObject o = new JSObject();
                o.put("date", day.date);
                o.put("minutes", Math.round(day.total / 60000.0));
                o.put("unlocks", day.unlocks);
                JSObject apps = new JSObject();
                for (Map.Entry<String, Long> en : day.apps.entrySet()) {
                    double min = Math.round(en.getValue() / 6000.0) / 10.0; // Minuten mit einer Nachkommastelle
                    if (min > 0) apps.put(en.getKey(), min);
                }
                o.put("apps", apps);
                o.put("hours", hourMinutes(day.hours));
                JSObject ah = new JSObject();
                for (Map.Entry<String, long[]> en : day.appHours.entrySet()) {
                    Long tot = day.apps.get(en.getKey());
                    if (tot != null && tot >= 30000L) ah.put(en.getKey(), hourMinutes(en.getValue()));
                }
                o.put("appHours", ah);
                out.put(o);
            }
            JSObject r = new JSObject();
            r.put("days", out);
            call.resolve(r);
        } catch (Exception ex) {
            call.reject("read_failed");
        }
    }

    /** App-Symbole als kleine PNG-Bilder (Base64), damit die App-Liste die echten Icons zeigen kann. */
    @PluginMethod
    public void getIcons(PluginCall call) {
        JSObject icons = new JSObject();
        try {
            JSArray pkgs = call.getArray("pkgs");
            PackageManager pm = getContext().getPackageManager();
            int n = pkgs == null ? 0 : Math.min(pkgs.length(), 120);
            for (int i = 0; i < n; i++) {
                String p = pkgs.optString(i, "");
                if (p.isEmpty()) continue;
                try {
                    android.graphics.drawable.Drawable d = pm.getApplicationIcon(p);
                    android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888);
                    android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
                    d.setBounds(0, 0, 96, 96);
                    d.draw(cv);
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                    icons.put(p, "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP));
                    bmp.recycle();
                } catch (Exception ex) {
                    // App nicht (mehr) installiert: ohne Symbol weiter
                }
            }
        } catch (Exception ex) {
            // leere Liste zurückgeben
        }
        JSObject r = new JSObject();
        r.put("icons", icons);
        call.resolve(r);
    }

    /** Liste der Apps, die in den letzten Tagen benutzt wurden, mit Namen, meistgenutzte zuerst. */
    @PluginMethod
    public void listApps(PluginCall call) {
        if (!granted()) { call.reject("no_access"); return; }
        int days = days(call);
        try {
            UsageStatsManager usm = (UsageStatsManager) getContext().getSystemService(Context.USAGE_STATS_SERVICE);
            Set<String> skip = ignored();
            long now = System.currentTimeMillis();
            final Map<String, Long> sum = new HashMap<>();
            for (int d = 0; d < days; d++) {
                Day day = countDay(usm, skip, d, now);
                for (Map.Entry<String, Long> en : day.apps.entrySet()) {
                    Long old = sum.get(en.getKey());
                    sum.put(en.getKey(), (old == null ? 0 : old) + en.getValue());
                }
            }
            List<String> pkgs = new ArrayList<>(sum.keySet());
            Collections.sort(pkgs, (a, b) -> Long.compare(sum.get(b), sum.get(a)));
            JSArray out = new JSArray();
            for (String p : pkgs) {
                JSObject o = new JSObject();
                o.put("pkg", p);
                o.put("label", label(p));
                o.put("minutes", Math.round(sum.get(p) / 60000.0));
                out.put(o);
            }
            JSObject r = new JSObject();
            r.put("apps", out);
            call.resolve(r);
        } catch (Exception ex) {
            call.reject("read_failed");
        }
    }
}
