/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2022 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package ch.threema.app.voip.groupcall.sfu

import androidx.annotation.WorkerThread
import ch.threema.app.voip.groupcall.GroupCallDescription
import ch.threema.app.voip.groupcall.sfu.connection.GroupCallConnectionState
import ch.threema.app.voip.groupcall.sfu.webrtc.RemoteCtx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.webrtc.EglBase

internal interface GroupCall {
    /**
     * A signal that indicates whether a call is valid and can move on to the CONNECTED state after
     * connecting.
     */
    val callConfirmedSignal: Deferred<Unit>
    val callId: CallId
    val callLeftSignal: Deferred<Unit>
    val connectedSignal: CompletableDeferred<Pair<ULong, Set<ParticipantId>>>

    var description: GroupCallDescription
    var parameters: GroupCallParameters
    var dependencies: GroupCallDependencies

    var context: GroupCallContext

    @WorkerThread
    fun setRemoteCtx(participantId: ParticipantId, remote: RemoteCtx)
    @WorkerThread
    fun setParticipant(participant: LocalParticipant)

    /**
     * Update the participants in a call.
     *
     * Note: There is no guarantee for this call to be run on a specific thread, therefore, it must
     * be able to be run on any thread (and switch threads by itself if required)
     */
    @WorkerThread
    fun updateParticipants(update: ParticipantsUpdate)

    @WorkerThread
    fun updateCaptureStates()
    @WorkerThread
    fun updateState(state: GroupCallConnectionState)
    @WorkerThread
    fun setEglBase(eglBase: EglBase)

    @WorkerThread
    fun addTeardownRoutine(routine: suspend () -> Unit)

    @WorkerThread
    suspend fun teardown()

    // TODO(ANDR-1951): Should be Collections of type RemoteParticipant (resolve inheritance problems...)
    data class ParticipantsUpdate(val add: Set<NormalRemoteParticipant>, val remove: Set<NormalRemoteParticipant>) {
        companion object {
            fun empty() = ParticipantsUpdate(setOf(), setOf())

            fun addParticipant(participant: NormalRemoteParticipant): ParticipantsUpdate = ParticipantsUpdate(
                add = setOf(participant), remove = setOf()
            )

            fun removeParticipant(participant: NormalRemoteParticipant) = ParticipantsUpdate(
                add = setOf(), remove = setOf(participant)
            )
        }
    }
}
