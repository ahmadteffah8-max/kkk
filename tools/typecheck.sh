#!/usr/bin/env bash
# Offline type-check of the Android sources against hand-written API stubs (no Android SDK needed).
# It catches typos, missing imports and wrong arguments in OUR code; real SDK verification is the Gradle build.
set -e
cd "$(dirname "$0")/.."
W=build/typecheck
rm -rf "$W" && mkdir -p "$W/stubs" "$W/r" "$W/out"
python3 -I tools/android-stubs/gen.py "$W/stubs"
python3 -I - <<'PY'
import re
names = re.findall(r'name="([a-z_0-9]+)"', open('app/src/main/res/values/strings.xml', encoding='utf-8').read())
body = "".join("        public static final int %s = %d;\n" % (n, i + 1) for i, n in enumerate(names))
open('build/typecheck/r/R.java', 'w').write("package com.pixelstudio.app;\npublic final class R {\n    public static final class string {\n%s    }\n}\n" % body)
PY
javac -nowarn -Xlint:-options -d "$W/out" $(find "$W/stubs" "$W/r" app/src/main/java -name '*.java')
echo "typecheck OK"
