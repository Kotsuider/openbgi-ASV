/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Android's container/codec sniffing also accepts MP4 files named .arc. */
final class AndroidMovie implements TextureView.SurfaceTextureListener {
    interface Geometry { Rect map(int x, int y, int w, int h); }
    private final Activity activity;
    private final GameDocuments documents;
    private final TextureView texture;
    private final Geometry geometry;
    private volatile MediaPlayer player;
    private Surface surface;
    private volatile boolean running;
    private volatile int duration;
    private int x, y, w, h;
    private CountDownLatch ready;

    AndroidMovie(Activity activity, GameDocuments documents, FrameLayout parent, Geometry geometry) {
        this.activity = activity; this.documents = documents; this.geometry = geometry;
        texture = new TextureView(activity);
        texture.setOpaque(true);
        texture.setVisibility(View.GONE);
        texture.setSurfaceTextureListener(this);
        parent.addView(texture, 1, new FrameLayout.LayoutParams(1, 1));
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (running) layout(); });
    }
    boolean open(String path) {
        stop();
        String relative = path.replace('\\', '/');
        if (relative.length() >= 3 && relative.charAt(1) == ':') relative = relative.substring(3);
        while (relative.startsWith("/")) relative = relative.substring(1);
        // MPEG-1 is handled by the embedded decoder, avoiding unsupported platform paths.
        int signatureFd = documents.open(relative);
        if (signatureFd < 0) return false;
        try (ParcelFileDescriptor.AutoCloseInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                ParcelFileDescriptor.adoptFd(signatureFd))) {
            byte[] signature = new byte[4];
            if (input.read(signature) == 4 && signature[0] == 0 && signature[1] == 0 &&
                    signature[2] == 1 && (signature[3] & 255) == 0xba) return false;
        } catch (java.io.IOException error) { return false; }
        int fd = documents.open(relative);
        if (fd < 0) return false;
        MediaPlayer candidate = new MediaPlayer();
        try (ParcelFileDescriptor source = ParcelFileDescriptor.adoptFd(fd)) {
            candidate.setDataSource(source.getFileDescriptor());
            candidate.setOnCompletionListener(p -> { if (player == p) running = false; });
            candidate.setOnErrorListener((p, what, extra) -> {
                if (player == p) running = false;
                android.util.Log.w("OpenBGI", "Movie error " + what + "/" + extra);
                return true;
            });
            candidate.prepare();
            duration = candidate.getDuration();
            player = candidate;
            android.util.Log.i("OpenBGI", "MediaPlayer opened " + relative + " (" + duration + " ms)");
            return true;
        } catch (java.io.IOException | RuntimeException error) {
            candidate.release();
            android.util.Log.w("OpenBGI", "Cannot open movie " + relative, error);
            return false;
        }
    }
    int start(int x, int y, int w, int h, int volume) {
        if (player == null) return -1;
        this.x = x; this.y = y; this.w = w; this.h = h;
        setVolume(volume);
        CountDownLatch latch = new CountDownLatch(1);
        ready = latch;
        activity.runOnUiThread(() -> {
            layout(); texture.setVisibility(View.VISIBLE);
            if (texture.isAvailable()) attach(texture.getSurfaceTexture());
        });
        try {
            if (!latch.await(5, TimeUnit.SECONDS) || !running) { stop(); return -1; }
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); stop(); return -1; }
        return Math.max(0, duration);
    }
    private void layout() {
        Rect rectangle = geometry.map(x, y, w, h);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.max(1, rectangle.width()), Math.max(1, rectangle.height()));
        params.leftMargin = rectangle.left; params.topMargin = rectangle.top;
        texture.setLayoutParams(params);
    }
    private void attach(SurfaceTexture source) {
        MediaPlayer p = player;
        if (p == null || source == null) return;
        try {
            if (surface != null) surface.release();
            surface = new Surface(source);
            p.setSurface(surface); p.start(); running = true;
        } catch (RuntimeException error) {
            running = false;
            android.util.Log.w("OpenBGI", "Cannot start movie surface", error);
        } finally { if (ready != null) ready.countDown(); }
    }
    boolean isRunning() { return running; }
    void setVolume(int value) {
        MediaPlayer p = player;
        if (p == null) return;
        float gain = value <= -10000 ? 0f : value >= 0 ? 1f : (float)Math.pow(10, value / 2000.0);
        try { p.setVolume(gain, gain); } catch (IllegalStateException ignored) { }
    }
    void stop() {
        running = false;
        MediaPlayer p = player; player = null;
        if (p != null) p.release();
        activity.runOnUiThread(() -> {
            // A later movie may already have been opened on the engine thread.
            if (player == null) {
                texture.setVisibility(View.GONE);
                if (surface != null) { surface.release(); surface = null; }
            }
        });
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int width, int height) { attach(s); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int width, int height) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { running = false; return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
}
