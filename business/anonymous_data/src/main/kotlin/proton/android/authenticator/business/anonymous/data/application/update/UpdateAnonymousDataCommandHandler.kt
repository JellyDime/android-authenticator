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

package proton.android.authenticator.business.anonymous.data.application.update

import me.proton.core.network.domain.ApiException
import proton.android.authenticator.business.shared.domain.errors.ErrorLoggingUtils
import proton.android.authenticator.business.shared.domain.infrastructure.network.getErrorCode
import proton.android.authenticator.shared.common.domain.answers.Answer
import proton.android.authenticator.shared.common.domain.infrastructure.commands.CommandHandler
import proton.android.authenticator.shared.common.logs.AuthenticatorLogger
import javax.inject.Inject

internal class UpdateAnonymousDataCommandHandler @Inject constructor(
    private val updater: AnonymousDataUpdater
) : CommandHandler<UpdateAnonymousDataCommand, Unit, UpdateAnonymousDataReason> {

    override suspend fun handle(command: UpdateAnonymousDataCommand): Answer<Unit, UpdateAnonymousDataReason> = try {
        updater.update(anonymousData = command.anonymousData)
            .also { AuthenticatorLogger.i(TAG, "Successfully updated anonymous data") }
            .let(Answer<Unit, UpdateAnonymousDataReason>::Success)
    } catch (exception: ApiException) {
        if (exception.getErrorCode() == ERROR_CODE_INVALID_USER) {
            ErrorLoggingUtils.logAndReturnFailure(
                tag = TAG,
                throwable = exception,
                message = "Could not update anonymous data: Invalid user",
                reason = UpdateAnonymousDataReason.InvalidUser
            )
        } else {
            ErrorLoggingUtils.logAndReturnFailure(
                tag = TAG,
                throwable = exception,
                message = "Could not update anonymous data: Network error, do you have Internet connection?",
                reason = UpdateAnonymousDataReason.NetworkError
            )
        }
    }

    private companion object {

        private const val TAG = "UpdateAnonymousDataCommandHandler"

        private const val ERROR_CODE_INVALID_USER = 9106

    }

}
