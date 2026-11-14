package app.treckit.mobile;

import android.content.Context;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Die Web-App schickt hierher die Werte für die Widgets auf dem Startbildschirm. */
@CapacitorPlugin(name = "TreckitWidget")
public class WidgetPlugin extends Plugin {
    @PluginMethod
    public void update(PluginCall call) {
        try {
            JSObject d = call.getData();
            getContext().getSharedPreferences(WidgetDraw.PREFS, Context.MODE_PRIVATE).edit().putString("data", d.toString()).apply();
            WidgetDraw.updateAll(getContext());
            call.resolve();
        } catch (Exception e) {
            call.reject("failed");
        }
    }
}
