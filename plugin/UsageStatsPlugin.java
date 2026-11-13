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
        r.put("version", 2);
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
        s.add(getContext().getPackageName());
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
        day.date = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        cal.add(Calendar.DAY_OF_YEAR, 1);
        long end = Math.min(cal.getTimeInMillis(), now);
        if (end <= start) return day;
        // 6 Stunden Vorlauf, damit Apps, die über Mitternacht offen waren, richtig gezählt werden
        UsageEvents ev = usm.queryEvents(start - 6L * 3600000L, end);
        UsageEvents.Event e = new UsageEvents.Event();
        Map<String, Integer> depth = new HashMap<>();
        Map<String, Long> since = new HashMap<>();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            String pkg = e.getPackageName();
            long ts = e.getTimeStamp();
            if (t == 18) { if (ts >= start) day.unlocks++; continue; } // KEYGUARD_HIDDEN = Bildschirm entsperrt
            if (pkg == null || skip.contains(pkg)) continue;
            if (t == 1) { // ACTIVITY_RESUMED: eine Seite der App kommt nach vorn
                Integer dd = depth.get(pkg);
                int n = dd == null ? 0 : dd;
                if (n == 0) since.put(pkg, ts);
                depth.put(pkg, n + 1);
            } else if (t == 2) { // ACTIVITY_PAUSED: eine Seite der App geht weg
                Integer dd = depth.get(pkg);
                int n = dd == null ? 0 : dd;
                if (n <= 0) continue;
                n--;
                depth.put(pkg, n);
                if (n == 0) {
                    Long s0 = since.remove(pkg);
                    if (s0 != null) add(day, pkg, overlap(s0, ts, start, end));
                }
            }
        }
        for (Map.Entry<String, Long> en : since.entrySet()) add(day, en.getKey(), overlap(en.getValue(), end, start, end));
        return day;
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
                out.put(o);
            }
            JSObject r = new JSObject();
            r.put("days", out);
            call.resolve(r);
        } catch (Exception ex) {
            call.reject("read_failed");
        }
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
