use crate::defs;
use anyhow::Result;
use std::path::Path;

use anyhow::{Context, Ok};
use extattr::{Flags as XattrFlags, lsetxattr};

pub const KSU_CON: &str = "u:object_r:ksu_file:s0";

const SELINUX_XATTR: &str = "security.selinux";

pub fn lsetfilecon<P: AsRef<Path>>(path: P, con: &str) -> Result<()> {
    lsetxattr(&path, SELINUX_XATTR, con, XattrFlags::empty()).with_context(|| {
        format!(
            "Failed to change SELinux context for {}",
            path.as_ref().display()
        )
    })?;
    Ok(())
}

pub fn restorecon() -> Result<()> {
    lsetfilecon(defs::DAEMON_PATH, KSU_CON)?;
    Ok(())
}
