package com.family.talkly.data.zego

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.family.talkly.data.models.CallDirection
import com.family.talkly.data.models.CallLog
import com.family.talkly.data.models.CallType
import com.family.talkly.data.models.FamilyMember
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CallState {
    IDLE,
    OUTGOING_CALLING,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    ACTIVE,
    ENDED
}

data class CurrentCallInfo(
    val state: CallState = CallState.IDLE,
    val callType: CallType = CallType.VIDEO,
    val targetMember: FamilyMember? = null,
    val roomID: String = "",
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isCameraOff: Boolean = false,
    val isFrontCamera: Boolean = true,
    val isSpeakerOn: Boolean = true,
    val zegoAppId: Long = ZegoCallEngineManager.ZEGO_APP_ID,
    val isZegoInitialized: Boolean = true
)

class ZegoCallEngineManager(private val context: Context) {

    companion object {
        const val TAG = "Talkly_ZegoEngine"
        val ZEGO_APP_ID: Long = try {
            com.family.talkly.BuildConfig.ZEGO_APP_ID.toString().toLongOrNull() ?: 2119647829L
        } catch (e: Exception) {
            2119647829L
        }
        val ZEGO_APP_SIGN: String = try {
            com.family.talkly.BuildConfig.ZEGO_APP_SIGN.ifEmpty { "f7b21c961d9ae91fc3ca9ee453c6ff4027c451e93e59ceaeeecfcafd29bdc872" }
        } catch (e: Exception) {
            "f7b21c961d9ae91fc3ca9ee453c6ff4027c451e93e59ceaeeecfcafd29bdc872"
        }
        const val FIREBASE_PROJECT_ID: String = "familycallapp-e6b21"
    }

    private val _callState = MutableStateFlow(CurrentCallInfo())
    val callState: StateFlow<CurrentCallInfo> = _callState.asStateFlow()

    private val _callLogs = MutableStateFlow<List<CallLog>>(emptyList())
    val callLogs: StateFlow<List<CallLog>> = _callLogs.asStateFlow()

    private var timerJob: Job? = null
    private var ringingTimeoutJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    var onCallLogAdded: ((CallLog) -> Unit)? = null

    private var firestore: FirebaseFirestore? = null
    private var callSignalListener: ListenerRegistration? = null
    private var currentListeningUserId: String? = null

