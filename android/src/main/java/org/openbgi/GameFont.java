/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** A private copy survives revoked document grants and changes to the source file. */
final class GameFont {
    private static File file(Context context, String game) {
        String key = java.util.UUID.nameUUIDFromBytes(game.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return new File(new File(context.getFilesDir(), "fonts"), key + ".font");
    }
    static String name(Context context, String game) {
        return context.getSharedPreferences("gameFonts", Context.MODE_PRIVATE).getString(game, "");
    }
    static String systemFamily(Context context, String game) {
        return context.getSharedPreferences("gameFonts", Context.MODE_PRIVATE).getString("system:" + game, "");
    }
    static synchronized void selectSystem(Context context, String game, String family) {
        if (!systemFamilies().contains(family)) throw new IllegalArgumentException("Unknown system font family");
        context.getSharedPreferences("gameFonts", Context.MODE_PRIVATE).edit()
                .putString("system:" + game, family).commit();
    }
    static java.util.List<String> systemFamilies() {
        java.util.LinkedHashSet<String> families = new java.util.LinkedHashSet<>();
        java.util.Collections.addAll(families, "sans-serif", "serif", "monospace");
        java.util.TreeSet<String> configured = new java.util.TreeSet<>();
        for (String path : new String[]{"/system/etc/fonts.xml", "/product/etc/fonts_customization.xml",
                "/system_ext/etc/fonts_customization.xml", "/vendor/etc/fonts.xml"}) {
            try (java.io.InputStream stream = new java.io.FileInputStream(path)) {
                org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
                parser.setInput(stream, null);
                for (int event = parser.next(); event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT; event = parser.next()) {
                    if (event != org.xmlpull.v1.XmlPullParser.START_TAG ||
                            !(parser.getName().equals("family") || parser.getName().equals("alias"))) continue;
                    String name = parser.getAttributeValue(null, "name");
                    if (name != null && !name.isEmpty() &&
                            !Typeface.create(name, Typeface.NORMAL).equals(Typeface.DEFAULT)) configured.add(name);
                }
            } catch (java.io.IOException | org.xmlpull.v1.XmlPullParserException | RuntimeException ignored) {
                // Vendor configuration is optional; standard Android families remain available.
            }
        }
        families.addAll(configured);
        return new java.util.ArrayList<>(families);
    }
    static boolean exists(Context context, String game) {
        return !systemFamily(context, game).isEmpty() || file(context, game).isFile();
    }
    static Typeface load(Context context, String game) {
        String family = systemFamily(context, game);
        if (!family.isEmpty()) return Typeface.create(family, Typeface.NORMAL);
        File font = file(context, game);
        if (!font.isFile()) return null;
        try { return new Typeface.Builder(font).build(); }
        catch (RuntimeException error) {
            android.util.Log.w("OpenBGI", "Cannot load selected game font", error);
            return null;
        }
    }
    static synchronized String copy(Context context, String game, Uri source) throws IOException {
        String name = "";
        try (android.database.Cursor cursor = context.getContentResolver().query(source,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        if (name == null || !name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(ttf|otf|ttc)$"))
            throw new IOException("Expected TTF, OTF or TTC");
        File target = file(context, game);
        if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs())
            throw new IOException("Cannot create font storage");
        File temporary = File.createTempFile("font-", ".tmp", target.getParentFile());
        try {
            try (java.io.InputStream input = context.getContentResolver().openInputStream(source);
                 java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {
                if (input == null) throw new IOException("Cannot open selected font");
                byte[] buffer = new byte[32768];
                int n; long total = 0;
                while ((n = input.read(buffer)) != -1) {
                    total += n;
                    if (total > 64L * 1024 * 1024) throw new IOException("Font exceeds 64 MiB");
                    output.write(buffer, 0, n);
                }
                if (total == 0) throw new IOException("Empty font file");
                output.getFD().sync();
            }
            try {
                if (new Typeface.Builder(temporary).build() == null) throw new IOException("Invalid font");
            }
            catch (RuntimeException error) { throw new IOException("Invalid font", error); }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException error) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            context.getSharedPreferences("gameFonts", Context.MODE_PRIVATE).edit().putString(game, name).remove("system:" + game).commit();
            return name;
        } finally { temporary.delete(); }
    }
    static synchronized boolean reset(Context context, String game) {
        File font = file(context, game);
        if (font.exists() && !font.delete()) return false;
        context.getSharedPreferences("gameFonts", Context.MODE_PRIVATE).edit().remove(game).remove("system:" + game).commit();
        return true;
    }
}
