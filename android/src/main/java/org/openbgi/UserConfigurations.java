/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** User presets live in private app storage, never in the read-only game folder. */
final class UserConfigurations {
    private final SharedPreferences preferences;
    UserConfigurations(Context context) { this(context.getSharedPreferences("userConfigurations", Context.MODE_PRIVATE)); }
    UserConfigurations(SharedPreferences preferences) { this.preferences = preferences; }
    JSONArray list() throws JSONException { return new JSONArray(preferences.getString("configs", "[]")); }
    String save(String id, String name, String arguments, String engine, String uif, boolean autoFont) throws JSONException {
        name = name.trim();
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("Invalid configuration name");
        JSONArray all = list();
        int index = -1;
        if (id != null) {
            if (!id.startsWith("user:")) throw new IllegalArgumentException("Not a user configuration");
            for (int i = 0; i < all.length(); i++) if (id.equals(all.getJSONObject(i).getString("id"))) index = i;
            if (index < 0) throw new IllegalArgumentException("Configuration no longer exists");
        } else id = "user:" + java.util.UUID.randomUUID();
        JSONObject entry = new JSONObject().put("id", id).put("name", name).put("arguments", arguments)
                .put("engine", engine).put("uif", uif).put("auto_font", autoFont);
        if (index < 0) all.put(entry); else all.put(index, entry);
        write(all);
        return id;
    }
    void delete(String id) throws JSONException {
        if (id == null || !id.startsWith("user:")) throw new IllegalArgumentException("Not a user configuration");
        JSONArray all = list(), remaining = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject entry = all.getJSONObject(i);
            if (!id.equals(entry.getString("id"))) remaining.put(entry);
        }
        write(remaining);
    }
    private void write(JSONArray all) {
        if (!preferences.edit().putString("configs", all.toString()).commit())
            throw new IllegalStateException("Cannot save configurations");
    }
}
