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

package proton.android.authenticator.features.shared.entries.usecases

import kotlinx.coroutines.flow.first
import proton.android.authenticator.business.entries.application.syncall.SyncEntriesCommand
import proton.android.authenticator.business.entries.application.syncall.SyncEntriesReason
import proton.android.authenticator.business.entries.application.syncall.SyncEntry
import proton.android.authenticator.business.entries.application.syncall.SyncKey
import proton.android.authenticator.features.shared.keys.usecases.GetAllKeysUseCase
import proton.android.authenticator.features.shared.usecases.settings.ObserveSettingsUseCase
import proton.android.authenticator.features.shared.usecases.settings.UpdateSettingsUseCase
import proton.android.authenticator.features.shared.users.usecases.ObserveUserUseCase
import proton.android.authenticator.shared.common.domain.answers.Answer
import proton.android.authenticator.shared.common.domain.infrastructure.commands.CommandBus
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import javax.inject.Inject

class SyncEntriesModelsUseCase @Inject constructor(
    private val commandBus: CommandBus,
    private val getAllKeysUseCase: GetAllKeysUseCase,
    private val observeUserUseCase: ObserveUserUseCase,
    private val observeEntryModelsUseCase: ObserveEntryModelsUseCase,
    private val observeSettingsUseCase: ObserveSettingsUseCase,
    private val updateSettingsUseCase: UpdateSettingsUseCase
) {

    suspend operator fun invoke(forceRefresh: Boolean): Answer<Int, SyncEntriesReason> {
        val user = observeUserUseCase().first() ?: run {
            return Answer.Failure(reason = SyncEntriesReason.UserNotFound)
        }

        val keys = getAllKeysUseCase(forceRefresh = forceRefresh)
            .ifEmpty { getAllKeysUseCase(forceRefresh = true) }
        if (keys.isEmpty()) {
            return Answer.Failure(reason = SyncEntriesReason.KeyNotFound)
        }


        return observeEntryModelsUseCase(includeDeletedEntries = true)
            .first()
            .map { entryModel ->
                SyncEntry(
                    id = entryModel.id,
                    name = entryModel.name,
                    issuer = entryModel.issuer,
                    secret = entryModel.secret,
                    uri = entryModel.uri,
                    period = entryModel.period,
                    note = entryModel.note,
                    type = entryModel.type,
                    position = entryModel.position,
                    modifyTime = entryModel.modifiedAt,
                    isDeleted = entryModel.isDeleted,
                    isSynced = entryModel.isSynced
                )
            }
            .let { syncEntries ->
                SyncEntriesCommand(
                    userId = user.id,
                    keys = keys.map { key ->
                        SyncKey(
                            id = key.id,
                            encryptedKey = key.encryptedKey
                        )
                    },
                    entries = syncEntries
                )
            }
            .let { command ->
                when (val result: Answer<Int, SyncEntriesReason> = commandBus.dispatch(command = command)) {
                    is Answer.Success -> {
                        AuthenticatorLogger.i(TAG, "Sync result: undecryptableCount=${result.data}")
                        if (result.data > 0) {
                            showUndecryptableEntriesWarning()
                        } else {
                            clearUndecryptableEntriesWarning()
                        }
                        Answer.Success(result.data)
                    }
                    is Answer.Failure -> Answer.Failure(result.reason)
                }
            }
    }

    private suspend fun showUndecryptableEntriesWarning() {
        val settings = observeSettingsUseCase().first()
        if (!settings.hasUndecryptableEntries) {
            updateSettingsUseCase(settings.copy(hasUndecryptableEntries = true))
        }
    }

    private suspend fun clearUndecryptableEntriesWarning() {
        val settings = observeSettingsUseCase().first()
        if (settings.hasUndecryptableEntries || settings.isUndecryptableEntriesWarningDismissed) {
            updateSettingsUseCase(
                settings.copy(
                    hasUndecryptableEntries = false,
                    isUndecryptableEntriesWarningDismissed = false
                )
            )
        }
    }

    private companion object {
        private const val TAG = "SyncEntriesModelsUseCase"
    }

}
