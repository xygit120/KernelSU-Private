#!/system/bin/sh
# Privileged entry: fixed payload/files, platform guards, backup before handoff.
set -eu
ACTION=${1:-invalid}
PAYLOAD=${2:-invalid}
BASE=/data/adb/vcam11-app
RUN=/data/local/tmp/vcam11-v37
WORKER=/data/local/tmp/vcam11-hidl-worker
# Compatibility list is only for backup/rollback of pre-three-file installs.
LIBS='libavcodec.so libavformat.so libavutil.so libswresample.so libswscale.so libc++_shared.so'
RUNTIME=/data/local/tmp/vcam11-runtime.zip
PROVIDER=android.hardware.camera.provider.ICameraProvider/vendor_qti/0
HOOK=/data/adb/service.d/vcam11.sh
changed=0
backup=
locked=0
sdk=

camera_count() {
    # AOSP ICameraService: Android 14 has one argument; Android 15 has three.
    # Android 16 takes an AttributionSourceState parcelable that `service call`
    # cannot build - every arity answers EX_NULL_POINTER (0xfffffffc) - so the
    # count comes from the service dump. sed reads the stream to EOF, so the
    # dump pipe is never closed early and QTI never takes SIGPIPE on it.
    case "$sdk" in
        34) timeout 3 service call media.camera 1 i32 1 > "$1" 2>/dev/null || return 1 ;;
        35) timeout 3 service call media.camera 1 i32 1 i32 0 i32 0 > "$1" 2>/dev/null || return 1 ;;
        36)
            dump_count=$(timeout 40 dumpsys media.camera 2>/dev/null |
                sed -n 's/^Number of camera devices: \([0-9][0-9]*\)$/\1/p')
            case "$dump_count" in ''|*[!0-9]*) return 1 ;; esac
            [ "$dump_count" -ge 1 ] && [ "$dump_count" -le 64 ] || return 1
            printf 'Number of camera devices: %s\n' "$dump_count" > "$1" 2>/dev/null
            printf '%s\n' "$dump_count"
            return 0 ;;
        *) return 1 ;;
    esac
    count_hex=$(sed -n 's/^Result: Parcel([[:space:]]*00000000 \([0-9a-fA-F]\{8\}\)[[:space:]].*/\1/p' "$1")
    case "$count_hex" in ''|*[!0-9a-fA-F]*) return 1 ;; esac
    count_value=$((0x$count_hex))
    [ "$count_value" -ge 1 ] && [ "$count_value" -le 64 ] || return 1
    printf '%s\n' "$count_value"
}
factory_count() {
    [ ! -L "$BASE/factory-camera-count" ] || return 1
    if [ -f "$BASE/factory-camera-count" ]; then saved_count=$(cat "$BASE/factory-camera-count")
    elif [ "$sdk" = 35 ]; then saved_count=6  # Exact pre-10008 deployment contract.
    else return 1; fi
    case "$saved_count" in ''|*[!0-9]*) return 1 ;; esac
    [ "$saved_count" -ge 1 ] && [ "$saved_count" -le 64 ] || return 1
    printf '%s\n' "$saved_count"
}

