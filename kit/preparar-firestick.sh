#!/bin/bash
# Prepara un Fire Stick nuevo (o deja uno al día) con todo lo de Inicio.
#
# Uso, desde Ubuntu (WSL), estés en la carpeta que estés:
#   bash /mnt/c/Users/Alejandro/Downloads/FireTV-Inicio/kit/preparar-firestick.sh 192.168.1.50
# (si no pones la IP, la pregunta). Opciones: --sin-reiniciar
#
# Hace, en este orden:
#   1. Conecta por ADB (la primera vez hay que aceptar el aviso en la tele).
#   2. Guarda una copia de los ajustes y apps del aparato en kit/registro/ (para poder deshacer).
#   3. Desactiva las apps de Amazon que no se usan (anuncios, métricas, tutoriales...).
#   4. Ajustes de fluidez: animaciones 0,5x y suspensión a los 20 min.
#   5. Instala Inicio, Stremio y AceStream con los APK del kit (no descarga nada).
#   6. Configura Inicio según la versión de Fire OS (botón Home, permisos, actualizaciones).
#   7. Apunta el aparato en kit/registro/aparatos.csv (control de versiones por aparato).
# Las apps de la tienda de Amazon (Proton VPN, Netflix...) se instalan desde la propia tele.

KIT="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$KIT")"
INICIO_APK="$ROOT/app-inicio/inicio.apk"
REINICIAR=1
IP=""
for a in "$@"; do
  case "$a" in
    --sin-reiniciar) REINICIAR=0 ;;
    *) IP="$a" ;;
  esac
done

# ADB: el de Windows de la carpeta herramientas (desde WSL) o, si no, el que haya instalado
if [ -n "$ADB" ]; then :;
elif [ -x "$ROOT/herramientas/platform-tools/adb.exe" ] && grep -qi microsoft /proc/version 2>/dev/null; then
  ADB="$ROOT/herramientas/platform-tools/adb.exe"
else
  ADB="$(command -v adb)"
fi
[ -n "$ADB" ] || { echo "No encuentro ADB"; exit 1; }
# adb.exe necesita rutas de Windows para los APK
ruta() { case "$ADB" in *.exe) wslpath -w "$1" ;; *) echo "$1" ;; esac; }

verde()   { printf '\033[32m   %s\033[0m\n' "$*"; }
amarillo(){ printf '\033[33m   %s\033[0m\n' "$*"; }
titulo()  { printf '\n\033[36m== %s\033[0m\n' "$*"; }

echo "Preparar Fire Stick con Inicio"
echo "Antes: en el Fire Stick, Configuración > Mi Fire TV > Opciones para desarrolladores > Depuración ADB: activada."
if [ -z "$IP" ]; then
  echo "La IP se ve en Configuración > Mi Fire TV > Acerca de > Red."
  read -r -p "IP del Fire Stick (p. ej. 192.168.1.50): " IP
fi
IP="$(echo "$IP" | tr -d '[:space:]')"
case "$IP" in *:*) ;; *) IP="$IP:5555" ;; esac

# ------------------------------------------------------------------ 1. Conectar
titulo "Conectando con $IP"
conectado=0
for i in $(seq 1 30); do
  "$ADB" connect "$IP" >/dev/null 2>&1
  if "$ADB" -s "$IP" get-state 2>/dev/null | grep -q device; then conectado=1; break; fi
  [ "$i" = 2 ] && amarillo 'Si en la tele sale "¿Permitir la depuración?", marca "Permitir siempre" y pulsa Aceptar.'
  sleep 4
done
[ "$conectado" = 1 ] || { echo "No se ha podido conectar. Revisa la IP, la misma wifi y la Depuración ADB."; exit 1; }

