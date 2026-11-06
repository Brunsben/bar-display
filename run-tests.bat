@echo off
rem Runs the bar display tests. Optional arguments name test namespaces to run.
setlocal

if "%JAVA_HOME%"=="" set "JAVA_HOME=%LOCALAPPDATA%\Programs\clojure-toolchain\jdk-21.0.13+9"
set "PATH=%JAVA_HOME%\bin;%LOCALAPPDATA%\Programs\clojure-toolchain\bin;%PATH%"

cd /d "%~dp0.."

rem Skip the user guide build, which is only needed to package Beat Link Trigger.
call lein.bat update-in :prep-tasks empty -- run -m clojure.main "%~dp0run-tests.clj" %*
exit /b %errorlevel%
