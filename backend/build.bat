@echo off
setlocal
set "POSSIBLE_1=C:\Program Files\Java\jdk-17.0.19"
set "POSSIBLE_2=C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"
set "POSSIBLE_3=C:\Program Files\Java\jdk-17"
set "POSSIBLE_4=C:\Program Files\Microsoft\jdk-17"

if exist "%POSSIBLE_1%\bin\java.exe" (
    set "JAVA_HOME=%POSSIBLE_1%"
) else if exist "%POSSIBLE_2%\bin\java.exe" (
    set "JAVA_HOME=%POSSIBLE_2%"
) else if exist "%POSSIBLE_3%\bin\java.exe" (
    set "JAVA_HOME=%POSSIBLE_3%"
) else if exist "%POSSIBLE_4%\bin\java.exe" (
    set "JAVA_HOME=%POSSIBLE_4%"
) else (
    echo JDK 17 not found in hard-coded paths.
    echo Install JDK 17 and set JAVA_HOME, or update this script.
    exit /b 1
)

echo Using JAVA_HOME=%JAVA_HOME%
set PATH=%JAVA_HOME%\bin;%PATH%
mvn clean install
endlocal
