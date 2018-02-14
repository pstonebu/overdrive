package com.stoneburner;

import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.UnsupportedTagException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.google.common.collect.Lists.newArrayList;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.net.URLDecoder.decode;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.walk;
import static java.nio.file.Paths.get;
import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FilenameUtils.getExtension;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.leftPad;
import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;
import static org.json.XML.toJSONObject;

public class Main {

    //mp3splt[.exe] -d overdrivesplit -o newfilename input start_time end_time
    private static final String MP3_SPLT_COMMAND = "%s %s -d %s -o %s -g %s %s %s %s";
    private static String path = "";

    public static void main(String[] args) {

        List<Chapter> chapters = newArrayList();

        AtomicReference<String> albumtitle = new AtomicReference<>("");
        AtomicReference<String> author = new AtomicReference<>("");

        boolean isWin = SystemUtils.IS_OS_WINDOWS;
        boolean isMac = SystemUtils.IS_OS_MAC;

        if (!isWin && !isMac) {
            logAndExit(new Exception("Not running windows or osx."));
        }

        getAllFilesInDirectoryWithExtension("mp3").stream().forEach(file -> {
            try {
                Mp3File mp3File = new Mp3File(file.getPath());
                if (isBlank(albumtitle.get())) {
                    albumtitle.set(mp3File.getId3v2Tag().getAlbum());
                    author.set(mp3File.getId3v2Tag().getArtist());
                }
                List<Chapter> innerList = newArrayList();

                String markers = decode(mp3File.getId3v2Tag().getOverDriveMarkers(), "UTF-8");
                log("markers = " + markers);
                JSONObject markerObj = toJSONObject(markers);
                JSONArray array = null;
                try {
                    array = markerObj.getJSONObject("Markers").getJSONArray("Marker");
                } catch (JSONException e) {
                    JSONObject chapter = markerObj.getJSONObject("Markers").getJSONObject("Marker");
                    String time = chapter.getString("Time");
                    String name = chapter.getString("Name");

                    chapters.add(new Chapter(mp3File, file, name, time));
                    return;
                }

                log(array.length() + " markers found in " + mp3File.getFilename());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject inner = (JSONObject)array.get(i);
                    String time = inner.getString("Time");
                    String name = inner.getString("Name");

                    String lastChapterName = chapters.isEmpty() ? "" : chapters.get(chapters.size()-1).getChapterName();
                    //only add a new chapter if it isn't a continutation of the last one
                    String regex = lastChapterName + " \\((.*)\\)";
                    if (!name.matches(regex)) {
                        Chapter newChapter = new Chapter(mp3File, file, name, time);
                        innerList.add(newChapter);
                        chapters.add(newChapter);
                    }
                }

            } catch (IOException | UnsupportedTagException | InvalidDataException | JSONException e) {
                logAndExit(e);
            }
        });

        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            String mp3splt = isMac ? "/usr/local/bin/mp3splt" : "mp3splt.exe";
            int beginminutes = chapter.getSecondsMark() / 60;
            int beginseconds = chapter.getSecondsMark() % 60;
            int beginhundredths = chapter.getHundredths();
            int endminutes = 0;
            int endseconds = 0;
            int endhundredths = 0;

            //if this is the last chapter or this is the last chapter of this file
            if (i+1 == chapters.size() || chapter.getMp3File() != chapters.get(i+1).getMp3File()) {
                endminutes = -1;
            } else {
                endminutes = chapters.get(i+1).getSecondsMark() / 60;
                endseconds = chapters.get(i+1).getSecondsMark() % 60;
                endhundredths = chapters.get(i+1).getHundredths();
            }

            String newFileName = "\"" + (leftPad(String.valueOf(i+1), 2, '0') + " - " + chapter.getChapterName())
                    .replace("\"", "")
                    .replace(".", "")
                    .replace(" ", "+")
                    .replace("'", "\\'")
                    .replace(":", "") + "\"";

            String command = format(MP3_SPLT_COMMAND,
                    mp3splt,
                    chapter.getMp3File().isVbr() ? "-f" : "",
                    cleanFileName(chapter.getFile().getParentFile().getAbsolutePath()) + "/overdrivesplit",
                    newFileName,
                    "\"[@o,@a=" + author + ",@b=" + albumtitle + ",@t=" + chapter.getChapterName() + ",@n=" + (i+1) + "]\"",
                    cleanFileName(chapter.getMp3File().getFilename()),
                    beginminutes + "." + beginseconds + "." + beginhundredths,
                    endminutes == -1 ? "EOF" : (endminutes + "." + endseconds + "." + endhundredths));

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

        getAllFilesInDirectoryWithExtension("jpg").stream().forEach(file -> {
            File destination = new File(file.getParentFile().getAbsolutePath() + "/overdrivesplit/" + file.getName());
            try {
                copyFile(file, destination);
            } catch (IOException e) {
                log(e.getMessage());
            }
        });

        log("Done");
    }

    private static List<File> getAllFilesInDirectoryWithExtension(String extension) {
        List<File> mp3Files = newArrayList();
        String decodedPath = "";
        try {
            decodedPath = decode(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            logAndExit(e);
        }

        File pathFile = new File(decodedPath);
        if (pathFile.isFile()) {
            decodedPath = pathFile.getParentFile().getAbsolutePath();
        }

        path = decodedPath;

        try (Stream<Path> paths = walk(get(decodedPath))) {
            paths.forEach(filePath -> {
                if (isRegularFile(filePath) && getExtension(filePath.toString()).equals(extension)) {
                    mp3Files.add(filePath.toFile());
                    log("found " + filePath.getFileName().toString());
                }
            });
        } catch (IOException e) {
            logAndExit(e);
        }

        return mp3Files;
    }

    private static String cleanFileName(String string) {
        return string.replace(" ", "\\ ")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    private static void logAndExit(Exception e) {
        log("Exception: " + e.getMessage());
        log(getStackTrace(e));
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

            bw.write(message + getProperty("line.separator"));

        } catch (IOException e) {
            logAndExit(e);
        } finally {
            try {
                if (bw != null)
                    bw.close();
                if (fw != null)
                    fw.close();
            } catch (IOException e) {
                logAndExit(e);
            }
        }
    }
}
