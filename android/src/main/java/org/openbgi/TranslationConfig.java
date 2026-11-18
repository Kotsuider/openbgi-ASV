/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.graphics.Typeface;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class TranslationConfig {
    private static final String TAG = "OpenBGI";
    private static final int MAX_CONFIG = 1024 * 1024;

    private static final class Redirect {
        final Pattern pattern;
        final String target;

        Redirect(String pattern, String target) {
            this.pattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            this.target = target;
        }
    }

    private final List<Redirect> redirects = new ArrayList<>();
    private final Map<Integer, Integer> glyphs = new HashMap<>();
    private Typeface font;

    TranslationConfig(GameDocuments documents, String configuredUif) {
        JSONObject root = read(documents, configuredUif, "uif_config.json", "config.json");
        JSONObject files = root.optJSONObject("file_monitor");
        if (files == null) {
            redirects.add(new Redirect("^(?:.*/)?([a-zA-Z0-9_]+(?:\\._bp)?)$", "patch/$1"));
        } else if (files.optBoolean("enable")) {
            JSONArray actions = files.optJSONArray("actions");
            for (int i = 0; actions != null && i < actions.length(); ++i) {
                JSONObject action = actions.optJSONObject(i);
                if (action == null || !action.has("pattern") || !action.has("redirect")) continue;
                try {
                    redirects.add(new Redirect(action.getString("pattern"), action.getString("redirect")));
                } catch (IllegalArgumentException | JSONException error) {
                    Log.w(TAG, "Ignoring invalid patch redirect", error);
                }
            }
        }

        JSONObject substitution = root.optJSONObject("character_substitution");
        if (substitution != null && substitution.optBoolean("enable")) {
            int[] from = substitution.optString("source_characters").codePoints().toArray();
            int[] to = substitution.optString("target_characters").codePoints().toArray();
            for (int i = 0; i < Math.min(from.length, to.length); ++i) glyphs.put(from[i], to[i]);
            Log.i(TAG, "Configured glyph substitutions: " + glyphs.size());
        }

        JSONObject fonts = root.optJSONObject("font_manager");
        if (fonts != null && fonts.optBoolean("enable")) {
            JSONObject spoof = fonts.optJSONObject("spoof_creation");
            if (spoof != null && spoof.optBoolean("enable")) {
                font = loadFont(documents, fonts.optJSONArray("resource_files"),
                        spoof.optString("override_face"));
            }
        }
    }

    static JSONObject read(GameDocuments documents, String... names) {
        for (String value : names) {
            if (value == null || value.isEmpty()) continue;
            String name = value.replace('\\', '/');
            int fd = documents.openOriginal(name);
            if (fd < 0) continue;
            try (ParcelFileDescriptor.AutoCloseInputStream stream =
                         new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd))) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = stream.read(chunk)) != -1) {
                    if (data.size() + n > MAX_CONFIG) throw new IOException("Configuration is too large");
                    data.write(chunk, 0, n);
                }
                String text = new String(data.toByteArray(), StandardCharsets.UTF_8);
                if (text.startsWith("\ufeff")) text = text.substring(1);
                JSONObject result = new JSONObject(text);
                Log.i(TAG, "Loaded config " + name);
                return result;
            } catch (IOException | JSONException error) {
                Log.w(TAG, "Cannot read " + name, error);
            }
        }
        return new JSONObject();
    }

    private static Typeface loadFont(GameDocuments documents, JSONArray resources, String face) {
        for (int i = 0; resources != null && i < resources.length(); ++i) {
            String path = resources.optString(i).replace('\\', '/');
            String file = path.substring(path.lastIndexOf('/') + 1);
            int dot = file.lastIndexOf('.');
            if (!face.isEmpty() && !face.equalsIgnoreCase(dot < 0 ? file : file.substring(0, dot)))
                continue;
            int fd = documents.openOriginal(path);
            if (fd < 0) continue;
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.adoptFd(fd)) {
                Typeface result = new Typeface.Builder(descriptor.getFileDescriptor()).build();
                if (result != null) {
                    Log.i(TAG, "Loaded translation font " + path);
                    return result;
                }
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Cannot load font " + path, error);
            }
        }
        return null;
    }

    String redirect(String path, GameDocuments documents) {
        for (Redirect redirect : redirects) {
            Matcher matcher = redirect.pattern.matcher(path);
            if (!matcher.find()) continue;
            try {
                String target = matcher.replaceFirst(redirect.target).replace('\\', '/');
                if (!target.equals(path) && documents.hasOriginalFile(target)) return target;
            } catch (IllegalArgumentException | IndexOutOfBoundsException error) {
                Log.w(TAG, "Invalid redirect target " + redirect.target, error);
            }
        }
        return path;
    }

    int glyph(int cp) {
        return glyphs.getOrDefault(cp, cp);
    }

    Typeface typeface() {
        return font == null ? Typeface.SANS_SERIF : font;
    }
}
