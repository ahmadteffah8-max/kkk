#!/usr/bin/env bash
# Compiles the pure-Java engine and runs the self test (needs only a JDK).
set -e
cd "$(dirname "$0")/.."
OUT=build/core-test
rm -rf "$OUT" && mkdir -p "$OUT"
javac -d "$OUT" app/src/main/java/com/pixelstudio/core/*.java tools/CoreSelfTest.java
java -cp "$OUT" CoreSelfTest "$@"
