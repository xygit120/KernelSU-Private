#[cfg(target_os = "android")]
mod android {
    use const_format::concatcp;

    pub const ADB_DIR: &str = "/data/adb/";
    pub const WORKING_DIR: &str = concatcp!(ADB_DIR, "ksu/");
    pub const BINARY_DIR: &str = concatcp!(WORKING_DIR, "bin/");
    pub const LIBRARY_DIR: &str = concatcp!(WORKING_DIR, "lib/");
    pub const LOG_DIR: &str = concatcp!(WORKING_DIR, "log/");
    pub const SULOGD_LOCK_PATH: &str = concatcp!(WORKING_DIR, "sulogd.lock");

    pub const PROFILE_DIR: &str = concatcp!(WORKING_DIR, "profile/");
    pub const PROFILE_SELINUX_DIR: &str = concatcp!(PROFILE_DIR, "selinux/");
    pub const PROFILE_TEMPLATE_DIR: &str = concatcp!(PROFILE_DIR, "templates/");

    pub const KSURC_PATH: &str = concatcp!(WORKING_DIR, ".ksurc");
    pub const DAEMON_PATH: &str = concatcp!(ADB_DIR, "ksud");
    pub const LIBADBROOT_PATH: &str = concatcp!(LIBRARY_DIR, "libadbroot.so");

    pub const DAEMON_LINK_PATH: &str = concatcp!(BINARY_DIR, "ksud");

    pub const KSU_BACKUP_DIR: &str = WORKING_DIR;
    pub const KSU_BACKUP_FILE_PREFIX: &str = "ksu_backup_";
    pub const BACKUP_FILENAME: &str = "stock_image.sha1";
    pub const KSU_TEMP_BACKUP_DIR_NAME: &str = "boot_backup";

    pub const DEFAULT_PACKAGE_NAME: &str = env!("KSU_PACKAGE_NAME");
}

#[allow(unused)]
pub const VERSION_CODE: &str = env!("VERSION_CODE");
pub const VERSION_NAME: &str = env!("VERSION_NAME");
#[cfg(target_os = "android")]
pub const FULL_VERSION: &str = const_format::formatcp!(
    "{VERSION_NAME} (uapi: {})",
    crate::ksu_uapi::KERNEL_SU_UAPI_VERSION
);

#[cfg(target_os = "android")]
pub use android::*;