fail() { echo "ERROR: $*"; exit 1; }
atomic_copy() {
    # All destinations are fixed below; rename prevents modifying mapped ELF pages.
    [ ! -L "$2.vcam11-new" ] || return 1
    cp -p "$1" "$2.vcam11-new" || return 1
    chown 0:0 "$2.vcam11-new" || return 1
    chmod "$3" "$2.vcam11-new" || return 1
    mv -f "$2.vcam11-new" "$2" || return 1
}
stop_stack() {
    setprop ctl.stop vendor.camera-provider || return 1
    for p in $(pidof vcam11-v37 2>/dev/null || true); do kill -9 "$p" 2>/dev/null || true; done
    # The worker changes argv/comm to QTI. Resolve /proc/exe instead of pidof(worker).
    for p in $(pidof vendor.qti.camera.provider-service_64 2>/dev/null || true); do
        exe=$(readlink "/proc/$p/exe" 2>/dev/null || true)
        case "$exe" in
          /vendor/bin/hw/vendor.qti.camera.provider-service_64|/data/local/tmp/vcam11-hidl-worker|'/data/local/tmp/vcam11-hidl-worker (deleted)') kill -9 "$p" 2>/dev/null || true ;;
        esac
    done
    sleep 1
}
audit() {
    vpid=$(pidof vcam11-v37 2>/dev/null || true)
    [ -n "$vpid" ] || return 1
    qti=0; worker=0
    for p in $(pidof vendor.qti.camera.provider-service_64 2>/dev/null || true); do
        exe=$(readlink "/proc/$p/exe" 2>/dev/null || true)
        case "$exe" in /vendor/bin/hw/vendor.qti.camera.provider-service_64) qti=$p ;; "$WORKER") worker=$p ;; esac
    done
    [ "$qti" != 0 ] && [ "$worker" != 0 ] || return 1
    [ "$(getprop init.svc.vendor.camera-provider)" = running ] || return 1
    expected_mode=${AUDIT_MODE:-$(cat "$BASE/mode-current" 2>/dev/null || echo virtual)}
    # dumpsys may be unavailable when this script runs in the kernel domain
    # (21479 escalation). Treat the AIDL-owner and camera-count probes as
    # advisory in that case; the process/property checks above are decisive.
    owner=$(timeout 4 dumpsys --pid "$PROVIDER" 2>/dev/null | tr -d '\r\n ' || true)
    if [ -n "$owner" ]; then
        if [ "$expected_mode" = real ]; then expected_owner=$qti; else expected_owner=$vpid; fi
        [ "$owner" = "$expected_owner" ] || return 1
    else
        owner=unavailable
    fi
    expected_count=6
    if [ "$expected_mode" = real ]; then expected_count=$(factory_count) || return 1; fi
    observed_count=$(camera_count "$BASE/camera-audit.txt" 2>/dev/null || true)
    if [ -n "$observed_count" ]; then
        [ "$observed_count" -eq "$expected_count" ] || return 1
    else
        observed_count=unavailable
        echo "AUDIT_NOTE: camera-count probe unavailable (kernel domain); process checks passed"
    fi
    echo "AUDIT_OK mode=$expected_mode vcam=$vpid aidl_owner=$owner worker=$worker factory_qti=$qti cameras=$observed_count"
    sha256sum "$RUN" "$WORKER"
}
start_stack() {
    AUDIT_MODE=virtual
    case "$(getprop ro.product.manufacturer)" in OnePlus|OPPO)
        setprop oplus.autotest.camera.debug.forcelog 0
        setprop persist.vendor.camera.debugPreviewDecisionMode 0 ;;
    esac
    # am/cmd passes inherited stdio over Binder. adb_data_file stdout caused
    # FAILED_TRANSACTION=2147483646 and stale Gallery rows on PHB110.
    # Match deploy.sh; native diagnostics still go to logcat/session trace.
    setsid nohup "$RUN" > /dev/null 2>&1 < /dev/null 9>&- &
    sleep 18
    setprop ctl.start vendor.camera-provider
    sleep 6
    attempt=0
    # Existing provider recovery hatch: wait until QTI finished registration,
    # then retake without restarting cameraserver or killing the QTI auxiliaries.
    while [ "$attempt" -lt 12 ]; do
        if audit; then
            sleep 3
            if audit; then printf 'virtual\n' > "$BASE/mode-current"; return 0; fi
        fi
        touch /data/camera/retake_provider
        sleep 2
        attempt=$((attempt + 1))
    done
    return 1
}
restore_backup() {
    [ -n "$backup" ] && [ -d "$backup" ] || return 1
    stop_stack || return 1
    for name in vcam11-v37 vcam11-service vcam11-hidl-worker vcam11-hidl-worker.bin vcam11_boot.sh vcam11-runtime.zip; do
        mode=755; [ "$name" != vcam11-runtime.zip ] || mode=644
        if [ -f "$backup/$name" ]; then atomic_copy "$backup/$name" "/data/local/tmp/$name" "$mode" || return 1
        elif [ -f "$backup/$name.missing" ]; then rm -f "/data/local/tmp/$name"; fi
    done
    for name in $LIBS; do
        if [ -f "$backup/$name" ]; then atomic_copy "$backup/$name" "/data/local/tmp/fflibs/$name" 644 || return 1
        elif [ -f "$backup/$name.missing" ]; then rm -f "/data/local/tmp/fflibs/$name"; fi
    done
    if [ -f "$backup/boot-hook.sh" ]; then atomic_copy "$backup/boot-hook.sh" "$HOOK" 755 || return 1
    elif [ -f "$backup/no-boot-hook" ]; then rm -f "$HOOK"; fi
    if [ -f "$backup/mode-switch.sh" ]; then atomic_copy "$backup/mode-switch.sh" "$BASE/mode-switch.sh" 700 || return 1
    elif [ -f "$backup/mode-switch.sh.missing" ]; then rm -f "$BASE/mode-switch.sh"; fi
    if [ -f "$backup/factory-camera-count" ]; then atomic_copy "$backup/factory-camera-count" "$BASE/factory-camera-count" 600 || return 1
    elif [ -f "$backup/factory-camera-count.missing" ]; then rm -f "$BASE/factory-camera-count"; fi
    if [ -f "$backup/vcam11-v37" ]; then start_stack
    else
        setprop ctl.start vendor.camera-provider
        sleep 8
        killall cameraserver 2>/dev/null || true
        sleep 5
        [ "$(getprop init.svc.vendor.camera-provider)" = running ] || return 1
        echo 'FACTORY_RESTORED (fresh-install rollback)'
    fi
}
finish() {
    rc=$?
    trap - EXIT HUP INT TERM
    if [ "$changed" = 1 ] && [ "$rc" != 0 ]; then
        echo "DEPLOY_FAILED; automatic rollback: $backup"
        set +e
        if restore_backup; then echo 'ROLLBACK_OK'; else echo "ROLLBACK_FAILED; retained backup: $backup"; fi
    fi
    # Kernel flock is released on process death as well as normal exit. Camera
    # children close fd 9 so they cannot accidentally keep the deployment lock.
    if [ "$locked" = 1 ]; then flock -u 9 9>&9 2>/dev/null || true; fi
    exit "$rc"
}