sh_()  { "$ADB" -s "$IP" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
SDK="$(sh_ getprop ro.build.version.sdk)"
MODELO="$(sh_ getprop ro.product.model)"
FIREOS="$(sh_ getprop ro.build.version.name)"
SERIE="$(sh_ getprop ro.serialno)"
verde "Conectado: $MODELO · $FIREOS · Android API $SDK"

# ------------------------------------------------------------------ 2. Copia de seguridad
titulo "Guardando copia de seguridad del aparato"
NOMBRE="$(echo "$MODELO" | tr -cd 'A-Za-z0-9')_$(echo "$SERIE" | tr -cd 'A-Za-z0-9')_$(date +%Y%m%d-%H%M)"
REG="$KIT/registro/$NOMBRE"
mkdir -p "$REG"
sh_ settings list global > "$REG/ajustes-global.txt"
sh_ settings list secure > "$REG/ajustes-secure.txt"
sh_ settings list system > "$REG/ajustes-system.txt"
sh_ pm list packages -d  > "$REG/apps-desactivadas-antes.txt"
sh_ pm list packages -3 --show-versioncode > "$REG/apps-instaladas-antes.txt"
verde "Copia en kit/registro/$NOMBRE"

# ------------------------------------------------------------------ 3. Limpieza de Amazon
titulo "Desactivando apps de Amazon que no se usan"
# Unión de las listas del HD y del 4K. Las que no existen en un modelo se saltan.
AMAZON="com.amazon.kso.blackbird com.amazon.sneakpeek com.amazon.tv.acr com.amazon.tv.fw.metrics
com.amazon.wirelessmetrics.service com.amazon.dp.logger com.amazon.shoptv.client
com.amazon.shoptv.firetv.client com.amazon.gamehub com.amazon.bueller.photos com.amazon.bueller.music
com.amazon.minitv.android.app com.amazon.storm.lightning.tutorial com.amazon.tmm.tutorial
com.amazon.tv.releasenotes com.amazon.stillwatching.activity com.amazon.cloud9
com.amazon.tv.website_launcher com.amazon.spotify.mediabrowserservice com.amazon.tv.livetv
com.amazon.ftv.screensaver com.amazon.tahoe com.amazon.media.recommendations"
TODAS="$(sh_ pm list packages)"
N=0
for p in $AMAZON; do
  if echo "$TODAS" | grep -qx "package:$p"; then sh_ pm disable-user --user 0 "$p" >/dev/null; N=$((N+1)); fi
done
sh_ pm trim-caches 999G >/dev/null
echo "$AMAZON" | tr ' ' '\n' | grep . > "$REG/apps-amazon-desactivadas.txt"
verde "$N apps de Amazon desactivadas (para volver atrás: pm enable <app>, lista en el registro)"

# ------------------------------------------------------------------ 4. Ajustes
titulo "Ajustes de fluidez"
sh_ settings put global window_animation_scale 0.5
sh_ settings put global transition_animation_scale 0.5
sh_ settings put global animator_duration_scale 0.5
# Sin el salvapantallas de Amazon, Android se suspende al acabar este tiempo: 20 min, no 5
sh_ settings put system screen_off_timeout 1200000
verde "Animaciones 0,5x · suspensión a los 20 min"

# ------------------------------------------------------------------ 5. Apps del kit
titulo "Instalando apps (sin descargar nada)"
instalar() {
  local apk="$1" nombre="$2" r
  [ -f "$apk" ] || { amarillo "Falta $apk"; return; }
  r="$("$ADB" -s "$IP" install -r "$(ruta "$apk")" </dev/null 2>&1 | tr -d '\r' | tail -1)"
  case "$r" in
    *Success*)   verde "$nombre instalada" ;;
    *DOWNGRADE*) verde "$nombre: ya tenía una versión más nueva" ;;
    *)           amarillo "$nombre: $r" ;;
  esac
}
instalar "$INICIO_APK" "Inicio"
# Apps del kit (lista en kit/apks/apps.txt): se saltan si ya está esa misma versión
grep -v '^#' "$KIT/apks/apps.txt" | while IFS=';' read -r archivo paquete ver; do
  [ -n "$archivo" ] || continue
  actual="$(sh_ dumpsys package "$paquete" | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2)"
  if [ "$actual" = "$ver" ]; then verde "${archivo%.apk}: ya instalada"; continue; fi
  instalar "$KIT/apks/$archivo" "${archivo%.apk}"
