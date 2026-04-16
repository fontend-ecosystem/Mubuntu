#!/bin/sh

set -e

APP_HOME=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

if [ -f "$WRAPPER_JAR" ]; then
  exec java -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi

echo "gradle-wrapper.jar is missing and no 'gradle' command is installed." >&2
echo "Install Gradle in Termux, then run 'gradle wrapper' once to generate the wrapper JAR." >&2
exit 1
