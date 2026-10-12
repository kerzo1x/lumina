#!/bin/bash
# usage: devscripts/run.sh <script name without .txt> [dev|prod] [width height]
# Starts from a fresh copy of the test world every time, so scripts can build and teleport freely.
# LUMINA_TEST_WORLD is the save folder to copy, it has to be named "New World".
# Without a test world: ./gradlew runDevClient -PluminaScript=<file> -PluminaNewWorld=<name> [-PluminaSeed=<seed>]
cd "$(dirname "$0")/.."
MODE=${2:-dev}
if [ "$MODE" = prod ]; then RUN=run-prod; TASK=prodClient; else RUN=run; TASK=runDevClient; fi
SAVE="${LUMINA_TEST_WORLD:?set LUMINA_TEST_WORLD to the path of a world folder named 'New World'}"
rm -rf "$RUN/saves/New World" "$RUN"/screenshots/*.png
mkdir -p "$RUN/saves"
cp -r "$SAVE" "$RUN/saves/"
EXTRA=""
if [ -n "$3" ]; then EXTRA="-PluminaWidth=$3 -PluminaHeight=$4"; fi
./gradlew $TASK -PluminaScript="$(pwd -W)/devscripts/$1.txt" $EXTRA --console=plain > "$RUN/gradle-run.log" 2>&1
echo "exit $?"
grep -E "ERROR|WARN|Exception|health|fps " "$RUN/logs/lumina.log" | cut -c1-900 | head -40
ls "$RUN/screenshots"
