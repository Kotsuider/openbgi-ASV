/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Translation presets are independent of VM/engine profiles. */
final class GameConfigurations {
    static final class Option {
        final String id, label, name, arguments, engine, uif;
        final boolean autoFont;
        Option(String id, String label, JSONObject data, String uif) {
            this.id = id; this.label = label; this.uif = uif;
            name = data.optString("name", label);
            arguments = data.optString("arguments", "");
            engine = data.optString("engine", "");
            autoFont = data.optBoolean("auto_font", true);
        }
        @Override public String toString() { return label; }
    }
    final List<Option> options = new ArrayList<>();
    final Option automatic;

    static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
    static boolean matches(JSONObject entry, String name) {
        String normalized = normalize(name);
        JSONArray aliases = entry.optJSONArray("match");
        for (int i = 0; aliases != null && i < aliases.length(); i++) {
            String alias = normalize(aliases.optString(i));
            if (!alias.isEmpty() && normalized.contains(alias)) return true;
        }
        return false;
    }

    private static byte[] readBytes(java.io.InputStream stream) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int n;
        while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    GameConfigurations(Context context, GameDocuments docs) {
        JSONObject local = TranslationConfig.read(docs, GameLaunchConfig.FILE);
        String uif = TranslationConfig.findConfig(docs, local.optString("uif", ""));
        Option matched = null;
        List<Option> presets = new ArrayList<>();
        try (java.io.InputStream stream = context.getAssets().open("game-configs.json")) {
            JSONObject root = new JSONObject(new String(readBytes(stream), StandardCharsets.UTF_8));
            JSONArray list = root.getJSONArray("configs");
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.getJSONObject(i);
                Option option = new Option("preset:" + entry.getString("id"), entry.getString("name"), entry, "");
                presets.add(option);
                if (matched == null && matches(entry, docs.name())) matched = option;
            }
        } catch (Exception error) {
            android.util.Log.w("OpenBGI", "Cannot read game-configs.json", error);
        }
        Option foundUif = uif.isEmpty() ? null : new Option("uif:" + uif, "UIF · " + uif, local, uif);
        Option foundLocal = docs.hasOriginalFile(GameLaunchConfig.FILE)
                ? new Option("local", GameLaunchConfig.FILE, local, uif) : null;
        Option none = new Option("none", context.getString(R.string.config_none), new JSONObject(), "");
        automatic = foundUif != null ? foundUif : foundLocal != null ? foundLocal : matched != null ? matched : none;
        options.add(new Option("auto", context.getString(R.string.config_auto, automatic.label), new JSONObject(), ""));
        if (foundUif != null) options.add(foundUif);
        if (foundLocal != null) options.add(foundLocal);
        options.addAll(presets);
        try {
            JSONArray users = new UserConfigurations(context).list();
            for (int i = 0; i < users.length(); i++) {
                JSONObject entry = users.getJSONObject(i);
                String id = entry.getString("id");
                if (id.startsWith("user:")) options.add(new Option(id,
                        context.getString(R.string.user_config_label, entry.getString("name")), entry, entry.optString("uif", "")));
            }
        } catch (Exception error) { android.util.Log.e("OpenBGI", "Cannot load user configurations", error); }
        options.add(none);
    }
    Option resolve(String id) {
        if (id == null || id.equals("auto")) return automatic;
        for (Option option : options) if (option.id.equals(id)) return option;
        // A removed preset or missing file should not silently switch to another translation.
        return options.get(options.size() - 1);
    }
}
