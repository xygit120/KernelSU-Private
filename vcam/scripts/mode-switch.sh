#!/system/bin/sh
# Fixed privileged handoff. No media paths or arbitrary shell commands accepted.
set -eu
MODE=${1:-invalid}
VPID=${2:-invalid}
TOKEN=${3:-invalid}
BASE=/data/adb/vcam11-app
PROVIDER=android.hardware.camera.provider.ICameraProvider/vendor_qti/0
FACTORY=/vendor/bin/hw/vendor.qti.camera.provider-service_64
journal=0
record() {
    echo "$*"
    if [ "$journal" = 1 ]; then printf '%s\n' "$*" >> "$BASE/mode-events.log"; fi
}
fail() { record "MODE_ERROR $TOKEN $*"; exit 1; }
[ "$(id -u)" = 0 ] || fail root
# Deployment owns the ABI/provider checks; do not reintroduce a model allowlist.
sdk=$(getprop ro.build.version.sdk)
case "$sdk" in 34|35|36) ;; *) fail sdk ;; esac
case "$MODE" in real|virtual) ;; *) fail mode ;; esac
case "$VPID" in ''|*[!0-9]*) fail pid ;; esac
case "$TOKEN" in "$VPID"-*) ;; *) fail token ;; esac
case "$TOKEN" in *[!0-9-]*) fail token ;; esac
[ "$(readlink /proc/$VPID/exe)" = /data/local/tmp/vcam11-v37 ] || fail stale_provider
[ ! -L "$BASE" ] && [ ! -L "$BASE/operation.lock" ] || fail symlink
umask 077
exec 9>"$BASE/operation.lock"
flock -n 9 9>&9 || { echo "MODE_BUSY $TOKEN"; exit 2; }
journal=1
expected_count=6
if [ "$MODE" = real ]; then
    [ ! -L "$BASE/factory-camera-count" ] || fail factory_baseline
    if [ -f "$BASE/factory-camera-count" ]; then expected_count=$(cat "$BASE/factory-camera-count")
    elif [ "$sdk" = 35 ]; then expected_count=6  # Pre-10008 install contract.
    else fail factory_baseline; fi
    case "$expected_count" in ''|*[!0-9]*) fail factory_baseline ;; esac
    [ "$expected_count" -ge 1 ] && [ "$expected_count" -le 64 ] || fail factory_baseline
fi
if [ -f "$BASE/mode-events.log" ] && [ "$(stat -c %s "$BASE/mode-events.log")" -gt 131072 ]; then
    mv -f "$BASE/mode-events.log" "$BASE/mode-events.previous.log"
fi
record "MODE_BEGIN token=$TOKEN mode=$MODE time=$(date +%s)"
echo "MODE_LOCKED $TOKEN"
read -r -t 8 answer || fail handshake_timeout
[ "$answer" = "READY $TOKEN" ] || fail handshake
factory_pid() {
    for p in $(pidof vendor.qti.camera.provider-service_64 2>/dev/null || true); do
        [ "$(readlink /proc/$p/exe 2>/dev/null || true)" != "$FACTORY" ] || { echo "$p"; return; }
    done
}
owner_pid() { timeout 3 dumpsys --pid "$PROVIDER" 2>/dev/null | tr -d '\r\n '; }
camera_count() {
    case "$sdk" in
        34) timeout 3 service call media.camera 1 i32 1 > "$BASE/mode-camera-audit.txt" 2>/dev/null || return 1 ;;
        35) timeout 3 service call media.camera 1 i32 1 i32 0 i32 0 > "$BASE/mode-camera-audit.txt" 2>/dev/null || return 1 ;;
        # Android 16 takes an AttributionSourceState parcelable that `service
        # call` cannot build (every arity answers EX_NULL_POINTER), so read the
        # count from the service dump. sed consumes the stream to EOF: the dump
        # pipe is never closed early, so QTI never takes SIGPIPE on it.
        36)
            dump_count=$(timeout 40 dumpsys media.camera 2>/dev/null |
                sed -n 's/^Number of camera devices: \([0-9][0-9]*\)$/\1/p')
            case "$dump_count" in ''|*[!0-9]*) return 1 ;; esac
            [ "$dump_count" -ge 1 ] && [ "$dump_count" -le 64 ] || return 1
            printf 'Number of camera devices: %s\n' "$dump_count" > "$BASE/mode-camera-audit.txt" 2>/dev/null
            printf '%s\n' "$dump_count"
            return 0 ;;
        *) return 1 ;;
    esac
    count_hex=$(sed -n 's/^Result: Parcel([[:space:]]*00000000 \([0-9a-fA-F]\{8\}\)[[:space:]].*/\1/p' "$BASE/mode-camera-audit.txt")
    case "$count_hex" in ''|*[!0-9a-fA-F]*) return 1 ;; esac
    count_value=$((0x$count_hex))
    [ "$count_value" -ge 1 ] && [ "$count_value" -le 64 ] || return 1
    printf '%s\n' "$count_value"
}
if [ "$MODE" = real ]; then
    # The native registration gate is now closed. QTI re-registers its genuine
    # AIDL/extension/HIDL services while vcam's control sockets stay alive.
    setprop ctl.restart vendor.camera-provider
    expected=
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
        expected=$(factory_pid)
        [ -z "$expected" ] || [ "$(owner_pid)" != "$expected" ] || break
        sleep 1
    done
    [ -n "$expected" ] && [ "$(owner_pid)" = "$expected" ] || fail factory_registration
else
    expected=$VPID
    [ -n "$(factory_pid)" ] || fail factory_not_alive
    [ "$(owner_pid)" = "$expected" ] || fail virtual_registration
fi
record "MODE_OWNER token=$TOKEN expected=$expected"
# CameraProviderManager retains the already-open binder. Changing the registry
# alone cannot switch a live session (confirmed by probe_pause_capture.ps1).
# The user explicitly authorized this short camera-session interruption.
killall cameraserver 2>/dev/null || true
sleep 3
ok=0
# Android 14's QTI provider can respawn and re-register the same AIDL name
# after cameraserver is bounced. The native provider watchdog already handles
# /data/camera/retake_provider as a manual retake hatch; drive that hatch here
# instead of assuming one registration is stable.
attempt=1
while [ "$attempt" -le 20 ]; do
    # Use the SDK-specific read-only ICameraService transaction shape.
    # Do not interrupt a full dump: QTI writes to its inherited dump pipe and
    # receives SIGPIPE when timeout closes that pipe (confirmed in init dmesg).
    if observed_count=$(camera_count) && [ "$observed_count" -eq "$expected_count" ] &&
       [ "$(owner_pid)" = "$expected" ]; then ok=1; break; fi
    if [ "$MODE" = virtual ]; then touch /data/camera/retake_provider 2>/dev/null || true; fi
    sleep 1
    attempt=$((attempt + 1))
done
[ "$ok" = 1 ] || fail camera_enumeration
sleep 1
[ "$(owner_pid)" = "$expected" ] || fail owner_changed
[ -n "$(factory_pid)" ] || fail factory_died
printf '%s\n' "$MODE" > "$BASE/mode-current.new"
mv -f "$BASE/mode-current.new" "$BASE/mode-current"
record "MODE_AUDIT mode=$MODE vcam=$VPID owner=$expected factory=$(factory_pid) cameras=$observed_count"
echo "MODE_OK $TOKEN"
