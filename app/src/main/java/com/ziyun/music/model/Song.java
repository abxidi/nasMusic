package com.ziyun.music.model;

import java.util.Locale;

public class Song {
    public final String id;
    public final String title;
    public final String artist;
    public final String album;
    public final String folder;
    public final String format;
    public final int durationSec;
    public final int colorStart;
    public final int colorEnd;
    public final String coverLetter;
    public final String streamUrl;
    public boolean downloaded;
    public boolean favorite;

    public Song(
            String id,
            String title,
            String artist,
            String album,
            String folder,
            String format,
            int durationSec,
            int colorStart,
            int colorEnd,
            String coverLetter,
            boolean downloaded,
            boolean favorite
    ) {
        this(id, title, artist, album, folder, format, durationSec, colorStart, colorEnd, coverLetter, downloaded, favorite, null);
    }

    public Song(
            String id,
            String title,
            String artist,
            String album,
            String folder,
            String format,
            int durationSec,
            int colorStart,
            int colorEnd,
            String coverLetter,
            boolean downloaded,
            boolean favorite,
            String streamUrl
    ) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.folder = folder;
        this.format = format;
        this.durationSec = durationSec;
        this.colorStart = colorStart;
        this.colorEnd = colorEnd;
        this.coverLetter = coverLetter;
        this.downloaded = downloaded;
        this.favorite = favorite;
        this.streamUrl = streamUrl;
    }

    public String durationText() {
        return String.format(Locale.US, "%d:%02d", durationSec / 60, durationSec % 60);
    }

    public String qualityText() {
        return artist + " · " + format;
    }

    public boolean hasStreamUrl() {
        return streamUrl != null && !streamUrl.trim().isEmpty();
    }
}