[ "$(id -u)" = 0 ] || fail 'Root denied; run 21479 escalation first (app must be uid=0).'
echo "ROOT_ID=$(id)"
# Model strings are not a compatibility contract; retain SDK/ABI/provider checks.
sdk=$(getprop ro.build.version.sdk)
case "$sdk" in 34|35|36) ;; *) fail 'Unsupported Android version (requires SDK 34, 35 or 36).' ;; esac
[ "$(getprop ro.product.cpu.abi)" = arm64-v8a ] || fail 'Unsupported ABI.'
case "$PAYLOAD" in /data/user/0/com.android.video/files/vcam-payload|/data/data/com.android.video/files/vcam-payload) ;; *) fail 'Invalid private payload path.' ;; esac
case "$ACTION" in deploy|status|rollback) ;; *) fail 'Unknown operation.' ;; esac
[ ! -L "$BASE" ] || fail 'Managed directory must not be a symlink.'
mkdir -p "$BASE"
chmod 700 "$BASE"
[ ! -L "$BASE/operation.lock" ] || fail 'Lock file must not be a symlink.'
umask 077
exec 9>"$BASE/operation.lock"
# mksh marks exec-opened descriptors CLOEXEC; explicitly inherit only for flock.
flock -n 9 9>&9 || fail 'Another transaction is active (do not deploy concurrently).'
# An older installer used mkdir instead of flock. Do not remove a live legacy
# installer's lock during migration (or let a new transaction race it).
if [ -d "$BASE/lock" ]; then
    for proc in /proc/[0-9]*; do
        [ "${proc##*/}" = "$$" ] && continue
        [ -r "$proc/cmdline" ] || continue
        args=$(tr '\000' ' ' < "$proc/cmdline" 2>/dev/null || true)
        case "$args" in '/system/bin/sh '*root-manager.sh\ *|sh\ *root-manager.sh\ *) fail 'A legacy installer is active; wait for it to finish.' ;; esac
    done
    rmdir "$BASE/lock" 2>/dev/null || fail 'Legacy lock is nonempty; retained for inspection.'
