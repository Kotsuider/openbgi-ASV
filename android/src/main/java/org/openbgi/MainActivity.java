/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.app.Activity;
import android.view.ViewConfiguration;
import android.graphics.*;
import android.os.Bundle;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    static { System.loadLibrary("openbgi_android"); }
    private native int runEngine(String overlay, String profile, String arguments, String logPath);
    private native void input(int type, int x, int y, int down, int button);
    private GameView view;
    private AndroidMovie movie;
    private GameDocuments documents;
    private TranslationConfig translation;
    private final ArrayList<Paint> fonts = new ArrayList<>();
    private Typeface externalTypeface, manualTypeface;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String folder = getIntent().getStringExtra("game");
        String initialProfile = getIntent().getStringExtra("profile");
        android.net.Uri tree = getIntent().getData();
        String saveKey = tree == null ? "" : java.util.UUID.nameUUIDFromBytes(
                tree.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        File overlay = new File(new File(getFilesDir(), "saves"), saveKey);
        if (tree == null || (!overlay.isDirectory() && !overlay.mkdirs())) {
            showStatus("Game folder or save storage is unavailable.");
            return;
        }
        documents = new GameDocuments(getContentResolver(), tree);
        view = new GameView();
        GameControls controls = new GameControls(this, view, this::pressVirtualKey, this::setVirtualKey);
        setContentView(controls);
        movie = new AndroidMovie(this, documents, controls, (x, y, w, h) -> {
            synchronized (view) {
                if (view.frame == null) return new Rect(0, 0, view.getWidth(), view.getHeight());
                view.fitFrame();
                float sx = view.bounds.width() / view.frame.getWidth();
                float sy = view.bounds.height() / view.frame.getHeight();
                return new Rect(Math.round(view.bounds.left + x * sx), Math.round(view.bounds.top + y * sy),
                        Math.round(view.bounds.left + (x+w) * sx), Math.round(view.bounds.top + (y+h) * sy));
            }
        });
        new Thread(() -> {
            GameConfigurations configurations = new GameConfigurations(this, documents);
            GameConfigurations.Option config = configurations.resolve(getIntent().getStringExtra("configuration"));
            String profile = !getIntent().getBooleanExtra("profileOverride", false) && !config.engine.isEmpty()
                    ? config.engine : initialProfile;
            if (profile == null) {
                runOnUiThread(() -> showStatus(getString(R.string.no_matching_profile)));
                return;
            }
            String override = getIntent().getStringExtra("arguments");
            String launchArguments = override == null ? config.arguments : override;
            org.json.JSONObject localConfig = TranslationConfig.read(documents, GameLaunchConfig.FILE);
            if (!launchArguments.contains("--letter-spacing=")) {
                Object spacing = localConfig.opt("letter_spacing");
                if (spacing instanceof Number) {
                    double value = ((Number) spacing).doubleValue();
                    if (value == Math.rint(value) && value >= -16 && value <= 64)
                        launchArguments += " --letter-spacing=" + (int)value;
                }
            }
            translation = new TranslationConfig(documents, config.uif);
            manualTypeface = GameFont.load(this, tree.toString());
            if (!getIntent().getBooleanExtra("autoFont", true) || !config.autoFont || translation.hasFont() || manualTypeface != null)
                launchArguments += " --no-auto-font";
            android.util.Log.i("OpenBGI", "Configuration: " + config.label);
            documents.setTranslation(translation);
            File log = new File(getFilesDir(), "openbgi-last.log");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(log)) {
                String header = new java.util.Date() + "\n" + folder + " | Profile: " + profile + "\nConfiguration: " + config.label + "\n\n";
                stream.write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException error) {
                android.util.Log.w("OpenBGI", "Cannot create launch log", error);
            }
            if (manualTypeface != null) {
                try (java.io.FileOutputStream stream = new java.io.FileOutputStream(log, true)) {
                    String systemFamily = GameFont.systemFamily(this, tree.toString());
                    String selectedFont = systemFamily.isEmpty() ? GameFont.name(this, tree.toString()) : "system family " + systemFamily;
                    stream.write(("Using manually selected game font: " + selectedFont + "\n")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (java.io.IOException error) { android.util.Log.w("OpenBGI", "Cannot log game font", error); }
            }
            int result = runEngine(overlay.getAbsolutePath(), profile,
                    "--movies=player " + launchArguments, log.getAbsolutePath());
            runOnUiThread(() -> {
                if (result != 0) android.util.Log.w("OpenBGI", "Engine exit: " + result);
                finish();
                new android.os.Handler(getMainLooper()).postDelayed(
                        () -> android.os.Process.killProcess(android.os.Process.myPid()), 200);

            });
        }, "OpenBGI").start();
    }

    @Override public void onBackPressed() {
        if (view == null) super.onBackPressed();
        else input(7, 0, 0, 0, 0);
    }

    @Override protected void onDestroy() {
        if (view != null) input(7, 0, 0, 0, 0);
        if (movie != null) movie.stop();
        super.onDestroy();
    }

    private void showStatus(String message) {
        TextView text = new TextView(this);
        text.setPadding(32, 32, 32, 32);
        text.setTextIsSelectable(true);
        text.setText(message);
        setContentView(text);
    }

    private boolean movieOpen(String path) { return movie.open(path); }
    private int movieStart(int x, int y, int w, int h, int volume) { return movie.start(x, y, w, h, volume); }
    private void movieStop() { movie.stop(); }
    private boolean movieRunning() { return movie.isRunning(); }
    private void movieVolume(int volume) { movie.setVolume(volume); }

    private int[] documentStat(String path) { return documents.stat(path); }
    private int documentOpen(String path) { return documents.open(path); }
    private byte[] documentList(String path) { return documents.list(path); }

    private void canvasSetup(int width, int height, boolean visible) {
        synchronized (view) {
            if (width > 0 && height > 0 &&
                    (view.frame == null || view.frame.getWidth() != width || view.frame.getHeight() != height)) {
                view.frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                view.frame.setHasAlpha(false);
                view.frame.eraseColor(Color.BLACK);
            }
            view.visible = visible;
        }
        view.postInvalidate();
    }

    private static native boolean copyFrame(Bitmap frame, ByteBuffer rgba, int width, int height, int x, int y);

    private void present(ByteBuffer rgba, int width, int height, int x, int y) {
        synchronized (view) {
            if (view.frame == null || !copyFrame(view.frame, rgba, width, height, x, y)) return;
        }
        view.postInvalidate();
    }

    private void fillBlack() {
        synchronized (view) {
            if (view.frame != null) view.frame.eraseColor(Color.BLACK);
        }
        view.postInvalidate();
    }

    private int[] sjisTable() {
        Charset cp932 = Charset.forName("windows-31j");
        int[] table = new int[65536];
        byte[] pair = new byte[2];
        for (int lead = 0x81; lead <= 0xfc; ++lead) {
            if (lead > 0x9f && lead < 0xe0) continue;
            pair[0] = (byte) lead;
            for (int trail = 0x40; trail <= 0xfc; ++trail) {
                if (trail == 0x7f) continue;
                pair[1] = (byte) trail;
                String value = new String(pair, cp932);
                if (value.length() == 1 && value.charAt(0) != 0xfffd)
                    table[(lead << 8) | trail] = value.charAt(0);
            }
        }
        return table;
    }

    private boolean fontAddMemory(byte[] data) {
        try {
            File file = File.createTempFile("engine-font-", ".ttf", getCacheDir());
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) { out.write(data); }
            Typeface loaded = Typeface.createFromFile(file);
            // Explicit selections and translation fonts keep priority over engine defaults.
            if (externalTypeface == null && manualTypeface == null && !translation.hasFont()) externalTypeface = loaded;
            file.delete();
            return true;
        } catch (java.io.IOException | RuntimeException error) { return false; }
    }

    private void fontAutoLoad() {
        for (String path : documents.fontFiles()) if (fontAddFile(path)) return;
    }

    private boolean fontAddFile(String path) {
        String relative = path.replace('\\', '/');
        if (relative.startsWith("C:/") || relative.startsWith("c:/")) relative = relative.substring(3);
        while (relative.startsWith("/")) relative = relative.substring(1);
        int fd = documents.openOriginal(relative);
        if (fd < 0) return false;
        try (android.os.ParcelFileDescriptor file = android.os.ParcelFileDescriptor.adoptFd(fd)) {
            Typeface loaded = new Typeface.Builder(file.getFileDescriptor()).build();
            if (loaded == null) return false;
            externalTypeface = loaded;
            android.util.Log.i("OpenBGI", "Loaded command-line font " + path);
            return true;
        } catch (java.io.IOException | RuntimeException error) {
            android.util.Log.w("OpenBGI", "Cannot load command-line font " + path, error);
            return false;
        }
    }

    private final java.util.Map<Integer, Charset> glyphCodepages = new java.util.HashMap<>();
    private int fontDecode(int value, int codepage) {
        Charset charset = glyphCodepages.get(codepage);
        if (charset == null) {
            try { charset = Charset.forName(codepage == 932 ? "windows-31j" : "windows-" + codepage); }
            catch (RuntimeException error) { charset = Charset.forName("windows-31j"); }
            glyphCodepages.put(codepage, charset);
        }
        String decoded = new String(new byte[]{(byte)value}, charset);
        return decoded.isEmpty() ? value : decoded.codePointAt(0);
    }

    private int fontOpen(String family, int height, int width, int weight, int italic) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        Typeface base = family == null ? translation.typeface() : Typeface.create(family, Typeface.NORMAL);
        if (externalTypeface != null) base = externalTypeface;
        if (manualTypeface != null) base = manualTypeface;
        paint.setTypeface(Typeface.create(base, weight, italic != 0));
        paint.setTextSize(100);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        paint.setTextSize(height * 100f / (metrics.descent - metrics.ascent));
        if (width > 0) paint.setTextScaleX(2f * width / Math.max(1f, paint.measureText("\u3042")));
        fonts.add(paint);
        return fonts.size() - 1;
    }

    private int fontAdvance(int id, int cp) {
        return Math.round(fonts.get(id).measureText(new String(Character.toChars(translation.glyph(cp)))));
    }

    private void fontRender(int id, int cp, ByteBuffer dst, int pitch, int width, int height, int mono) {
        if (width <= 0 || height <= 0) return;
        Paint paint = fonts.get(id);
        Bitmap glyph = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(glyph).drawText(new String(Character.toChars(translation.glyph(cp))), 0, -paint.ascent(), paint);
        int[] pixels = new int[width * height];
        glyph.getPixels(pixels, 0, width, 0, 0, width, height);
        glyph.recycle();
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int alpha = Color.alpha(pixels[y * width + x]);
                if (mono == 0) dst.put(y * pitch + x, (byte) alpha);
                else if (alpha >= 128) {
                    int offset = y * pitch + x / 8;
                    dst.put(offset, (byte) (dst.get(offset) | (0x80 >> (x % 8))));
                }
            }
        }
    }

    private AudioTrack audio;
    private int queuedFrames;
    private boolean loggedSignal;

    private boolean audioStart(int rate) {
        audioStop();
        int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) return false;
        try {
            audio = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, rate * 4 / 5),
                    AudioTrack.MODE_STREAM);
            if (audio.getState() != AudioTrack.STATE_INITIALIZED) {
                audioStop();
                return false;
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                audio.setStartThresholdInFrames(Math.min(audio.getBufferSizeInFrames(), rate / 10));
            }
            audio.play();
            return true;
        } catch (IllegalArgumentException | IllegalStateException error) {
            audioStop();
            return false;
        }
    }

    private void audioStop() {
        if (audio != null) {
            audio.release();
            audio = null;
        }
        queuedFrames = 0;
        loggedSignal = false;
    }

    private int audioWanted() {
        if (audio == null) return 0;
        int pending = queuedFrames - audio.getPlaybackHeadPosition();
        return Math.max(0, audio.getBufferSizeInFrames() - Math.max(0, pending));
    }

    private void audioPush(ByteBuffer samples) {
        if (audio == null) return;
        int written = audio.write(samples, samples.remaining(), AudioTrack.WRITE_NON_BLOCKING);
        if (written <= 0) return;
        queuedFrames += written / 4;
        if (!loggedSignal) {
            for (int i = samples.position() - written; i < samples.position(); ++i) {
                if (samples.get(i) != 0) {
                    loggedSignal = true;
                    android.util.Log.i("OpenBGI", "Non-silent PCM accepted by AudioTrack");
                    break;
                }
            }
        }
    }

    private void pressVirtualKey(int key) {
        if (key == GameControls.RIGHT_MOUSE) {
            view.click(1);
            return;
        }
        setVirtualKey(key, true);
        view.postDelayed(() -> setVirtualKey(key, false), 70);
    }

    private void setVirtualKey(int key, boolean down) {
        if (key == GameControls.RIGHT_MOUSE) view.mouseButton(1, down);
        else input(down ? 1 : 2, key, 0, 0, 0);
    }

    private final class GameView extends View {
        Bitmap frame;
        boolean visible;
        private final RectF bounds = new RectF();
        private final Paint filter = new Paint(Paint.FILTER_BITMAP_FLAG);

        GameView() { super(MainActivity.this); }

        synchronized void click(int button) {
            if (frame == null || !visible) return;
            int x = frame.getWidth() / 2;
            int y = frame.getHeight() / 2;
            clickAt(x, y, button);
        }

        synchronized void mouseButton(int button, boolean down) {
            if (frame == null || !visible) return;
            input(4, frame.getWidth() / 2, frame.getHeight() / 2, down ? 1 : 0, button);
        }

        private void fitFrame() {
            float scale = Math.min(getWidth() / (float) frame.getWidth(), getHeight() / (float) frame.getHeight());
            float width = frame.getWidth() * scale, height = frame.getHeight() * scale;
            float left = (getWidth() - width) / 2f, top = (getHeight() - height) / 2f;
            bounds.set(left, top, left + width, top + height);
        }

        @Override protected synchronized void onDraw(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            if (frame != null && visible) {
                fitFrame();
                canvas.drawBitmap(frame, null, bounds, filter);
            }
        }

        private final float wheelStep = 48 * getResources().getDisplayMetrics().density;
        private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private float startX, startY, wheelY, secondX, secondY;
        private boolean moved, twoFingers, invalidGesture;
        private int tapX, tapY;

        @Override public synchronized boolean onTouchEvent(MotionEvent event) {
            if (frame == null || !visible) return true;
            fitFrame();
            if (bounds.isEmpty()) return true;
            float touchX = event.getX(), touchY = event.getY();
            int x = gameX(touchX), y = gameY(touchY);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = touchX;
                    startY = wheelY = touchY;
                    moved = twoFingers = false;
                    invalidGesture = !bounds.contains(touchX, touchY);
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() != 2 || twoFingers || moved) {
                        invalidGesture = true;
                        break;
                    }
                    twoFingers = true;
                    secondX = event.getX(1);
                    secondY = event.getY(1);
                    invalidGesture |= !bounds.contains(secondX, secondY);
                    tapX = gameX((touchX + secondX) / 2);
                    tapY = gameY((touchY + secondY) / 2);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (invalidGesture) break;
                    if (twoFingers && event.getPointerCount() < 2) break;
                    moved |= Math.abs(touchX - startX) > touchSlop || Math.abs(touchY - startY) > touchSlop;
                    if (twoFingers) {
                        if (event.getPointerCount() == 2) {
                            moved |= Math.abs(event.getX(1) - secondX) > touchSlop ||
                                    Math.abs(event.getY(1) - secondY) > touchSlop;
                        }
                        break;
                    }
                    input(3, x, y, 0, 0);
                    if (Math.abs(touchY - startY) >= Math.abs(touchX - startX)) {
                        int steps = (int) ((touchY - wheelY) / wheelStep);
                        if (steps != 0) {
                            input(5, -steps * 120, 0, 0, 0);
                            wheelY += steps * wheelStep;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (!invalidGesture && !moved) {
                        if (!twoFingers) clickAt(x, y, 0);
                        else if (event.getEventTime() - event.getDownTime() <= 350) clickAt(tapX, tapY, 1);
                    }
                    break;
                default:
                    break;
            }
            return true;
        }

        private int gameX(float x) {
            return Math.max(0, Math.min(frame.getWidth() - 1,
                    (int) ((x - bounds.left) * frame.getWidth() / bounds.width())));
        }

        private int gameY(float y) {
            return Math.max(0, Math.min(frame.getHeight() - 1,
                    (int) ((y - bounds.top) * frame.getHeight() / bounds.height())));
        }

        private void clickAt(int x, int y, int button) {
            input(4, x, y, 1, button);
            input(4, x, y, 0, button);
        }
    }
}
