#!/usr/bin/env bash
# ============================================================
# build_private.sh — 私人 KernelSU 一键构建脚本
#
# 产出：
#   1. 专属签名密钥 ksu-keystore.jks（首次自动生成，之后复用）
#   2. 静默版 ksud 守护进程（Rust 交叉编译，logcat 输出已关闭）
#   3. com.android.video 管理器 APK（release，纯 v2 签名）
#   4. 最终 APK：dist/KernelSU_Private_*.apk（已注入 ksud）
#   5. 内核签名常量：自动把证书 size/hash 写回 kernel/Kbuild
#      （此后编译内核即只认你的 APK，官方 APK 完全失效）
#
# 前置依赖：
#   JDK 17+（含 keytool）  Python 3  Android SDK（含 NDK r29） Rust
#   （缺什么脚本会明确提示）
#
# 用法：
#   PROXY=127.0.0.1:7890 ./build_private.sh        # 走代理构建
#   ./build_private.sh                              # 不走代理
#   KSU_ARCH="arm64-v8a x86_64" ./build_private.sh  # 多架构
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

# ---------- 常量 ----------
PACKAGE_NAME="com.android.video"
KEYSTORE_FILE="ksu-keystore.jks"
KEY_ALIAS="ksu"
PASS_FILE=".ksu-keystore.pass"
CERTS_DIR=".ksu-certs"
ARCHS=(${KSU_ARCH:-arm64-v8a})
OUT_NAME="KernelSU_Private"
PROXY="${PROXY:-}"

# ---------- 工具定位 ----------
PYTHON="$(command -v python || true)"
if [ -z "$PYTHON" ]; then PYTHON="$(command -v python3 || true)"; fi
JAVA_HOME_DIR="$(dirname "$(command -v java 2>/dev/null)" 2>/dev/null || echo /dev/null)"
# `which java` may point to a javapath shim without keytool; fall back to java.home
if [ ! -x "$JAVA_HOME_DIR/keytool" ]; then
    JH="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/.*java.home = //p' | head -1)"
    JH="${JH//\\//}"  # convert Windows backslashes for Git Bash
    [ -n "$JH" ] && JAVA_HOME_DIR="$JH/bin"
fi
KEYTOOL="${KEYTOOL:-$JAVA_HOME_DIR/keytool}"
CARGO="${CARGO:-$HOME/.cargo/bin/cargo}"
ANDROID_HOME="${ANDROID_HOME:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"