done

# ------------------------------------------------------------------ 6. Configurar Inicio
titulo "Configurando Inicio"
sh_ settings put global alarm_manager_constants min_futurity=0
sh_ dumpsys deviceidle whitelist +tv.inicio.launcher >/dev/null
if [ "${SDK:-0}" -ge 26 ]; then
  # Fire OS 7+: botón Home por accesibilidad; permiso para instalar sus propias actualizaciones
  sh_ settings put secure enabled_accessibility_services tv.inicio.launcher/tv.inicio.launcher.GuardService
  sh_ settings put secure accessibility_enabled 1
  sh_ appops set tv.inicio.launcher REQUEST_INSTALL_PACKAGES allow
  # Para que Inicio reactive su botón Home tras actualizarse a distancia (Fire OS lo apaga)
  sh_ pm grant tv.inicio.launcher android.permission.WRITE_SECURE_SETTINGS
  # AceStream: sin ventanita (PiP) al salir del reproductor
  sh_ appops set org.acestream.node.web PICTURE_IN_PICTURE deny
  verde "Botón Home por accesibilidad · actualizaciones permitidas · AceStream sin PiP"
else
  # Fire OS 6: Amazon bloquea la accesibilidad por ADB; se usa el registro del sistema
  sh_ pm grant tv.inicio.launcher android.permission.READ_LOGS
  sh_ appops set tv.inicio.launcher SYSTEM_ALERT_WINDOW allow
  sh_ settings put secure install_non_market_apps 1
  verde "Botón Home por registro del sistema · actualizaciones permitidas"
fi
sh_ am start -n tv.inicio.launcher/.MainActivity >/dev/null

# ------------------------------------------------------------------ 7. Registro
version() { sh_ dumpsys package "$1" | grep -m1 -o 'versionName=[^ ]*' | cut -d= -f2; }
V_INICIO="$(version tv.inicio.launcher)"
{
  echo "Fecha: $(date '+%Y-%m-%d %H:%M')"
  echo "Modelo: $MODELO"
  echo "Fire OS: $FIREOS (API $SDK)"
  echo "Serie: $SERIE"
  echo "IP: $IP"
  echo "Inicio: $V_INICIO"
  echo "Stremio: $(version com.stremio.one)"
  echo "AceStream: $(version org.acestream.node.web)"
  echo "Apps de Amazon desactivadas: $N"
} > "$REG/resumen.txt"
CSV="$KIT/registro/aparatos.csv"
[ -f "$CSV" ] || echo "fecha;modelo;fireos;serie;ip;inicio" > "$CSV"
echo "$(date '+%Y-%m-%d %H:%M');$MODELO;$FIREOS;$SERIE;$IP;$V_INICIO" >> "$CSV"

titulo "Falta hacer en la tele (necesita la cuenta de cada persona)"
echo "   · Proton VPN: Appstore > buscar «Proton VPN» > Obtener. Sirve para Stremio los días de bloqueos."
echo "   · Stremio: iniciar sesión (o crear cuenta) y añadir sus addons si los quiere."
echo "   · AceStream: la primera vez que se abra, aceptar lo que pida (el motor ya va dentro)."
echo "   · Netflix, HBO Max, YouTube...: desde la Appstore, si se usan."

if [ "$REINICIAR" = 1 ]; then
  titulo "Reiniciando para que se guarde la limpieza"
  echo "   Esperando 40 s (si se reinicia antes, Fire OS puede deshacer lo desactivado)..."
  sleep 40
  "$ADB" -s "$IP" reboot
  verde "Reiniciando. Al encender saldrá Inicio."
fi
# Soltar ADB: algunos modelos (Fire OS 6) solo admiten un ordenador conectado a la vez
"$ADB" disconnect "$IP" >/dev/null 2>&1
echo
echo "Listo. Resumen en kit/registro/$NOMBRE/resumen.txt"
