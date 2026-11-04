# Inicio para Fire TV

Pantalla de inicio propia para Fire TV Stick (sin publicidad de Amazon), con buscador de AceStream,
detector de bloqueos y actualizaciones a distancia.

| Carpeta | Contenido |
|---|---|
| `app/` | Código de la app (Java, sin Gradle): `AndroidManifest.xml`, `src/`, `res/`, `assets/` |
| `scripts/compilar.sh` | Compila y firma el APK (lo usan los otros scripts y GitHub) |
| `scripts/compilar-e-instalar.sh` | Compila e instala por ADB en Fire Sticks de tu red |
| `kit/preparar-firestick.sh` | Deja un Fire Stick nuevo listo: limpieza, ajustes, apps, Inicio y permisos |
| `.github/workflows/` | `publicar.yml` publica versiones; `comprobar.yml` comprueba que compila |
| `CHANGELOG.md` | Qué cambia en cada versión |

## Publicar una versión nueva (desde cualquier sitio, también desde el móvil)

1. Sube los cambios del código a `main` (la comprobación automática te dirá si compila).
2. En GitHub: **Actions → Publicar versión → Run workflow**. Escribe la versión (p. ej. `2.7.1`)
   y qué cambia, en una frase: es lo que verá la persona en la tele.
3. GitHub sube el número de versión, compila, firma, guarda la etiqueta `v2.7.1` y publica el APK.
4. Cada Fire Stick lo detecta solo (al abrir Inicio, como mucho cada 6 h): sale el aviso
   «Nueva versión de Inicio», se pulsa **Actualizar ahora** y luego **Instalar**.

Si un Fire Stick se queda sin poder actualizarse (p. ej. Inicio no arranca), se reinstala sin ADB
desde la app **Downloader** (ya instalada por el kit) con la dirección fija de la última versión:
`https://github.com/alexmaga07/inicio-firetv/releases/latest/download/inicio.apk`

Para volver atrás: publica una versión nueva con el código anterior (Android no deja instalar una
versión con número menor encima de una mayor).

### Primera vez: secretos de GitHub

En el repositorio: **Settings → Secrets and variables → Actions → New repository secret**:

- `KEYSTORE_B64`: la clave `inicio.keystore` en base64 (en Ubuntu/WSL: `base64 -w0 inicio.keystore`).
- `KEYSTORE_PASS`: su contraseña.

Sin la clave de siempre, los Fire Stick rechazan la actualización: guárdala también fuera de GitHub.

Si el código es privado, las versiones tienen que publicarse en un repositorio público aparte
(los Fire Stick no pueden descargar de uno privado): crea la variable `RELEASES_REPO`
(`usuario/repositorio-publico`) y el secreto `RELEASES_TOKEN` (token con permiso de escritura en él).

## Preparar un Fire Stick nuevo

1. En el Fire Stick: Configuración → Mi Fire TV → Acerca de → pulsa 7 veces el nombre para ver las
   opciones para desarrolladores; actívalas con **Depuración ADB** y **Apps de origen desconocido**.
2. En el PC, desde Ubuntu (WSL), con el Fire Stick en la misma red:
   `bash kit/preparar-firestick.sh 192.168.1.XX`
3. Acepta en la tele el aviso de depuración. El script guarda una copia de seguridad del aparato en
   `kit/registro/` y lo apunta en `kit/registro/aparatos.csv`.
4. Luego, en la tele, con la cuenta de cada persona: Proton VPN (Appstore), sesión en Stremio, etc.

Los APK de Stremio y AceStream no van en git (pesan demasiado): están en la carpeta del proyecto
(`kit/apks/`) y su lista en `kit/apks/apps.txt`.

## Compilar en tu PC

Ubuntu (WSL) con JDK 17 y el SDK de Android (`platforms;android-28` y `build-tools;34.0.0`) en
`~/tools/sdk`, y en la raíz del repositorio `inicio.keystore` y un archivo `inicio.keystore.pass` con su
contraseña (ninguno de los dos se sube a GitHub):

    bash scripts/compilar-e-instalar.sh                  # los dos Fire Stick de casa
    bash scripts/compilar-e-instalar.sh 192.168.1.50     # otro
    bash scripts/compilar-e-instalar.sh --sin-instalar   # solo compilar
