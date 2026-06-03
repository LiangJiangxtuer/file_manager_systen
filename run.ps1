$ErrorActionPreference = "Stop"

javac -encoding UTF-8 -d out src/main/java/com/example/filemanager/FileManagerApplication.java
java -cp out com.example.filemanager.FileManagerApplication
