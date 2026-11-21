#!/bin/sh
# Lightweight launcher. If a generated Gradle wrapper JAR is present it is used;
# otherwise a locally installed Gradle is used. Android Studio also supports
# importing this project and will use gradle-wrapper.properties directly.
set -e

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -f "$WRAPPER_JAR" ]; then
    if [ -n "$JAVA_HOME" ]; then
        JAVA_CMD="$JAVA_HOME/bin/java"
    else
        JAVA_CMD="java"
    fi
    exec "$JAVA_CMD" -Dorg.gradle.appname=gradlew \
        -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi

if command -v gradle >/dev/null 2>&1; then
    exec gradle "$@"
fi

cat >&2 <<'EOF'
Gradle wrapper JAR was not generated in this checkout.
Open the project in Android Studio (it will use Gradle 8.7 from
gradle/wrapper/gradle-wrapper.properties), or install Gradle 8.7 and run:
    gradle wrapper --gradle-version 8.7
EOF
exit 1
