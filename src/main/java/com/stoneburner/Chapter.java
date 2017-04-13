package com.stoneburner;

import com.mpatric.mp3agic.Mp3File;

public class Chapter {
    private Mp3File mp3File;
    private String chapterName;
    private int secondsMark;

    public Chapter(Mp3File mp3File, String chapterName, int secondsMark) {
        this.mp3File = mp3File;
        this.chapterName = chapterName;
        this.secondsMark = secondsMark;
    }

    public Mp3File getMp3File() {
        return mp3File;
    }

    public void setMp3File(Mp3File mp3File) {
        this.mp3File = mp3File;
    }

    public String getChapterName() {
        return chapterName;
    }

    public void setChapterName(String chapterName) {
        this.chapterName = chapterName;
    }

    public int getSecondsMark() {
        return secondsMark;
    }

    public void setSecondsMark(int secondsMark) {
        this.secondsMark = secondsMark;
    }
}
