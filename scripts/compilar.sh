#!/bin/bash
# Compila y firma Inicio (sin Gradle). Lo usan compilar-e-instalar.sh y GitHub Actions.
#
#   scripts/compilar.sh [salida.apk]
#
# Variables opcionales:
#   SDK      carpeta del SDK de Android (por defecto $ANDROID_HOME o ~/tools/sdk)
#            necesita platforms/android-28 y build-tools/34.0.0
#   KS       clave de firma (por defecto inicio.keystore en la raíz del repositorio)
#   KS_PASS  contraseña de la clave (si no se da, se lee de inicio.keystore.pass, que no se sube)
# Requiere JDK 17 (con JDK 21, d8 falla con un error interno).
set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
APP="$REPO/app"
SDK="${SDK:-${ANDROID_HOME:-$HOME/tools/sdk}}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-28/android.jar"
KS="${KS:-$REPO/inicio.keystore}"
KS_PASS="${KS_PASS:-$(cat "$REPO/inicio.keystore.pass" 2>/dev/null | tr -d '\r\n')}"
[ -n "$KS_PASS" ] || { echo "Falta la contraseña de la clave: KS_PASS=... o el archivo inicio.keystore.pass"; exit 1; }
OUT="${1:-$REPO/inicio.apk}"

for f in "$BT/aapt2" "$BT/d8" "$PLAT" "$KS"; do
  [ -e "$f" ] || { echo "Falta: $f"; exit 1; }
done
command -v javac >/dev/null || { echo "Falta javac (JDK 17)"; exit 1; }

B="$(mktemp -d)"
trap 'rm -rf "$B"' EXIT
mkdir -p "$B/gen" "$B/classes" "$B/dex"
cd "$APP"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -I "$PLAT" --manifest AndroidManifest.xml -A assets --java "$B/gen" \
  --min-sdk-version 22 --target-sdk-version 28 -o "$B/base.apk" "$B/res.zip"
# -source/-target 8 y sin lambdas: d8 sin desugaring no las admite
if ! javac -nowarn -source 8 -target 8 -bootclasspath "$PLAT" -encoding UTF-8 -d "$B/classes" \
     $(find src "$B/gen" -name '*.java') > "$B/javac.log" 2>&1; then
  grep -v "warning: \[options\]" "$B/javac.log"
  echo "== ERROR de compilación"; exit 1
fi
"$BT/d8" --release --min-api 22 --lib "$PLAT" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --out "$OUT" "$B/aligned.apk"
"$BT/apksigner" verify "$OUT"
rm -f "$OUT.idsig"
echo "== APK listo: $OUT ($(grep -o 'versionName="[^"]*"' "$APP/AndroidManifest.xml"))"
