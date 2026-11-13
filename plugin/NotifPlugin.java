package app.treckit.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Zugriff auf Benachrichtigungen ausgewählter Apps, damit Track.it daraus Tageswerte machen kann. */
@CapacitorPlugin(name = "TreckitNotif")
public class NotifPlugin extends Plugin {

    private boolean granted() {
        return NotificationManagerCompat.getEnabledListenerPackages(getContext()).contains(getContext().getPackageName());
    }

    private ComponentName component() {
        return new ComponentName(getContext(), TreckitNotifService.class);
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getContext().getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Throwable t) {
            return pkg;
        }
    }

    private JSObject out(JSONObject e) {
        JSObject o = new JSObject();
        String pkg = e.optString("pkg");
        o.put("pkg", pkg);
        o.put("t", e.optLong("t"));
        o.put("title", e.optString("title"));
        o.put("text", e.optString("text"));
        o.put("ongoing", e.optBoolean("ongoing"));
        return o;
    }

    @PluginMethod
    public void hasAccess(PluginCall call) {
        boolean g = granted();
        if (g && TreckitNotifService.instance == null && Build.VERSION.SDK_INT >= 24) {
            try {
                NotificationListenerService.requestRebind(component());
            } catch (Throwable t) {
                // egal
            }
        }
        JSObject r = new JSObject();
        r.put("granted", g);
        r.put("connected", TreckitNotifService.instance != null);
        r.put("version", 1);
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Context c = getContext();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component().flattenToString());
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                call.resolve();
                return;
            } catch (Throwable t) {
                // allgemeine Liste versuchen
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            call.resolve();
        } catch (Throwable t) {
            call.reject("failed");
        }
    }

    /** App-Info von Track.it, dort lassen sich bei manchen Handys „eingeschränkte Einstellungen“ freigeben. */
    @PluginMethod
    public void openAppInfo(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Throwable t) {
            call.reject("failed");
        }
    }

    @PluginMethod
    public void getWatch(PluginCall call) {
        JSArray a = new JSArray();
        for (String p : TreckitNotifService.watched(getContext())) a.put(p);
        JSObject r = new JSObject();
        r.put("pkgs", a);
        call.resolve(r);
    }

    @PluginMethod
    public void setWatch(PluginCall call) {
        JSArray arr = call.getArray("pkgs");
        Set<String> s = new HashSet<>();
        if (arr != null) {
            for (int i = 0; i < arr.length() && i < 50; i++) {
                String p = arr.optString(i, "");
                if (p.matches("[A-Za-z0-9_.]{2,120}")) s.add(p);
            }
        }
        TreckitNotifService.setWatched(getContext(), s);
        JSArray a = new JSArray();
        for (String p : s) a.put(p);
        JSObject r = new JSObject();
        r.put("pkgs", a);
        call.resolve(r);
    }

    /** Gespeicherte Benachrichtigungen (nur von beobachteten Apps). */
    @PluginMethod
    public void getNotifications(PluginCall call) {
        String pkg = call.getString("pkg", "");
        long since = call.getLong("since", 0L);
        JSArray items = new JSArray();
        try {
            JSONArray log;
            synchronized (TreckitNotifService.LOCK) {
                log = TreckitNotifService.load(getContext());
            }
            for (int i = 0; i < log.length(); i++) {
                JSONObject e = log.getJSONObject(i);
                if (!pkg.isEmpty() && !pkg.equals(e.optString("pkg"))) continue;
                if (e.optLong("t") < since) continue;
                items.put(out(e));
            }
        } catch (Throwable t) {
            call.reject("failed");
            return;
        }
        JSObject r = new JSObject();
        r.put("items", items);
        call.resolve(r);
    }

    /** Was gerade in der Benachrichtigungsleiste steht (als Beispiele für die Einrichtung). */
    @PluginMethod
    public void getActive(PluginCall call) {
        String pkg = call.getString("pkg", "");
        JSArray items = new JSArray();
        JSArray apps = new JSArray();
        Set<String> seen = new HashSet<>();
        TreckitNotifService svc = TreckitNotifService.instance;
        if (svc != null) {
            try {
                StatusBarNotification[] list = svc.getActiveNotifications();
                if (list != null) {
                    for (StatusBarNotification sbn : list) {
                        String p = sbn.getPackageName();
                        if (p.equals(getContext().getPackageName())) continue;
                        if (!seen.contains(p)) {
                            seen.add(p);
                            JSObject a = new JSObject();
                            a.put("pkg", p);
                            a.put("name", label(p));
                            apps.put(a);
                        }
                        if (!pkg.isEmpty() && !pkg.equals(p)) continue;
                        JSONObject e = TreckitNotifService.toItem(sbn);
                        if (e != null) items.put(out(e));
                    }
                }
            } catch (Throwable t) {
                // ohne Beispiele weiter
            }
        }
        JSObject r = new JSObject();
        r.put("connected", svc != null);
        r.put("items", items);
        r.put("apps", apps);
        call.resolve(r);
    }

    @PluginMethod
    public void clear(PluginCall call) {
        String pkg = call.getString("pkg", "");
        try {
            synchronized (TreckitNotifService.LOCK) {
                JSONArray log = TreckitNotifService.load(getContext());
                JSONArray keep = new JSONArray();
                for (int i = 0; i < log.length(); i++) {
                    JSONObject e = log.getJSONObject(i);
                    if (!pkg.isEmpty() && pkg.equals(e.optString("pkg"))) continue;
                    if (pkg.isEmpty()) continue;
                    keep.put(e);
                }
                TreckitNotifService.save(getContext(), keep);
            }
            call.resolve();
        } catch (Throwable t) {
            call.reject("failed");
        }
    }
}
