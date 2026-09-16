# VCAM integration

VCAM (virtual camera HAL) is bundled into this repository and deployed by the
KernelSU manager. No separate VCAM build or install step is required: the runtime
payload ships inside the manager APK (`manager/app/src/main/assets/vcam/`) and is
extracted into the manager's private dir, then deployed through `root-manager.sh`
via KernelSU `su`.

## Layout

```
vcam/
├── runtime/
│   ├── vcam11-service        # provider/worker payload (deployed to /data/local/tmp/vcam11-v37)
│   ├── vcam11-hidl-worker    # QTI HIDL worker
│   └── vcam11-runtime.zip    # FFmpeg runtime libs
├── scripts/
│   ├── root-manager.sh       # privileged entry: deploy | status | rollback
│   ├── root-boot.sh          # boot hook (installed to /data/adb/service.d/vcam11.sh)
│   └── mode-switch.sh        # real/virtual mode handoff (used by the controller IPC)
└── SHA256SUMS                # flat manifest verified by root-manager.sh
```

The same files are packaged as APK assets under `assets/vcam/` with the same
relative paths.

## Deploy contract

`root-manager.sh <action> <payload>`:

- `<action>`: `deploy`, `status` or `rollback`
- `<payload>`: the manager's private payload dir
  (`/data/user/0/com.android.video/files/vcam-payload`)

The script verifies `SHA256SUMS` inside the payload dir and installs:

| source                       | destination                                  |
|------------------------------|----------------------------------------------|
| `vcam11-service`             | `/data/local/tmp/vcam11-v37` (755)           |
| `vcam11-hidl-worker`         | `/data/local/tmp/vcam11-hidl-worker` (755)   |
| `vcam11-runtime.zip`         | `/data/local/tmp/vcam11-runtime.zip` (644)   |
| `root-boot.sh`               | `/data/adb/service.d/vcam11.sh` (755)        |
| `mode-switch.sh`             | `/data/adb/vcam11-app/mode-switch.sh` (700)  |

State and logs live in `/data/adb/vcam11-app/`.

## Manager integration

- `com.android.video.vcam.VcamManager` extracts the assets and drives the script
  through `su`.
- The manager home pager gains a `VCAM` page (deploy/update, status, rollback and
  the raw script output).
- The KernelSU boot hook (`/data/adb/service.d/vcam11.sh`) keeps the provider
  running across reboots; the manager page can redeploy or roll back at any time.

## Notes

- The payload paths inside `root-manager.sh` are pinned to the manager package
  (`com.android.video`) instead of the original controller app; keep this in sync
  if the package name changes.
- Source selection (image/video) and the real/virtual mode handoff are still owned
  by the VCAM controller app IPC; the manager only covers the privileged
  lifecycle (deploy/status/rollback).
