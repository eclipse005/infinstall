package com.infinstall.app.adb

/**
 * Re-exports for UI / ViewModel so call sites can import from [com.infinstall.app.adb].
 * Canonical definitions live in [com.infinstall.app.adb.model].
 */
typealias RemoteFile = com.infinstall.app.adb.model.RemoteFile
typealias RemoteFileProps = com.infinstall.app.adb.model.RemoteFileProps
typealias TransferProgress = com.infinstall.app.adb.model.TransferProgress
typealias TransferCancelledException = com.infinstall.app.adb.model.TransferCancelledException
typealias AdbException = com.infinstall.app.adb.model.AdbException
