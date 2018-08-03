package com.stoneburner;

import com.google.common.base.Stopwatch;
import com.mpatric.mp3agic.InvalidDataException;
import com.mpatric.mp3agic.Mp3File;
import com.mpatric.mp3agic.UnsupportedTagException;
import org.apache.commons.lang3.SystemUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Stopwatch.createStarted;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.io.Files.move;
import static java.lang.Math.abs;
import static java.lang.String.format;
import static java.lang.System.getProperty;
import static java.net.URLDecoder.decode;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Paths.get;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.IntStream.range;
import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FilenameUtils.getExtension;
import static org.apache.commons.lang3.StringUtils.*;
import static org.apache.commons.lang3.exception.ExceptionUtils.getStackTrace;
import static org.json.XML.toJSONObject;

public class Main {

    //mp3splt[.exe] -d overdrivesplit -o newfilename input start_time end_time
    private static final String MP3_SPLT_COMMAND = "%s %s -d %s -o %s -g %s %s %s %s";
    private static String path = "";
    private static Deque<File> processedFiles = new ArrayDeque<>();
    private static File logFile = null;

    public static void main(String[] args) {

        List<Chapter> chapters = newArrayList();

        AtomicReference<String> albumtitle = new AtomicReference<>("");
        AtomicReference<String> author = new AtomicReference<>("");

        boolean isWin = SystemUtils.IS_OS_WINDOWS;
        boolean isMac = SystemUtils.IS_OS_MAC;

        if (!isWin && !isMac) {
            logAndExit(new Exception("Not running windows or osx."));
        }

        AtomicLong totalLengthOriginal = new AtomicLong(0);

        getAllFilesInDirectoryWithExtension("mp3", null).stream().forEach(file -> {
            try {
                Mp3File mp3File = new Mp3File(file.getPath());
                totalLengthOriginal.addAndGet(mp3File.getLengthInMilliseconds());
                if (mp3File.getId3v2Tag().getOverDriveMarkers() == null) {
                    log("no markers found in file: " + file.getName());
                    return;
                }

                processedFiles.add(file);

                if (isBlank(albumtitle.get())) {
                    albumtitle.set(mp3File.getId3v2Tag().getAlbum());
                    author.set(mp3File.getId3v2Tag().getArtist());
                }
                List<Chapter> innerList = newArrayList();

                String markers = decode(mp3File.getId3v2Tag().getOverDriveMarkers(), "UTF-8");
                log("markers = " + markers);
                JSONObject markerObj = toJSONObject(markers);
                AtomicReference<JSONArray> array = new AtomicReference<>();
                try {
                    array.set(markerObj.getJSONObject("Markers").getJSONArray("Marker"));
                } catch (JSONException e) {
                    array.set(new JSONArray("[" + markerObj.getJSONObject("Markers").getJSONObject("Marker").toString() + "]"));
                }

                log(array.get().length() + " markers found in " + mp3File.getFilename());
                range(0, array.get().length()).forEach(i -> {
                    try {
                        JSONObject inner = (JSONObject) array.get().get(i);
                        String time = inner.getString("Time");
                        if (i==0 && !time.equals("0:00.000")) {
                            time = "0:00.000";
                        }
                        if (i != array.get().length()-1 && time.equals(((JSONObject)array.get().get(i+1)).getString("Time"))) {
                            return;
                        }
                        String name = trim(inner.getString("Name")
                                .replace(".", "")
                                .replaceAll(" \\(([0-9]{2}:)?[0-9]{2}:[0-9]{2}\\)$", "")
                                .replaceAll(" - [cC]ontinued$", ""));

                        String lastChapterName = chapters.isEmpty() ? "" : chapters.get(chapters.size() - 1).getChapterName();

                        if (!name.equals(lastChapterName) || !chapters.get(chapters.size() - 1).getFile().equals(file)) {
                            Chapter newChapter = new Chapter(mp3File, file, name, time);
                            if (name.equals(lastChapterName)) {
                                newChapter.setChapterNameFormatted(name + " continued");
                            }
                            innerList.add(newChapter);
                            chapters.add(newChapter);
                        }
                    } catch (JSONException ex) {
                        //do nothing
                    }
                });

            } catch (IOException | UnsupportedTagException | InvalidDataException | JSONException e) {
                logAndExit(e);
            }
        });
        processedFiles.add(logFile);

        AtomicReference<String> chapteredDirectory = new AtomicReference<String>("");

        range(0, chapters.size()).forEach(i -> {
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

            String newFileName = "\"" + (leftPad(String.valueOf(i+1), 2, '0') + "+-+@t\"");
            chapteredDirectory.set(cleanFileName(chapter.getFile().getParentFile().getAbsolutePath() + "/" + albumtitle.get() + " (Chaptered)"));

            String command = format(MP3_SPLT_COMMAND,
                    mp3splt,
                    chapter.getMp3File().isVbr() ? "-f" : "",
                    chapteredDirectory.get(),
                    newFileName,
                    "\"[@o,@a=" + author + ",@b=" + albumtitle + ",@t=" + cleanForCommandLine(chapter.getChapterNameFormatted()) + ",@n=" + (i+1) + "]\"",
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
        });

        getAllFilesInDirectoryWithExtension("jpg", null).parallelStream()
                .filter(file -> !chapters.isEmpty())
                .forEach(file -> {
            processedFiles.add(file);
            StringBuilder builder = new StringBuilder(file.getParentFile().getAbsolutePath());
            builder.append("/").append(albumtitle.get()).append(" (Chaptered)/").append(file.getName());
            File destination = new File(builder.toString());
            try {
                copyFile(file, destination);
            } catch (IOException e) {
                log(e.getMessage());
            }
        });

        long lengthOfNewFiles = getAllFilesInDirectoryWithExtension("mp3", chapteredDirectory.get()).stream()
                .mapToLong(f -> {
                    try {
                        return new Mp3File(f).getLengthInMilliseconds();
                    } catch (Exception e) {
                        log("Error with mp3File: " + e);
                        return 0;
                    }
                }).sum();

        if (!chapters.isEmpty()) {
            long differenceInTime = abs(lengthOfNewFiles - totalLengthOriginal.get());
            if (differenceInTime > 10000) {
                log("Original files totaled: " + totalLengthOriginal.get() + " ms.");
                log("New files totaled: " + lengthOfNewFiles + " ms.");
                log("Total length difference was: " + differenceInTime + ". Something went wrong.");
            } else {
                swapDirectories(chapteredDirectory.get().replaceAll("\\\\", ""));
            }
        }

        log("Done");
    }

    private static Set<File> getAllFilesInDirectoryWithExtension(String extension, String optionalPath) {
        Set<File> files = new TreeSet<File>(new Comparator<File>() {
            public int compare(File one, File other) {
                return one.getName().compareToIgnoreCase(other.getName());
            }
        });

        String decodedPath = "";
        try {
            decodedPath = decode(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            logAndExit(e);
        }

        if (isNotEmpty(optionalPath)) {
            decodedPath = optionalPath.replaceAll("\\\\", "");
        }

        File pathFile = new File(decodedPath);
        if (pathFile.isFile()) {
            decodedPath = pathFile.getParentFile().getAbsolutePath();
        }

        if (!isNotEmpty(optionalPath)) {
            path = decodedPath;
        }

        Arrays.stream(new File(decodedPath).listFiles()).forEach(file -> {
            Path path = get(file.getAbsolutePath());
            if (isRegularFile(path) && (isBlank(extension) || getExtension(file.getName()).equals(extension))) {
                files.add(file);
                log("found " + file.getName());
            }
        });


        return files;
    }

    private static void swapDirectories(String chapteredDirectory) {
        new File(path + "/backup").mkdir();
        processedFiles.forEach(file -> {
            moveFileWithRetry(file, new File(path + "/backup/" + file.getName()));
        });

        getAllFilesInDirectoryWithExtension("", chapteredDirectory).forEach(file -> {
            moveFileWithRetry(file, new File(path + "/" + file.getName()));
        });

        File chapteredDirectoryFile = new File(chapteredDirectory);
        if (chapteredDirectoryFile.exists() && chapteredDirectoryFile.isDirectory() && chapteredDirectoryFile.list().length == 0) {
            chapteredDirectoryFile.delete();
        }
    }

    private static String cleanFileName(String string) {
        return string.replace(" ", "\\ ")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("'", "\\'");
    }

    private static String cleanForCommandLine(String chapterName) {
        return chapterName.replaceAll("\"", "\\\\\"");
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
            File movedLogFile = new File(path + "/backup/output.log");
            if (logFile == null) {
                logFile = new File(path + "/output.log");
            } else if (movedLogFile.exists()) {
                logFile = movedLogFile;
            }

            // if file doesnt exists, then create it
            if (!logFile.exists()) {
                logFile.createNewFile();
            }

            // true = append file
            fw = new FileWriter(logFile.getAbsoluteFile(), true);
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

    private static void moveFileWithRetry(File originalLocation, File newLocation) {
        Stopwatch stopwatch = createStarted();
        boolean success = false;
        while (!success && stopwatch.elapsed(MINUTES) < 30) {
            try {
                move(originalLocation, newLocation);
                success = true;
                stopwatch.stop();
            } catch (IOException e) {
                log("Error moving file! " + e);
                try {
                    SECONDS.sleep(10);
                } catch (InterruptedException e1) {
                    //no-op
                }
            }
        }
    }
}
