/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.BiConsumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class GameControls extends FrameLayout {
    private final SharedPreferences settings;
    static final int RIGHT_MOUSE = -1;
    private final Control gear;
    private final List<Control> keys = new ArrayList<>();
    private final List<Key> windowsKeys = windowsKeys();
    private final IntConsumer sendKey;
    private final BiConsumer<Integer, Boolean> keyState;
    private static final String[] ICONS = {"Text", "\u25b6", "\u23f8", "\u2630", "\u2190", "\u2191",
            "\u2192", "\u2193", "\u2713", "\u2715", "\u2605", "\u23ce", "Blank"};
    private static final int BLANK = ICONS.length - 1;
    private static final String[] STYLES = {"Classic", "Rounded", "Outlined"};
    private boolean editing;
    private int size;

    GameControls(Context context, View game, IntConsumer sendKey, BiConsumer<Integer, Boolean> keyState) {
        super(context);
        this.sendKey = sendKey;
        this.keyState = keyState;
        settings = context.getSharedPreferences("gameControls", Context.MODE_PRIVATE);
        size = settings.getInt("size", 58);
        addView(game, new LayoutParams(-1, -1));

        loadKeys();
        if (!settings.getBoolean("editableDefaults", false)) {
            keys.add(0, keyControl("escape", "Esc", 27, 0, false));
            keys.add(1, keyControl("menu", "Rclick", RIGHT_MOUSE, 0, false));
            keys.add(2, keyControl("skip", "Skip", 17, 0, true));
            persistKeys();
            settings.edit().putBoolean("editableDefaults", true).apply();
        }
        for (Control button : keys) {
            if (button.id.equals("menu") && button.key == RIGHT_MOUSE && button.label.equals("Menu")) {
                button.label = "Rclick";
                persistKeys();
                break;
            }
        }
        gear = new Control("gear", "\u2699", 0, 0, false, () -> {
            if (editing) {
                editing = false;
                update();
            } else {
                showSettings();
            }
        });
        gear.setAlpha(0.78f);
        LayoutParams placement = new LayoutParams(dp(54), dp(54), Gravity.TOP | Gravity.END);
        placement.setMargins(0, dp(8), dp(10), 0);
        addView(gear, placement);
        addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> placeControls());
        update();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class Control extends FrameLayout {
        final String id;
        final Runnable callback;
        final int key;
        String label;
        int icon;
        final boolean hold;
        private boolean held;
        int currentStyle = -1;
        String currentLabel;
        private float startX, startY, buttonX, buttonY;

        Control(String id, String label, int key, int icon, boolean hold, Runnable callback) {
            super(GameControls.this.getContext());
            this.id = id;
            this.label = label;
            this.key = key;
            this.icon = icon;
            this.hold = hold;
            this.callback = callback;
        }

        String caption() {
            return icon == BLANK ? "" : icon == 0 ? label : ICONS[icon];
        }

        boolean isRound() {
            String text = caption();
            return icon != 0 || text.codePointCount(0, text.length()) <= 1;
        }

        void render() {
            int style = settings.getInt("style", 0);
            String caption = caption();
            if (style == currentStyle && caption.equals(currentLabel)) return;
            currentStyle = style;
            currentLabel = caption;
            removeAllViews();
            View button;
            if (style == 0) {
                Button classic = new Button(getContext());
                classic.setText(caption);
                classic.setSingleLine();
                classic.setMinWidth(0);
                classic.setMinimumWidth(0);
                classic.setPadding(dp(8), 0, dp(8), 0);
                if (isRound()) {
                    GradientDrawable circle = new GradientDrawable();
                    circle.setShape(GradientDrawable.OVAL);
                    circle.setColor(0xff535353);
                    classic.setBackground(new RippleDrawable(
                            ColorStateList.valueOf(0x44ffffff), circle, null));
                    classic.setTextColor(Color.WHITE);
                }
                classic.setOnClickListener(view -> callback.run());
                button = classic;
            } else {
                button = GameButtonStyle.create(getContext(), caption, style == 2, callback);
            }
            button.setContentDescription(id.equals("gear")
                    ? (editing ? "Finish editing game controls" : "Game controls settings") : label);
            addView(button, new LayoutParams(-1, -1));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            return !id.equals("gear") && (editing || hold);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!editing && hold) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!held) {
                            held = true;
                            keyState.accept(key, true);
                            setAlpha(1f);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        release();
                        break;
                    default:
                        break;
                }
                return true;
            }
            if (!editing) return super.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getRawX();
                    startY = event.getRawY();
                    buttonX = getX();
                    buttonY = getY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    setX(clamp(buttonX + event.getRawX() - startX, GameControls.this.getWidth() - getWidth()));
                    setY(clamp(buttonY + event.getRawY() - startY, GameControls.this.getHeight() - getHeight()));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    settings.edit()
                            .putFloat(id + "X", getX() / Math.max(1, GameControls.this.getWidth() - getWidth()))
                            .putFloat(id + "Y", getY() / Math.max(1, GameControls.this.getHeight() - getHeight()))
                            .apply();
                    break;
                default:
                    break;
            }
            return true;
        }
        void release() {
            if (!held) return;
            held = false;
            keyState.accept(key, false);
            setAlpha(0.8f);
        }

        @Override protected void onDetachedFromWindow() {
            release();
            super.onDetachedFromWindow();
        }

    }

    private Control keyControl(String id, String label, int key, int icon, boolean hold) {
        Control control = new Control(id, label, key, icon, hold, () -> sendKey.accept(key));
        addView(control, Math.max(0, getChildCount() - (gear == null ? 0 : 1)),
                new LayoutParams(dp(size + 16), dp(size)));
        return control;
    }

    private static float clamp(float value, int end) {
        return Math.max(0, Math.min(Math.max(0, end), value));
    }

    private void place(Control button, String key, float defaultX, float defaultY) {
        LayoutParams layout = (LayoutParams) button.getLayoutParams();
        button.setX(settings.getFloat(key + "X", defaultX) * Math.max(0, getWidth() - layout.width));
        button.setY(settings.getFloat(key + "Y", defaultY) * Math.max(0, getHeight() - layout.height));
    }

    private void placeControls() {
        for (int i = 0; i < keys.size(); ++i) {
            Control button = keys.get(i);
            switch (button.id) {
                case "escape": place(button, button.id, 0.02f, 0.02f); break;
                case "menu": place(button, button.id, 0.97f, 0.5f); break;
                case "skip": place(button, button.id, 0.97f, 0.88f); break;
                default: place(button, button.id, 0.02f + (i % 5) * 0.19f, 0.94f - (i / 5 % 4) * 0.19f);
            }
        }
    }

    private void update() {
        boolean visible = editing || !settings.getBoolean("hide", false);
        for (Control button : keys) updateButton(button, visible);
        gear.label = editing ? "\u2713" : "\u2699";
        gear.render();
        placeControls();
    }

    private void updateButton(Control button, boolean visible) {
        if (!visible || editing) button.release();
        button.render();
        button.setVisibility(visible ? VISIBLE : GONE);
        button.setAlpha(editing ? 1f : 0.8f);
        LayoutParams layout = (LayoutParams) button.getLayoutParams();
        layout.height = dp(size);
        layout.width = layout.height;
        if (!button.isRound()) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            boolean classic = settings.getInt("style", 0) == 0;
            String caption = button.caption();
            if (classic) caption = caption.toUpperCase(java.util.Locale.getDefault());
            paint.setTypeface(Typeface.create(classic ? "sans-serif" : "sans-serif-medium", Typeface.NORMAL));
            View content = button.getChildAt(0);
            paint.setTextSize(content instanceof Button ? ((Button) content).getTextSize()
                    : TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, getResources().getDisplayMetrics()));
            int screen = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
            layout.width = Math.min(screen, Math.max(layout.height + dp(8),
                    (int) Math.ceil(paint.measureText(caption)) + dp(24)));
        }
        button.setLayoutParams(layout);
    }

    private CheckBox checkbox(LinearLayout panel, String title, String key, boolean fallback) {
        CheckBox check = new CheckBox(getContext());
        check.setText(title);
        check.setChecked(settings.getBoolean(key, fallback));
        panel.addView(check);
        return check;
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), 0);
        CheckBox hide = checkbox(panel, "Hide game buttons", "hide", false);
        TextView label = new TextView(getContext());
        label.setText("Button size");
        panel.addView(label);
        SeekBar scale = new SeekBar(getContext());
        scale.setMax(48);
        scale.setProgress(size - 48);
        scale.setContentDescription("Button size");
        panel.addView(scale);
        TextView styleLabel = new TextView(getContext());
        styleLabel.setText("Button style");
        panel.addView(styleLabel);
        Spinner style = spinner(panel, STYLES);
        style.setSelection(settings.getInt("style", 0));
        Button custom = new Button(getContext());
        custom.setText("Manage key buttons");
        custom.setOnClickListener(view -> manageKeys());
        panel.addView(custom);
        ScrollView scroll = new ScrollView(getContext());
        scroll.addView(panel);
        new AlertDialog.Builder(getContext())
                .setTitle("Game controls")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Move buttons", (dialog, which) ->
                        apply(hide, scale, style, true))
                .setPositiveButton("Done", (dialog, which) ->
                        apply(hide, scale, style, false))
                .show();
    }

    private void apply(CheckBox hide, SeekBar scale, Spinner style, boolean move) {
        size = 48 + scale.getProgress();
        settings.edit().putBoolean("hide", hide.isChecked())
                .putInt("size", size).putInt("style", style.getSelectedItemPosition()).apply();
        editing = move;
        update();
    }
    private Spinner spinner(LinearLayout panel, String[] values) {
        Spinner spinner = new Spinner(getContext());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        panel.addView(spinner);
        return spinner;
    }

    private void manageKeys() {
        String[] actions = new String[keys.size() + 1];
        actions[0] = "Add key button";
        for (int i = 0; i < keys.size(); ++i) actions[i + 1] = keys.get(i).label;
        new AlertDialog.Builder(getContext()).setTitle("Key buttons")
                .setItems(actions, (dialog, index) -> editKey(index == 0 ? null : keys.get(index - 1)))
                .setNegativeButton("Back", null).show();
    }

    private void editKey(Control old) {
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), 0);
        Spinner key = spinner(panel, windowsKeys.stream().map(k -> k.name).toArray(String[]::new));
        Spinner icon = spinner(panel, ICONS);
        EditText label = new EditText(getContext());
        label.setSingleLine();
        label.setHint("Custom label (optional)");
        panel.addView(label);
        CheckBox hold = new CheckBox(getContext());
        hold.setText("Hold while pressed");
        panel.addView(hold);
        if (old != null) {
            for (int i = 0; i < windowsKeys.size(); ++i) {
                if (windowsKeys.get(i).code == old.key) key.setSelection(i);
            }
            icon.setSelection(old.icon);
            hold.setChecked(old.hold);
            label.setText(old.label);
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(getContext()).setTitle("Windows key")
                .setView(panel).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (window, which) -> {
                    Key selected = windowsKeys.get(key.getSelectedItemPosition());
                    String caption = label.getText().toString().trim();
                    int index = old == null ? keys.size() : keys.indexOf(old);
                    if (old != null) {
                        keys.remove(old);
                        removeView(old);
                    }
                    keys.add(index, keyControl(old == null ? UUID.randomUUID().toString() : old.id,
                            caption.isEmpty() ? selected.name : caption,
                            selected.code, icon.getSelectedItemPosition(), hold.isChecked()));
                    saveKeys();
                });
        if (old != null) dialog.setNeutralButton("Remove", (window, which) -> {
            keys.remove(old);
            removeView(old);
            settings.edit().remove(old.id + "X").remove(old.id + "Y").apply();
            saveKeys();
        });
        dialog.show();
    }

    private void loadKeys() {
        try {
            JSONArray stored = new JSONArray(settings.getString("keys", "[]"));
            for (int i = 0; i < stored.length(); ++i) {
                JSONObject key = stored.getJSONObject(i);
                int icon = Math.max(0, Math.min(ICONS.length - 1, key.optInt("icon")));
                keys.add(keyControl(key.getString("id"), key.getString("label"), key.getInt("key"), icon, key.optBoolean("hold")));
            }
        } catch (JSONException error) {
            android.util.Log.w("OpenBGI", "Cannot read game key buttons", error);
        }
    }

    private void saveKeys() {
        persistKeys();
        update();
    }

    private void persistKeys() {
        JSONArray stored = new JSONArray();
        try {
            for (Control key : keys) {
                stored.put(new JSONObject().put("id", key.id).put("label", key.label)
                        .put("key", key.key).put("icon", key.icon).put("hold", key.hold));
            }
            settings.edit().putString("keys", stored.toString()).apply();
        } catch (JSONException error) {
            android.util.Log.w("OpenBGI", "Cannot save game key buttons", error);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) for (Control button : keys) button.release();
    }

    private static final class Key {
        final String name;
        final int code;
        Key(String name, int code) { this.name = name; this.code = code; }
    }

    private static List<Key> windowsKeys() {
        List<Key> keys = new ArrayList<>();
        String[] labels = {"Enter", "Escape", "Space", "Tab", "Backspace", "Shift", "Ctrl", "Alt",
                "Left", "Up", "Right", "Down", "Page Up", "Page Down", "Home", "End", "Insert", "Delete",
                "Pause", "Caps Lock", "Num Lock", "Scroll Lock", "Print Screen", "Windows", "Menu"};
        int[] codes = {13, 27, 32, 9, 8, 16, 17, 18, 37, 38, 39, 40, 33, 34, 36, 35, 45, 46,
                19, 20, 144, 145, 44, 91, 93};
        for (int i = 0; i < labels.length; ++i) keys.add(new Key(labels[i], codes[i]));
        keys.add(new Key("Right mouse button", RIGHT_MOUSE));
        for (char c = 'A'; c <= 'Z'; ++c) keys.add(new Key(String.valueOf(c), c));
        for (int i = 0; i < 10; ++i) keys.add(new Key(Integer.toString(i), '0' + i));
        for (int i = 1; i <= 24; ++i) keys.add(new Key("F" + i, 111 + i));
        for (int i = 0; i < 10; ++i) keys.add(new Key("Numpad " + i, 96 + i));
        String[] symbols = {"Numpad *", "Numpad +", "Numpad -", "Numpad .", "Numpad /",
                ";", "=", ",", "-", ".", "/", "`", "[", "\\", "]", "'"};
        int[] symbolCodes = {106, 107, 109, 110, 111, 186, 187, 188, 189, 190, 191, 192, 219, 220, 221, 222};
        for (int i = 0; i < symbols.length; ++i) keys.add(new Key(symbols[i], symbolCodes[i]));
        return keys;
    }

}
