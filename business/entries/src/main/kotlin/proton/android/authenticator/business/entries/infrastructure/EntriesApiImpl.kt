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

package proton.android.authenticator.business.entries.infrastructure

import kotlinx.coroutines.withContext
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import proton.android.authenticator.business.entries.domain.EntriesApi
import proton.android.authenticator.business.entries.domain.EntryRemote
import proton.android.authenticator.business.entries.domain.FetchEntriesResult
import proton.android.authenticator.business.entries.infrastructure.network.CreateEntriesRequestDto
import proton.android.authenticator.business.entries.infrastructure.network.CreateEntryRequestDto
import proton.android.authenticator.business.entries.infrastructure.network.DeleteEntriesRequestDto
import proton.android.authenticator.business.entries.infrastructure.network.EntryDto
import proton.android.authenticator.business.entries.infrastructure.network.SortEntriesRequestDto
import proton.android.authenticator.business.entries.infrastructure.network.UpdateEntriesRequestDto
import proton.android.authenticator.business.entries.infrastructure.network.UpdateEntryRequestDto
import proton.android.authenticator.business.entries.infrastructure.network.retrofit.RetrofitEntriesDataSource
import proton.android.authenticator.commonrust.AuthenticatorCryptoInterface
import proton.android.authenticator.AuthenticatorEntryModel
import proton.android.authenticator.shared.common.domain.dispatchers.AppDispatchers
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import proton.android.authenticator.shared.crypto.domain.keys.EncryptionKey
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
internal class EntriesApiImpl @Inject constructor(
    private val apiProvider: ApiProvider,
    private val appDispatchers: AppDispatchers,
    private val authenticatorCrypto: AuthenticatorCryptoInterface
) : EntriesApi() {

    override suspend fun createAll(
        userId: String,
        keyId: String,
        encryptionKey: EncryptionKey,
        entryModels: List<AuthenticatorEntryModel>
    ): List<EntryRemote> = withContext(appDispatchers.default) {
        authenticatorCrypto.encryptManyEntries(
            key = encryptionKey.asByteArray(),
            models = entryModels
        ).map { encryptedEntryModel ->
            CreateEntryRequestDto(
                authenticatorKeyID = keyId,
                content = Base64.encodeToByteArray(encryptedEntryModel).let(::String),
                contentFormatVersion = contentFormatVersion
            )
        }
    }
        .let(::CreateEntriesRequestDto)
        .let { request ->
            apiProvider
                .get<RetrofitEntriesDataSource>(userId = UserId(id = userId))
                .invoke { createEntries(request = request) }
                .valueOrThrow
                .entries
                .toRemoteEntriesResult(encryptionKeys = listOf(encryptionKey))
                .entries
        }

    override suspend fun deleteAll(userId: String, entryIds: List<String>) {
        entryIds
            .let(::DeleteEntriesRequestDto)
            .also { request ->
                apiProvider
                    .get<RetrofitEntriesDataSource>(userId = UserId(id = userId))
                    .invoke { deleteEntries(request = request) }
                    .valueOrThrow
            }
    }

    override suspend fun fetchAll(userId: String, encryptionKeys: List<EncryptionKey>): FetchEntriesResult {
        var lastId: String? = null

        return buildList {
            do {
                apiProvider
                    .get<RetrofitEntriesDataSource>(userId = UserId(id = userId))
                    .invoke { getEntries(lastId = lastId) }
                    .valueOrThrow
                    .fetchEntriesDto
                    .also { fetchEntriesDto -> lastId = fetchEntriesDto.lastId }
                    .also { fetchEntriesDto -> addAll(fetchEntriesDto.entries) }
            } while (lastId != null)
        }.toRemoteEntriesResult(encryptionKeys = encryptionKeys)
    }

    override suspend fun sortAll(
        userId: String,
        startingPosition: Int,
        entryIds: List<String>
    ): Long = SortEntriesRequestDto(
        startingPosition = startingPosition,
        entryIds = entryIds
    ).let { request ->
        apiProvider
            .get<RetrofitEntriesDataSource>(userId = UserId(id = userId))
            .invoke { sortEntries(request = request) }
            .valueOrThrow
            .result
            .modifyTime
    }

    override suspend fun updateAll(
        userId: String,
        entryIds: List<String>,
        keyId: String,
        encryptionKey: EncryptionKey,
        entryModels: List<AuthenticatorEntryModel>,
        remoteEntriesMap: Map<String, EntryRemote>
    ): List<EntryRemote> = withContext(appDispatchers.default) {
        authenticatorCrypto.encryptManyEntries(
            key = encryptionKey.asByteArray(),
            models = entryModels
        )
            .zip(entryIds)
            .map { (encryptedEntryModel, entryId) ->
                UpdateEntryRequestDto(
                    authenticatorKeyID = keyId,
                    entryId = entryId,
                    content = Base64.encodeToByteArray(encryptedEntryModel).let(::String),
                    contentFormatVersion = contentFormatVersion,
                    lastRevision = remoteEntriesMap.getValue(entryId).revision
                )
            }
    }
        .let(::UpdateEntriesRequestDto)
        .let { request ->
            apiProvider
                .get<RetrofitEntriesDataSource>(userId = UserId(id = userId))
                .invoke { updateEntries(request = request) }
                .valueOrThrow
                .entries
                .toRemoteEntriesResult(encryptionKeys = listOf(encryptionKey))
                .entries
        }

    private suspend fun List<EntryDto>.toRemoteEntriesResult(encryptionKeys: List<EncryptionKey>): FetchEntriesResult =
        withContext(appDispatchers.default) {
            if (isEmpty()) return@withContext FetchEntriesResult(emptyList(), 0)

            if (encryptionKeys.isEmpty()) return@withContext FetchEntriesResult(emptyList(), 0)

            val ciphertexts = map { entryDto -> Base64.decode(entryDto.content) }
            encryptionKeys.tryWithAnyKey { key ->
                val entryModels = authenticatorCrypto.decryptManyEntries(
                    key = key.asByteArray(),
                    ciphertexts = ciphertexts
                )

                withIndex().zip(entryModels) { indexedEntryDto, entryModel ->
                    EntryRemote(
                        id = indexedEntryDto.value.entryId,
                        revision = indexedEntryDto.value.revision,
                        createdAt = indexedEntryDto.value.createTime,
                        modifiedAt = indexedEntryDto.value.modifyTime,
                        position = indexedEntryDto.index,
                        model = entryModel
                    )
                }
            }?.let { return@withContext FetchEntriesResult(it, 0) }

            // Batch failed - decrypt individually, trying all available keys
            val results = mapIndexedNotNull { index, entryDto ->
                val ciphertext = Base64.decode(entryDto.content)

                encryptionKeys.tryWithAnyKey { key ->
                    val entryModel = authenticatorCrypto.decryptEntry(
                        key = key.asByteArray(),
                        ciphertext = ciphertext
                    )

                    EntryRemote(
                        id = entryDto.entryId,
                        revision = entryDto.revision,
                        createdAt = entryDto.createTime,
                        modifiedAt = entryDto.modifyTime,
                        position = index,
                        model = entryModel
                    )
                }
            }

            val undecryptableCount = size - results.size
            if (undecryptableCount > 0) {
                AuthenticatorLogger.w(
                    TAG,
                    "Failed to decrypt $undecryptableCount out of $size entries " +
                        "(likely encrypted with old keys after password reset)"
                )
            }

            FetchEntriesResult(results, undecryptableCount)
        }

    private inline fun <T> List<EncryptionKey>.tryWithAnyKey(block: (EncryptionKey) -> T): T? =
        firstNotNullOfOrNull { key ->
            runCatching { block(key) }
                .onFailure { e -> if (e is CancellationException) throw e }
                .getOrNull()
        }

    private companion object {
        private const val TAG = "EntriesApiImpl"
    }

}
