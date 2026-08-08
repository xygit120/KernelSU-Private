# 私人 KernelSU 构建指南

本仓库已改造完成，实现两个目的：

| 目的 | 实现方式 | 修改位置 |
|---|---|---|
| **静默运行、去除日志** | 内核全部 `pr_*` 编译为 no-op；ksud logcat 输出级别 Off；manager 的 99 处 `Log.*` 调用由 R8 在 release 构建时彻底移除 | `kernel/include/klog.h`、`userspace/ksud/src/cli.rs`、`manager/app/proguard-rules.pro` |
| **包名 com.android.video + 内核只认你的 APK** | 全量重命名 `me.weishu.kernelsu → com.android.video`；内核签名常量改为你的证书（构建时自动写入）；强制包名校验；删除官方签名与 PR 第二签名分支 | 全仓库（见下文清单） |

---

## 一、改造内容清单

### 1. 包名重命名（manager / ksud 全量）
- `manager/app/src/main/java/me/weishu/kernelsu/` → `com/android/video/`（git mv，含 aidl）
- 所有 `package` / `import` / 全限定名 → `com.android.video.*`
- JNI 符号 `Java_me_weishu_kernelsu_*` → `Java_com_android_video_*`
- `FindClass("me/weishu/kernelsu/...")` → `com/android/video/...`
- `AndroidManifest.xml`：`zygotePreloadName`、`LAUNCH` action → `com.android.video.*`
- `userspace/ksud/build.rs`：`KSU_PACKAGE_NAME=com.android.video`（备份目录等随包名）
- `userspace/ksud/src/late_load.rs`：拉起 manager Activity 的 component 路径
- 更新检查/关于页 URL：`tiann/KernelSU` → 你的 `xygit120/KernelSU`（防止去官方仓库检查更新）
- 删除官方赞助卡片（HomeMaterial.kt / HomeMiuix.kt 的 DonateCard）与作者署名

### 2. 内核识别（只认你的 APK，官方彻底失效）
- `kernel/Kbuild`：
  - 官方签名 `0x033b / c371061b...` 删除 → 默认占位（`build_private.sh` 构建后自动写入你的证书值）
  - 强制 `KSU_MANAGER_PACKAGE = com.android.video`（包名校验恒开启）
  - 删除 `KSU_EXPECTED_SIZE2/HASH2`（官方 PR 构建签名支持）
- `kernel/manager/apk_sign.c`：`is_manager_apk()` 删除第二签名分支，仅校验唯一签名 + 包名

### 3. 日志静默
- 内核：`kernel/include/klog.h` 中 `pr_emerg/alert/crit/err/warn/notice/info/debug` 全部重定义为 `no_printk`（编译期移除，参数仍做类型检查）；`symbol_resolver.c` 补 include
- ksud：`cli.rs` 中 `android_logger` 级别 `Info → Off`（logcat 零输出）
- manager：`proguard-rules.pro` 添加 `-assumenosideeffects`，R8 移除全部 `Log.*` 调用（release 构建自动生效）

---

## 二、构建环境准备（一次性）

### Windows（推荐 WSL 或 Git Bash）
```bash
# 1. JDK 17+（已有 26 可跳过）
# 2. Python 3（已有）
# 3. Rust（大陆网络走代理或镜像）
export RUSTUP_DIST_SERVER=https://rsproxy.cn
export RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
rustup target add aarch64-linux-android

# 4. Android SDK cmdline-tools
#    下载 https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip
#    解压到 %LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest
sdkmanager --install "platform-tools" "build-tools;36.0.0" "platforms;android-36" "ndk;29.3.7700798"
```

### 编译 ksud 需要的 Rust target
`aarch64-linux-android`（arm64 手机）、`x86_64-linux-android`（模拟器/部分平板）

---

## 三、一键构建

```bash
cd KernelSU
PROXY=127.0.0.1:7890 ./build_private.sh        # 走代理（下载 gradle 依赖等）
```

脚本自动完成：
1. 生成专属密钥 `ksu-keystore.jks`（密码存 `.ksu-keystore.pass`）
2. 交叉编译静默版 ksud
3. `gradlew assembleRelease` 构建 `com.android.video` APK
4. repack：注入 ksud + zipalign 16KB + **纯 v2 重签名**
5. 计算证书 size/hash → 自动写回 `kernel/Kbuild`
6. 输出 `dist/KernelSU_Private_*.apk` 与内核编译命令

---

## 四、内核编译与刷入

在 **Android 内核源码树**（你的设备对应内核版本）中集成：

```bash
# 把本仓库 kernel/ 目录放入内核源码（或按 KernelSU 官方流程集成）
# KernelSU 已写入默认签名值，直接：
CONFIG_KSU=m CC=clang make 或经 DDK / 内核构建系统编译 kernelsu 模块/镜像
```

> 若想显式传参（不依赖 Kbuild 默认值）：
> `make CONFIG_KSU=m KSU_EXPECTED_SIZE=0x0xxx KSU_EXPECTED_HASH=<你的hash>`

流程：刷入编译好的内核 → 安装 `dist/KernelSU_Private_*.apk` → 打开 APK（或等 throne_tracker 自动识别）→ 完成。

---

## 五、验证清单

| 检查项 | 方法 |
|---|---|
| APK 包名 | `aapt dump badging <apk> \| grep package` → `com.android.video` |
| 纯 v2 签名 | `apksigner verify --verbose <apk>` 只有 `v2` 行 |
| 内核不识别官方 APK | 安装官方 KernelSU Manager 打开 → 应显示"未受支持/未检测到" |
| 内核识别你的 APK | 安装本 APK → 被授予 manager 权限 |
| 无日志 | `logcat -d \| grep -i kernelsu` 空；`dmesg \| grep -i kernelsu` 空 |

---

## 六、注意事项（务必阅读）

1. **密钥是命根子**：`ksu-keystore.jks` + `.ksu-keystore.pass` 一旦丢失，已刷入的内核将无法识别任何 APK（需重刷内核）。请离线备份。
2. **内核与 ksud 版本匹配**：ksud 与内核通过 supercall 协议通信，两者均从本仓库源码构建，天然匹配。若混用官方 ksud 二进制，静默效果会失效。
3. **纯 v2 签名是硬性要求**：内核 `check_v2_signature` 拒绝 v1/v3 签名（`apk_sign.c` 第 308-325 行逻辑）。构建脚本已用 `apksigner` 强制 v2-only，**不要手动重签名**。
4. **更新检查**：已指向你的 fork 的 GitHub releases；若你的 fork 无 release，更新检查静默失败，不会提示官方版本。
5. **改名建议（可选）**：`manager/app/build.gradle.kts` 中 `KSU_NAME`（应用显示名，默认 "KernelSU"）可按需修改，如 `-PKSU_NAME="Video"`。
6. **合规提示**：伪装系统包名（com.android.video）用于规避检测属于灰色地带，请确保用途合法合规；此改造不用于窃取或破坏任何系统服务。
