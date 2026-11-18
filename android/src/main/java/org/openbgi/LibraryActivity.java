/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.*;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LibraryActivity extends LauncherActivity {
    private static final String[] LANGUAGE_TAGS = {
        "en", "ru", "pl", "es", "fr", "pt", "de", "zh-Hant", "zh-Hans", "ja", "ko"
    };
    private static final String[] LANGUAGES = {
        "English", "Русский", "Polski", "Español", "Français", "Português", "Deutsch",
        "繁體中文", "简体中文", "日本語", "한국어"
    };
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final TextView[] tabs = new TextView[3];
    private FrameLayout content;
    private TextView add, errorView;
    private String[] profiles;
    private String error;
    private int page, ink, accent, surface, card, muted, warning, versionWarning, onAccent;
    private float downX, downY;
    private boolean swiping;

    @Override protected void attachBaseContext(Context base) {
        String tag = base.getSharedPreferences("launcher", MODE_PRIVATE).getString("language", "en");
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(Locale.forLanguageTag(tag));
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        page = state == null ? 0 : state.getInt("page");
        profiles = engineProfiles();
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        ink = dark ? 0xffe6e1e9 : 0xff1d1b20;
        accent = dark ? 0xffd0bcff : 0xff65558f;
        onAccent = dark ? 0xff36275d : 0xffffffff;
        surface = dark ? 0xff141218 : 0xfffff7ff;
        card = dark ? 0xff26232d : 0xfff0e9f5;
        muted = dark ? 0xffcdc3d5 : 0xff49454f;
        warning = dark ? 0xffffb4ab : 0xffba1a1a;
        versionWarning = dark ? 0xffcbb79f : 0xff745b42;

        if (android.os.Build.VERSION.SDK_INT >= 31) {
            ink = getColor(dark ? android.R.color.system_neutral1_100 : android.R.color.system_neutral1_900);
            accent = getColor(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
            onAccent = getColor(dark ? android.R.color.system_accent1_800 : android.R.color.system_accent1_0);
            surface = getColor(dark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_10);
            card = getColor(dark ? android.R.color.system_neutral2_800 : android.R.color.system_neutral2_100);
            muted = getColor(dark ? android.R.color.system_neutral2_200 : android.R.color.system_neutral2_700);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(surface);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        int lightBars = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(lightBars);
        LinearLayout body = column();
        root.addView(body, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout toolbar = row();
        toolbar.setPadding(dp(16), 0, dp(8), 0);
        TextView title = text("OpenBGI", 23, ink);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView settings = action("⚙", false, this::showLanguages);
        settings.setTextSize(23);
        settings.setContentDescription(getString(R.string.settings));
        toolbar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        body.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout tabRow = row();
        int[] labels = {R.string.folders, R.string.library, R.string.logs};
        for (int index = 0; index < tabs.length; index++) {
            final int target = index;
            tabs[index] = action(getString(labels[index]), false, () -> showPage(target));
            tabRow.addView(tabs[index], new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        body.addView(tabRow);
        errorView = text("", 14, warning);
        errorView.setPadding(dp(16), dp(8), dp(16), dp(8));
        body.addView(errorView);
        content = new FrameLayout(this);
        body.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        add = action("+", true, this::chooseFolder);
        add.setTextSize(30);
        add.setContentDescription(getString(R.string.add));
        add.setElevation(dp(4));
        FrameLayout.LayoutParams fab = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.END | Gravity.BOTTOM);
        fab.setMargins(dp(16), dp(16), dp(16), dp(16));
        root.addView(add, fab);
        setContentView(root);
        showLauncher(error);
        String editing = state == null ? null : state.getString("editing");
        for (Game game : games) {
            if (game.uri.equals(editing)) {
                editGame(game, state);
                break;
            }
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("page", page);
        if (editor != null && editor.isShowing()) {
            state.putString("editing", editingUri);
            state.putInt("profile", profileChoice.getSelectedItemPosition());
            state.putBoolean("fromConfig", configChoice.isChecked());
            state.putString("arguments", configChoice.isChecked() ? customArguments
                    : argumentsField.getText().toString());
        }
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        if (content != null && page == 2) showPage(2);
    }

    @Override protected void showLauncher(String message) {
        error = message;
        if (content == null) return;
        errorView.setText(message);
        errorView.setVisibility(message == null ? View.GONE : View.VISIBLE);
        if (editor != null && editor.isShowing()) updateCoverButtons();
        showPage(page);
    }

    private void showPage(int index) {
        page = index;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected(i == page);
            tabs[i].setTextColor(i == page ? accent : muted);
            tabs[i].setTypeface(null, i == page ? Typeface.BOLD : Typeface.NORMAL);
            tabs[i].setBackground(sheet(i == page ? card : surface, 12, 0));
        }
        add.setVisibility(page == 2 ? View.GONE : View.VISIBLE);
        content.removeAllViews();
        if (page == 2) {
            content.addView(logs());
        } else if (games.isEmpty()) {
            TextView empty = text(getString(R.string.empty), 15, muted);
            empty.setGravity(Gravity.CENTER);
            content.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        } else {
            content.addView(gameList(page == 1), new FrameLayout.LayoutParams(-1, -1));
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                swiping = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!swiping && content != null && Math.abs(dx) > dp(28)
                        && Math.abs(dx) > 1.6f * Math.abs(dy) && withinContent()) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                    swiping = true;
                }
                if (swiping) return true;
                break;
            case MotionEvent.ACTION_UP:
                if (swiping) {
                    swiping = false;
                    if (Math.abs(dx) > dp(56)) {
                        showPage(Math.max(0, Math.min(2, page + (dx < 0 ? 1 : -1))));
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                swiping = false;
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean withinContent() {
        int[] location = new int[2];
        content.getLocationInWindow(location);
        return downY >= location[1] && downY < location[1] + content.getHeight();
    }

    private AbsListView gameList(boolean covers) {
        AbsListView list;
        if (covers) {
            GridView grid = new GridView(this);
            boolean portrait = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_PORTRAIT;
            grid.setNumColumns(portrait ? 2 : GridView.AUTO_FIT);
            grid.setColumnWidth(dp(176));
            grid.setHorizontalSpacing(dp(12));
            grid.setVerticalSpacing(dp(12));
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            list = grid;
        } else {
            ListView rows = new ListView(this);
            rows.setDivider(new android.graphics.drawable.ColorDrawable(surface));
            rows.setDividerHeight(dp(10));
            list = rows;
        }
        list.setClipToPadding(false);
        list.setPadding(dp(16), dp(16), dp(16), dp(88));
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return games.size(); }
            @Override public Game getItem(int position) { return games.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View recycled, ViewGroup parent) {
                GameTile tile = recycled instanceof GameTile ? (GameTile) recycled : new GameTile(covers);
                tile.bind(getItem(position));
                return tile;
            }
        });
        return list;
    }

    private void openGame(Game game) {
        if (game.profile == null) editGame(game, null);
        else launch(game);
    }

    private final class GameTile extends LinearLayout {
        final TextView name, path, version, config, profile, launch;
        final ImageView image;
        Game bound;
        int generation;

        GameTile(boolean covers) {
            super(LibraryActivity.this);
            setOrientation(VERTICAL);
            setBackground(sheet(covers ? card : Color.TRANSPARENT, 16,
                    covers ? 0 : outlineColor()));
            setClipToOutline(true);
            if (covers) {
                image = new ImageView(LibraryActivity.this) {
                    @Override protected void onMeasure(int width, int height) {
                        int size = MeasureSpec.getSize(width);
                        setMeasuredDimension(size, size * 4 / 3);
                    }
                };
                image.setBackgroundColor(card);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                addView(image, new LayoutParams(-1, -2));
            } else {
                image = null;
            }
            LinearLayout details = column();
            details.setPadding(dp(14), dp(10), dp(14), dp(10));
            addView(details, new LayoutParams(-1, -2));
            name = text("", 16, ink);
            name.setTypeface(null, Typeface.BOLD);
            details.addView(name);
            launch = action(getString(R.string.launch), covers, () -> openGame(bound));
            if (covers) {
                path = version = config = profile = null;
                name.setLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                details.addView(launch, new LayoutParams(-1, dp(44)));
                setOnClickListener(view -> openGame(bound));
                setOnLongClickListener(view -> { editGame(bound, null); return true; });
                launch.setOnLongClickListener(view -> { editGame(bound, null); return true; });
            } else {
                path = text("", 13, muted);
                version = text("", 13, muted);
                config = text(getString(R.string.config_detected), 13, accent);
                details.addView(path);
                details.addView(version);
                details.addView(config);
                LinearLayout actions = row();
                profile = action("", false, () -> editGame(bound, null));
                styleAction(profile, accent, Color.TRANSPARENT, outlineColor());
                profile.setPaddingRelative(dp(18), 0, dp(18), 0);
                actions.addView(profile, new LayoutParams(-2, dp(48)));
                actions.addView(new View(LibraryActivity.this), new LayoutParams(0, 1, 1));
                actions.addView(launch, new LayoutParams(-2, dp(44)));
                LayoutParams actionRow = new LayoutParams(-1, -2);
                actionRow.topMargin = dp(8);
                details.addView(actions, actionRow);
            }
        }

        void bind(Game game) {
            bound = game;
            int request = ++generation;
            name.setText(game.name);
            if (image != null) {
                image.setImageDrawable(null);
                String cover = game.cover;
                io.execute(() -> {
                    Bitmap bitmap = readCover(game.uri, cover);
                    runOnUiThread(() -> {
                        if (generation == request && !isDestroyed()) image.setImageBitmap(bitmap);
                    });
                });
            } else {
                Uri uri = Uri.parse(game.uri);
                path.setText(DocumentsContract.getTreeDocumentId(uri));
                profile.setText(game.profile == null ? getString(R.string.choose_profile) : game.profile);
                version.setText("");
                config.setVisibility(GONE);
                io.execute(() -> {
                    GameDocuments docs = new GameDocuments(getContentResolver(), uri);
                    BgiVersion detected = BgiVersion.detect(docs, profiles);
                    boolean hasConfig = docs.stat(GameLaunchConfig.FILE) != null
                            || docs.stat("uif_config.json") != null || docs.stat("config.json") != null;
                    runOnUiThread(() -> {
                        if (generation != request || isDestroyed()) return;
                        showVersion(version, detected);
                        config.setVisibility(hasConfig ? VISIBLE : GONE);
                    });
                });
            }
        }
    }

    private Bitmap readCover(String tree, String cover) {
        if (cover != null) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), Uri.parse(cover));
                return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
                    int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                    int edge = Math.max(width, height);
                    if (edge > 960) decoder.setTargetSize(
                            Math.max(1, width * 960 / edge), Math.max(1, height * 960 / edge));
                });
            } catch (Exception failure) {
                android.util.Log.w("OpenBGI", "Cannot read game cover", failure);
            }
        }
        GameDocuments docs = new GameDocuments(getContentResolver(), Uri.parse(tree));
        return new GameLaunchConfig(docs).previewBitmap(docs);
    }

    private void showVersion(TextView view, BgiVersion detected) {
        String label = detected.label == null ? "" : "BGI " + detected.label;
        if (!detected.exact) {
            label += label.isEmpty() ? getString(R.string.version_unknown)
                    : "\n" + (detected.profile == null ? getString(R.string.no_matching_profile)
                    : getString(R.string.profile_unknown, detected.profile));
        }
        view.setTextColor(muted);
        if (detected.exact) {
            view.setText(label);
        } else {
            android.text.SpannableString status = new android.text.SpannableString(label);
            status.setSpan(new android.text.style.ForegroundColorSpan(versionWarning),
                    label.indexOf('\n') + 1, label.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(status);
        }
    }

    private void showLanguages() {
        String current = settings().getString("language", "en");
        int selected = java.util.Arrays.asList(LANGUAGE_TAGS).indexOf(current);
        new AlertDialog.Builder(this).setTitle(R.string.language)
                .setSingleChoiceItems(LANGUAGES, selected, (dialog, index) -> {
                    dialog.dismiss();
                    if (!LANGUAGE_TAGS[index].equals(current)) {
                        settings().edit().putString("language", LANGUAGE_TAGS[index]).apply();
                        recreate();
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private AlertDialog editor;
    private String editingUri, customArguments;
    private Game editingGame;
    private Spinner profileChoice;
    private CheckBox configChoice;
    private EditText argumentsField;
    private TextView coverButton, resetButton;

    private void editGame(Game game, Bundle state) {
        editingGame = game;
        editingUri = game.uri;
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView version = text(getString(R.string.loading), 14, muted);
        form.addView(version);
        TextView detectedButton = formAction(R.string.detected_profile, accent, null);
        detectedButton.setEnabled(false);
        form.addView(detectedButton);
        Spinner profile = new Spinner(this, Spinner.MODE_DROPDOWN);
        java.util.ArrayList<String> choices = new java.util.ArrayList<>(profiles.length + 1);
        choices.add(getString(R.string.choose_profile));
        java.util.Collections.addAll(choices, profiles);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profile.setAdapter(adapter);
        profile.setContentDescription(getString(R.string.choose_profile));
        int selected = state == null ? adapter.getPosition(game.profile) : state.getInt("profile");
        profile.setSelection(Math.max(0, selected));
        profileChoice = profile;
        form.addView(profile, new LinearLayout.LayoutParams(-1, dp(48)));

        CheckBox config = new CheckBox(this);
        config.setText(R.string.use_config);
        config.setButtonTintList(ColorStateList.valueOf(accent));
        config.setChecked(state == null ? game.arguments == null : state.getBoolean("fromConfig"));
        configChoice = config;
        form.addView(config);
        EditText args = new EditText(this);
        args.setHint(R.string.arguments);
        args.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        args.setMaxLines(3);
        argumentsField = args;
        customArguments = state == null ? (game.arguments == null ? "" : game.arguments)
                : state.getString("arguments", "");
        String[] defaults = {""};
        args.setEnabled(!config.isChecked());
        args.setText(config.isChecked() ? defaults[0] : customArguments);
        form.addView(args, new LinearLayout.LayoutParams(-1, -2));
        config.setOnCheckedChangeListener((button, checked) -> {
            if (checked) customArguments = args.getText().toString();
            args.setText(checked || customArguments.isEmpty() ? defaults[0] : customArguments);
            args.setEnabled(!checked);
        });

        View separator = new View(this);
        separator.setBackgroundColor(outlineColor());
        LinearLayout.LayoutParams divider = new LinearLayout.LayoutParams(-1, dp(1));
        divider.setMargins(0, dp(12), 0, dp(4));
        form.addView(separator, divider);
        coverButton = formAction(R.string.choose_cover, accent, () -> chooseCover(game));
        resetButton = formAction(R.string.use_preview, accent, () -> resetCover(game));
        form.addView(coverButton, new LinearLayout.LayoutParams(-1, -2));
        form.addView(resetButton);
        updateCoverButtons();
        TextView remove = formAction(R.string.remove_game, warning, () ->
                new AlertDialog.Builder(this).setTitle(R.string.remove_title)
                        .setMessage(getString(R.string.remove_message, game.name))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.remove, (dialog, which) -> {
                            editor.dismiss();
                            removeGame(game);
                        }).show());
        form.addView(remove);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.game_settings)
                .setView(scroll).setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (ignored, which) -> {
                    int index = profile.getSelectedItemPosition();
                    game.profile = index > 0 ? profiles[index - 1] : null;
                    game.arguments = config.isChecked() ? null : args.getText().toString();
                    saveGames(game.uri);
                    showLauncher(null);
                }).create();
        editor = dialog;
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        styleDialogAction(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), muted, Color.TRANSPARENT);
        styleDialogAction(dialog.getButton(AlertDialog.BUTTON_POSITIVE), onAccent, accent);
        io.execute(() -> {
            GameDocuments docs = new GameDocuments(getContentResolver(), Uri.parse(game.uri));
            String defaultArgs = new GameLaunchConfig(docs).arguments;
            BgiVersion detected = BgiVersion.detect(docs, profiles);
            runOnUiThread(() -> {
                if (!dialog.isShowing() || isDestroyed()) return;
                defaults[0] = defaultArgs;
                if (config.isChecked()) args.setText(defaultArgs);
                showVersion(version, detected);
                int match = adapter.getPosition(detected.profile);
                detectedButton.setEnabled(match > 0);
                detectedButton.setOnClickListener(view -> profile.setSelection(match));
            });
        });
    }

    private void updateCoverButtons() {
        boolean hasCover = editingGame.cover != null;
        coverButton.setText(hasCover ? R.string.change_cover : R.string.choose_cover);
        resetButton.setVisibility(hasCover ? View.VISIBLE : View.GONE);
    }

    private View logs() {
        LinearLayout layout = column();
        layout.setPadding(dp(16), dp(8), dp(16), dp(16));
        LinearLayout heading = row();
        heading.addView(text(getString(R.string.last_launch), 17, ink), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(action(getString(R.string.refresh), false, () -> showPage(2)));
        layout.addView(heading);
        TextView log = text(getString(R.string.loading), 13, ink);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        io.execute(() -> {
            String message = readLog();
            runOnUiThread(() -> { if (!isDestroyed()) log.setText(message); });
        });
        return layout;
    }

    private String readLog() {
        File file = new File(getFilesDir(), "openbgi-last.log");
        if (!file.isFile()) return getString(R.string.no_log);
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            long offset = Math.max(0, length - 65536);
            byte[] bytes = new byte[(int) (length - offset)];
            input.seek(offset);
            input.readFully(bytes);
            String tail = new String(bytes, StandardCharsets.UTF_8);
            return offset == 0 ? tail : getString(R.string.log_tail) + "\n\n"
                    + tail.substring(tail.indexOf('\n') + 1);
        } catch (IOException failure) {
            return getString(R.string.log_error);
        }
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private TextView action(String label, boolean filled, Runnable click) {
        Button view = new Button(this);
        view.setStateListAnimator(null);
        view.setMinWidth(0);
        view.setMinimumWidth(0);
        view.setMinHeight(dp(44));
        view.setPadding(dp(12), 0, dp(12), 0);
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(14);
        styleAction(view, filled ? onAccent : accent, filled ? accent : Color.TRANSPARENT, 0);
        if (click != null) view.setOnClickListener(v -> click.run());
        return view;
    }

    private TextView formAction(int label, int color, Runnable click) {
        TextView view = action(getString(label), false, click);
        view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        view.setPaddingRelative(dp(8), dp(6), dp(8), dp(6));
        styleAction(view, color, Color.TRANSPARENT, 0);
        return view;
    }

    private void styleDialogAction(Button button, int color, int background) {
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setPaddingRelative(dp(16), 0, dp(16), 0);
        styleAction(button, color, background, 0);
    }

    private void styleAction(TextView view, int color, int background, int stroke) {
        view.setTextColor(new ColorStateList(
                new int[][] {{-android.R.attr.state_enabled}, {}},
                new int[] {(color & 0x00ffffff) | 0x61000000, color}));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33808080),
                sheet(background, 24, stroke), sheet(Color.WHITE, 24, 0)));
    }

    private int outlineColor() {
        return (muted & 0x00ffffff) | 0x50000000;
    }

    private GradientDrawable sheet(int color, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }
}
