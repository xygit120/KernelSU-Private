package com.android.video.vcam

import android.content.Context
import android.util.Log
import java.io.File

/**
 * VCAM (virtual camera HAL) integration.
 *
 * The runtime payload ships inside the manager APK (assets/vcam) and is extracted
 * into the app private dir, then deployed through root-manager.sh via KernelSU su.
 * The scripts keep their original fixed runtime paths (/data/local/tmp/...), so the
 * payload dir only needs to be the manager's private path expected by the script.
 */
object VcamManager {
    private const val TAG = "VcamManager"
    private const val PAYLOAD_DIR_NAME = "vcam-payload"
    private const val APP_DIR = "/data/adb/vcam11-app"

    private val ASSET_FILES = listOf(
        "runtime/vcam11-service",
        "runtime/vcam11-hidl-worker",
        "runtime/vcam11-runtime.zip",
        "scripts/root-manager.sh",
        "scripts/root-boot.sh",
        "scripts/mode-switch.sh",
        "SHA256SUMS",
    )

    fun payloadDir(context: Context): File = File(context.filesDir, PAYLOAD_DIR_NAME)

    /** True when the VCAM app dir exists (a deployment happened at least once). */
    fun isDeployed(): Boolean = File(APP_DIR).isDirectory

    /** Extract the bundled payload into the app private dir (flat layout expected by root-manager.sh). */
    fun ensurePayload(context: Context): Boolean {
        val dir = payloadDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.e(TAG, "cannot create payload dir $dir")
            return false
        }
        for (asset in ASSET_FILES) {
            val name = asset.substringAfterLast('/')
            val out = File(dir, name)
            try {
                context.assets.open("vcam/$asset").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out.setExecutable(true, false)
            } catch (t: Throwable) {
                Log.e(TAG, "extract $asset failed", t)
                return false
            }
        }
        return true
    }

    /** Run root-manager.sh <action> <payload> through KernelSU su. */
    fun runAction(context: Context, action: String): String {
        if (!ensurePayload(context)) {
            return "ERROR: payload extraction failed"
        }
        val dir = payloadDir(context)
        val cmd = "sh ${dir.absolutePath}/root-manager.sh $action ${dir.absolutePath}"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val rc = process.waitFor()
            val text = (out + err).trim()
            if (rc == 0) text.ifEmpty { "OK" } else "ERROR(rc=$rc)\n$text"
        } catch (t: Throwable) {
            Log.e(TAG, "run $action failed", t)
            "ERROR: $t"
        }
    }

    fun deploy(context: Context): String = runAction(context, "deploy")

    fun status(context: Context): String = runAction(context, "status")

    fun rollback(context: Context): String = runAction(context, "rollback")
}
