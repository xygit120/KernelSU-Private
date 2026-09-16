use anyhow::{Context, Result};
use log::{info, warn};
use rustix::cstr;
use std::process::Command;

use crate::{assets, restorecon, utils};

fn dump_process_info(label: &str) {
    use rustix::process::{getgid, getgroups, getpid, getuid};

    let pid = getpid().as_raw_nonzero();
    let uid = getuid().as_raw();
    let gid = getgid().as_raw();
    let groups: Vec<String> = getgroups()
        .unwrap_or_default()
        .iter()
        .map(|g| g.as_raw().to_string())
        .collect();
    let selinux = std::fs::read_to_string("/proc/self/attr/current")
        .unwrap_or_else(|_| "unknown".to_string());
    let seccomp = std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.starts_with("Seccomp:"))
                .map(|l| l.trim().to_string())
        })
        .unwrap_or_else(|| "unknown".to_string());

    info!(
        "[{label}] pid={pid}, uid={uid}, gid={gid}, groups=[{}], selinux={}, {seccomp}",
        groups.join(","),
        selinux.trim(),
    );
}

pub fn run(package_name: &String, kmi: Option<String>, allow_shell: bool) -> Result<()> {
    utils::daemonize(false)?;
    info!("late-load command triggered!");
    dump_process_info("late-load start");

    // 1. Check if KernelSU is already loaded
    if ksuinit::has_kernelsu() {
        info!("KernelSU already loaded, skip loading ko");
    } else {
        // 2. Detect current KMI version
        let kmi = kmi.map_or_else(
            || crate::boot_patch::get_current_kmi().context("Failed to detect current KMI version"),
            Ok,
        )?;
        info!("Detected KMI: {kmi}");

        // 3. Get kernelsu.ko from embedded assets
        let ko_name = format!("{kmi}_kernelsu.ko");
        let ko_data = assets::get_asset_data(&ko_name)
            .with_context(|| format!("Failed to get {ko_name} from assets"))?;

        // 4. Load kernelsu.ko from memory with manual relocation
        info!("Loading kernelsu.ko for KMI {kmi}...");
        let params = if allow_shell {
            cstr!("allow_shell=1")
        } else {
            cstr!("")
        };
        ksuinit::load_module(&ko_data, params).context("Failed to load kernelsu.ko")?;
        info!("kernelsu.ko loaded successfully!");
        dump_process_info("after load_module");
    }

    // We need to reset stdin/stdout/stderr; otherwise, sending file descriptors via cmd transactions
    // will be blocked by SELinux because its fsec->sid is still u:r:su:s0 instead of u:r:ksu:s0.
    utils::reset_std()?;

    utils::umask(0);

    utils::install(None, None).context("Failed to install ksud")?;

    if let Err(e) = restorecon::restorecon() {
        warn!("restorecon failed: {e}");
    }

    if let Err(e) = crate::profile::apply_sepolies() {
        warn!("apply root profile sepolicy failed: {e}");
    }

    // 5. Initialize features
    if let Err(e) = crate::feature::init_features() {
        warn!("init features failed: {e}");
    }

    // 6. Restart Manager so it gets a fresh ksu fd from the newly loaded kernel module
    info!("Restarting KernelSU Manager {package_name}...");
    let _ = Command::new("am")
        .args(["force-stop", package_name])
        .status();
    let _ = Command::new("am")
        .args([
            "start",
            "-n",
            &format!("{package_name}/com.android.video.ui.MainActivity"),
        ])
        .status();

    Ok(())
}
