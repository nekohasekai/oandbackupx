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
package com.machiav3lli.backup.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

object FileUtils {
    private var backupLocation: Uri? = null

    // TODO Change to StorageFile-based
    fun getExternalStorageDirectory(context: Context): File {
        return context.getExternalFilesDir(null)!!.parentFile!!.parentFile!!.parentFile!!.parentFile!!
    }

    /**
     * Returns the backup directory URI. Users have to select it themselves to avoid SAF headache.
     *
     * @return URI to OABX storage directory
     */
    @Throws(StorageLocationNotConfiguredException::class, BackupLocationIsAccessibleException::class)
    fun getBackupDirUri(context: Context): Uri {
        if (backupLocation == null) {
            val storageRoot = context.backupDirPath
            if (storageRoot.isEmpty()) {
                throw StorageLocationNotConfiguredException()
            }
            val storageRootDoc = DocumentFile.fromTreeUri(context, Uri.parse(storageRoot))
            if (storageRootDoc == null || !storageRootDoc.exists()) {
                throw BackupLocationIsAccessibleException("Cannot access the root location.")
            }
            backupLocation = storageRootDoc.uri
        }
        return backupLocation as Uri
    }

    /**
     * Invalidates the cached value for the backup location URI so that the next call to
     * `getBackupDir` will set it again.
     */
    fun invalidateBackupLocation() {
        backupLocation = null
    }

    fun getName(fullPath: String): String {
        var path = fullPath
        if (path.endsWith(File.separator)) path = path.substring(0, path.length - 1)
        return path.substring(path.lastIndexOf(File.separator) + 1)
    }

    fun translatePosixPermissionToMode(permissions: String): Int {
        var str = permissions.takeLast(9)
        str = str.replace('s', 'x', false)
        str = str.replace('S', '-', false)
        val set = PosixFilePermissions.fromString(str)
        return translatePosixPermissionToMode(set)
    }

    fun translatePosixPermissionToMode(permissions: Set<PosixFilePermission?>): Int {
        var mode = 0
        PosixFilePermission.values().forEach {
            mode = mode shl 1
            mode += if (permissions.contains(it)) 1 else 0
        }
        return mode
    }

    class BackupLocationIsAccessibleException : Exception {
        constructor() : super()
        constructor(message: String?) : super(message)
        constructor(message: String?, cause: Throwable?) : super(message, cause)
    }
}