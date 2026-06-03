package com.ziyun.music.data;

import android.graphics.Color;

import com.ziyun.music.model.Album;
import com.ziyun.music.model.Playlist;
import com.ziyun.music.model.Song;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MusicRepository {
    private final List<Song> songs = new ArrayList<>();
    private final List<Album> albums = new ArrayList<>();
    private final List<Playlist> playlists = new ArrayList<>();

    public MusicRepository() {
        seedSongs();
        seedAlbums();
        seedPlaylists();
    }

    public List<Song> songs() {
        return Collections.unmodifiableList(songs);
    }

    public List<Song> recommendedSongs() {
        return slice(songs, 1, 4);
    }

    public List<Song> recentSongs() {
        return slice(songs, 0, Math.min(6, songs.size()));
    }

    public List<Album> albums() {
        return Collections.unmodifiableList(albums);
    }

    public List<Album> recentAlbums() {
        return slice(albums, 0, Math.min(5, albums.size()));
    }

    public List<Playlist> playlists() {
        return Collections.unmodifiableList(playlists);
    }

    public Playlist primaryPlaylist() {
        return playlists.get(0);
    }

    public void replaceWithNasSongs(List<Song> nasSongs) {
        if (nasSongs == null || nasSongs.isEmpty()) {
            return;
        }
        songs.clear();
        songs.addAll(nasSongs);
        rebuildAlbumsFromSongs();
        rebuildPlaylistsFromSongs();
    }

    public List<Song> search(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return recentSongs();
        }
        List<Song> result = new ArrayList<>();
        for (Song song : songs) {
            String haystack = (song.title + " " + song.artist + " " + song.album + " " + song.folder + " " + song.format)
                    .toLowerCase(Locale.ROOT);
            if (haystack.contains(normalized)) {
                result.add(song);
            }
        }
        return result;
    }

    private <T> List<T> slice(List<T> source, int start, int end) {
        List<T> result = new ArrayList<>();
        for (int i = start; i < end && i < source.size(); i++) {
            result.add(source.get(i));
        }
        return result;
    }

    private void seedSongs() {
        songs.add(new Song("midnight-radio", "午夜电台", "陈以北", "深夜私藏", "/music/hi-res/flac", "FLAC · 24bit", 276, c("#581C87"), c("#EC4899"), "V", true, true));
        songs.add(new Song("lake-lights", "Lake Lights", "Yun Duo", "Archive 03", "/music/ambient", "FLAC", 228, c("#0F766E"), c("#06B6D4"), "L", false, false));
        songs.add(new Song("south-night-flight", "南方夜航", "徐白", "山谷回声", "/music/cn/2021", "44.1kHz", 252, c("#BE123C"), c("#F59E0B"), "S", false, false));
        songs.add(new Song("private-cloud", "Private Cloud", "Northline", "Cloud Archive", "/music/synth", "Synth · MP3", 306, c("#0F172A"), c("#06B6D4"), "P", false, false));
        songs.add(new Song("moon-bridge", "Moon Bridge", "Low Island", "深夜私藏", "/music/chill", "已下载", 244, c("#1D4ED8"), c("#F472B6"), "M", true, false));
        songs.add(new Song("riverside-tape", "Riverside Tape", "Yun Duo", "Archive 03", "/music/ambient/live", "FLAC", 251, c("#0369A1"), c("#C084FC"), "R", false, false));
        songs.add(new Song("city-low", "城市低频", "南风计划", "城市低频", "/music/cn/electronic", "24bit", 298, c("#312E81"), c("#F97316"), "城", false, false));
        songs.add(new Song("quiet-server", "Quiet Server", "Northline", "Cloud Archive", "/music/synth", "NAS", 235, c("#111827"), c("#38BDF8"), "Q", false, false));
        songs.add(new Song("after-rain", "After Rain", "Studio North", "Blue Room", "/music/jazz/live", "FLAC", 241, c("#7C3AED"), c("#A78BFA"), "A", false, false));
        songs.add(new Song("north-station", "北站以南", "周寻", "蓝色候车室", "/music/cn/indie", "44.1kHz", 267, c("#312E81"), c("#F97316"), "北", false, true));
        songs.add(new Song("cold-drive", "Cold Drive", "Blue Room", "Blue Room", "/music/pop", "MP3", 218, c("#111827"), c("#2563EB"), "B", false, false));
        songs.add(new Song("cloudless-night", "Cloudless Night", "Blue Room", "Cloud Archive", "/music/wav", "WAV", 265, c("#0369A1"), c("#06B6D4"), "C", false, false));
    }

    private void seedAlbums() {
        albums.add(new Album("午夜夜台", "陈以北", 14, 2026, c("#FF6A1A"), c("#EC4899"), "午"));
        albums.add(new Album("Cloud Archive", "Various Artists", 28, 2024, c("#0F172A"), c("#06B6D4"), "C"));
        albums.add(new Album("Northline", "Northline", 9, 2024, c("#0F172A"), c("#0EA5E9"), "N"));
        albums.add(new Album("Archive 03", "Yun Duo", 18, 2024, c("#1D4ED8"), c("#F472B6"), "A"));
        albums.add(new Album("Blue Room", "Blue Room", 11, 2023, c("#111827"), c("#38BDF8"), "B"));
        albums.add(new Album("山谷回声", "徐白", 9, 2021, c("#166534"), c("#A3E635"), "山"));
        albums.add(new Album("深夜私藏", "本地歌单", 86, 2026, c("#2E1065"), c("#EC4899"), "夜"));
    }

    private void seedPlaylists() {
        playlists.add(new Playlist("late-night", "深夜私藏", "86 首 · 更新于今天 08:16", c("#2E1065"), c("#EC4899"), "夜", slice(songs, 0, 8)));
        playlists.add(new Playlist("hires", "Hi-Res 原始音质", "5,628 首 · FLAC/WAV", c("#0F766E"), c("#06B6D4"), "H", slice(songs, 0, 6)));
        playlists.add(new Playlist("downloaded", "已下载", "1,284 首 · 离线可听", c("#581C87"), c("#7C3AED"), "D", slice(songs, 0, 5)));
        playlists.add(new Playlist("queue-save", "上次播放队列", "30 首 · 顺序播放", c("#1D4ED8"), c("#F472B6"), "Q", slice(songs, 2, 10)));
    }

    private void rebuildAlbumsFromSongs() {
        albums.clear();
        Map<String, List<Song>> byAlbum = new LinkedHashMap<>();
        for (Song song : songs) {
            String key = song.album == null || song.album.trim().isEmpty() ? "NAS 曲库" : song.album;
            List<Song> bucket = byAlbum.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byAlbum.put(key, bucket);
            }
            bucket.add(song);
        }
        for (Map.Entry<String, List<Song>> entry : byAlbum.entrySet()) {
            Song first = entry.getValue().get(0);
            albums.add(new Album(entry.getKey(), first.artist, entry.getValue().size(), 2026, first.colorStart, first.colorEnd, first.coverLetter));
        }
    }

    private void rebuildPlaylistsFromSongs() {
        playlists.clear();
        Song first = songs.get(0);
        playlists.add(new Playlist("nas-all", "NAS 曲库", songs.size() + " 首 · Audio Station", first.colorStart, first.colorEnd, "N", songs));

        List<Song> hiRes = new ArrayList<>();
        List<Song> downloaded = new ArrayList<>();
        for (Song song : songs) {
            String format = song.format == null ? "" : song.format.toLowerCase(Locale.ROOT);
            if (format.contains("flac") || format.contains("wav") || format.contains("24")) {
                hiRes.add(song);
            }
            if (song.downloaded) {
                downloaded.add(song);
            }
        }
        if (!hiRes.isEmpty()) {
            Song head = hiRes.get(0);
            playlists.add(new Playlist("nas-hires", "Hi-Res 原始音质", hiRes.size() + " 首 · FLAC/WAV", head.colorStart, head.colorEnd, "H", hiRes));
        }
        if (!downloaded.isEmpty()) {
            Song head = downloaded.get(0);
            playlists.add(new Playlist("nas-downloaded", "已下载", downloaded.size() + " 首 · 离线可听", head.colorStart, head.colorEnd, "D", downloaded));
        }
    }

    private int c(String hex) {
        return Color.parseColor(hex);
    }
}
