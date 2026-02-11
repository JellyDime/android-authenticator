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

package proton.android.authenticator.business.keys.infrastructure

import kotlinx.coroutines.withContext
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.pgp.VerificationStatus
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.decryptAndVerifyData
import me.proton.core.key.domain.getArmored
import me.proton.core.network.data.ApiProvider
import me.proton.core.user.domain.entity.User
import me.proton.core.user.domain.repository.UserRepository
import proton.android.authenticator.business.keys.domain.Key
import proton.android.authenticator.business.keys.domain.KeysApi
import proton.android.authenticator.business.keys.domain.errors.AllRemoteKeysUndecryptableException
import proton.android.authenticator.business.keys.infrastructure.network.CreateKeyRequestDto
import proton.android.authenticator.business.keys.infrastructure.network.KeyDto
import proton.android.authenticator.business.keys.infrastructure.network.retrofit.RetrofitKeysDataSource
import proton.android.authenticator.shared.common.domain.dispatchers.AppDispatchers
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContext
import proton.android.authenticator.shared.crypto.domain.contexts.EncryptionContextProvider
import proton.android.authenticator.shared.crypto.domain.extensions.tryUseKeys
import javax.inject.Inject
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal class KeysApiImpl @Inject constructor(
    private val apiProvider: ApiProvider,
    private val appDispatchers: AppDispatchers,
    private val cryptoContext: CryptoContext,
    private val encryptionContextProvider: EncryptionContextProvider,
    private val userRepository: UserRepository
) : KeysApi {

    override suspend fun create(userId: String, encryptedKey: String): Key? {
        val sessionUserId = UserId(id = userId)
        val keyDto = apiProvider
            .get<RetrofitKeysDataSource>(userId = sessionUserId)
            .invoke { createKey(request = CreateKeyRequestDto(key = encryptedKey)) }
            .valueOrThrow
            .key

        val user = userRepository.getUser(sessionUserId = sessionUserId)

        return encryptionContextProvider.withEncryptionContext {
            keyDto.toDomain(user = user, encryptionContext = this)
        }
    }

    override suspend fun fetchAll(userId: String): List<Key> {
        val sessionUserId = UserId(id = userId)
        val keyDtos = apiProvider
            .get<RetrofitKeysDataSource>(userId = sessionUserId)
            .invoke { getKeys() }
            .valueOrThrow
            .keys
            .keys

        AuthenticatorLogger.i(TAG, "Fetched ${keyDtos.size} key DTOs from server")

        val user = userRepository.getUser(sessionUserId = sessionUserId)

        return encryptionContextProvider.withEncryptionContext {
            val keys = keyDtos.mapNotNull { keyDto ->
                keyDto.toDomain(user = user, encryptionContext = this)
            }

            AuthenticatorLogger.i(TAG, "Successfully decrypted ${keys.size} out of ${keyDtos.size} keys")

            if (keyDtos.isNotEmpty() && keys.isEmpty()) {
                throw AllRemoteKeysUndecryptableException("All ${keyDtos.size} keys failed to decrypt")
            }

            keys
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun KeyDto.toDomain(user: User, encryptionContext: EncryptionContext): Key? = runCatching {
        val decryptedData = withContext(appDispatchers.default) {
            val decodedKey = Base64.decode(key)
            val armored = cryptoContext.pgpCrypto.getArmored(decodedKey)
            user.tryUseKeys(message = KEY_MESSAGE, cryptoContext) {
                decryptAndVerifyData(armored)
            }
        }

        if (decryptedData.status != VerificationStatus.Success) {
            AuthenticatorLogger.i(TAG, "Key not verified")
            return null
        }

        Key(
            id = keyId,
            key = key,
            userId = user.userId.id,
            userKeyId = userKeyId,
            encryptedKey = encryptionContext.encrypt(decryptedData.data)
        )
    }.onFailure { error ->
        if (error is kotlinx.coroutines.CancellationException) throw error
        AuthenticatorLogger.w(TAG, "Failed to decrypt key")
        AuthenticatorLogger.w(TAG, error)
    }.getOrNull()

    private companion object {

        private const val KEY_MESSAGE = "reencrypt authenticator key request"
        private const val TAG = "KeysApiImpl"

    }

}
