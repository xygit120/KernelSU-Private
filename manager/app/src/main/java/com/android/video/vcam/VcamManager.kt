package com.android.video.vcam

import android.content.Context
import android.util.Log
import com.android.video.magica.KsuSetup
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
        val su = KsuSetup.suPath()
            ?: return "ERROR: no working su binary found (is KernelSU active?)"
        val dir = payloadDir(context)
        val cmd = "sh ${dir.absolutePath}/root-manager.sh $action ${dir.absolutePath}"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(su, "-c", cmd))
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

    /** Shared runtime data dir (stable across manager updates and uninstalls). */
    const val DATA_DIR = "/data/adb/vcam"
    const val MEDIA_DIR = "$DATA_DIR/media"

    private const val CTRL_TCP_PORT = 34927

    /** Send a raw control command to the runtime's TCP channel and read one response line. */
    fun sendCommand(command: String): String {
        return try {
            java.net.Socket("127.0.0.1", CTRL_TCP_PORT).use { s ->
                s.soTimeout = 5000
                s.getOutputStream().write((command + "\n").toByteArray())
                s.getOutputStream().flush()
                s.getInputStream().bufferedReader().readLine() ?: ""
            }
        } catch (t: Throwable) {
            "ERROR: runtime control port unreachable ($t)"
        }
    }

    /**
     * Import a SAF document into the shared media dir and tell the runtime to play it.
     * The content is staged in the app cache, then copied to MEDIA_DIR and atomically
     * renamed (source.<ext>.tmp -> source.<ext>) so the runtime never sees a partial file.
     */
    fun importMedia(context: Context, uri: android.net.Uri, extension: String): String {
        val su = KsuSetup.suPath()
            ?: return "ERROR: no working su binary found (is KernelSU active?)"
        val stage = File(context.cacheDir, "vcam-source.$extension")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) {
                    return "ERROR: cannot open $uri"
                }
                stage.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "importMedia stage failed", t)
            return "ERROR: cannot read selection: $t"
        }
        val target = "$MEDIA_DIR/source.$extension"
        val cmd = "mkdir -p $MEDIA_DIR && chmod 755 $DATA_DIR $MEDIA_DIR && " +
            "cp '${stage.absolutePath}' '$target.tmp' && sync && mv -f '$target.tmp' '$target' && " +
            "ls -l '$target'"
        val copyResult = try {
            val process = Runtime.getRuntime().exec(arrayOf(su, "-c", cmd))
            val out = process.inputStream.bufferedReader().readText().trim()
            val err = process.errorStream.bufferedReader().readText().trim()
            val rc = process.waitFor()
            if (rc == 0) out else "ERROR(rc=$rc) $err"
        } catch (t: Throwable) {
            Log.e(TAG, "importMedia copy failed", t)
            "ERROR: $t"
        } finally {
            stage.delete()
        }
        if (copyResult.startsWith("ERROR")) {
            return copyResult
        }
        val response = sendCommand("CMD_PLAY $target")
        return "$copyResult\nCMD_PLAY -> $response"
    }

    fun deploy(context: Context): String = runAction(context, "deploy")

    fun status(context: Context): String = runAction(context, "status")

    fun rollback(context: Context): String = runAction(context, "rollback")

    /** Current real/virtual mode as recorded by the runtime. */
    fun currentMode(context: Context): String {
        val su = KsuSetup.suPath() ?: return "unknown"
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(su, "-c", "cat $APP_DIR/mode-current 2>/dev/null"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.ifEmpty { "unknown" }
        } catch (t: Throwable) {
            "unknown"
        }
    }

    /**
     * Switch between the real and the virtual camera through mode-switch.sh.
     * The script requires the live provider pid and a token of the form
     * "<pid>-<n>", so both are derived here instead of being accepted from UI.
     */
    fun setMode(context: Context, mode: String): String {
        if (mode != "real" && mode != "virtual") {
            return "ERROR: bad mode $mode"
        }
        if (!ensurePayload(context)) {
            return "ERROR: payload extraction failed"
        }
        val su = KsuSetup.suPath()
            ?: return "ERROR: no working su binary found (is KernelSU active?)"
        val dir = payloadDir(context)
        val cmd = "vpid=\$(pidof vcam11-v37 | tr -d '\\r'); " +
            "[ -n \"\$vpid\" ] || { echo 'ERROR: vcam11 runtime is not running'; exit 1; }; " +
            "exec sh ${dir.absolutePath}/mode-switch.sh $mode \"\$vpid\" \"\$vpid-\$(date +%s)\""
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(su, "-c", cmd))
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val rc = process.waitFor()
            val text = (out + err).trim()
            if (rc == 0) text.ifEmpty { "OK" } else "ERROR(rc=$rc)\n$text"
        } catch (t: Throwable) {
            Log.e(TAG, "setMode $mode failed", t)
            "ERROR: $t"
        }
    }
}
