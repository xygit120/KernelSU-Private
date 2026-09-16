#!/system/bin/sh
# KernelSU boot hook; explicit QTI start and retake use the tested root-shell path.
BASE=/data/adb/vcam11-app
RUN=/data/local/tmp/vcam11-v37
sdk=$(getprop ro.build.version.sdk)
case "$sdk" in 34|35|36) ;; *) exit 1 ;; esac
[ -x "$RUN" ] && [ -x /data/local/tmp/vcam11-hidl-worker ] || exit 1
[ -s /data/local/tmp/vcam11-runtime.zip ] || exit 1
mkdir -p "$BASE"
chmod 700 "$BASE"
[ ! -L "$BASE/operation.lock" ] || exit 1
umask 077
exec 9>"$BASE/operation.lock"
flock -n 9 9>&9 || exit 1
exec >> "$BASE/boot.log" 2>&1
echo "vcam11 boot $(date)"
case "$(getprop ro.product.manufacturer)" in OnePlus|OPPO)
    setprop oplus.autotest.camera.debug.forcelog 0
    setprop persist.vendor.camera.debugPreviewDecisionMode 0 ;;
esac
setprop ctl.stop vendor.camera-provider
sleep 3
for p in $(pidof vcam11-v37 vendor.qti.camera.provider-service_64 2>/dev/null); do
    exe=$(readlink "/proc/$p/exe" 2>/dev/null)
    case "$exe" in /data/local/tmp/vcam11-v37|/data/local/tmp/vcam11-hidl-worker|/vendor/bin/hw/vendor.qti.camera.provider-service_64) kill -9 "$p" 2>/dev/null ;; esac
done
# Child am/cmd needs Binder-transferable stdio (see test_deploy_stdio.ps1).
setsid nohup "$RUN" > /dev/null 2>&1 < /dev/null 9>&- &
sleep 18
setprop ctl.start vendor.camera-provider
sleep 8
# Avoid cameraserver bounce after the factory process registers its HIDL services.
for attempt in 1 2 3 4 5; do
    touch /data/camera/retake_provider
    sleep 3
    owner=$(timeout 4 dumpsys --pid android.hardware.camera.provider.ICameraProvider/vendor_qti/0)
    [ "$owner" = "$(pidof vcam11-v37)" ] && break
done
case "$sdk" in
    34) timeout 3 service call media.camera 1 i32 1 ;;
    35) timeout 3 service call media.camera 1 i32 1 i32 0 i32 0 ;;
    # Android 16 hid getNumberOfCameras behind an AttributionSourceState
    # parcelable; every `service call` arity answers EX_NULL_POINTER. Read the
    # count out of the service dump instead. sed consumes the whole stream, so
    # the dump pipe is never closed early and QTI never takes SIGPIPE.
    36) timeout 40 dumpsys media.camera 2>/dev/null |
        sed -n 's/^Number of camera devices: \([0-9][0-9]*\)$/cameras=\1/p' ;;
esac
timeout 4 dumpsys --pid android.hardware.camera.provider.ICameraProvider/vendor_qti/0
# Start every boot in real-camera standby; selecting media alone must not
# replace another app's camera until the user presses Start.
flock -u 9 9>&9
exec 9>&-
printf 'CMD_DISABLE\n' | timeout 4 nc 127.0.0.1 34927
