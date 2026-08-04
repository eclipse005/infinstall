package com.infinstall.app.adb

import android.content.Context
import com.infinstall.app.adb.model.RemoteFile
import com.infinstall.app.adb.model.RemoteFileProps
import com.infinstall.app.adb.model.TransferProgress
import java.io.File
import java.io.InputStream

/**
 * Thin facade for ViewModel. All real work goes through the unified stack:
 * [AdbClient] (transport) → [RemoteFs] / [ApkInstaller] (ops).
 *
 * Do not open AdbStream or shell here.
 */
class TvSession(appContext: Context) {
    private val client = AdbClient.get(appContext)
    private val fs = RemoteFs(client)
    private val installer = ApkInstaller(client)

    val host: String? get() = client.host
    val port: Int? get() = client.port
    val isConnected: Boolean get() = client.isConnected

    fun requestCancel() = client.requestCancel()
    fun clearCancel() = client.clearCancel()
    fun isSessionUp(): Boolean = client.isConnected
    fun isTcpAlive(): Boolean = client.isConnected

    suspend fun pair(host: String, pairingPort: Int, pairingCode: String) =
        client.pair(host, pairingPort, pairingCode)

    suspend fun connect(host: String, port: Int) = client.connect(host, port)

    suspend fun disconnect() = client.disconnect()

    /** Soft in-band ping; returns false on failure without killing the session. */
    suspend fun pingInBand(): Boolean {
        if (!client.isConnected) return false
        return try {
            client.shell("echo ping_ok", 5_000).contains("ping_ok")
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun installApk(
        input: InputStream,
        displayName: String,
        cacheDir: File,
        onProgress: (TransferProgress) -> Unit = {},
    ) = installer.install(input, displayName, cacheDir, onProgress)

    suspend fun pushToRemote(
        input: InputStream,
        remotePath: String,
        cacheDir: File,
        onProgress: (TransferProgress) -> Unit = {},
    ) = fs.upload(input, remotePath, cacheDir, onProgress)

    suspend fun listDir(path: String): List<RemoteFile> = fs.list(path)

    suspend fun statRemote(path: String): RemoteFileProps = fs.props(path)

    suspend fun deleteRemote(path: String) = fs.delete(path)

    suspend fun mkdirRemote(path: String) = fs.mkdir(path)

    suspend fun renameRemote(from: String, to: String) = fs.rename(from, to)

    suspend fun copyRemote(from: String, to: String) = fs.copy(from, to)

    suspend fun moveRemote(from: String, to: String) = fs.move(from, to)

    suspend fun pullToLocal(
        remotePath: String,
        localFile: File,
        onProgress: (Long) -> Unit = {},
    ) = fs.download(remotePath, localFile, onProgress)

    suspend fun installRemoteApk(remotePath: String) = installer.installRemotePath(remotePath)
}
