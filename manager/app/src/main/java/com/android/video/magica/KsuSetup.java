package com.android.video.magica;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 21479 前置准备 + 越狱后清理（OnePlus 11 / PHB110 专用）。
 *
 * 点「越狱」时：
 *   1) 跑 CVE-2025-21479（Adreno GPU 漏洞）把 SELinux 设为 Permissive，
 *      并在 exploit 自己的子进程里临时换成 init_cred + 清 seccomp，
 *      然后直接 exec libksud.so late-load —— 加载 KernelSU LKM。
 *      全程不给本 App 提权（子进程内部临时 root 只用于加载模块）。
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
    private static volatile boolean sStarted = false;

    private KsuSetup() {
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
                    logTo(app, "starting exploit...");
                    Process p = pb.start();
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        logTo(app, "[21479] " + line);
                    }
                    r.close();
                    int rc = p.waitFor();
                    logTo(app, "21479 + late-load finished rc=" + rc);
                } catch (Throwable t) {
                    logTo(app, "ksu setup failed: " + t);
                }
                cleanupGuardAsync(app);
            }
        }, "ksu-setup").start();
    }

    /**
     * 越狱成功后清理 oplus 安全模块：轮询等待 su 可用（KernelSU 起来），
     * 然后 `su -c` 卸载 guard/harden/keventupload/common（存在才卸）。
     */
    public static void cleanupGuardAsync(final Context app) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String cmd = "for m in oplus_security_guard oplus_secure_harden"
                        + " oplus_security_keventupload oplus_secure_common; do"
                        + " if grep -q \"^$m \" /proc/modules 2>/dev/null; then"
                        + " rmmod $m 2>&1; echo \"rmmod-$m-rc=$?\"; fi; done;"
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
