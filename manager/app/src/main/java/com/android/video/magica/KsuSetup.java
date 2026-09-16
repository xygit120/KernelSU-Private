package com.android.video.magica;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;

/**
 * 21479 前置准备 + 越狱后清理（OnePlus 11 / PHB110 专用）。
 *
 * 点「越狱」时：
 *   1) 优先通过 Shizuku 以 shell 身份跑 21479 exploit（CVE-2025-21479, Adreno GPU）：
 *      shell 上下文不受 App 域 lmkd/seccomp 限制（实测 App 域 6GB spray 会被杀），
 *      exploit 会设 SELinux Permissive 并直接 exec libksud.so late-load 加载 KernelSU。
 *      拿不到 Shizuku 时退回 App 域直跑（可能被 lmkd 杀，仅作兜底）。
 *   2) 越狱成功后（su 可用），用 su 卸载 oplus_security_guard 等安全模块
 *      （它们会把 heapspray 上报给用户态守护进程按 pid 追杀，必须清掉）。
 *
 * 注：本 ROM 上 kill zygote 软重启不可靠（zygote_secondary 监视者会触发整机
 * 重启、permissive 丢失），因此加载走 exploit -> ksud 直连，不重启框架。
 *
 * 日志：release 构建里 R8 会剥离 android.util.Log，所以同时写一份到
 * /sdcard/Android/data/com.android.video/files/ksu_setup.log（adb shell 可读）。
 */
public final class KsuSetup {
    private static final String TAG = "KernelSUMagica";
    private static final String LOG_NAME = "ksu_setup.log";
    private static final int SHIZUKU_REQ = 0x5A17;
    private static volatile boolean sStarted = false;
    private static volatile boolean sPermListenerAdded = false;

    private KsuSetup() {
    }

