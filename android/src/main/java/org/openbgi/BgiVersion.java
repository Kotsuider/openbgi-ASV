/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BgiVersion {
    private static final Pattern VERSION = Pattern.compile(
            "Buriko General Interpreter ver\\s+(\\d+(?:\\.\\d+)+)(?:\\s*\\(\\s*build\\s*:\\s*(\\d+(?:\\.\\d+)*))?",
            Pattern.CASE_INSENSITIVE);
    private static final int LIMIT = 8 * 1024 * 1024;
    final String label, profile;
    final boolean exact;

    private BgiVersion(String label, String profile, boolean exact) {
        this.label = label;
        this.profile = profile;
        this.exact = exact;
    }

    static BgiVersion detect(GameDocuments documents, String[] profiles) {
        for (String name : documents.executableNames()) {
            int fd = documents.openOriginal(name);
            if (fd < 0) continue;
            try (ParcelFileDescriptor.AutoCloseInputStream input =
                         new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(fd))) {
                byte[] header = new byte[2];
                if (input.read(header) != 2 || header[0] != 'M' || header[1] != 'Z') continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int size;
                while (bytes.size() < LIMIT &&
                        (size = input.read(chunk, 0, Math.min(chunk.length, LIMIT - bytes.size()))) > 0) {
                    bytes.write(chunk, 0, size);
                }
                Matcher match = VERSION.matcher(new String(bytes.toByteArray(), StandardCharsets.ISO_8859_1));
                if (match.find()) return select(match.group(1), match.group(2), profiles);
            } catch (IOException error) {
                android.util.Log.w("OpenBGI", "Could not inspect " + name, error);
            }
        }
        return new BgiVersion(null, null, false);
    }

    private static BgiVersion select(String version, String build, String[] profiles) {
        String label = build == null ? version : version + " build " + build;
        String key = build == null ? version : version + "/" + build.split("\\.")[0];
        // Releases such as 1.685.3 share the 1.685 engine generation.
        String generation = version.replaceFirst("^(1\\.\\d{3})\\.\\d+$", "$1");
        for (String profile : profiles) {
            if (profile.equals(key) || (!version.equals("1.69") && profile.equals(generation)))
                return new BgiVersion(label, profile, true);
        }
        if ("1.69".equals(version) && build != null) {
            int target;
            try {
                target = Integer.parseInt(build.split("\\.")[0]);
            } catch (NumberFormatException error) {
                return new BgiVersion(label, null, false);
            }
            String best = null;
            long distance = Long.MAX_VALUE;
            for (String profile : profiles) {
                if (!profile.startsWith("1.69/")) continue;
                try {
                    long difference = Math.abs((long) Integer.parseInt(profile.substring(5)) - target);
                    if (difference < distance) {
                        distance = difference;
                        best = profile;
                    }
                } catch (NumberFormatException ignored) { }
            }
            return new BgiVersion(label, best, false);
        }
        return new BgiVersion(label, null, false);
    }
}
