#!/usr/bin/env bash
set -euo pipefail
out="$(realpath "$1")"
tool="$ANDROID_HOME/build-tools/36.0.0"
android_jar="$ANDROID_HOME/platforms/android-36/android.jar"
fixture=scripts/task056/recipient
mkdir -p "$out/classes"
javac --release 11 -encoding UTF-8 -classpath "$android_jar" -d "$out/classes" "$fixture/ReceiverActivity.java"
"$tool/d8" --min-api 32 --lib "$android_jar" --output "$out" "$out/classes/org/example/task056/recipient/ReceiverActivity.class"
"$tool/aapt2" link -I "$android_jar" --manifest "$fixture/AndroidManifest.xml" -o "$out/unsigned.apk"
(cd "$out" && zip -q unsigned.apk classes.dex)
"$tool/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
keytool -genkeypair -keyalg RSA -keystore "$out/fixture.p12" -alias fixture -storepass android -keypass android -dname CN=AndroidTest -validity 30
"$tool/apksigner" sign --ks "$out/fixture.p12" --ks-pass pass:android --out "$out/recipient.apk" "$out/aligned.apk"
