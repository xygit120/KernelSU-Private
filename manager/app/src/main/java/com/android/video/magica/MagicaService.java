package com.android.video.magica;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * 越狱按钮启动的隔离服务（isolatedProcess + useAppZygote）。
 *
 * onCreate 里向 BootCompletedReceiver 发一条 LAUNCH 广播：让 21479 前置准备
 * （SELinux Permissive + 软重启框架）跑在 App 主进程里 —— 主进程是前台优先级，
 * 不会被 6GB 喷淋引发的 lmkd 回收，且是 untrusted_app 域（有 GPU 设备权限）。
 * 越狱成功后再由 KsuSetup 用 su 清理 oplus 安全模块。
 */
public class MagicaService extends Service {

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Intent intent = new Intent(this, BootCompletedReceiver.class);
            intent.setAction("com.android.video.magica.LAUNCH");
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new Binder();
    }
}
