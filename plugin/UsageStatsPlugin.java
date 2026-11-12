package app.treckit.mobile;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Process;
import android.provider.Settings;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Liest die Bildschirmzeit und Entsperrungen aus Android (nur lesen, nur auf diesem Gerät). */
@CapacitorPlugin(name = "UsageStats")
public class UsageStatsPlugin extends Plugin {

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

    @PluginMethod
    public void getDaily(PluginCall call) {
        if (!granted()) { call.reject("no_access"); return; }
        int days = call.getInt("days", 7);
        if (days < 1) days = 1;
        if (days > 10) days = 10; // Android hält Einzelereignisse nur wenige Tage vor
        try {
            UsageStatsManager usm = (UsageStatsManager) getContext().getSystemService(Context.USAGE_STATS_SERVICE);
            Set<String> skip = ignored();
            long now = System.currentTimeMillis();
            JSArray out = new JSArray();
            for (int d = 0; d < days; d++) {
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0);
                cal.add(Calendar.DAY_OF_YEAR, -d);
                long start = cal.getTimeInMillis();
                String date = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
                cal.add(Calendar.DAY_OF_YEAR, 1);
                long end = Math.min(cal.getTimeInMillis(), now);
                if (end <= start) continue;
                // 6 Stunden Vorlauf, damit Apps, die über Mitternacht offen waren, richtig gezählt werden
                UsageEvents ev = usm.queryEvents(start - 6L * 3600000L, end);
                UsageEvents.Event e = new UsageEvents.Event();
                Map<String, Integer> depth = new HashMap<>();
                Map<String, Long> since = new HashMap<>();
                long total = 0;
                int unlocks = 0;
                while (ev.hasNextEvent()) {
                    ev.getNextEvent(e);
                    int t = e.getEventType();
                    String pkg = e.getPackageName();
                    long ts = e.getTimeStamp();
                    if (t == 18) { if (ts >= start) unlocks++; continue; } // KEYGUARD_HIDDEN = Bildschirm entsperrt
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
                            if (s0 != null) total += overlap(s0, ts, start, end);
                        }
                    }
                }
                for (Long s0 : since.values()) total += overlap(s0, end, start, end);
                JSObject o = new JSObject();
                o.put("date", date);
                o.put("minutes", Math.round(total / 60000.0));
                o.put("unlocks", unlocks);
                out.put(o);
            }
            JSObject r = new JSObject();
            r.put("days", out);
            call.resolve(r);
        } catch (Exception ex) {
            call.reject("read_failed");
        }
    }
}
