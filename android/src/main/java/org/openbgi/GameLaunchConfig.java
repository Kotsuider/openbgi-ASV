/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import org.json.JSONObject;

final class GameLaunchConfig {
    static final String FILE = "OBAConfig.json";
    final String preview, uif, arguments;

    GameLaunchConfig(GameDocuments documents) {
        JSONObject config = TranslationConfig.read(documents, FILE);
        preview = config.optString("preview", "").replace('\\', '/');
        uif = config.optString("uif", "");
        arguments = config.optString("arguments", "");
    }

    Bitmap previewBitmap(GameDocuments documents) {
        if (preview.isEmpty()) return null;
        int fd = documents.openOriginal(preview);
        if (fd < 0) return null;
        try (ParcelFileDescriptor file = ParcelFileDescriptor.adoptFd(fd)) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, opts);
            opts.inSampleSize = 1;
            while (Math.max(opts.outWidth, opts.outHeight) / opts.inSampleSize > 1280)
                opts.inSampleSize *= 2;
            opts.inJustDecodeBounds = false;
            return BitmapFactory.decodeFileDescriptor(file.getFileDescriptor(), null, opts);
        } catch (IOException | RuntimeException error) {
            android.util.Log.w("OpenBGI", "Cannot read configured preview", error);
            return null;
        }
    }
}
