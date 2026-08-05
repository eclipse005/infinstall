package com.infinstall.app.adb

import android.content.Context
import com.infinstall.app.adb.model.RemoteFile
import com.infinstall.app.adb.model.RemoteFileProps
import com.infinstall.app.adb.model.SessionState
import com.infinstall.app.adb.model.TransferProgress
import com.infinstall.app.adb.session.AdbSession
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.InputStream

/**
 * App-facing facade. Session lifecycle is entirely owned by [AdbSession].
 */
class TvSession(appContext: Context) {
    private val session = AdbSession.get(appContext)
    private val fs = RemoteFs(session)
    private val installer = ApkInstaller(session)

    val state: StateFlow<SessionState> get() = session.state
    val host: String? get() = session.host
    val port: Int? get() = session.port
    val isConnected: Boolean get() = session.isConnected
    /** Set when session drops without user disconnect (proven link death). */
    val lastDropReason: String? get() = session.lastDropReason

    fun requestCancel() = session.requestCancel()
    fun clearCancel() = session.clearCancel()
    fun isSessionUp(): Boolean = session.isConnected

    suspend fun pair(host: String, pairingPort: Int, pairingCode: String) =
        session.pair(host, pairingPort, pairingCode)

    suspend fun connect(host: String, port: Int) = session.connect(host, port)

    suspend fun disconnect() = session.disconnect()

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
