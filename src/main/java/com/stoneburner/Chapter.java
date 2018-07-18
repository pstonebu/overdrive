package com.stoneburner;

import com.mpatric.mp3agic.Mp3File;
import lombok.Getter;
import lombok.Setter;

import java.io.File;

import static java.lang.Integer.valueOf;

@Getter @Setter
public class Chapter {
    private Mp3File mp3File;
    private File file;
    private String chapterName;
    private String chapterNameFormatted;
    private int secondsMark;
    private int hundredths;

    public Chapter(Mp3File mp3File, File file, String chapterName, String timeString) {
        this.mp3File = mp3File;
        this.file = file;
        this.chapterName = chapterName;
        this.chapterNameFormatted = chapterName;
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
}
