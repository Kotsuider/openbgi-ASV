/* SPDX-License-Identifier: GPL-2.0-only */
package org.openbgi;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public abstract class LauncherActivity extends Activity {
    static { System.loadLibrary("openbgi_android"); }
    protected static native String[] engineProfiles();
    private static final int CHOOSE_GAME = 1, CHOOSE_COVER = 2;
    private String coverGame;

    protected static final class Game {
        final String uri, name;
        String profile;
        String cover;
        String arguments;

        Game(String uri, String name, String profile) {
            this.uri = uri;
            this.name = name;
            this.profile = profile;
        }
    }

    protected final List<Game> games = new ArrayList<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        coverGame = state == null ? null : state.getString("coverGame");
        loadGames();
        showLauncher(null);
    }

    protected SharedPreferences settings() {
        return getSharedPreferences("launcher", MODE_PRIVATE);
    }

    private void loadGames() {
        SharedPreferences prefs = settings();
        String stored = prefs.getString("games", null);
        if (stored == null) {
            String previous = prefs.getString("tree", "");
            if (!previous.isEmpty()) {
                GameDocuments docs = new GameDocuments(getContentResolver(), Uri.parse(previous));
                String name = docs.name();
                String profile = prefs.getString("profile:" + previous,
                        prefs.getString("profile:" + name, null));
                if (profile == null) profile = BgiVersion.detect(docs, engineProfiles()).profile;
                games.add(new Game(previous, name, profile));
                saveGames(previous);
            }
            return;
        }

        try {
            JSONArray list = new JSONArray(stored);
            for (int i = 0; i < list.length(); ++i) {
                JSONObject item = list.getJSONObject(i);
                Game game = new Game(item.getString("uri"), item.getString("name"),
                        item.optString("profile", null));
                game.cover = item.optString("cover", null);
                game.arguments = item.optString("arguments", null);
                games.add(game);
            }
        } catch (JSONException error) {
            android.util.Log.w("OpenBGI", "Cannot read game library", error);
        }
    }

    protected void saveGames(String selected) {
        JSONArray list = new JSONArray();
        try {
            for (Game game : games) {
                JSONObject item = new JSONObject();
                item.put("uri", game.uri);
                item.put("name", game.name);
                item.put("profile", game.profile);
                item.put("cover", game.cover);
                item.put("arguments", game.arguments);
                list.put(item);
            }
            settings().edit().putString("games", list.toString())
                    .putString("selectedGame", selected).apply();
        } catch (JSONException error) {
            android.util.Log.e("OpenBGI", "Cannot save game library", error);
        }
    }

    protected void chooseFolder() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Games"));
        startActivityForResult(picker, CHOOSE_GAME);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("coverGame", coverGame);
    }

    protected void chooseCover(Game game) {
        coverGame = game.uri;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            Intent photos = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            try {
                startActivityForResult(photos, CHOOSE_COVER);
                return;
            } catch (ActivityNotFoundException ignored) { }
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, CHOOSE_COVER);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == CHOOSE_COVER) {
            for (Game game : games) {
                if (!game.uri.equals(coverGame)) continue;
                try {
                    retainRead(uri);
                    String previous = game.cover;
                    game.cover = uri.toString();
                    releaseCover(previous);
                    saveGames(game.uri);
                    showLauncher(null);
                } catch (SecurityException error) {
                    showLauncher(getString(R.string.cover_error));
                }
                break;
            }
        } else if (request == CHOOSE_GAME) {
            addGame(uri);
        }
    }

    private void retainRead(Uri uri) {
        getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    private void addGame(Uri tree) {
        GameDocuments documents = new GameDocuments(getContentResolver(), tree);
        if (!documents.isGame()) {
            showLauncher(getString(R.string.folder_invalid));
            return;
        }
        try {
            retainRead(tree);
        } catch (SecurityException error) {
            showLauncher(getString(R.string.folder_permission));
            return;
        }
        String uri = tree.toString();
        for (Game game : games) {
            if (game.uri.equals(uri)) {
                saveGames(uri);
                showLauncher(null);
                return;
            }
        }
        games.add(new Game(uri, documents.name(), BgiVersion.detect(documents, engineProfiles()).profile));
        saveGames(uri);
        showLauncher(null);
    }

    private void releaseCover(String cover) {
        if (cover == null) return;
        for (Game game : games) {
            if (cover.equals(game.cover) || cover.equals(game.uri)) return;
        }
        releaseRead(cover);
    }

    private void releaseRead(String uri) {
        try {
            getContentResolver().releasePersistableUriPermission(Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
    }

    protected void resetCover(Game game) {
        String previous = game.cover;
        game.cover = null;
        saveGames(game.uri);
        releaseCover(previous);
        showLauncher(null);
    }

    protected void removeGame(Game game) {
        games.remove(game);
        String selected = settings().getString("selectedGame", "");
        if (game.uri.equals(selected)) selected = games.isEmpty() ? "" : games.get(0).uri;
        saveGames(selected);
        releaseRead(game.uri);
        releaseCover(game.cover);
        showLauncher(null);
    }

    protected void launch(Game game) {
        Uri tree = Uri.parse(game.uri);
        if (!new GameDocuments(getContentResolver(), tree).isGame()) {
            showLauncher(getString(R.string.game_unreadable));
            return;
        }
        saveGames(game.uri);
        Intent intent = new Intent(this, MainActivity.class);
        intent.setData(tree);
        intent.putExtra("game", game.name);
        intent.putExtra("profile", game.profile);
        intent.putExtra("arguments", game.arguments);
        startActivity(intent);
    }

    protected abstract void showLauncher(String error);
}
