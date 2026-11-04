#!/bin/sh
# Build the debug APK with the local JDK 21 and Android SDK, then install it on the connected phone.
cd "$(dirname "$0")"
export JAVA_HOME="$(cygpath -m "$LOCALAPPDATA/Programs/Microsoft/jdk-21.0.12.1/jdk-21.0.12.1+1")"
export ANDROID_HOME="$(cygpath -m "$LOCALAPPDATA/Android/Sdk")"
"$JAVA_HOME/bin/java" -Xmx64m -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:assembleDebug --console=plain -q || exit 1
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
[ -n "$1" ] && "$ADB" -s "$1" install -r -g app/build/outputs/apk/debug/app-debug.apk
