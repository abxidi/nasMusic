package com.ziyun.music.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.ziyun.music.model.Song;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MusicLibraryStore {
    private static final String PREFS = "music_library";
    private static final String KEY_SONGS = "songs_json";

    private final SharedPreferences preferences;

    public MusicLibraryStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Song> loadSongs() {
        List<Song> songs = new ArrayList<>();
        String raw = preferences.getString(KEY_SONGS, "");
        if (raw == null || raw.trim().isEmpty()) {
            return songs;
        }

        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    songs.add(readSong(item));
                }
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return songs;
    }

    public void saveSongs(List<Song> songs) {
        JSONArray array = new JSONArray();
        if (songs != null) {
            for (Song song : songs) {
                array.put(writeSong(song));
            }
        }
        preferences.edit().putString(KEY_SONGS, array.toString()).apply();
    }

    private JSONObject writeSong(Song song) {
        JSONObject object = new JSONObject();
        try {
            object.put("id", safe(song.id));
            object.put("title", safe(song.title));
            object.put("artist", safe(song.artist));
            object.put("album", safe(song.album));
            object.put("folder", safe(song.folder));
            object.put("format", safe(song.format));
            object.put("durationSec", song.durationSec);
            object.put("colorStart", song.colorStart);
            object.put("colorEnd", song.colorEnd);
            object.put("coverLetter", safe(song.coverLetter));
            object.put("downloaded", song.downloaded);
            object.put("favorite", song.favorite);
        } catch (Exception ignored) {
            return new JSONObject();
        }
        return object;
    }

    private Song readSong(JSONObject object) {
        return new Song(
                object.optString("id", ""),
                object.optString("title", "未知曲目"),
                object.optString("artist", "未知艺术家"),
                object.optString("album", "NAS 曲库"),
                object.optString("folder", "/music"),
                object.optString("format", "NAS"),
                object.optInt("durationSec", 240),
                object.optInt("colorStart", 0xff7c3aed),
                object.optInt("colorEnd", 0xff06b6d4),
                object.optString("coverLetter", "N"),
                object.optBoolean("downloaded", false),
                object.optBoolean("favorite", false)
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
