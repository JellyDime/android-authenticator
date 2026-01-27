/*
 * Copyright (c) 2025 Proton AG
 * This file is part of Proton AG and Proton Authenticator.
 *
 * Proton Authenticator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Authenticator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Authenticator.  If not, see <https://www.gnu.org/licenses/>.
 */

package proton.android.authenticator.business.shared.infrastructure.files

import android.content.ContentResolver
import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import proton.android.authenticator.business.shared.domain.errors.FileTooLargeException
import proton.android.authenticator.business.shared.domain.infrastructure.files.FileReader
import proton.android.authenticator.shared.common.domain.dispatchers.AppDispatchers
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject

internal class FileReaderImpl @Inject constructor(
    private val appDispatchers: AppDispatchers,
    private val contentResolver: ContentResolver,
    @param:ApplicationContext private val context: Context
) : FileReader {

    override suspend fun readText(path: String): String = withContext(appDispatchers.io) {
        path.toUri().let { pathUri ->
            val document = DocumentFile.fromSingleUri(context, pathUri)
            when {
                document == null -> {
                    AuthenticatorLogger.w(TAG, "Cannot read file: DocumentFile is null for URI: $pathUri")
                    throw FileNotFoundException("Cannot access file at URI: $pathUri")
                }
                !document.exists() -> {
                    AuthenticatorLogger.w(TAG, "Cannot read file: File does not exist at URI: $pathUri")
                    throw FileNotFoundException("File does not exist at URI: $pathUri")
                }
                !document.isFile -> {
                    AuthenticatorLogger.w(TAG, "Cannot read file: URI points to directory: $pathUri")
                    throw IllegalArgumentException("URI must point to a file, not a directory: $pathUri")
                }
                else -> {
                    contentResolver.openInputStream(pathUri)?.bufferedReader()?.use { reader ->
                        val content = reader.readText()
                        if (content.isEmpty()) {
                            AuthenticatorLogger.w(TAG, "File is empty at URI: $pathUri")
                        }
                        content
                    } ?: run {
                        AuthenticatorLogger.w(TAG, "Cannot open input stream for URI: $pathUri")
                        throw IOException("Cannot open file for reading: $pathUri")
                    }
                }
            }
        }
    }

    override suspend fun readBinary(path: String, maxSize: Int): ByteArray = withContext(appDispatchers.io) {
        path.toUri().let { pathUri ->
            val document = DocumentFile.fromSingleUri(context, pathUri)
            when {
                document == null -> {
                    AuthenticatorLogger.w(TAG, "Cannot read file: DocumentFile is null for URI: $pathUri")
                    throw FileNotFoundException("Cannot access file at URI: $pathUri")
                }
                !document.exists() -> {
                    AuthenticatorLogger.w(TAG, "Cannot read file: File does not exist at URI: $pathUri")
                    throw FileNotFoundException("File does not exist at URI: $pathUri")
                }
                !document.isFile -> {
                    AuthenticatorLogger.w(TAG, "Cannot read file: URI points to directory: $pathUri")
                    throw IllegalArgumentException("URI must point to a file, not a directory: $pathUri")
                }
                else -> {
                    contentResolver.openInputStream(pathUri)?.use { inputStream ->
                        if (inputStream.available() > maxSize) throw FileTooLargeException(maxSize)
                        val outputStream = ByteArrayOutputStream()
                        val buffer = ByteArray(1_024)
                        var bytesRead: Int
                        while (true) {
                            bytesRead = inputStream.read(buffer)
                            if (bytesRead == -1) {
                                break
                            }
                            outputStream.write(buffer, 0, bytesRead)
                        }
                        val data = outputStream.toByteArray()
                        if (data.isEmpty()) {
                            AuthenticatorLogger.w(TAG, "File is empty at URI: $pathUri")
                        }
                        data
                    } ?: run {
                        AuthenticatorLogger.w(TAG, "Cannot open input stream for URI: $pathUri")
                        throw IOException("Cannot open file for reading: $pathUri")
                    }
                }
            }
        }
    }

    private companion object {
        private const val TAG = "FileReaderImpl"
    }
}