fi
locked=1
trap finish EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

if [ "$ACTION" = status ]; then
    if [ -f "$BASE/mode-events.log" ]; then tail -n 16 "$BASE/mode-events.log"; fi
    audit || fail 'Runtime audit failed; deploy/recover from the app.'
    exit 0
fi
if [ "$ACTION" = rollback ]; then
    [ -f "$BASE/last-backup" ] || fail 'No previous deployment backup.'
    backup=$(cat "$BASE/last-backup")
    case "$backup" in "$BASE"/backup-*) ;; *) fail 'Invalid backup index.' ;; esac
    [ "$(dirname "$backup")" = "$BASE" ] || fail 'Backup must be a direct child.'
    restore_backup || fail "Rollback failed; preserved $backup"
    echo "ROLLBACK_OK $backup"
    exit 0
fi

echo 'Checking payload before changing the running camera...'
(cd "$PAYLOAD" && sha256sum -c SHA256SUMS) || fail 'Payload digest mismatch.'
for name in vcam11-service vcam11-hidl-worker vcam11-runtime.zip root-boot.sh mode-switch.sh; do
    [ -s "$PAYLOAD/$name" ] && [ ! -L "$PAYLOAD/$name" ] || fail "Missing/invalid payload: $name"
done
# Fresh installs can restore the factory service; upgrades restore exact saved files.
[ -f /vendor/bin/hw/vendor.qti.camera.provider-service_64 ] || fail 'Factory provider missing.'
[ ! -L "$BASE/factory-camera-count" ] && [ ! -L "$BASE/factory-camera-count.new" ] || fail 'Invalid factory camera baseline path.'
if [ -f "$BASE/factory-camera-count" ]; then
    baseline_count=$(factory_count) || fail 'Invalid saved factory camera baseline.'
elif [ "$sdk" = 35 ] && [ -f "$RUN" ]; then
    # Pre-10008 installs never wrote the file; that deployment contract is 6.
    baseline_count=$(factory_count) || fail 'Missing/invalid factory camera baseline; do not guess during upgrade.'
else
    # No saved baseline (fresh install, or an upgrade that predates the file on
    # a platform with no fixed contract): measure it, never guess it, and only
    # while the genuine factory provider still owns the AIDL name. When the
    # probe is unavailable (kernel domain under 21479), SDK 35 falls back to
    # the documented contract instead of guessing on other platforms.
    baseline_owner=$(timeout 4 dumpsys --pid "$PROVIDER" 2>/dev/null | tr -d '\r\n ' || true)
    if [ -z "$baseline_owner" ]; then
        [ "$sdk" = 35 ] || fail 'Factory AIDL owner unavailable.'
        baseline_count=6
        echo 'BASELINE_NOTE: owner probe unavailable (kernel domain); SDK 35 contract = 6'
    else
        case "$baseline_owner" in *[!0-9]*) fail 'Factory AIDL owner unavailable.' ;; esac
        [ "$(readlink "/proc/$baseline_owner/exe")" = /vendor/bin/hw/vendor.qti.camera.provider-service_64 ] || fail 'Current owner is not the factory provider; switch back to the real camera before deploying.'
        baseline_count=$(camera_count "$BASE/factory-camera-before.txt") || fail 'Cannot establish factory camera baseline.'
    fi
fi
echo "FACTORY_BASELINE sdk=$sdk cameras=$baseline_count"
backup="$BASE/backup-$(date +%Y%m%d-%H%M%S)-$$"
mkdir "$backup"
for name in vcam11-v37 vcam11-service vcam11-hidl-worker vcam11-hidl-worker.bin vcam11_boot.sh vcam11-runtime.zip; do
    if [ -f "/data/local/tmp/$name" ]; then cp -p "/data/local/tmp/$name" "$backup/$name"
    else touch "$backup/$name.missing"; fi
