#!/bin/bash
# Compila Inicio y lo instala por ADB en los Fire Stick que le digas (red local).
#
#   bash scripts/compilar-e-instalar.sh                      # los dos de casa
#   bash scripts/compilar-e-instalar.sh 192.168.1.50 ...     # los que indiques
#   bash scripts/compilar-e-instalar.sh --sin-instalar       # solo compilar
#
# Para los Fire Stick de fuera de casa no hace falta esto: se publica una versión en GitHub
# (ver README) y cada Fire Stick la ofrece sola.
set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/inicio.apk"
IPS=()
for a in "$@"; do
  case "$a" in
    --sin-instalar) SOLO=1 ;;
    *) IPS+=("$a") ;;
  esac
done
[ ${#IPS[@]} -eq 0 ] && IPS=(192.168.1.91 192.168.1.34)

echo "== Compilando…"
bash "$REPO/scripts/compilar.sh" "$OUT"
[ -n "$SOLO" ] && exit 0

# ADB: el de Windows (herramientas/platform-tools) si estamos en WSL, si no el del sistema
ADB="${ADB:-}"
if [ -z "$ADB" ]; then
  for c in "$REPO/../herramientas/platform-tools/adb.exe" "$REPO/herramientas/platform-tools/adb.exe"; do
    [ -x "$c" ] && grep -qi microsoft /proc/version 2>/dev/null && ADB="$c" && break
  done
  [ -n "$ADB" ] || ADB="$(command -v adb)"
fi
APK="$OUT"; case "$ADB" in *.exe) APK="$(wslpath -w "$OUT")" ;; esac

echo "== Instalando…"
for IP in "${IPS[@]}"; do
  case "$IP" in *:*) D="$IP" ;; *) D="$IP:5555" ;; esac
  "$ADB" connect "$D" >/dev/null
  echo -n "$D: "; "$ADB" -s "$D" install -r "$APK" | tail -1
  SDKV="$("$ADB" -s "$D" shell getprop ro.build.version.sdk | tr -d '\r')"
  "$ADB" -s "$D" shell "settings put global alarm_manager_constants min_futurity=0; dumpsys deviceidle whitelist +tv.inicio.launcher >/dev/null"
  if [ "${SDKV:-0}" -lt 26 ]; then
    # Fire OS 6: botón Home por registro del sistema
    "$ADB" -s "$D" shell "pm grant tv.inicio.launcher android.permission.READ_LOGS; appops set tv.inicio.launcher SYSTEM_ALERT_WINDOW allow"
  fi
  "$ADB" -s "$D" shell am start -n tv.inicio.launcher/.MainActivity >/dev/null
  "$ADB" disconnect "$D" >/dev/null
done
# Soltar ADB: el 4K (Fire OS 6) solo admite un ordenador conectado a la vez
"$ADB" kill-server
echo "== Hecho."
