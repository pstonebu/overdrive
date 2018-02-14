# Overdrive Splitter
Java jar to split overdrive mp3s into their chapters

# Prerequisites
The system that runs the jar needs to be:
-Either running OSX or Windows
-Have Java JRE installed
-Have mp3splt installed (http://mp3splt.sourceforge.net/mp3splt_page/downloads.php)

# Building the jar
To build this jar, simply run 'mvn clean package' from the root directory

# Splitting mp3s
Copy the built overdrive-splitter.jar to any directory containing overdrive mp3s, and it will scan the mp3s for overdrive markers, and create a chaptered subfolder with the new chaptered mp3s.
