@echo off
rem Starts the bar display. Edit launch.clj to change what it shows.
setlocal

if "%JAVA_HOME%"=="" set "JAVA_HOME=%LOCALAPPDATA%\Programs\clojure-toolchain\jdk-21.0.13+9"
set "PATH=%JAVA_HOME%\bin;%LOCALAPPDATA%\Programs\clojure-toolchain\bin;%PATH%"

cd /d "%~dp0.."

rem Skip the user guide build, which is only needed to package Beat Link Trigger.
call lein.bat update-in :prep-tasks empty -- run -m clojure.main "%~dp0launch.clj"

if errorlevel 1 pause
