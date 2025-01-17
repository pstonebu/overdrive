package com.stoneburner;

import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.mpatric.mp3agic.*;
import org.apache.commons.lang3.SystemUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Stopwatch.createStarted;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.io.Files.move;
import static java.lang.Math.abs;
import static java.lang.String.format;
import static java.lang.System.lineSeparator;
import static java.net.URLDecoder.decode;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Paths.get;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
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
    private static final Deque<File> processedFiles = new ArrayDeque<>();
    private static File logFile = null;

    public static void main(String[] args) {

        List<Chapter> chapters = newArrayList();
        AtomicBoolean commas = new AtomicBoolean(false);

        AtomicReference<String> albumtitle = new AtomicReference<>("");
        AtomicReference<String> author = new AtomicReference<>("");

        boolean isWin = SystemUtils.IS_OS_WINDOWS;
        boolean isMac = SystemUtils.IS_OS_MAC;

        if (!isWin && !isMac) {
            logAndExit(new Exception("Not running windows or osx."));
        }

        AtomicLong totalLengthOriginal = new AtomicLong(0);

        HashMap<String,List<Chapter>> tracksToCombine = newHashMap();

        getAllFilesInDirectoryWithExtension("mp3", null).forEach(file -> {
            try {
                Mp3File mp3File = new Mp3File(file.getPath());
                totalLengthOriginal.addAndGet(mp3File.getLengthInMilliseconds());
                if (mp3File.getId3v2Tag().getOverDriveMarkers() == null) {
                    log("no markers found in file: " + file.getName());
                    return;
                }

                processedFiles.add(file);

                if (isBlank(albumtitle.get()) && isNotBlank(mp3File.getId3v2Tag().getAlbum())) {
                    albumtitle.set(mp3File.getId3v2Tag().getAlbum().replaceAll(",", ""));
                }
                if (isBlank(author.get()) && isNotBlank(mp3File.getId3v2Tag().getArtist())) {
                    author.set(mp3File.getId3v2Tag().getArtist());
                }

                String markers = decode(mp3File.getId3v2Tag().getOverDriveMarkers(), StandardCharsets.UTF_8);
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
                        if (inner.get("Name").toString().contains(",")) {
                            commas.set(true);
                        }
                        String name = trim(inner.get("Name").toString()
                                .replace(".", "")
                                .replaceAll(" {2,}", " ")
                                .replaceAll(" \\(([0-9]{2}:)?[0-9]{2}:[0-9]{2}\\)$", "")
                                .replaceAll("( -)? [cC]ontinued$", "")
                                .replaceAll(",", "COMMA"));

                        String lastChapterName = chapters.isEmpty() ? "" : chapters.get(chapters.size() - 1).getChapterName();

                        if (!name.equals(lastChapterName) || !chapters.get(chapters.size()-1).getFile().equals(file)) {
                            Chapter newChapter = new Chapter(mp3File, file, name, time);
                            if (name.equals(lastChapterName)) {
                                newChapter.setChapterNameFormatted(name + " continued");
                                if (tracksToCombine.get(name) == null) {
                                    tracksToCombine.put(name, newArrayList(chapters.get(chapters.size()-1), newChapter));
                                } else {
                                    tracksToCombine.get(name).add(newChapter);
                                }
                            }
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

        AtomicReference<String> chapteredDirectory = new AtomicReference<>("");

        range(0, chapters.size()).forEach(i -> {
            Chapter chapter = chapters.get(i);
            String mp3splt = isMac ? "/usr/local/bin/mp3splt" : "mp3splt.exe";
            int beginminutes = chapter.getSecondsMark() / 60;
            int beginseconds = chapter.getSecondsMark() % 60;
            int beginhundredths = chapter.getHundredths();
            int endminutes;
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
            chapter.setFileName(cleanForCommandLine(leftPad(String.valueOf(i+1), 2, '0') + " - " + chapter.getChapterNameFormatted()));
            chapteredDirectory.set(chapter.getFile().getParentFile().getAbsolutePath() + "/" + albumtitle.get() + " (Chaptered)");

            String command = format(MP3_SPLT_COMMAND,
                    mp3splt,
                    chapter.getMp3File().isVbr() ? "-f" : "",
                    wrapInQuotes(chapteredDirectory.get()),
                    newFileName,
                    "\"[@o,@a=" + author.get() + ",@b=" + albumtitle.get() + ",@t=" + cleanForCommandLine(chapter.getChapterNameFormatted()) + ",@n=" + (i+1) + "]\"",
                    wrapInQuotes(chapter.getMp3File().getFilename()),
                    beginminutes + "." + beginseconds + "." + beginhundredths,
                    endminutes == -1 ? "EOF" : (endminutes + "." + endseconds + "." + endhundredths));

            executeCommand(command, isMac);

        });

        getAllFilesInDirectoryWithExtension("jpg", null).parallelStream()
                .filter(file -> !chapters.isEmpty())
                .forEach(file -> {
            processedFiles.add(file);
            File destination = new File(file.getParentFile().getAbsolutePath() + "/" + albumtitle.get() + " (Chaptered)/" + file.getName());
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
            double differenceInTime = (lengthOfNewFiles * 1.0) / totalLengthOriginal.get();
            long secondsDifference = abs(lengthOfNewFiles - totalLengthOriginal.get());
            if (differenceInTime < .999 || differenceInTime > 1.001) {
                log("Original files totaled: " + totalLengthOriginal.get() + " ms.");
                log("New files totaled: " + lengthOfNewFiles + " ms.");
                log("Total length difference was: " + secondsDifference + ", and percent diff was " + differenceInTime + ". Something went wrong.");
                logAndExit(new Exception("File lengths weren't close enough"));
            } else {
                swapDirectories(chapteredDirectory.get().replaceAll("\\\\", ""));
            }
        }

        log("Combining split tracks");

        tracksToCombine.forEach((key, value) -> {
            List<String> fileNames = value.stream()
                    .map(c -> (c.getFile().getParent() + "/" + c.getFileName() + ".mp3").replaceAll(" ", "\\\\ ").replaceAll(":", "_").replaceAll("'", "\\\\'").replaceAll("\\)", "\\\\)").replaceAll("\\(", "\\\\("))
                    .collect(toList());
            combine(fileNames, isMac);
        });

        if (commas.get()) {
            log("Fixing commas.");
            getAllFilesInDirectoryWithExtension("mp3", null).stream()
                    .filter(file -> file.getName().contains("COMMA"))
                    .forEach(file -> {
                        try {
                            Mp3File mp3File = new Mp3File(file);
                            mp3File.getId3v2Tag().setTitle(mp3File.getId3v2Tag().getTitle().replace("COMMA", ","));
                            mp3File.save(file.getAbsolutePath().replaceAll("COMMA", ","));
                            file.delete();
                        } catch (Exception e) {
                            log("Error");
                        }
                    });
        }

        log("Done");
    }

    private static Set<File> getAllFilesInDirectoryWithExtension(String extension, String optionalPath) {
        Set<File> files = new TreeSet<>((one, other) -> one.getName().compareToIgnoreCase(other.getName()));

        String decodedPath = decode(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath(), StandardCharsets.UTF_8);

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

        Arrays.stream(Objects.requireNonNull(new File(decodedPath).listFiles())).forEach(file -> {
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
        processedFiles.forEach(file -> moveFileWithRetry(file, new File(path + "/backup/" + file.getName())));

        getAllFilesInDirectoryWithExtension("", chapteredDirectory).forEach(file -> moveFileWithRetry(file, new File(path + "/" + file.getName())));

        File chapteredDirectoryFile = new File(chapteredDirectory);
        if (chapteredDirectoryFile.exists() && chapteredDirectoryFile.isDirectory() && Objects.requireNonNull(chapteredDirectoryFile.list()).length == 0) {
            chapteredDirectoryFile.delete();
        }
    }

    private static String wrapInQuotes(String string) {
        return "\"" + string + "\"";
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

            // if file doesn't exist, then create it
            if (!logFile.exists()) {
                logFile.createNewFile();
            }

            // true = append file
            fw = new FileWriter(logFile.getAbsoluteFile(), true);
            bw = new BufferedWriter(fw);

            bw.write(message + lineSeparator());

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

    public static void combine(List<String> fileNames, boolean isMac) {
        try {
            executeCommand("/usr/local/bin/mp3wrap " + fileNames.get(0) + " " + Joiner.on(" ").join(fileNames), isMac);

            //make a new directory for combined files
            File file = new File(fileNames.get(0));
            Mp3File mp3File = new Mp3File(fileNames.get(0).replaceAll("\\\\", "").replaceAll("(:|\\?)", "_"));
            String processedDirectory = file.getParent() + "/overdrive_processed";
            executeCommand("mkdir " + processedDirectory, isMac);

            //move combined files into directory
            fileNames.forEach(s -> executeCommand("mv " + s + " " + processedDirectory, isMac));

            //grab mp3 metadata from new file
            ID3v2 v2 = mp3File.getId3v2Tag();
            ID3v1 v1 = mp3File.getId3v1Tag();

            String commandLineFileName = fileNames.get(0).replaceAll(".mp3$", "_MP3WRAP.mp3");
            String prettyFileName = commandLineFileName.replaceAll("\\\\", "").replaceAll("(:|\\?)", "_");

            //repair new file
            executeCommand("/usr/local/bin/mp3val -f -nb " + commandLineFileName, isMac);

            //copy metadata to repaired new file
            mp3File = new Mp3File(prettyFileName);
            mp3File.setId3v2Tag(v2);
            mp3File.setId3v1Tag(v1);
            mp3File.save(prettyFileName.replaceAll(".mp3$", "2.mp3"));
            executeCommand("rm -rf " + commandLineFileName, isMac);
            executeCommand("mv " + commandLineFileName.replaceAll(".mp3$", "2.mp3" + " \"" + fileNames.get(0) + "\""), isMac);
        } catch (Exception e) {
            log(e.getMessage());
        }
    }

    private static void executeCommand(String command, boolean isMac) {
        log("executing '" + command + "'");

        StringBuilder output = new StringBuilder();

        Process p;
        try {
            if (isMac) {
                p = Runtime.getRuntime().exec(new String[] { "sh", "-c", command});
            } else {
                p = Runtime.getRuntime().exec(new String[] { "CMD", "/C", command});
            }

            p.waitFor();

            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            log(output.toString());

            BufferedReader ereader = new BufferedReader(new InputStreamReader(p.getErrorStream()));

            output = new StringBuilder();
            while ((line = ereader.readLine()) != null) {
                output.append(line).append("\n");
            }

            log(output.toString());

        } catch (Exception e) {
            logAndExit(e);
        }
    }
}
