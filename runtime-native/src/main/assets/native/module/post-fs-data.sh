#!/system/bin/sh
set -eu

DS_DIR=/data/local/Droidspaces
LOG_DIR="${DS_DIR}/Logs"
LOG_FILE="${LOG_DIR}/anct-boot.log"
BIN="${DS_DIR}/bin/droidspaces"

mkdir -p "${LOG_DIR}"
exec >> "${LOG_FILE}" 2>&1

echo "[$(date '+%Y-%m-%d %H:%M:%S')] starting AnCT native daemon"
if [ ! -x "${BIN}" ]; then
    echo "Droidspaces binary is missing: ${BIN}"
    exit 1
fi
if [ -f "${DS_DIR}/.daemon_mode" ] && [ "$(cat "${DS_DIR}/.daemon_mode")" = "1" ]; then
    "${BIN}" daemon
fi

