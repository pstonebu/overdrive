package com.stoneburner;

import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.UnsupportedTagException;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.XML;

import java.io.*;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class Main {

    //mp3splt[.exe] -d overdrivesplit -o newfilename input start_time end_time
    private static final String MP3_SPLT_COMMAND = "%s %s -d overdrivesplit -o %s -g %s %s %s %s";
    private static String path = "";

    public static void main(String[] args) {

        List<File> mp3Files = getAllMp3FilesInDirectory();
        List<Chapter> chapters = new ArrayList<Chapter>();

        String albumtitle = "";
        String author = "";

        boolean isWin = SystemUtils.IS_OS_WINDOWS;
        boolean isMac = SystemUtils.IS_OS_MAC;

        if (!isWin && !isMac) {
            logAndExit(new Exception("Not running windows or osx."));
        }

        for (File file : mp3Files) {
            try {
                Mp3File mp3File = new Mp3File(file.getPath());
                if (StringUtils.isBlank(albumtitle)) {
                    albumtitle = mp3File.getId3v2Tag().getAlbum();
                    author = mp3File.getId3v2Tag().getArtist();
                }
                List<Chapter> innerList = new ArrayList<Chapter>();

                String markers = URLDecoder.decode(mp3File.getId3v2Tag().getOverDriveMarkers(), "UTF-8");
                log("markers = " + markers);
                JSONObject markerObj = XML.toJSONObject(markers);
                JSONArray array = null;
                try {
                    array = markerObj.getJSONObject("Markers").getJSONArray("Marker");
                } catch (JSONException e) {
                    JSONObject chapter = markerObj.getJSONObject("Markers").getJSONObject("Marker");
                    String time = chapter.getString("Time");
                    String[] timeParts = time.substring(0, time.indexOf(".")).split(":");
                    int minutes = Integer.valueOf(timeParts[0]);
                    int seconds = Integer.valueOf(timeParts[1]);
                    int length = minutes * 60 + seconds;
                    String name = chapter.getString("Name");

                    chapters.add(new Chapter(mp3File, name, length));
                    continue;
                }

                log(array.length() + " markers found in " + mp3File.getFilename());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject inner = (JSONObject)array.get(i);
                    String time = inner.getString("Time");
                    String[] timeParts = time.substring(0, time.indexOf(".")).split(":");
                    int minutes = Integer.valueOf(timeParts[0]);
                    int seconds = Integer.valueOf(timeParts[1]);
                    int length = minutes * 60 + seconds;
                    String name = inner.getString("Name");

                    innerList.add(new Chapter(mp3File, name, length));
                }

                chapters.addAll(innerList);

            } catch (IOException | UnsupportedTagException | InvalidDataException | JSONException e) {
                logAndExit(e);
            }
        }

        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            String mp3splt = new StringBuilder().append(isMac ? "/usr/local/bin/mp3splt" : "mp3splt.exe").toString();
            int beginminutes = chapter.getSecondsMark() / 60;
            int beginseconds = chapter.getSecondsMark() % 60;
            int endminutes = 0;
            int endseconds = 0;

            //if this is the last chapter or this is the last chapter of this file
            if (i+1 == chapters.size() || chapter.getMp3File() != chapters.get(i+1).getMp3File()) {
                endminutes = (int)chapter.getMp3File().getLengthInSeconds() / 60;
                endseconds = (int)chapter.getMp3File().getLengthInSeconds() % 60;
            } else {
                endminutes = chapters.get(i+1).getSecondsMark() / 60;
                endseconds = chapters.get(i+1).getSecondsMark() % 60;
            }

            String newFileName = "\"" + (i+1 + " - " + chapter.getChapterName()).replace(" ", "+").replace("'", "\\'").replace(":", "") + "\"";

            String command = String.format(MP3_SPLT_COMMAND,
                    mp3splt,
                    chapter.getMp3File().isVbr() ? "-f" : "",
                    newFileName,
                    "\"[@o,@a=" + author + ",@b=" + albumtitle + ",@t=" + chapter.getChapterName() + ",@n=" + (i+1) + "]\"",
                    (chapter.getMp3File().getFilename()).replace(" ", "\\ "),
                    beginminutes + "." + beginseconds,
                    endminutes + "." + endseconds);

            log("executing '" + command + "'");

            StringBuffer output = new StringBuffer();

            Process p;
            try {
                if (isMac) {
                    p = Runtime.getRuntime().exec(new String[] { "bash", "-c", command});
                } else {
                    p = Runtime.getRuntime().exec(new String[] { "CMD", "/C", command});
                }

                p.waitFor();

                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

                String line = "";
                while ((line = reader.readLine())!= null) {
                    output.append(line + "\n");
                }

                log(output.toString());

                BufferedReader ereader = new BufferedReader(new InputStreamReader(p.getErrorStream()));

                line = "";
                output = new StringBuffer();
                while ((line = ereader.readLine())!= null) {
                    output.append(line + "\n");
                }

                log(output.toString());

            } catch (Exception e) {
                logAndExit(e);
            }
        }

        log("Done");
    }

    private static List<File> getAllMp3FilesInDirectory() {
        List<File> mp3Files = new ArrayList<File>();
        String decodedPath = "";
        try {
            decodedPath = URLDecoder.decode(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath(), "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            logAndExit(ex);
        }

        File pathFile = new File(decodedPath);
        if (pathFile.isFile()) {
            decodedPath = pathFile.getParentFile().getAbsolutePath();
        }

        path = decodedPath;

        try(Stream<Path> paths = Files.walk(Paths.get(decodedPath))) {
            paths.forEach(filePath -> {
                if (Files.isRegularFile(filePath) && FilenameUtils.getExtension(filePath.toString()).equals("mp3")) {
                    mp3Files.add(filePath.toFile());
                    log("found " + filePath.getFileName().toString());
                }
            });
        } catch (IOException e) {
            logAndExit(e);
        }

        return mp3Files;
    }

    private static void logAndExit(Exception e) {
        log("Exception: " + e.getMessage());
        for (StackTraceElement element : e.getStackTrace()) {
            log(element.toString());
        }
        System.exit(0);
    }

    private static void log(String message) {
        System.out.println(message);

        BufferedWriter bw = null;
        FileWriter fw = null;

        try {
            File file = new File(path + "/output.log");

            // if file doesnt exists, then create it
            if (!file.exists()) {
                file.createNewFile();
            }

            // true = append file
            fw = new FileWriter(file.getAbsoluteFile(), true);
            bw = new BufferedWriter(fw);

            bw.write(message + System.getProperty("line.separator"));

        } catch (IOException e) {
            logAndExit(e);
        } finally {
            try {
                if (bw != null)
                    bw.close();
                if (fw != null)
                    fw.close();
            } catch (IOException ex) {
                logAndExit(ex);
            }
        }
    }
}
