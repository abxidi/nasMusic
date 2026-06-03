package com.ziyun.music.model;

public class Album {
    public final String title;
    public final String artist;
    public final int songCount;
    public final int year;
    public final int colorStart;
    public final int colorEnd;
    public final String coverLetter;

    public Album(String title, String artist, int songCount, int year, int colorStart, int colorEnd, String coverLetter) {
        this.title = title;
        this.artist = artist;
        this.songCount = songCount;
        this.year = year;
        this.colorStart = colorStart;
        this.colorEnd = colorEnd;
        this.coverLetter = coverLetter;
    }
}
