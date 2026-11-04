package me.paranoid.wifikill;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * adb entry point of the debug bridge (debug builds only, declared in
 * app/src/debug/AndroidManifest.xml).
 *
 * <p>Manifest receivers are deferred for backgrounded apps on recent
 * Android releases, which made long adb sessions unreliable. An Activity
 * start, in contrast, wakes the process immediately. This transparent
 * activity parses the same extras as the broadcast receiver, hands the
 * command to the bridge worker and finishes without drawing anything.</p>
 *
 * <pre>adb shell am start -n me.paranoid.wifikill.debug/me.paranoid.wifikill.BridgeActivity
 *   --es cmd status</pre>
 */
public final class BridgeActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        if (intent == null) {
            return;
        }
        if (!BuildConfig.DEBUG) {
            return;
        }
        DebugBridgeReceiver.enqueue(this, intent);
    }
}
