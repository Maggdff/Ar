#!/usr/bin/env bash
# يترجم الشيفرة النقية (model/logic/data-parser) والاختبارات بمترجم Kotlin المرفق مع Gradle ويشغّل JUnit.
# للتحقق المحلي عندما لا يتوفر Android SDK. البناء الحقيقي يتم في GitHub Actions.
set -euo pipefail
L=${GRADLE_LIB:-/opt/gradle-8.14.3/lib}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${TMPDIR:-/tmp}/aska_build; rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
J=$ROOT/app/src/main/java/com/maggd/arabicskills
CP=$L/kotlin-stdlib-2.0.21.jar:$L/kotlinx-serialization-core-jvm-1.6.2.jar:$L/kotlinx-serialization-json-jvm-1.6.2.jar:$L/junit-4.13.2.jar:$L/hamcrest-core-1.3.jar
KC="java -cp $L/kotlin-compiler-embeddable-2.0.21.jar:$L/kotlin-stdlib-2.0.21.jar:$L/kotlin-reflect-2.0.21.jar:$L/kotlin-script-runtime-2.0.21.jar:$L/kotlin-daemon-embeddable-2.0.21.jar:$L/trove4j-1.0.20200330.jar:$L/annotations-24.0.1.jar:$L/kotlinx-coroutines-core-jvm-1.6.4.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect"
$KC -cp "$CP" -d "$OUT/main" $J/model/*.kt $J/logic/*.kt $J/data/ContentParser.kt $J/data/ProgressCodec.kt
$KC -cp "$CP:$OUT/main" -d "$OUT/test" $ROOT/app/src/test/java/com/maggd/arabicskills/*.kt
cd "$ROOT/app"
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's|^\./||;s|\.class$||;s|/|.|g')
java -cp "$CP:$OUT/main:$OUT/test" org.junit.runner.JUnitCore $CLASSES
