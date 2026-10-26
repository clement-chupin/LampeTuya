#!/usr/bin/env bash
# Compiles the app's BulbClient/EffectEngine for the desktop JVM and runs BulbTest against the bulb.
# Needs bulb.properties at the project root and a JDK (JAVA_HOME or java on PATH).
set -euo pipefail
cd "$(dirname "$0")"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
SRC=../../app/src/main/java/com/lampe/tuya
mkdir -p lib out
[ -f lib/json.jar ] || curl -sfL -o lib/json.jar https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
"${JAVA_BIN}javac" -nowarn -cp lib/json.jar -d out BulbTest.java "$SRC/BulbClient.java" "$SRC/EffectEngine.java"
"${JAVA_BIN}java" -cp out:lib/json.jar BulbTest "$@"
