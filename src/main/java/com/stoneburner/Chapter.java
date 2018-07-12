package com.stoneburner;

import com.mpatric.mp3agic.Mp3File;

import java.io.File;

import static java.lang.Integer.valueOf;

public class Chapter {
    private Mp3File mp3File;
    private File file;
    private String chapterName;
    private int secondsMark;
    private int hundredths;

    public Chapter(Mp3File mp3File, File file, String chapterName, String timeString) {
        this.mp3File = mp3File;
        this.file = file;
        this.chapterName = chapterName;
        assignTimeFromString(timeString);
    }

    private void assignTimeFromString(String timeString) {
        String[] timeParts = timeString.split("[:.]");
        int hours = timeParts.length == 3 ? 0 : valueOf(timeParts[0]);
        int minutes = valueOf(timeParts[timeParts.length == 3 ? 0 : 1]);
        int seconds = valueOf(timeParts[timeParts.length == 3 ? 1 : 2]);
        int length = (hours * 3600) + (minutes * 60) + seconds;
        this.secondsMark = length;
        this.hundredths = valueOf(timeParts[timeParts.length == 3 ? 2 : 3]) / 10;
    }

    public Mp3File getMp3File() {
        return mp3File;
    }

    public void setMp3File(Mp3File mp3File) {
        this.mp3File = mp3File;
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public String getChapterName() {
        return chapterName.replace(".", "")
                .replace("\"", "")
                .replaceAll("\\([0-9]{2}:[0-9]{2}\\)$", "continued");
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

    public int getHundredths() {
        return hundredths;
    }

    public void setHundredths(int hundredths) {
        this.hundredths = hundredths;
    }
}
