#!/bin/sh
# Самопроверка слоя выборки для отката (предикат rolled_back и порядок bind-параметров).
# Запускать из корня проекта после успешной сборки:  sh tools/selfcheck.sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.14-bin/*/gradle-8.14/bin/gradle 2>/dev/null | head -1)"
[ -n "$GRADLE" ] || GRADLE="$ROOT/gradlew"

# Сначала пересобираем: иначе проверки гоняются по УСТАРЕВШИМ классам и молча зеленеют
# на коде, которого уже нет.
"$GRADLE" -p "$ROOT" classes -q || exit 1

CP="$("$GRADLE" -p "$ROOT" printRuntimeClasspath -q 2>/dev/null | tail -1)"
OUT="$ROOT/build/selfcheck"
mkdir -p "$OUT"
javac -nowarn -cp "$CP" -d "$OUT" "$ROOT/tools/GLSelfCheck.java"
java -cp "$OUT:$CP" GLSelfCheck