    /** Shizuku 服务是否在运行。 */
    public static boolean shizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shizuku 可用且已授权。 */
    public static boolean shizukuUsable() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 在主线程调用：弹出 Shizuku 授权请求；授权成功后自动继续越狱流程。 */
    public static void requestShizukuPermission(final Context context) {
        final Context app = context.getApplicationContext();
        try {
            if (!sPermListenerAdded) {
                sPermListenerAdded = true;
                Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                    @Override
                    public void onRequestPermissionResult(int requestCode, int grantResult) {
                        logTo(app, "shizuku permission result: rc=" + requestCode + " grant=" + grantResult);
                        if (requestCode == SHIZUKU_REQ && grantResult == PackageManager.PERMISSION_GRANTED) {
                            sStarted = false;
                            ensurePermissiveAsync(app);
                        }
                    }
                });
            }
            logTo(app, "requesting shizuku permission...");
            Shizuku.requestPermission(SHIZUKU_REQ);
        } catch (Throwable t) {
            logTo(app, "requestShizukuPermission failed: " + t);
        }
    }

    /** 由 HomeScreen / BootCompletedReceiver 调用；不阻塞调用线程。 */
    public static void ensurePermissiveAsync(Context context) {
        if (sStarted) {
            return;
        }
        sStarted = true;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    logTo(app, "== ensurePermissiveAsync start, uid=" + android.os.Process.myUid());
                    if (isKernelSuWorking()) {
                        logTo(app, "KernelSU already working, skip 21479");
                        cleanupGuardAsync(app);
                        return;
                    }
                    if (shizukuUsable()) {
                        runViaShizuku(app);
                    } else if (shizukuAvailable()) {
                        logTo(app, "shizuku available but not granted; requesting on main thread");
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(new Runnable() {
                                    @Override
                                    public void run() {
                                        requestShizukuPermission(app);
                                    }
                                });
                        return;
                    } else {
                        logTo(app, "shizuku not available, fallback to direct app-context run");
                        runDirect(app);
                    }
                } catch (Throwable t) {
                    logTo(app, "ksu setup failed: " + t);
                }
                cleanupGuardAsync(app);
            }
        }, "ksu-setup").start();
    }

    /** Shizuku 路径：以 shell(uid 2000) 身份跑 exploit -> ksud late-load。 */
    private static void runViaShizuku(Context app) throws Exception {
        File bin = new File(app.getApplicationInfo().nativeLibraryDir, "libcheese_ksu.so");
        File ksud = new File(app.getApplicationInfo().nativeLibraryDir, "libksud.so");
        logTo(app, "shizuku bin=" + bin + " exists=" + bin.isFile()
                + " ksud=" + ksud + " exists=" + ksud.isFile());
        if (!bin.isFile() || !ksud.isFile()) {
            logTo(app, "missing binaries, abort");
            return;
        }
        String[] cmd = {bin.getAbsolutePath()};
        String[] env = {
                "CHEESE_KSUD=" + ksud.getAbsolutePath(),
                "PATH=/system/bin:/system/xbin",
        };
        logTo(app, "shizuku: running exploit as shell...");
        IShizukuService service = IShizukuService.Stub.asInterface(
                new ShizukuBinderWrapper(Shizuku.getBinder()));
        final IRemoteProcess p = service.newProcess(cmd, env, "/data/local/tmp");
        final Context appCtx = app;
        Thread errThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader er = new BufferedReader(new InputStreamReader(
                            new ParcelFileDescriptor.AutoCloseInputStream(p.getErrorStream())));
                    String l;
                    while ((l = er.readLine()) != null) {
                        logTo(appCtx, "[21479e] " + l);
                    }
                    er.close();
                } catch (Throwable t) {
                    logTo(appCtx, "[21479e] reader ended: " + t);
                }
            }
        }, "ksu-exploit-stderr");
        errThread.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new ParcelFileDescriptor.AutoCloseInputStream(p.getInputStream())));
        String line;
        while ((line = r.readLine()) != null) {
            logTo(app, "[21479] " + line);
        }
        r.close();
        int rc = p.waitFor();
        errThread.join(2000);
        logTo(app, "shizuku exploit + late-load finished rc=" + rc);
    }

    /** 兜底：App 域直跑（实测 6GB spray 阶段可能被 lmkd/安全模块杀）。 */
    private static void runDirect(Context app) throws Exception {
        File bin = new File(app.getApplicationInfo().nativeLibraryDir, "libcheese_ksu.so");
        File ksud = new File(app.getApplicationInfo().nativeLibraryDir, "libksud.so");
        logTo(app, "bin=" + bin + " exists=" + bin.isFile() + " canExec=" + bin.canExecute());
        logTo(app, "ksud=" + ksud + " exists=" + ksud.isFile() + " canExec=" + ksud.canExecute());
        if (!bin.isFile() || !ksud.isFile()) {
            logTo(app, "missing binaries, abort");
            return;
        }
        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath());
        pb.environment().put("CHEESE_KSUD", ksud.getAbsolutePath());
        pb.redirectErrorStream(true);
        logTo(app, "starting exploit (direct)...");
        Process p = pb.start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        while ((line = r.readLine()) != null) {
            logTo(app, "[21479] " + line);
        }
        r.close();
        int rc = p.waitFor();
        logTo(app, "21479 + late-load finished rc=" + rc);
    }

    /**
     * App 启动时调用：如果 KernelSU 已经在工作（late-load 重启 manager 之后），
     * 补做 guard 模块清理 —— late-load 会 force-stop 本进程，按钮流程里的清理
     * 线程会被杀掉，所以在重启后补一次。
     */
    public static void cleanupIfWorkingAsync(final Context context) {
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (isKernelSuWorking()) {
                    logTo(app, "cleanupIfWorking: KernelSU working, cleaning guard modules");
                    cleanupGuardAsync(app);
                }
            }
        }, "ksu-cleanup-check").start();
    }

    /**
     * 越狱成功后清理 oplus 安全模块：轮询等待 su 可用（KernelSU 起来），
     * 然后 `su -c` 卸载 guard/harden/keventupload/common（存在才卸），
     * 最后恢复 SELinux permissive（模块守护会把它改回 enforcing）。
     */
    public static void cleanupGuardAsync(final Context app) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String cmd = "for m in oplus_security_guard oplus_secure_harden"
                        + " oplus_security_keventupload oplus_secure_common; do"
                        + " if grep -q \"^$m \" /proc/modules 2>/dev/null; then"
                        + " rmmod $m 2>&1; echo \"rmmod-$m-rc=$?\"; fi; done;"
                        + " setenforce 0 2>&1; echo \"setenforce-rc=$?\";"
                        + " grep -c \"^oplus_security_guard \" /proc/modules 2>/dev/null || echo 0";
                for (int i = 0; i < 90; i++) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    try {
                        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                        StringBuilder sb = new StringBuilder();
                        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) {
                            sb.append(line).append('\n');
                        }
                        r.close();
                        int rc = p.waitFor();
                        if (rc == 0) {
                            String out = sb.toString();
                            logTo(app, "guard cleanup: " + out.replace('\n', ' '));
                            if (out.contains("rmmod-") || out.contains("grep") || out.trim().endsWith("0")) {
                                // su 已可用：模块已卸或本就不在
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        // su 还没起来，继续等
                    }
                }
                logTo(app, "guard cleanup: su not available after timeout");
            }
        }, "ksu-guard-cleanup").start();
    }

    /** KernelSU 是否已工作（su 能拿到 uid 0）。 */
    private static boolean isKernelSuWorking() {
        BufferedReader r = null;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id -u"});
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s = r.readLine();
            int rc = p.waitFor();
            return rc == 0 && s != null && s.trim().equals("0");
        } catch (Throwable t) {
            return false;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void logTo(Context app, String msg) {
        Log.i(TAG, msg);
        FileOutputStream fos = null;
        try {
            File dir = app.getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            fos = new FileOutputStream(new File(dir, LOG_NAME), true);
            fos.write((ts + " " + msg + "\n").getBytes());
        } catch (Throwable ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