say()  { echo -e "\033[1;34m[KSU]\033[0m $*"; }
die()  { echo -e "\033[1;31m[KSU-ERR]\033[0m $*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# ---------- 1. 环境检查 ----------
say "== 环境检查 =="
have java       || die "缺少 JDK（需 17+）。请安装并加入 PATH。"
[ -x "$KEYTOOL" ] || die "keytool 未找到（应位于 JDK 的 bin 目录）。"
[ -n "$PYTHON" ]  || die "缺少 Python 3。请安装 https://www.python.org/downloads/ 并勾选 Add to PATH。"
[ -x "$CARGO" ]   || die "缺少 Rust。请执行: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh  （大陆网络可用 RUSTUP_DIST_SERVER=https://rsproxy.cn RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup 加速）"
[ -d "$ANDROID_HOME" ] || die "未找到 Android SDK（$ANDROID_HOME）。请先安装 SDK，或设置 ANDROID_HOME 环境变量。"
echo "    JDK:        $(java -version 2>&1 | head -1)"
echo "    Python:     $($PYTHON --version 2>&1)"
echo "    Rust:       $($CARGO --version 2>&1)"
echo "    Android SDK: $ANDROID_HOME"

# NDK 检测
NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [ -z "$NDK_ROOT" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_ROOT="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$NDK_ROOT" ] && [ -d "$NDK_ROOT" ] || die "未找到 Android NDK（需 r29+）。请执行: sdkmanager \"ndk;29.3.7700798\" 或设置 ANDROID_NDK_HOME。"
echo "    NDK:        $NDK_ROOT"

# ---------- 2. 生成/复用专属签名密钥 ----------
say "== 签名密钥 =="
if [ ! -f "$KEYSTORE_FILE" ]; then
    KEYSTORE_PASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
    echo "$KEYSTORE_PASS" > "$PASS_FILE"
    chmod 600 "$PASS_FILE"
    "$KEYTOOL" -genkeypair -keyalg RSA -keysize 2048 -validity 10950 \
        -alias "$KEY_ALIAS" -keystore "$KEYSTORE_FILE" \
        -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
        -dname "CN=KSU Private, OU=Private, O=Private, C=CN" \
        -storetype JKS >/dev/null 2>&1 \
        || die "keytool 生成密钥失败。"
    say "已生成专属密钥: $KEYSTORE_FILE（密码保存在 $PASS_FILE，请妥善保管）"
else
    KEYSTORE_PASS="$(cat "$PASS_FILE" 2>/dev/null || true)"
    [ -n "$KEYSTORE_PASS" ] || die "缺少密码文件 $PASS_FILE（密钥存在但密码丢失）。"
    say "复用已有密钥: $KEYSTORE_FILE"
fi
KEYSTORE_ABS="$(cd "$(dirname "$KEYSTORE_FILE")" && pwd)/$(basename "$KEYSTORE_FILE")"

# ---------- 3. 配置 cargo 交叉编译（NDK）----------
say "== 配置 Rust 交叉编译工具链 =="
export ANDROID_NDK_HOME="$NDK_ROOT"
"$PYTHON" scripts/setup_cargo_config.py --force --ndk-root "$NDK_ROOT" >/dev/null 2>&1 \
    || die "cargo 交叉编译配置失败。"

# ---------- 3.5 bindgen 需要 libclang（NDK 自带）----------
if [ -z "${LIBCLANG_PATH:-}" ]; then
    LCL="$(find "$NDK_ROOT" -iname "libclang.dll" 2>/dev/null | head -1)"
    if [ -z "$LCL" ] && [ -d "$ANDROID_HOME/ndk" ]; then
        LCL="$(find "$ANDROID_HOME/ndk" -iname "libclang.dll" 2>/dev/null | head -1)"
    fi
    [ -n "$LCL" ] && export LIBCLANG_PATH="$(dirname "$LCL")"
fi
[ -n "${LIBCLANG_PATH:-}" ] || die "未找到 libclang.dll（bindgen 需要）。请安装 NDK 或设置 LIBCLANG_PATH。"
say "    LIBCLANG_PATH=$LIBCLANG_PATH"

# ---------- 4. 编译静默版 ksud ----------
say "== 编译 ksud（静默版，无 logcat 输出）=="
RUST_TARGETS=()
for arch in "${ARCHS[@]}"; do
    case "$arch" in
        arm64-v8a) RUST_TARGETS+=("aarch64-linux-android");;
        x86_64)    RUST_TARGETS+=("x86_64-linux-android");;
        armeabi-v7a) RUST_TARGETS+=("armv7-linux-androideabi");;
        x86)       RUST_TARGETS+=("i686-linux-android");;
        *) die "不支持的架构: $arch";;
    esac
done
for t in "${RUST_TARGETS[@]}"; do
    say "    cargo build --release --target $t"
    "$CARGO" build --release --target "$t" || die "ksud 编译失败（target=$t）。"
done

# ---------- 5. 构建 manager APK（com.android.video）----------
say "== 构建 manager APK（$PACKAGE_NAME）=="
SDK_FWD="${ANDROID_HOME//\\//}"   # Windows paths with forward slashes for Gradle
export ANDROID_HOME="$SDK_FWD"
echo "sdk.dir=$SDK_FWD" > manager/local.properties   # also helps IDE builds
GRADLE_PROXY_ARGS=()
if [ -n "$PROXY" ]; then
    GRADLE_PROXY_ARGS+=("-Dhttp.proxyHost=${PROXY%:*}" "-Dhttp.proxyPort=${PROXY#*:}"
                        "-Dhttps.proxyHost=${PROXY%:*}" "-Dhttps.proxyPort=${PROXY#*:}")
