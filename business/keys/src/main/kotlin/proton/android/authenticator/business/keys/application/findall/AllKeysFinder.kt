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

package proton.android.authenticator.business.keys.application.findall

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.core.network.domain.ApiException
import proton.android.authenticator.business.keys.application.create.KeyCreator
import proton.android.authenticator.business.keys.domain.Key
import proton.android.authenticator.business.keys.domain.KeysApi
import proton.android.authenticator.business.keys.domain.KeysRepository
import proton.android.authenticator.business.keys.domain.errors.AllRemoteKeysUndecryptableException
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import javax.inject.Inject

internal class AllKeysFinder @Inject constructor(
    private val api: KeysApi,
    private val keyCreator: KeyCreator,
    private val repository: KeysRepository
) {

    private val refreshMutex = Mutex()
    private var lastRefreshAttemptAtMs: Long = 0L
    private var lastRefreshSuccessAtMs: Long = 0L
    private var consecutiveRefreshFailures: Int = 0

    internal fun findAll(userId: String, forceRefresh: Boolean): Flow<List<Key>> = flow {
        val localKeys = repository.findAll().first()
        AuthenticatorLogger.i(TAG, "Local keys count: ${localKeys.size}")

        val shouldRefresh = forceRefresh || localKeys.isEmpty() || shouldRefreshByStaleness()
        if (shouldRefresh) {
            refreshKeys(
                userId = userId,
                forceRefresh = forceRefresh
            )
        }

        emitAll(repository.findAll())
    }

    private suspend fun refreshKeys(userId: String, forceRefresh: Boolean) {
        refreshMutex.withLock {
            val latestLocalKeys = repository.findAll().first()
            if (!forceRefresh && latestLocalKeys.isNotEmpty() && !shouldRefreshByStaleness()) return

            val now = System.currentTimeMillis()
            if (shouldSkipRefreshDueToFailures(now)) return

            lastRefreshAttemptAtMs = now
            runCatching {
                api.fetchAll(userId = userId)
            }.onSuccess { remoteKeys ->
                AuthenticatorLogger.i(TAG, "Remote keys count: ${remoteKeys.size}")
                if (remoteKeys.isEmpty()) {
                    handleEmptyRemoteKeys(userId, latestLocalKeys)
                } else {
                    syncRemoteKeys(remoteKeys, latestLocalKeys)
                }
                registerRefreshSuccess(now)
            }.onFailure { error ->
                val isHandledAsSuccess = handleFetchError(error, userId, latestLocalKeys)
                if (isHandledAsSuccess) {
                    registerRefreshSuccess(now)
                } else {
                    registerRefreshFailure()
                }
            }
        }
    }

    private fun shouldRefreshByStaleness(now: Long = System.currentTimeMillis()): Boolean {
        if (lastRefreshSuccessAtMs == 0L) return true
        return now - lastRefreshSuccessAtMs >= REFRESH_STALE_AFTER_MS
    }

    private fun shouldSkipRefreshDueToFailures(now: Long): Boolean {
        if (consecutiveRefreshFailures >= MAX_CONSECUTIVE_REFRESH_FAILURES) {
            AuthenticatorLogger.w(
                TAG,
                "Skipping key refresh due to circuit breaker: failures=$consecutiveRefreshFailures"
            )
            return true
        }

        if (consecutiveRefreshFailures > 0 && now - lastRefreshAttemptAtMs < REFRESH_FAILURE_COOLDOWN_MS) {
            AuthenticatorLogger.i(
                TAG,
                "Skipping key refresh due to cooldown after failure"
            )
            return true
        }

        return false
    }

    private fun registerRefreshSuccess(now: Long) {
        consecutiveRefreshFailures = 0
        lastRefreshSuccessAtMs = now
    }

    private fun registerRefreshFailure() {
        consecutiveRefreshFailures += 1
    }

    private suspend fun handleEmptyRemoteKeys(userId: String, localKeys: List<Key>) {
        if (localKeys.isNotEmpty()) {
            handlePasswordResetRecovery(userId, localKeys)
        } else {
            handleInitialKeyCreation(userId)
        }
    }

    private suspend fun handlePasswordResetRecovery(userId: String, localKeys: List<Key>) {
        AuthenticatorLogger.w(
            TAG,
            "All keys are invalid (likely due to password reset), creating replacement key"
        )

        runCatching {
            keyCreator.create(userId = userId)

            val newKeys = repository.findAll().first()
            val replacementCreated = newKeys.any { newKey ->
                localKeys.none { oldKey -> oldKey.id == newKey.id }
            }

            if (replacementCreated) {
                AuthenticatorLogger.i(TAG, "Successfully created new key after password reset")
                repository.deleteAll(localKeys)
            } else {
                AuthenticatorLogger.w(TAG, "Key creation did not persist a replacement")
            }
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            AuthenticatorLogger.w(TAG, "Failed to create new key: ${error.message}")
        }
    }

    private suspend fun handleInitialKeyCreation(userId: String) {
        AuthenticatorLogger.w(TAG, "No valid keys found - creating new key")

        runCatching {
            keyCreator.create(userId = userId)
            AuthenticatorLogger.i(TAG, "Successfully created initial key")
        }.onFailure { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            AuthenticatorLogger.w(TAG, "Failed to create initial key: ${error.message}")
        }
    }

    private suspend fun syncRemoteKeys(remoteKeys: List<Key>, localKeys: List<Key>) {
        repository.saveAll(remoteKeys)

        val remoteKeyIds = remoteKeys.map { it.id }.toSet()
        val staleLocalKeys = localKeys.filter { it.id !in remoteKeyIds }
        if (staleLocalKeys.isNotEmpty()) {
            AuthenticatorLogger.w(TAG, "Removing ${staleLocalKeys.size} stale local keys")
            repository.deleteAll(staleLocalKeys)
        }
    }

    private suspend fun handleFetchError(
        error: Throwable,
        userId: String,
        localKeys: List<Key>
    ): Boolean {
        when (error) {
            is kotlinx.coroutines.CancellationException -> throw error
            is ApiException -> {
                AuthenticatorLogger.w(TAG, "Failed to fetch keys from server: ${error.message}")
                // On network error, use local keys if available
                return false
            }
            is AllRemoteKeysUndecryptableException -> {
                AuthenticatorLogger.w(TAG, "All remote keys undecryptable: ${error.message}")
                // Treat as if remoteKeys.isEmpty() - password reset scenario.
                handleEmptyRemoteKeys(userId = userId, localKeys = localKeys)
                return true
            }
            else -> throw error
        }
    }

    private companion object {
        private const val TAG = "AllKeysFinder"
        private const val REFRESH_STALE_AFTER_MS = 24 * 60 * 60 * 1000L
        private const val REFRESH_FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
        private const val MAX_CONSECUTIVE_REFRESH_FAILURES = 3
    }

}
