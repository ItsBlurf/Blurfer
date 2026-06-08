#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

VENV_DIR="$ROOT_DIR/.build-venv-linux"
PYTHON_EXE="$VENV_DIR/bin/python"

if [ ! -x "$PYTHON_EXE" ]; then
    python3 -m venv "$VENV_DIR"
fi

"$PYTHON_EXE" -m pip install -r requirements-build.txt

"$PYTHON_EXE" -m PyInstaller \
    --noconfirm \
    --clean \
    --onefile \
    --windowed \
    --name Blurfer \
    --add-data "assets:assets" \
    blurfer.py

chmod +x "$ROOT_DIR/dist/Blurfer"
echo "Built: $ROOT_DIR/dist/Blurfer"

cp "$ROOT_DIR/dist/Blurfer" "$ROOT_DIR/dist/Blurfer-linux-x86_64.bin"
chmod +x "$ROOT_DIR/dist/Blurfer-linux-x86_64.bin"
echo "Built: $ROOT_DIR/dist/Blurfer-linux-x86_64.bin"

if command -v appimagetool >/dev/null 2>&1; then
    APPDIR="$ROOT_DIR/dist/Blurfer.AppDir"
    rm -rf "$APPDIR"
    mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/share/applications" "$APPDIR/usr/share/metainfo"

    cp "$ROOT_DIR/dist/Blurfer" "$APPDIR/usr/bin/Blurfer"
    cp "$ROOT_DIR/assets/blurfer_icon_256.png" "$APPDIR/blurfer.png"

    cat > "$APPDIR/AppRun" <<'APP_RUN'
#!/usr/bin/env bash
HERE="$(dirname "$(readlink -f "${0}")")"
exec "$HERE/usr/bin/Blurfer" "$@"
APP_RUN
    chmod +x "$APPDIR/AppRun"

    cat > "$APPDIR/com.github.ItsBlurf.Blurfer.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Blurfer
Exec=Blurfer
Icon=blurfer
Categories=Network;
Terminal=false
DESKTOP

    cp "$APPDIR/com.github.ItsBlurf.Blurfer.desktop" \
        "$APPDIR/usr/share/applications/com.github.ItsBlurf.Blurfer.desktop"

    cat > "$APPDIR/usr/share/metainfo/com.github.ItsBlurf.Blurfer.appdata.xml" <<'APPDATA'
<?xml version="1.0" encoding="UTF-8"?>
<component type="desktop-application">
  <id>com.github.ItsBlurf.Blurfer</id>
  <name>Blurfer</name>
  <summary>Send payload files over TCP</summary>
  <metadata_license>CC0-1.0</metadata_license>
  <description>
    <p>Blurfer manages payload queues and sends selected files to a target host over TCP.</p>
  </description>
  <launchable type="desktop-id">com.github.ItsBlurf.Blurfer.desktop</launchable>
  <url type="homepage">https://github.com/ItsBlurf/Blurfer</url>
  <content_rating type="oars-1.1" />
</component>
APPDATA

    appimagetool "$APPDIR" "$ROOT_DIR/dist/Blurfer-x86_64.AppImage"
    echo "Built: $ROOT_DIR/dist/Blurfer-x86_64.AppImage"
else
    echo "appimagetool was not found, so AppImage packaging was skipped."
    echo "Install appimagetool on Linux and rerun this script to create a .AppImage."
fi
