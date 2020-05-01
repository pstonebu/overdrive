package com.stoneburner;

import com.google.common.base.Joiner;

import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

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

            String wrapCommand = "mp3wrap " + fileNames.get(0) + " " + Joiner.on(" ").join(fileNames);
            String[] cmd = {"/bin/sh", "-c", wrapCommand};
            Runtime.getRuntime().exec(cmd).waitFor();

            if (line.length() == 0) {
                loop = false;
            }
        }
    }
}