fi
# 用 -P 属性覆盖签名参数（apksign 插件读取 KEYSTORE_FILE 等）
(
    cd manager
    JAVA_OPTS="${GRADLE_PROXY_ARGS[*]:-}" \
    ./gradlew.bat --no-daemon assembleRelease \
        -PKEYSTORE_FILE="$KEYSTORE_ABS" \
        -PKEYSTORE_PASSWORD="$KEYSTORE_PASS" \
        -PKEY_ALIAS="$KEY_ALIAS" \
        -PKEY_PASSWORD="$KEYSTORE_PASS" \
        || die "manager 构建失败（请检查 gradle 输出）。"
)
[ -n "$(ls manager/app/build/outputs/apk/release/*.apk 2>/dev/null || true)" ] \
    || die "未找到构建产物 APK。"

# ---------- 6. repack：注入 ksud + zipalign + 纯 v2 重签名 ----------
say "== repack（注入 ksud + 纯 v2 签名）=="
REPACK_ARGS=(repack -b release -t release -n "$OUT_NAME")
for arch in "${ARCHS[@]}"; do REPACK_ARGS+=(-a "$arch"); done
REPACK_ARGS+=(-K "$KEYSTORE_ABS" -A "$KEY_ALIAS" -P "$KEYSTORE_PASS" -S "$KEYSTORE_PASS")
if [ -n "$PROXY" ]; then
    export HTTP_PROXY="http://$PROXY" HTTPS_PROXY="http://$PROXY" ALL_PROXY="http://$PROXY"
fi
"$PYTHON" repack_apk.py "${REPACK_ARGS[@]}" || die "repack 失败。"

FINAL_APK="$(ls -t dist/${OUT_NAME}*.apk 2>/dev/null | head -1)"
[ -n "$FINAL_APK" ] || die "未找到最终 APK（dist/ 目录）。"

# ---------- 7. 计算证书 size/hash 并写回内核 ----------
say "== 更新内核签名识别（kernel/Kbuild）=="
mkdir -p "$CERTS_DIR"
"$KEYTOOL" -exportcert -alias "$KEY_ALIAS" -keystore "$KEYSTORE_FILE" \
    -storepass "$KEYSTORE_PASS" -file "$CERTS_DIR/cert.der" >/dev/null 2>&1 \
    || die "导出证书失败。"
EXPECTED_SIZE="$(stat -c%s "$CERTS_DIR/cert.der")"
EXPECTED_SIZE_HEX="$(printf '0x%04x' "$EXPECTED_SIZE")"
EXPECTED_HASH="$(sha256sum "$CERTS_DIR/cert.der" | awk '{print $1}')"
sed -i "s/^KSU_EXPECTED_SIZE := .*/KSU_EXPECTED_SIZE := $EXPECTED_SIZE_HEX/" kernel/Kbuild
sed -i "s/^KSU_EXPECTED_HASH := .*/KSU_EXPECTED_HASH := $EXPECTED_HASH/" kernel/Kbuild
echo "    EXPECTED_SIZE = $EXPECTED_SIZE_HEX  ($EXPECTED_SIZE bytes)"
echo "    EXPECTED_HASH = $EXPECTED_HASH"

# ---------- 8. 完成 ----------
echo ""
say "================================================================"
say " 构建完成！"
say "   最终 APK : $(pwd)/$FINAL_APK"
say "   包名     : $PACKAGE_NAME"
say "   签名证书 : $KEYSTORE_FILE（密钥永不过期，丢失后旧内核将无法识别新装 APK）"
say ""
say " 内核编译：在 Android 内核源码目录执行（或 DDK 环境）"
say "   CONFIG_KSU=m make KSU_EXPECTED_SIZE=$EXPECTED_SIZE_HEX KSU_EXPECTED_HASH=$EXPECTED_HASH"
say "   （上述值已写入 kernel/Kbuild 默认值，不传参也会生效）"
say "   内核编译完成后：安装 APK 到设备 -> 刷入内核 -> 打开 APK 即被识别"
say "================================================================"
