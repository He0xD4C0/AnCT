#!/system/bin/sh
set -u

DS_DIR=/data/local/Droidspaces
LOG_DIR="${DS_DIR}/Logs"
LOG_FILE="${LOG_DIR}/anct-boot.log"
BIN="${DS_DIR}/bin/droidspaces"

mkdir -p "${LOG_DIR}"
exec >> "${LOG_FILE}" 2>&1

while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    sleep 1
done

echo "[$(date '+%Y-%m-%d %H:%M:%S')] scanning managed boot containers"
BB="${DS_DIR}/bin/busybox"
TABLE="${DS_DIR}/.anct-autoboot"
: > "${TABLE}"
for cfg in "${DS_DIR}"/Containers/*/container.config; do
    [ -f "${cfg}" ] || continue
    grep -q '^anct_managed=1$' "${cfg}" || continue
    grep -q '^run_at_boot=1$' "${cfg}" || continue
    priority=$(grep '^run_at_boot_priority=' "${cfg}" | head -1 | sed 's/^[^=]*=//')
    case "${priority}" in ''|*[!0-9]*) priority=99999999 ;; esac
    printf '%s\t%s\n' "${priority}" "${cfg}" >> "${TABLE}"
done
if [ ! -s "${TABLE}" ]; then
    echo "No managed boot containers"
    rm -f "${TABLE}"
    exit 0
fi
sleep 8
"${BB}" sort -n -k1,1 "${TABLE}" | cut -f2- | while IFS= read -r cfg; do
    echo "Starting ${cfg}"
    "${BIN}" --config "${cfg}" start || true
done
rm -f "${TABLE}"

