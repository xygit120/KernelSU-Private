package com.android.video.magica;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 21479 前置准备 + 越狱后清理（OnePlus 11 / PHB110 专用）。
 *
 * 点「越狱」时按顺序做两件事：
 *   1) 用 CVE-2025-21479（Adreno GPU 漏洞）把 SELinux 设为 Permissive，
 *      并软重启框架（kill zygote）——让 app zygote 重新初始化、不再安装 setuid
 *      seccomp 过滤器，这是 Magica（隔离进程 setresuid(0)）能成功的前提；
 *      全程不给本 App 提权（exploit 子进程内部临时 root 只用于重启框架）。
 *   2) 越狱成功后（KernelSU 已工作），用 su 卸载 oplus_security_guard 等安全模块
 *      （它们会把 heapspray 上报给用户态守护进程按 pid 追杀，必须清掉）。
 *
 * 重启状态用私有文件标记（zygote pid 签名 + 60s 时效），幂等可重试。
 */
public final class KsuSetup {
    private static final String TAG = "KernelSUMagica";
    private static final String MARKER = "ksu_restart_done";
    private static volatile boolean sStarted = false;

    private KsuSetup() {
    }

    /** 由 MagicaService.onCreate / BootCompletedReceiver 调用；不阻塞调用线程。 */
    public static void ensurePermissiveAsync(Context context) {
        if (sStarted) {
            return;
        }
        sStarted = true;
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File marker = new File(app.getFilesDir(), MARKER);
                try {
                    String sig = zygoteSignature();
                    String prev = readMarker(marker);
                    boolean fresh = marker.isFile()
                            && System.currentTimeMillis() - marker.lastModified() < 60 * 1000L;
                    boolean restarted = sig != null && prev != null && !prev.equals(sig);
                    if (isPermissive() && restarted && fresh) {
                        Log.i(TAG, "permissive + framework restarted (" + prev + " -> " + sig + "), skip 21479");
                        cleanupGuardAsync();
                        return;
                    }
                    File bin = new File(app.getApplicationInfo().nativeLibraryDir, "libcheese_ksu.so");
                    if (!bin.isFile()) {
                        Log.e(TAG, "exploit binary missing: " + bin);
                        return;
                    }
                    try (FileOutputStream fos = new FileOutputStream(marker)) {
                        fos.write(("v2:" + (sig == null ? "unknown" : sig) + "\n").getBytes());
                    }
                    Log.i(TAG, "running 21479: permissive + framework restart (no app escalation)");
                    ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath());
                    pb.environment().put("CHEESE_CRED", "1");
                    pb.environment().put("CHEESE_NO_PARENT_ROOT", "1");
                    pb.environment().put("CHEESE_SKIP_RMMOD", "1");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        Log.i(TAG, "[21479] " + line);
                    }
                    r.close();
                    int rc = p.waitFor();
                    Log.i(TAG, "21479 finished rc=" + rc + " (framework restart did not happen, will retry)");
                } catch (Throwable t) {
                    Log.e(TAG, "ksu setup failed", t);
                }
            }
        }, "ksu-setup").start();
    }

    /**
     * 越狱成功后清理 oplus 安全模块：轮询等待 su 可用（KernelSU 起来），
     * 然后 `su -c` 卸载 guard/harden/keventupload/common（存在才卸）。
     */
    public static void cleanupGuardAsync() {
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
                            Log.i(TAG, "guard cleanup: " + out.replace('\n', ' '));
                            if (out.contains("rmmod-") || out.contains("grep") || out.trim().endsWith("0")) {
                                // su 已可用：模块已卸或本就不在
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        // su 还没起来，继续等
                    }
                }
                Log.w(TAG, "guard cleanup: su not available after timeout");
            }
        }, "ksu-guard-cleanup").start();
    }

    /**
     * 框架重启签名。优先用 init 暴露的 init.svc_debug_pid.zygote；拿不到时退回本进程 pid
     * —— 框架重启必然换掉本 App 进程，pid 会变。
     */
    private static String zygoteSignature() {
        String prop = getProp("init.svc_debug_pid.zygote");
        if (prop != null && !prop.isEmpty()) {
            return "zygote:" + prop;
        }
        return "app:" + android.os.Process.myPid();
    }

    private static String getProp(String name) {
        BufferedReader r = null;
        try {
            Process p = Runtime.getRuntime().exec("getprop " + name);
            r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s = r.readLine();
            p.waitFor();
            return s == null ? null : s.trim();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static String readMarker(File marker) {
        String s = readFile(marker);
        if (s == null) {
            return null;
        }
        s = s.trim();
        if (!s.startsWith("v2:")) {
            return null;
        }
        s = s.substring(3);
        return s.isEmpty() || s.equals("unknown") ? null : s;
    }

    private static String readFile(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader(f));
            return r.readLine();
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean isPermissive() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/sys/fs/selinux/enforce"));
            String s = r.readLine();
            return s != null && s.trim().equals("0");
        } catch (Throwable t) {
            try {
                Process p = Runtime.getRuntime().exec("getenforce");
                BufferedReader r2 = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String s = r2.readLine();
                r2.close();
                return s != null && s.trim().equalsIgnoreCase("Permissive");
            } catch (Throwable t2) {
                return false;
            }
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
