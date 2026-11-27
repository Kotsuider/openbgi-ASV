/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class GameDocuments {
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    };

    private static final class Entry {
        final String id, name;
        final int attrs;
        final long size, modified;
        Entry(String id, String name, boolean directory, long size, long modified) {
            this.id = id;
            this.name = name;
            this.attrs = directory ? 16 : 128;
            this.size = size;
            this.modified = modified;
        }
    }

    private final ContentResolver resolver;
    private final Uri tree;
    private final Entry root;
    private TranslationConfig translation;
    private final Map<String, String> resolved = new HashMap<>();
    private final Map<String, List<Entry>> directories = new HashMap<>();

    GameDocuments(ContentResolver resolver, Uri tree) {
        this.resolver = resolver;
        this.tree = tree;
        root = new Entry(DocumentsContract.getTreeDocumentId(tree), "", true, 0, 0);
    }

    private List<Entry> children(Entry dir) {
        List<Entry> cached = directories.get(dir.id);
        if (cached != null) return cached;
        List<Entry> entries = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir.id);
        try (Cursor cursor = resolver.query(uri, COLUMNS, null, null, null)) {
            if (cursor == null) return entries;
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null) continue;
                entries.add(new Entry(cursor.getString(0), name,
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)),
                        cursor.getLong(3), cursor.getLong(4)));
            }
        }
        directories.put(dir.id, entries);
        return entries;
    }

    private Entry find(String path) {
        Entry entry = root;
        if (path.isEmpty()) return entry;
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || entry.attrs != 16)
                return null;
            Entry match = null;
            for (Entry child : children(entry)) {
                if (child.name.equals(part)) {
                    match = child;
                    break;
                }
                if (match == null && child.name.equalsIgnoreCase(part)) match = child;
            }
            if (match == null) return null;
            entry = match;
        }
        return entry;
    }

    List<String> executableNames() {
        List<String> files = new ArrayList<>();
        try {
            for (Entry entry : children(root)) {
                if (entry.attrs != 16 && entry.name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
                    if (entry.name.equalsIgnoreCase("BGI.exe")) files.add(0, entry.name);
                    else files.add(entry.name);
                }
            }
        } catch (RuntimeException error) {
            android.util.Log.w("OpenBGI", "Cannot list engine executables", error);
        }
        return files;
    }

    List<String> fontFiles() {
        List<String> paths = new ArrayList<>();
        List<String> queue = new ArrayList<>(); queue.add("");
        for (int head = 0; head < queue.size() && head < 4096; head++) {
            String prefix = queue.get(head);
            Entry dir = find(prefix);
            if (dir == null) continue;
            List<Entry> entries = new ArrayList<>(children(dir));
            entries.sort((a, b) -> a.name.compareTo(b.name));
            for (Entry entry : entries) {
                String path = prefix.isEmpty() ? entry.name : prefix + "/" + entry.name;
                if (entry.attrs == 16) { if (queue.size() < 4096 && path.split("/").length < 16) queue.add(path); }
                else if (path.toLowerCase(Locale.ROOT).matches(".*\\.(ttf|otf|ttc)$")) paths.add(path);
            }
        }
        return paths;
    }

    boolean isGame() {
        return stat("system.arc") != null || stat("ipl._bp") != null;
    }

    String name() {
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, root.id);
        try (Cursor cursor = resolver.query(uri, new String[]{COLUMNS[1]}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getString(0) != null)
                return cursor.getString(0);
        } catch (RuntimeException error) {
            android.util.Log.w("OpenBGI", "Could not read folder name", error);
        }
        return root.id;
    }

    void setTranslation(TranslationConfig translation) {
        this.translation = translation;
        resolved.clear();
    }

    private String resolve(String path) {
        if (translation == null) return path;
        String target = resolved.get(path);
        if (target == null) {
            target = translation.redirect(path, this);
            resolved.put(path, target);
            if (!target.equals(path)) android.util.Log.d("OpenBGI", "Patch: " + path + " -> " + target);
        }
        return target;
    }

    boolean hasOriginalFile(String path) {
        Entry entry = find(path);
        return entry != null && entry.attrs != 16;
    }

    int[] stat(String path) {
        try {
            Entry e = find(resolve(path));
            if (e == null) return null;
            long time = e.modified > 0 ? (e.modified + 11644473600000L) * 10000L : 0;
            return new int[]{e.attrs, (int) e.size, (int) (e.size >>> 32), (int) time, (int) (time >>> 32)};
        } catch (RuntimeException error) {
            android.util.Log.w("OpenBGI", "Cannot inspect " + path, error);
            return null;
        }
    }

    int open(String path) {
        return openFile(path, true);
    }

    int openOriginal(String path) {
        return openFile(path, false);
    }

    private int openFile(String path, boolean applyPatch) {
        try {
            Entry e = find(applyPatch ? resolve(path) : path);
            if (e == null || e.attrs == 16) return -1;
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, e.id);
            try (ParcelFileDescriptor file = resolver.openFileDescriptor(uri, "r")) {
                return file == null ? -1 : file.detachFd();
            }
        } catch (IOException | RuntimeException error) {
            android.util.Log.w("OpenBGI", "Cannot open " + path, error);
            return -1;
        }
    }

    private static void write32(ByteArrayOutputStream output, long value) {
        for (int shift = 0; shift < 32; shift += 8) output.write((int) (value >>> shift));
    }

    byte[] list(String path) {
        try {
            Entry dir = find(path);
            if (dir == null || dir.attrs != 16) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            for (Entry e : children(dir)) {
                byte[] name = e.name.getBytes(StandardCharsets.UTF_8);
                if (name.length > 65535) continue;
                write32(output, e.attrs);
                write32(output, e.size);
                write32(output, e.size >>> 32);
                output.write(name.length);
                output.write(name.length >>> 8);
                output.write(name, 0, name.length);
            }
            return output.toByteArray();
        } catch (RuntimeException error) {
            android.util.Log.w("OpenBGI", "Cannot list " + path, error);
            return null;
        }
    }
}
