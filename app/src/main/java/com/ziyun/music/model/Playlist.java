package com.ziyun.music.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Playlist {
    public final String id;
    public final String title;
    public final String description;
    public final int colorStart;
    public final int colorEnd;
    public final String coverLetter;
    private final List<Song> songs;

    public Playlist(String id, String title, String description, int colorStart, int colorEnd, String coverLetter, List<Song> songs) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.colorStart = colorStart;
        this.colorEnd = colorEnd;
        this.coverLetter = coverLetter;
        this.songs = new ArrayList<>(songs);
    }

    public List<Song> songs() {
        return Collections.unmodifiableList(songs);
    }

    public int totalDurationSec() {
        int total = 0;
        for (Song song : songs) {
            total += song.durationSec;
        }
        return total;
    }

    public String durationSummary() {
        int minutes = totalDurationSec() / 60;
        int hours = minutes / 60;
        int remainingMinutes = minutes % 60;
        if (hours > 0) {
            return hours + " 小时 " + remainingMinutes + " 分钟";
        }
        return minutes + " 分钟";
    }
}
