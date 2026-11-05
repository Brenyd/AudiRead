#!/usr/bin/env bash
# Installs AudiRead Sync for the current user: a private Python environment,
# an `audiread-sync` command, and an app-menu entry.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/audiread-sync"
VENV="$APP_DIR/venv"
BIN="$HOME/.local/bin"

mkdir -p "$APP_DIR" "$BIN"

# faster-whisper ships wheels for Python 3.9–3.13; use one of those.
if command -v uv >/dev/null 2>&1; then
  echo "Creating environment with uv (Python 3.12)…"
  uv venv --python 3.12 --allow-existing "$VENV"
  PIP=(uv pip install --python "$VENV/bin/python")
else
  PY=""
  for c in python3.12 python3.13 python3.11 python3.10 python3; do
    if command -v "$c" >/dev/null 2>&1; then PY="$c"; break; fi
  done
  [ -n "$PY" ] || { echo "Python 3 not found. On CachyOS/Arch: sudo pacman -S python"; exit 1; }
  echo "Creating environment with $PY…"
  "$PY" -m venv "$VENV"
  PIP=("$VENV/bin/python" -m pip install --upgrade)
fi

"${PIP[@]}" -r "$HERE/requirements.txt"
rm -rf "$APP_DIR/audiread_sync"
cp -r "$HERE/audiread_sync" "$APP_DIR/"

if ! "$VENV/bin/python" -c "import tkinter" 2>/dev/null; then
  echo
  echo "Note: the window needs Tk. On CachyOS/Arch run:  sudo pacman -S tk"
  echo "(The command line works without it.)"
fi

cat > "$BIN/audiread-sync" <<LAUNCH
#!/usr/bin/env bash
cd "$APP_DIR" && exec "$VENV/bin/python" -m audiread_sync "\$@"
LAUNCH
chmod +x "$BIN/audiread-sync"

mkdir -p "${XDG_DATA_HOME:-$HOME/.local/share}/applications"
cat > "${XDG_DATA_HOME:-$HOME/.local/share}/applications/audiread-sync.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=AudiRead Sync
Comment=Line up an audiobook with its ebook for AudiRead
Exec=$BIN/audiread-sync --gui
Icon=audio-headphones
Terminal=false
Categories=AudioVideo;Office;
DESK

echo
echo "Installed. Open \"AudiRead Sync\" from your app menu, or run: audiread-sync"
