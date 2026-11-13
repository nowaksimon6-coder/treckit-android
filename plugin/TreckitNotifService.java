package app.treckit.mobile;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Merkt sich Benachrichtigungen, aber nur von den Apps, die der Nutzer in Track.it ausgewählt hat.
 * Alles bleibt auf dem Handy (Datei im App-Speicher).
 */
public class TreckitNotifService extends NotificationListenerService {

    static volatile TreckitNotifService instance;
    static final String PREFS = "treckit_notif";
    static final String LOG = "treckit_notif_log.json";
    static final int MAX = 3000;
    static final long KEEP_MS = 120L * 24 * 3600 * 1000;
    static final Object LOCK = new Object();

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            record(getApplicationContext(), sbn);
        } catch (Throwable t) {
            // nie die Benachrichtigungen des Handys stören
        }
    }

    static Set<String> watched(Context c) {
        Set<String> s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("watch", null);
        return s == null ? new HashSet<String>() : new HashSet<String>(s);
    }

    static void setWatched(Context c, Set<String> pkgs) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("watch", new HashSet<String>(pkgs)).apply();
    }

    static String cut(CharSequence cs, int max) {
        if (cs == null) return "";
        String s = cs.toString().trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Wandelt eine Benachrichtigung in einen Eintrag um (oder null, wenn sie nichts Neues enthält). */
    static JSONObject toItem(StatusBarNotification sbn) throws Exception {
        Notification n = sbn.getNotification();
        if (n == null) return null;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        Bundle x = n.extras;
        String title = x == null ? "" : cut(x.getCharSequence(Notification.EXTRA_TITLE), 200);
        String text = x == null ? "" : cut(x.getCharSequence(Notification.EXTRA_TEXT), 400);
        if (x != null) {
            String big = cut(x.getCharSequence(Notification.EXTRA_BIG_TEXT), 400);
            if (big.length() > text.length()) text = big;
        }
        if (title.isEmpty() && text.isEmpty()) return null;
        JSONObject o = new JSONObject();
        o.put("pkg", sbn.getPackageName());
        o.put("t", sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis());
        o.put("title", title);
        o.put("text", text);
        o.put("key", sbn.getKey());
        o.put("ongoing", (n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        return o;
    }

    static void record(Context c, StatusBarNotification sbn) throws Exception {
        if (!watched(c).contains(sbn.getPackageName())) return;
        JSONObject item = toItem(sbn);
        if (item == null) return;
        synchronized (LOCK) {
            JSONArray log = load(c);
            long now = item.getLong("t");
            String key = item.optString("key");
            // dieselbe Benachrichtigung wird oft nur aktualisiert: dann ersetzen statt doppelt zählen
            for (int i = log.length() - 1; i >= 0 && i >= log.length() - 200; i--) {
                JSONObject e = log.getJSONObject(i);
                if (!key.equals(e.optString("key"))) continue;
                long age = now - e.optLong("t");
                boolean same = item.optString("title").equals(e.optString("title")) && item.optString("text").equals(e.optString("text"));
                if (same && age < 10 * 60 * 1000) return;
                if (item.optBoolean("ongoing") && age < 5 * 60 * 1000) {
                    log.put(i, item);
                    save(c, log);
                    return;
                }
                break;
            }
            log.put(item);
            save(c, prune(log, now));
        }
    }

    static JSONArray prune(JSONArray log, long now) throws Exception {
        JSONArray out = new JSONArray();
        int start = Math.max(0, log.length() - MAX);
        for (int i = start; i < log.length(); i++) {
            JSONObject e = log.getJSONObject(i);
            if (now - e.optLong("t") <= KEEP_MS) out.put(e);
        }
        return out;
    }

    static JSONArray load(Context c) {
        try {
            File f = new File(c.getFilesDir(), LOG);
            if (!f.exists()) return new JSONArray();
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) break;
                off += r;
            }
            in.close();
            return new JSONArray(new String(b, 0, off, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    static void save(Context c, JSONArray log) throws Exception {
        File tmp = new File(c.getFilesDir(), LOG + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(log.toString().getBytes(StandardCharsets.UTF_8));
        out.close();
        File f = new File(c.getFilesDir(), LOG);
        if (!tmp.renameTo(f)) {
            f.delete();
            tmp.renameTo(f);
        }
    }
}
