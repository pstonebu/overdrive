package com.stoneburner;

import com.google.common.base.Joiner;
import com.mpatric.mp3agic.ID3v1;
import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.Mp3File;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import static java.lang.Runtime.getRuntime;
import static java.util.stream.Collectors.toList;

public class Main {

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter mp3 files to wrap.");

        boolean loop = true;
        while (loop) {
            String line = scanner.nextLine();
            List<String> fileNames = Arrays.asList(line.split(".mp3 ")).stream().map(s -> {
                if (!s.endsWith(".mp3")) {
                    s = s + ".mp3";
                }
                return s;
            }).collect(toList());

            executeCommand("mp3wrap " + fileNames.get(0) + " " + Joiner.on(" ").join(fileNames));

            //make a new directory for combined files
            File file = new File(fileNames.get(0));
            Mp3File mp3File = new Mp3File(fileNames.get(0).replaceAll("\\\\", ""));
            String processedDirectory = file.getParent() + "/overdrive_processed";
            executeCommand("mkdir " + processedDirectory);

            //move combined files into directory
            fileNames.forEach(s -> {executeCommand("mv " + s + " " + processedDirectory);});

            //grab mp3 metadata from new file
            ID3v2 v2 = mp3File.getId3v2Tag();
            ID3v1 v1 = mp3File.getId3v1Tag();

            String commandLineFileName = fileNames.get(0).replaceAll(".mp3$", "_MP3WRAP.mp3");
            String prettyFileName = commandLineFileName.replaceAll("\\\\", "");

            //repair new file
            executeCommand("mp3val -f -nb " + commandLineFileName);

            //copy metadata to repaired new file
            mp3File = new Mp3File(prettyFileName);
            mp3File.setId3v2Tag(v2);
            mp3File.setId3v1Tag(v1);
            mp3File.save(prettyFileName.replaceAll(".mp3$", "2.mp3"));
            executeCommand("rm -rf " + commandLineFileName);
            executeCommand("mv " + commandLineFileName.replaceAll(".mp3$", "2.mp3" + " \"" + fileNames.get(0) + "\""));

            if (line.length() == 0) {
                loop = false;
            }
        }
    }

    private static void executeCommand(String command) {
        String[] cmd = {"/bin/sh", "-c", command};
        try {
            getRuntime().exec(cmd).waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
