/*
 * OAndBackupX: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.machiav3lli.backup.handler.action

import android.content.Context
import android.net.Uri
import android.util.Log
import com.machiav3lli.backup.Constants
import com.machiav3lli.backup.Constants.classTag
import com.machiav3lli.backup.handler.BackupBuilder
import com.machiav3lli.backup.handler.Crypto
import com.machiav3lli.backup.handler.Crypto.CryptoSetupException
import com.machiav3lli.backup.handler.Crypto.encryptStream
import com.machiav3lli.backup.handler.ShellHandler
import com.machiav3lli.backup.handler.ShellHandler.Companion.isFileNotFoundException
import com.machiav3lli.backup.handler.ShellHandler.ShellCommandFailedException
import com.machiav3lli.backup.items.*
import com.machiav3lli.backup.items.StorageFile.Companion.fromUri
import com.machiav3lli.backup.utils.DocumentUtils.suCopyFileToDocument
import com.machiav3lli.backup.utils.DocumentUtils.suRecursiveCopyFileToDocument
import com.machiav3lli.backup.utils.FileUtils.BackupLocationIsAccessibleException
import com.machiav3lli.backup.utils.PrefUtils.StorageLocationNotConfiguredException
import com.machiav3lli.backup.utils.PrefUtils.getCryptoSalt
import com.machiav3lli.backup.utils.PrefUtils.getDefaultSharedPreferences
import com.machiav3lli.backup.utils.PrefUtils.isEncryptionEnabled
import com.machiav3lli.backup.utils.PrefUtils.isKillBeforeActionEnabled
import com.machiav3lli.backup.utils.TarUtils.suAddFiles
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

open class BackupAppAction(context: Context, shell: ShellHandler) : BaseAppAction(context, shell) {

    open fun run(app: AppInfoX, backupMode: Int): ActionResult? {
        Log.i(TAG, "Backing up: ${app.packageName} [${app.packageLabel}]")
        val appBackupRootUri: Uri?
        appBackupRootUri = try {
            app.getBackupDir(true)
        } catch (e: BackupLocationIsAccessibleException) {
            // Usually, this should never happen, but just in case...
            val realException: Exception = BackupFailedException("Cannot backup data. Storage location not set or inaccessible", e)
            return ActionResult(app, null, "${realException.javaClass.simpleName}: ${e.message}", false)
        } catch (e: StorageLocationNotConfiguredException) {
            val realException: Exception = BackupFailedException("Cannot backup data. Storage location not set or inaccessible", e)
            return ActionResult(app, null, "${realException.javaClass.simpleName}: ${e.message}", false)
        }
        val backupBuilder = BackupBuilder(context, app.appInfo!!, appBackupRootUri!!)
        val backupInstanceDir = backupBuilder.backupPath
        val stopProcess = isKillBeforeActionEnabled(context)
        val backupItem: BackupItem
        if (stopProcess) {
            Log.d(TAG, "pre-process package (to avoid file inconsistencies during backup etc.)")
            preprocessPackage(app.packageName)
        }
        try {
            if (backupMode and MODE_APK == MODE_APK) {
                Log.i(TAG, "$app: Backing up package")
                backupPackage(app, backupInstanceDir)
                backupBuilder.setHasApk(true)
            }
            if (backupMode and MODE_DATA == MODE_DATA) {
                Log.i(TAG, "$app: Backing up data")
                var backupCreated = backupData(app, backupInstanceDir)
                backupBuilder.setHasAppData(backupCreated)
                if (getDefaultSharedPreferences(context).getBoolean(Constants.PREFS_EXTERNALDATA, true)) {
                    backupCreated = backupExternalData(app, backupInstanceDir)
                    backupBuilder.setHasExternalData(backupCreated)
                    backupCreated = backupObbData(app, backupInstanceDir)
                    backupBuilder.setHasObbData(backupCreated)
                }
                if (getDefaultSharedPreferences(context).getBoolean(Constants.PREFS_DEVICEPROTECTEDDATA, true)) {
                    backupCreated = backupDeviceProtectedData(app, backupInstanceDir)
                    backupBuilder.setHasDevicesProtectedData(backupCreated)
                }
            }
            if (isEncryptionEnabled(context)) {
                backupBuilder.setCipherType(Crypto.CIPHER_ALGORITHM)
            }
            backupItem = backupBuilder.createBackupItem()
            saveBackupProperties(fromUri(context, appBackupRootUri), backupItem.backupProperties)
            app.addBackup(backupItem)
        } catch (e: BackupFailedException) {
            Log.e(TAG, "Backup failed due to ${e.javaClass.simpleName}: ${e.message}")
            Log.d(TAG, "Backup deleted: ${backupBuilder.backupPath!!.delete()}")
            return ActionResult(app, null, "${e.javaClass.simpleName}: ${e.message}", false)
        } catch (e: CryptoSetupException) {
            Log.e(TAG, "Backup failed due to ${e.javaClass.simpleName}: ${e.message}")
            Log.d(TAG, "Backup deleted: ${backupBuilder.backupPath!!.delete()}")
            return ActionResult(app, null, "${e.javaClass.simpleName}: ${e.message}", false)
        } catch (e: IOException) {
            Log.e(TAG, "Backup failed due to ${e.javaClass.simpleName}: ${e.message}")
            Log.d(TAG, "Backup deleted: ${backupBuilder.backupPath!!.delete()}")
            return ActionResult(app, null, "${e.javaClass.simpleName}: ${e.message}", false)
        } finally {
            if (stopProcess) {
                Log.d(TAG, "post-process package (to set it back to normal operation)")
                postprocessPackage(app.packageName)
            }
        }
        Log.i(TAG, "$app: Backup done: $backupItem")
        return ActionResult(app, backupItem.backupProperties, "", true)
    }

    @Throws(IOException::class)
    protected fun saveBackupProperties(packageBackupDir: StorageFile, properties: BackupProperties) {
        val propertiesFileName = String.format(BackupProperties.BACKUP_INSTANCE_PROPERTIES,
                Constants.BACKUP_DATE_TIME_FORMATTER.format(properties.backupDate), properties.profileId)
        val propertiesFile = packageBackupDir.createFile("application/octet-stream", propertiesFileName)
        BufferedOutputStream(context.contentResolver.openOutputStream(propertiesFile!!.uri, "w"))
                .use { propertiesOut -> propertiesOut.write(properties.toGson().toByteArray(StandardCharsets.UTF_8)) }
        Log.i(TAG, "Wrote $propertiesFile file for backup: $properties")
    }

    @Throws(IOException::class, CryptoSetupException::class)
    protected fun createBackupArchive(backupInstanceDir: Uri?, what: String?, allFilesToBackup: List<ShellHandler.FileInfo>) {
        Log.i(TAG, "Creating $what backup")
        val backupDir = fromUri(context, backupInstanceDir!!)
        val backupFilename = getBackupArchiveFilename(what!!, isEncryptionEnabled(context))
        val backupFile = backupDir.createFile("application/octet-stream", backupFilename)
        val password = getDefaultSharedPreferences(context).getString(Constants.PREFS_PASSWORD, "")
        var outStream: OutputStream = BufferedOutputStream(context.contentResolver.openOutputStream(backupFile!!.uri, "w"))
        if (password!!.isNotEmpty()) {
            outStream = encryptStream(outStream, password, getCryptoSalt(context))
        }
        try {
            TarArchiveOutputStream(GzipCompressorOutputStream(outStream)).use { archive ->
                archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                suAddFiles(archive, allFilesToBackup)
            }
        } finally {
            Log.d(TAG, "Done compressing. Closing $backupFilename")
            outStream.close()
        }
    }

    @Throws(IOException::class)
    protected fun copyToBackupArchive(backupInstanceDir: Uri?, what: String?, allFilesToBackup: List<ShellHandler.FileInfo>) {
        val backupInstance = fromUri(context, backupInstanceDir!!)
        val backupDir = backupInstance.createDirectory(what!!)
        suRecursiveCopyFileToDocument(context, allFilesToBackup, backupDir!!.uri)
    }

    @Throws(BackupFailedException::class)
    protected open fun backupPackage(app: AppInfoX, backupInstanceDir: StorageFile?) {
        Log.i(TAG, "[${app.packageName}] Backup package apks")
        var apksToBackup = arrayOf(app.apkPath)
        if (app.apkSplits == null) {
            Log.d(TAG, "[${app.packageName}] The app is a normal apk")
        } else {
            apksToBackup += app.apkSplits!!.drop(0)
            Log.d(TAG, String.format("[${app.packageName}] Package is splitted into %d apks", apksToBackup.size))
        }
        Log.d(TAG, String.format("[${app.packageName}] Backing up package (%d apks: %s)", apksToBackup.size,
                apksToBackup.joinToString(separator = " ") { s: String -> File(s).name }
        ))
        try {
            for (apk in apksToBackup) {
                suCopyFileToDocument(context.contentResolver, apk, backupInstanceDir!!)
            }
        } catch (e: IOException) {
            Log.e(TAG, "$app: Backup APKs failed: $e")
            throw BackupFailedException("Could not backup apk", e)
        }
    }

    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected fun genericBackupData(backupType: String?, backupInstanceDir: Uri?, filesToBackup: List<ShellHandler.FileInfo>, compress: Boolean): Boolean {
        Log.i(TAG, String.format("Backing up %s got %d files to backup", backupType, filesToBackup.size))
        if (filesToBackup.isEmpty()) {
            Log.i(TAG, "Nothing to backup for $backupType. Skipping")
            return false
        }
        try {
            if (compress) {
                createBackupArchive(backupInstanceDir, backupType, filesToBackup)
            } else {
                copyToBackupArchive(backupInstanceDir, backupType, filesToBackup)
            }
        } catch (e: IOException) {
            val message = "${e.javaClass.canonicalName} occurred on $backupType backup: $e"
            Log.e(TAG, message)
            throw BackupFailedException(message, e)
        }
        return true
    }

    @Throws(BackupFailedException::class)
    private fun assembleFileList(sourceDirectory: String): List<ShellHandler.FileInfo> {

        // Check what are the contents to backup. No need to start working, if the directory does not exist
        return try {
            // Get a list of directories in the directory to backup
            var dirsInSource = shell.suGetDetailedDirectoryContents(
                    sourceDirectory,
                    false,
                    null
            ).filter { it.filename != "no_backup" }

            // Excludes cache and libs, when we don't want to backup'em
            val skipCache = getDefaultSharedPreferences(context).getBoolean(Constants.PREFS_EXCLUDECACHE, true);
            if (skipCache) {
                dirsInSource = dirsInSource
                        .filterNot { it.filename.startsWith("app_") || DATA_EXCLUDED_DIRS.contains(it.filename) }
                        .toList()
            }

            // if the list is empty, there is nothing to do
            val allFilesToBackup: MutableList<ShellHandler.FileInfo> = ArrayList()
            if (dirsInSource.isEmpty()) {
                return allFilesToBackup
            }
            for (dir in dirsInSource) {
                allFilesToBackup.add(dir)
                // Do not process files in the "root" directory of the app's data
                if (dir.fileType === ShellHandler.FileInfo.FileType.DIRECTORY) try {
                    var filesToBackup = shell.suGetDetailedDirectoryContents(dir.absolutePath, true, dir.filename)
                    if (skipCache && dir.filename == "shared_prefs") {
                        filesToBackup = filesToBackup.filterNot { it.filename.toLowerCase().contains("(google|firebase)".toRegex()) }
                    }
                    allFilesToBackup.addAll(filesToBackup)
                } catch (e: ShellCommandFailedException) {
                    if (isFileNotFoundException(e)) {
                        Log.w(TAG, "Directory has been deleted during processing: $dir")
                    }
                }
            }
            allFilesToBackup
        } catch (e: ShellCommandFailedException) {
            throw BackupFailedException("Could not list contents of $sourceDirectory", e)
        }
    }

    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected open fun backupData(app: AppInfoX, backupInstanceDir: StorageFile?): Boolean {
        val backupType = BACKUP_DIR_DATA
        Log.i(TAG, String.format(LOG_START_BACKUP, app.packageName, backupType))
        val filesToBackup = assembleFileList(app.dataDir)
        return genericBackupData(backupType, backupInstanceDir!!.uri, filesToBackup, true)
    }

    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected open fun backupExternalData(app: AppInfoX, backupInstanceDir: StorageFile?): Boolean {
        val backupType = BACKUP_DIR_EXTERNAL_FILES
        Log.i(TAG, String.format(LOG_START_BACKUP, app.packageName, backupType))
        return try {
            val filesToBackup = assembleFileList(app.externalDataDir)
            genericBackupData(backupType, backupInstanceDir!!.uri, filesToBackup, true)
        } catch (ex: BackupFailedException) {
            if (ex.cause is ShellCommandFailedException
                    && isFileNotFoundException((ex.cause as ShellCommandFailedException?)!!)) {
                Log.i(TAG, String.format(LOG_NO_THING_TO_BACKUP, backupType, app.packageName))
                return false
            }
            throw ex
        }
    }

    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected open fun backupObbData(app: AppInfoX, backupInstanceDir: StorageFile?): Boolean {
        val backupType = BACKUP_DIR_OBB_FILES
        Log.i(TAG, String.format(LOG_START_BACKUP, app.packageName, backupType))
        return try {
            val filesToBackup = assembleFileList(app.obbFilesDir)
            genericBackupData(backupType, backupInstanceDir!!.uri, filesToBackup, false)
        } catch (ex: BackupFailedException) {
            if (ex.cause is ShellCommandFailedException
                    && isFileNotFoundException((ex.cause as ShellCommandFailedException?)!!)) {
                Log.i(TAG, String.format(LOG_NO_THING_TO_BACKUP, backupType, app.packageName))
                return false
            }
            throw ex
        }
    }

    @Throws(BackupFailedException::class, CryptoSetupException::class)
    protected open fun backupDeviceProtectedData(app: AppInfoX, backupInstanceDir: StorageFile?): Boolean {
        val backupType = BACKUP_DIR_DEVICE_PROTECTED_FILES
        Log.i(TAG, String.format(LOG_START_BACKUP, app.packageName, backupType))
        return try {
            val filesToBackup = assembleFileList(app.deviceProtectedDataDir)
            genericBackupData(backupType, backupInstanceDir!!.uri, filesToBackup, true)
        } catch (ex: BackupFailedException) {
            if (ex.cause is ShellCommandFailedException
                    && isFileNotFoundException((ex.cause as ShellCommandFailedException?)!!)) {
                Log.i(TAG, String.format(LOG_NO_THING_TO_BACKUP, backupType, app.packageName))
                return false
            }
            throw ex
        }
    }

    class BackupFailedException(message: String?, cause: Throwable?) : AppActionFailedException(message, cause)
    companion object {
        private val TAG = classTag(".BackupAppAction")
        const val LOG_START_BACKUP = "[%s] Starting %s backup"
        const val LOG_NO_THING_TO_BACKUP = "[%s] No %s to backup available"
    }
}