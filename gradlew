#!/bin/sh
#
# Gradle wrapper launcher.
#
# NOTE: gradle-wrapper.jar is a binary and is not included in this scaffold.
# Android Studio does not need it -- it reads gradle/wrapper/gradle-wrapper.properties
# directly and downloads the right Gradle for you on first sync.
#
# For command-line builds, generate it once with a local Gradle install:
#     gradle wrapper --gradle-version 8.14.3
# after which this script works normally.
#
if [ ! -f "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "gradle-wrapper.jar is missing."
  echo "Run:  gradle wrapper --gradle-version 8.14.3"
  echo "or just open the project in Android Studio, which handles this for you."
  exit 1
fi
exec java -classpath "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