    init {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                firestore = FirebaseFirestore.getInstance()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore init in ZegoCallEngineManager exception: ${e.localizedMessage}")
        }
        Log.i(TAG, "ZEGOCloud Express Engine configured with AppID: $ZEGO_APP_ID for Firebase Project $FIREBASE_PROJECT_ID")
    }

    fun startListeningForIncomingCalls(
        currentUserId: String,
        memberLookup: ((String) -> FamilyMember?)? = null
    ) {
        if (currentUserId.isBlank()) return
        if (currentListeningUserId == currentUserId && callSignalListener != null) return

        callSignalListener?.remove()
        currentListeningUserId = currentUserId

        try {
            callSignalListener = firestore?.collection("call_signals")
                ?.document(currentUserId)
                ?.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Listen failed for call signals: ${error.localizedMessage}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        val status = snapshot.getString("status") ?: return@addSnapshotListener
                        val callId = snapshot.getString("callId") ?: ""
                        val callerUid = snapshot.getString("callerUid") ?: ""
                        val callerName = snapshot.getString("callerName") ?: "Talkly User"
                        val callerPhone = snapshot.getString("callerPhone") ?: ""
                        val callerAvatar = snapshot.getString("callerAvatarUrl")
                        val callTypeStr = snapshot.getString("callType") ?: "VIDEO"
                        val callType = try { CallType.valueOf(callTypeStr) } catch (e: Exception) { CallType.VIDEO }
                        val timestamp = snapshot.getLong("timestamp") ?: 0L

                        // Ignore stale signals older than 45 seconds
                        if (System.currentTimeMillis() - timestamp > 45000) return@addSnapshotListener

                        when (status) {
                            "RINGING" -> {
                                if (callerUid != currentUserId && _callState.value.state == CallState.IDLE) {
                                    val foundMember = memberLookup?.invoke(callerUid)
                                    val targetMember = foundMember ?: FamilyMember(
                                        id = callerUid,
                                        name = callerName,
                                        relation = "Contact",
                                        avatarUrl = callerAvatar.takeIf { !it.isNullOrBlank() },
                                        phone = callerPhone,
                                        isOnline = true,
                                        isRegisteredOnTalkly = true,
                                        firebaseUid = callerUid
                                    )

                                    _callState.value = CurrentCallInfo(
                                        state = CallState.INCOMING_RINGING,
                                        callType = callType,
                                        targetMember = targetMember,
                                        roomID = callId,
                                        durationSeconds = 0
                                    )
                                }
                            }
                            "ACCEPTED" -> {
                                val currentState = _callState.value.state
                                if (currentState == CallState.OUTGOING_CALLING || currentState == CallState.OUTGOING_RINGING) {
                                    _callState.value = _callState.value.copy(state = CallState.ACTIVE)
                                    startCallTimer()
                                }
                            }
                            "DECLINED", "CANCELLED", "ENDED" -> {
                                if (_callState.value.state != CallState.IDLE) {
                                    endCallInternal("Call $status")
                                }
                            }
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up call signals listener: ${e.localizedMessage}")
        }
    }

    fun startOutgoingCall(
        member: FamilyMember,
        callType: CallType,
        currentUserUid: String,
        currentUserName: String,
        currentUserPhone: String,
        currentUserAvatarUrl: String?,
        isBlocked: Boolean = false
    ) {
        if (isBlocked) {
            Log.w(TAG, "Cannot start call: ${member.name} is blocked")
            Toast.makeText(context, "Call failed: User is blocked", Toast.LENGTH_SHORT).show()
            endCallInternal("User Blocked")
            return
        }

        val targetUid = member.firebaseUid.takeIf { !it.isNullOrBlank() } ?: member.id
        if (targetUid.isBlank()) {
            Log.w(TAG, "Cannot start call: ${member.name} has no valid UID")
            Toast.makeText(context, "User not registered on Talkly", Toast.LENGTH_SHORT).show()
            return
        }

        val roomID = "talkly_room_${currentUserUid}_${System.currentTimeMillis()}"

        _callState.value = CurrentCallInfo(
            state = CallState.OUTGOING_CALLING,
            callType = callType,
            targetMember = member,
            roomID = roomID,
            durationSeconds = 0,
            isMuted = false,
            isCameraOff = false,
            isFrontCamera = true,
            isSpeakerOn = true
        )
        Log.d(TAG, "Starting outgoing ${callType.name} call to Firebase UID: $targetUid (${member.name}) in room $roomID")

        val signalData = mapOf(
            "callId" to roomID,
            "callerUid" to currentUserUid,
            "callerName" to currentUserName,
            "callerPhone" to currentUserPhone,
            "callerAvatarUrl" to (currentUserAvatarUrl ?: ""),
            "calleeUid" to targetUid,
            "callType" to callType.name,
            "status" to "RINGING",
            "timestamp" to System.currentTimeMillis()
        )

        try {
            firestore?.collection("call_signals")?.document(targetUid)?.set(signalData)
            if (currentUserUid.isNotBlank()) {
                firestore?.collection("call_signals")?.document(currentUserUid)?.set(signalData)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write call signal to Firestore: ${e.localizedMessage}")
        }

        ringingTimeoutJob?.cancel()

        scope.launch {
            delay(1500)
            if (_callState.value.state == CallState.OUTGOING_CALLING) {
                _callState.value = _callState.value.copy(state = CallState.OUTGOING_RINGING)
            }
        }

        ringingTimeoutJob = scope.launch {
            delay(30000)
            val currentState = _callState.value.state
            if (currentState == CallState.OUTGOING_CALLING || currentState == CallState.OUTGOING_RINGING) {
                Log.d(TAG, "Call timed out after 30s: No answer from ${member.name}")
                Toast.makeText(context, "No answer from ${member.name}", Toast.LENGTH_SHORT).show()
                cancelCallSignal(targetUid, currentUserUid)
                endCall(currentUserUid)
            }
        }
    }

    fun triggerIncomingCall(member: FamilyMember, callType: CallType) {
        val roomID = "incoming_room_${member.id}"
        _callState.value = CurrentCallInfo(
            state = CallState.INCOMING_RINGING,
            callType = callType,
            targetMember = member,
            roomID = roomID,
            durationSeconds = 0
        )
    }

    fun acceptCall(currentUserUid: String? = null) {
        ringingTimeoutJob?.cancel()
        val current = _callState.value
        val targetUid = current.targetMember?.firebaseUid ?: current.targetMember?.id ?: ""

        _callState.value = current.copy(state = CallState.ACTIVE)
        startCallTimer()

        val updateData = mapOf("status" to "ACCEPTED")
        try {
            if (targetUid.isNotBlank()) {
                firestore?.collection("call_signals")?.document(targetUid)?.update(updateData)
            }
            if (!currentUserUid.isNullOrBlank()) {
                firestore?.collection("call_signals")?.document(currentUserUid)?.update(updateData)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update call signal ACCEPTED: ${e.localizedMessage}")
        }
    }

    fun declineCall(currentUserUid: String? = null) {
        ringingTimeoutJob?.cancel()
        val current = _callState.value
        val member = current.targetMember
        val targetUid = member?.firebaseUid ?: member?.id ?: ""

        if (member != null) {
            addCallLog(
                CallLog(
                    id = "call_${System.currentTimeMillis()}",
                    memberId = member.id,
                    memberName = member.name,
                    direction = CallDirection.MISSED,
                    callType = current.callType,
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = 0
                )
            )
        }

        val updateData = mapOf("status" to "DECLINED")
        try {
            if (targetUid.isNotBlank()) {
                firestore?.collection("call_signals")?.document(targetUid)?.update(updateData)
            }
            if (!currentUserUid.isNullOrBlank()) {
                firestore?.collection("call_signals")?.document(currentUserUid)?.update(updateData)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update call signal DECLINED: ${e.localizedMessage}")
        }

        endCallInternal("Call Declined")
    }

    fun endCall(currentUserUid: String? = null) {
        ringingTimeoutJob?.cancel()
        val current = _callState.value
        val member = current.targetMember
        val targetUid = member?.firebaseUid ?: member?.id ?: ""

        if (member != null) {
            val direction = if (current.state == CallState.OUTGOING_RINGING || current.state == CallState.OUTGOING_CALLING) CallDirection.OUTGOING else CallDirection.INCOMING
            addCallLog(
                CallLog(
                    id = "call_${System.currentTimeMillis()}",
                    memberId = member.id,
                    memberName = member.name,
                    direction = direction,
                    callType = current.callType,
                    timestamp = System.currentTimeMillis(),
                    durationSeconds = current.durationSeconds
                )
            )
        }

        cancelCallSignal(targetUid, currentUserUid)
        endCallInternal("Call Ended")
    }

    private fun cancelCallSignal(targetUid: String, currentUserUid: String?) {
        val updateData = mapOf("status" to "ENDED")
        try {
            if (targetUid.isNotBlank()) {
                firestore?.collection("call_signals")?.document(targetUid)?.update(updateData)
            }
            if (!currentUserUid.isNullOrBlank()) {
                firestore?.collection("call_signals")?.document(currentUserUid)?.update(updateData)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update call signal ENDED: ${e.localizedMessage}")
        }
    }

    private fun endCallInternal(reason: String) {
        ringingTimeoutJob?.cancel()
        timerJob?.cancel()
        _callState.value = _callState.value.copy(state = CallState.ENDED)
        scope.launch {
            delay(1000)
            _callState.value = CurrentCallInfo(state = CallState.IDLE)
        }
    }

    fun toggleMute() {
        val current = _callState.value
        _callState.value = current.copy(isMuted = !current.isMuted)
    }

    fun toggleCamera() {
        val current = _callState.value
        _callState.value = current.copy(isCameraOff = !current.isCameraOff)
    }

    fun flipCamera() {
        val current = _callState.value
        _callState.value = current.copy(isFrontCamera = !current.isFrontCamera)
    }

    fun toggleSpeaker() {
        val current = _callState.value
        _callState.value = current.copy(isSpeakerOn = !current.isSpeakerOn)
    }

    private fun startCallTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (_callState.value.state == CallState.ACTIVE) {
                delay(1000)
                _callState.value = _callState.value.copy(
                    durationSeconds = _callState.value.durationSeconds + 1
                )
            }
        }
    }

    private fun addCallLog(log: CallLog) {
        val list = _callLogs.value.toMutableList()
        list.add(0, log)
        _callLogs.value = list
        onCallLogAdded?.invoke(log)
    }
}