done
for name in $LIBS; do
    if [ -f "/data/local/tmp/fflibs/$name" ]; then cp -p "/data/local/tmp/fflibs/$name" "$backup/$name"
    else touch "$backup/$name.missing"; fi
done
if [ -f "$HOOK" ]; then cp -p "$HOOK" "$backup/boot-hook.sh"; else touch "$backup/no-boot-hook"; fi
if [ -f "$BASE/mode-switch.sh" ]; then cp -p "$BASE/mode-switch.sh" "$backup/mode-switch.sh"
else touch "$backup/mode-switch.sh.missing"; fi
if [ -f "$BASE/factory-camera-count" ]; then cp -p "$BASE/factory-camera-count" "$backup/factory-camera-count"
else touch "$backup/factory-camera-count.missing"; fi
printf '%s\n' "$backup" > "$BASE/last-backup"
echo "BACKUP_OK $backup"
changed=1
printf '%s\n' "$baseline_count" > "$BASE/factory-camera-count.new"
chmod 600 "$BASE/factory-camera-count.new"
mv -f "$BASE/factory-camera-count.new" "$BASE/factory-camera-count"
stop_stack
mkdir -p /data/local/tmp/fflibs /data/adb/service.d
atomic_copy "$PAYLOAD/vcam11-runtime.zip" "$RUNTIME" 644
atomic_copy "$PAYLOAD/vcam11-service" "$RUN" 755
atomic_copy "$PAYLOAD/vcam11-hidl-worker" "$WORKER" 755
atomic_copy "$PAYLOAD/root-boot.sh" /data/local/tmp/vcam11_boot.sh 755
atomic_copy "$PAYLOAD/root-boot.sh" "$HOOK" 755
atomic_copy "$PAYLOAD/mode-switch.sh" "$BASE/mode-switch.sh" 700
start_stack || fail 'New camera stack failed ownership/coexistence checks.'
# Prove that the actual provider uses the packed runtime, not legacy copies.
vpid=$(pidof vcam11-v37)
grep -q '/data/local/tmp/vcam11-runtime.zip$' "/proc/$vpid/maps" || fail 'Packed runtime is not mapped.'
if grep -q '/data/local/tmp/fflibs/' "/proc/$vpid/maps"; then fail 'Provider still depends on loose libraries.'; fi
# Retire only our exact old files, after successful startup and recoverable
# backup. Leave a library alone if another live process still maps that path.
for name in $LIBS; do
    path="/data/local/tmp/fflibs/$name"
    if [ -f "$path" ] && [ ! -L "$path" ] && [ -f "$backup/$name" ]; then
        old=$(sha256sum "$path"); saved=$(sha256sum "$backup/$name")
        [ "${old%% *}" = "${saved%% *}" ] || fail "Legacy library changed during deployment: $name"
        in_use=0
        for map in /proc/[0-9]*/maps; do
            if grep -Fq "$path" "$map" 2>/dev/null; then in_use=1; break; fi
        done
        if [ "$in_use" = 0 ]; then rm -f "$path"; else echo "LEGACY_RETAINED active consumer: $path"; fi
    fi
done
for name in vcam11-service vcam11-hidl-worker.bin; do
    if [ -f "$backup/$name" ] && [ ! -L "/data/local/tmp/$name" ]; then
        old=$(sha256sum "/data/local/tmp/$name"); saved=$(sha256sum "$backup/$name")
        [ "${old%% *}" = "${saved%% *}" ] || fail "Legacy staging file changed: $name"
        rm -f "/data/local/tmp/$name"
    fi
done
echo 'RUNTIME_FILES_OK count=3 (vcam11-v37, vcam11-hidl-worker, vcam11-runtime.zip); no extraction'
changed=0
echo "DEPLOY_OK backup=$backup"
