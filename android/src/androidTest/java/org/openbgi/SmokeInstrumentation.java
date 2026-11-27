/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Explicit adb smoke driver; never included in the application APK. */
public final class SmokeInstrumentation extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle args) { this.args = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String temporaryConfiguration = null;
        try {
            if ("settings".equals(args.getString("mode")) || "editor".equals(args.getString("mode"))) {
                if ("editor".equals(args.getString("mode"))) SettingsChecks.editor(this, args);
                else SettingsChecks.run(getTargetContext());
                result.putString("result", "Settings checks passed"); finish(Activity.RESULT_OK, result); return;
            }
            String tree = args.getString("tree");
            if(tree == null) throw new IllegalArgumentException("Pass -e tree <persisted game tree URI>");
            if ("user-config-launch".equals(args.getString("mode"))) {
                GameConfigurations configs = new GameConfigurations(getTargetContext(), new GameDocuments(getTargetContext().getContentResolver(), Uri.parse(tree)));
                GameConfigurations.Option source = configs.resolve(args.getString("source", "preset:eustia-ru"));
                String configured = FontArguments.apply(source.arguments, new String[]{"24", "0", "2", "400"});
                temporaryConfiguration = new UserConfigurations(getTargetContext()).save(null, "Configuration launch check", configured, source.engine, source.uif, source.autoFont);
                args.putString("configuration", temporaryConfiguration);
            }
            Intent intent = new Intent().setClassName("org.openbgi", "org.openbgi.MainActivity")
                    .setData(Uri.parse(tree)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("game", "API v1 smoke test")
                    .putExtra("profile", args.getString("profile", "1.69/472"))
                    .putExtra("profileOverride", true)
                    
                    .putExtra("configuration", args.getString("configuration", "auto"));
            if(args.containsKey("arguments")) intent.putExtra("arguments", args.getString("arguments"));
            getTargetContext().startActivity(intent);
            Thread.sleep(Long.parseLong(args.getString("wait", "15000")));
            if (args.containsKey("tapX") && args.containsKey("tapY")) {
                float x = Float.parseFloat(args.getString("tapX")), y = Float.parseFloat(args.getString("tapY"));
                long now = android.os.SystemClock.uptimeMillis();
                android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0);
                android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80, android.view.MotionEvent.ACTION_UP, x, y, 0);
                getUiAutomation().injectInputEvent(down, true); getUiAutomation().injectInputEvent(up, true);
                down.recycle(); up.recycle();
                Thread.sleep(10000);
            }
            android.graphics.Bitmap screenshot = getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                java.io.File file = new java.io.File(getTargetContext().getFilesDir(), "smoke-last.png");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
                screenshot.recycle();
            }
            result.putString("result", "Game launched; inspect openbgi-last.log and logcat for its result.");
            if (temporaryConfiguration != null) new UserConfigurations(getTargetContext()).delete(temporaryConfiguration);
            finish(Activity.RESULT_OK, result);
        } catch(Exception error) {
            if (temporaryConfiguration != null) try { new UserConfigurations(getTargetContext()).delete(temporaryConfiguration); } catch(Exception cleanup) { error.addSuppressed(cleanup); }
            result.putString("error", error.toString()); finish(Activity.RESULT_CANCELED, result);
        }
    }
}
