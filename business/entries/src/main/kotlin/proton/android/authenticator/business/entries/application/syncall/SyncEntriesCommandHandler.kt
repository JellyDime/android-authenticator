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

package proton.android.authenticator.business.entries.application.syncall

import kotlinx.coroutines.CancellationException
import me.proton.core.network.domain.ApiException
import proton.android.authenticator.business.shared.domain.errors.ErrorLoggingUtils
import proton.android.authenticator.business.shared.domain.infrastructure.network.getErrorCode
import proton.android.authenticator.shared.common.domain.answers.Answer
import proton.android.authenticator.shared.common.domain.infrastructure.commands.CommandHandler
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import javax.inject.Inject

internal class SyncEntriesCommandHandler @Inject constructor(
    private val syncer: EntriesSyncer
) : CommandHandler<SyncEntriesCommand, Int, SyncEntriesReason> {

    override suspend fun handle(command: SyncEntriesCommand): Answer<Int, SyncEntriesReason> {
        return runCatching {
            AuthenticatorLogger.i(TAG, "Starting sync with ${command.entries.size} local entries")
            syncer.sync(
                userId = command.userId,
                keys = command.keys,
                entries = command.entries
            )
        }.fold(
            onSuccess = { undecryptableCount ->
                AuthenticatorLogger.i(TAG, "Successfully synced entries for user: ${command.userId}")
                Answer.Success(undecryptableCount)
            },
            onFailure = { throwable: Throwable ->
                when (throwable) {
                    is ApiException -> {
                        when (throwable.getErrorCode()) {
                            ERROR_CODE_UNAUTHORIZED ->
                                ErrorLoggingUtils.logAndReturnFailure(
                                    tag = TAG,
                                    throwable = throwable,
                                    message = "Could not sync entries due to API unauthorized",
                                    reason = SyncEntriesReason.Unauthorized
                                )
                            else ->
                                ErrorLoggingUtils.logAndReturnFailure(
                                    tag = TAG,
                                    throwable = throwable,
                                    message = "Could not sync entries due to API exception",
                                    reason = SyncEntriesReason.Unknown
                                )
                        }
                    }
                    is NoValidSyncKeysException -> ErrorLoggingUtils.logAndReturnFailure(
                        tag = TAG,
                        throwable = throwable,
                        message = "Could not sync entries due to no valid sync keys",
                        reason = SyncEntriesReason.KeyNotFound
                    )
                    is CancellationException -> throw throwable
                    else -> ErrorLoggingUtils.logAndReturnFailure(
                        tag = TAG,
                        throwable = throwable,
                        message = "Could not sync entries due to unexpected error",
                        reason = SyncEntriesReason.Unknown
                    )
                }
            }
        )
    }

    private companion object {

        private const val TAG = "SyncEntriesCommandHandler"

        private const val ERROR_CODE_UNAUTHORIZED = 401

    }

}
