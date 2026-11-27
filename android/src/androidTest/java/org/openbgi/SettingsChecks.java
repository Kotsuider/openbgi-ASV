/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

final class SettingsChecks {
    private static void check(boolean condition, String message) { if(!condition) throw new AssertionError(message); }
    static void run(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("configurationChecks", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        try {
            UserConfigurations store = new UserConfigurations(prefs);
            String first = store.save(null, "  Itsusora settings  ", "--font-size=24 --letter-spacing=2", "1.66", "patch/uif.json", false);
            String second = store.save(null, "Other", "--enable=C0:18", "1.69/472", "", true);
            JSONArray list = new UserConfigurations(prefs).list();
            check(list.length() == 2, "Creation persists");
            JSONObject entry = list.getJSONObject(0);
            check(entry.getString("name").equals("Itsusora settings") && entry.getString("id").equals(first), "Name and identity");
            check(entry.getString("uif").equals("patch/uif.json") && !entry.getBoolean("auto_font"), "Configuration fields retained");
            check(store.save(first, "Updated", "--font-size=30", "1.66", "", true).equals(first), "Updates keep identity");
            check(store.list().length() == 2 && store.list().getJSONObject(0).getString("arguments").equals("--font-size=30"), "Update in place");
            store.delete(first);
            check(store.list().length() == 1 && store.list().getJSONObject(0).getString("id").equals(second), "Delete preserves other configurations");
            try { store.delete("preset:itsusora-ru"); throw new AssertionError("Deleted built-in"); } catch(IllegalArgumentException expected) { }
            try { store.save(first, "Missing", "", "", "", true); throw new AssertionError("Recreated missing ID"); } catch(IllegalArgumentException expected) { }
            try { store.save(null, "  ", "", "", "", true); throw new AssertionError("Empty name"); } catch(IllegalArgumentException expected) { }
            prefs.edit().putString("configs", "broken JSON").commit();
            try { store.save(null, "New", "", "", "", true); throw new AssertionError("Overwrote corrupted storage"); } catch(org.json.JSONException expected) { }
            check(prefs.getString("configs", "").equals("broken JSON"), "Corrupt data retained for recovery");
        } finally { prefs.edit().clear().commit(); }
    }
    private static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private static void invoke(Instrumentation inst, LibraryActivity activity, String name, Class<?>[] types, Object... args) {
        inst.runOnMainSync(() -> {
            try { Method method = LibraryActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(activity, args); }
            catch(Exception error) { throw new RuntimeException(error); }
        });
        inst.waitForIdleSync();
    }
    private static void ready(Instrumentation inst, LibraryActivity activity) throws Exception {
        for(int i=0;i<200;i++) {
            AtomicBoolean enabled = new AtomicBoolean();
            inst.runOnMainSync(() -> { try { enabled.set(((android.widget.Spinner)field(activity,"configurationChoice")).isEnabled()); } catch(Exception e) { throw new RuntimeException(e); } });
            if(enabled.get()) { inst.waitForIdleSync(); return; }
            Thread.sleep(50);
        }
        throw new AssertionError("Configuration picker never loaded");
    }
    private static void edit(Instrumentation inst, String label, String value) throws Exception {
        AccessibilityNodeInfo root = inst.getUiAutomation().getRootInActiveWindow();
        check(root != null, "Active dialog available");
        for(AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) if(node.isEditable()) {
            Bundle bundle = new Bundle(); bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
            check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle), "Set field " + label);
            inst.waitForIdleSync(); return;
        }
        throw new AssertionError("Editable field missing: " + label);
    }
    private static void positive(Instrumentation inst) throws Exception {
        Thread.sleep(150);
        AccessibilityNodeInfo root = inst.getUiAutomation().getRootInActiveWindow();
        for(AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId("android:id/button1")) {
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK), "Dialog positive button"); inst.waitForIdleSync(); return;
        }
        throw new AssertionError("Positive button missing");
    }
    private static void screenshot(Instrumentation inst, String name) throws Exception {
        Thread.sleep(200);
        android.graphics.Bitmap bitmap = inst.getUiAutomation().takeScreenshot();
        check(bitmap != null, "Screenshot available");
        try(java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(inst.getTargetContext().getFilesDir(), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
    static void editor(Instrumentation inst, Bundle args) throws Exception {
        run(inst.getTargetContext());
        LibraryActivity activity = (LibraryActivity)inst.startActivitySync(new Intent(inst.getTargetContext(), LibraryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        String created = null;
        try {
            LauncherActivity.Game chosen = null;
            for(LauncherActivity.Game game : activity.games) if(game.uri.equals(args.getString("tree"))) chosen = game;
            check(chosen != null, "Existing game selected");
            invoke(inst, activity, "editGame", new Class<?>[]{LauncherActivity.Game.class, Bundle.class}, chosen, null);
            ready(inst, activity);
            invoke(inst, activity, "editFontParameters", new Class<?>[]{});
            screenshot(inst, "settings-font-ui.png");
            edit(inst, activity.getString(R.string.font_size), "3"); positive(inst);
            check(inst.getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(activity.getString(R.string.font_parameters)).size() > 0, "Invalid size keeps editor open");
            edit(inst, activity.getString(R.string.font_size), "24");
            edit(inst, activity.getString(R.string.font_width), "0");
            edit(inst, activity.getString(R.string.font_spacing), "-2");
            edit(inst, activity.getString(R.string.font_weight), "400"); positive(inst);
            String arguments = ((android.widget.EditText)field(activity,"argumentsField")).getText().toString();
            check(java.util.Arrays.equals(FontArguments.read(arguments),new String[]{"24","0","-2","400"}), "Fields change arguments");
            check(!((android.widget.CheckBox)field(activity,"configChoice")).isChecked(), "Font edit uses manual arguments");
            invoke(inst,activity,"editUserConfiguration",new Class<?>[]{boolean.class},true);
            edit(inst,activity.getString(R.string.config_name),"UI configuration check"); positive(inst); ready(inst,activity);
            created = (String)field(activity,"selectedConfiguration"); check(created.startsWith("user:"),"Created config selected");
            GameConfigurations configs = (GameConfigurations)field(activity,"editingConfigurations");
            check(configs.resolve(created).arguments.equals(arguments),"New config stores font controls and existing arguments");
            invoke(inst,activity,"editUserConfiguration",new Class<?>[]{boolean.class},false);
            edit(inst,activity.getString(R.string.config_name),"UI configuration updated"); positive(inst); ready(inst,activity);
            check(created.equals(field(activity,"selectedConfiguration")),"Update preserves selected identity");
            configs = (GameConfigurations)field(activity,"editingConfigurations");
            check(configs.resolve(created).name.equals("UI configuration updated"),"Updated name shown");
            screenshot(inst,"settings-config-ui.png");
            invoke(inst,activity,"deleteUserConfiguration",new Class<?>[]{}); positive(inst); ready(inst,activity);
            check(field(activity,"selectedConfiguration").equals("none"),"Deleted selection falls back to None");
            JSONArray list = new UserConfigurations(activity).list();
            for(int i=0;i<list.length();i++) check(!created.equals(list.getJSONObject(i).getString("id")),"Deleted config gone from storage");
        } finally {
            if(created != null) new UserConfigurations(activity).delete(created);
            inst.runOnMainSync(() -> { try { ((AlertDialog)field(activity,"editor")).dismiss(); activity.finish(); } catch(Exception e) { throw new RuntimeException(e); } });
        }
    }
}
