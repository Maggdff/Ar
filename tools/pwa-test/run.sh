#!/usr/bin/env bash
# اختبار توافق نسخة الويب (PWA) مع Kotlin: يتطلب kotlinc وJDK وNode 20+.  المتغيرات: KOTLINC_HOME ORGJSON(مجلد/jar لـ org.json) CORE_CLASSES
set -euo pipefail
K="${KOTLINC_HOME:?}"; OJ="${ORGJSON:?}"; B="${BUILD:-/tmp/pwa-build}"; R="$(cd "$(dirname "$0")/../.." && pwd)"
mkdir -p "$B"
[ -d "$B/core" ] || "$K/bin/kotlinc" "$R"/core/src/main/kotlin/com/uniatt/core/*.kt -d "$B/core" 2>/dev/null
"$K/bin/kotlinc" "$R/tools/pwa-test/PwaVectors.kt" -cp "$B/core:$OJ" -d "$B/pv" 2>/dev/null
CP="$B/pv:$B/core:$OJ:$K/lib/kotlin-stdlib.jar"
java -cp "$CP" PwaVectorsKt gen "$B/vectors.json"
node "$R/tools/pwa-test/interop.test.js" "$B/vectors.json" "$B/js-out.json"
java -cp "$CP" PwaVectorsKt verify "$B/vectors.json" "$B/js-out.json"
