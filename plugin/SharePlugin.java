package app.treckit.mobile;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Nimmt Bilder und Dateien an, die über „Teilen“ an Track.it geschickt werden
 * (z. B. ein Screenshot direkt aus der Galerie oder ein Export aus einer anderen App).
 */
@CapacitorPlugin(name = "TreckitShare")
public class SharePlugin extends Plugin {
    private static final int MAX = 30 * 1024 * 1024;
    private JSObject pending = null;

    @Override
    public void load() {
        if (getActivity() != null) take(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        if (take(intent)) notifyListeners("shared", new JSObject(), true);
    }

    private boolean take(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return false;
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            // Links (z. B. aus TikTok, YouTube, Spotify) kommen als Text
            CharSequence txt = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (txt == null || txt.length() == 0) return false;
            JSObject o = new JSObject();
            o.put("mime", "text/plain");
            o.put("text", txt.toString().length() > 2000 ? txt.toString().substring(0, 2000) : txt.toString());
            CharSequence subj = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
            if (subj != null) o.put("subject", subj.toString());
            pending = o;
            intent.setAction(Intent.ACTION_MAIN);
            return true;
        }
        try {
            String mime = intent.getType();
            if (mime == null) mime = getContext().getContentResolver().getType(uri);
            String name = "datei";
            try (Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0) name = c.getString(i);
                }
            } catch (Exception ignored) {
                // ohne Namen weiter
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
                if (in == null) return false;
                byte[] buf = new byte[65536];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX) { JSObject big = new JSObject(); big.put("tooBig", true); big.put("mime", mime == null ? "" : mime); pending = big; intent.setAction(Intent.ACTION_MAIN); return true; }
                    out.write(buf, 0, n);
                }
            }
            JSObject o = new JSObject();
            o.put("mime", mime == null ? "" : mime);
            o.put("name", name);
            o.put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            pending = o;
            intent.setAction(Intent.ACTION_MAIN);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PluginMethod
    public void getShared(PluginCall call) {
        JSObject r = new JSObject();
        if (pending != null) { r.put("file", pending); pending = null; }
        call.resolve(r);
    }
}
