package com.family.talkly.data.firebase

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.family.talkly.data.models.ChatMessage
import com.family.talkly.data.models.DEFAULT_FAMILY_MEMBERS
import com.family.talkly.data.models.FamilyMember
import com.family.talkly.data.models.MessageType
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import com.family.talkly.data.models.StatusItem
import com.family.talkly.data.models.UserStatusGroup
import com.family.talkly.data.models.StatusViewer
import com.family.talkly.data.models.StatusLiker

class FirebaseChatRepository(private val context: Context) {

    companion object {
        const val TAG = "Talkly_FirebaseChat"
        const val FIREBASE_PROJECT_ID = "familycallapp-e6b21"
        private const val CONTACTS_PREFS = "talkly_saved_contacts_prefs"
        private const val KEY_SAVED_CONTACTS_JSON = "saved_contacts_json"
        private const val KEY_DEMO_CLEARED = "demo_contacts_cleared"
        private const val KEY_STATUSES_JSON = "talkly_statuses_json"
        private const val KEY_BLOCKED_USERS = "talkly_blocked_user_ids"
    }

    private var firestore: FirebaseFirestore? = null
    private var membersListener: ListenerRegistration? = null
    private val contactPrefs = context.getSharedPreferences(CONTACTS_PREFS, Context.MODE_PRIVATE)

    // Real-time family members presence and status
    private val _familyMembers = MutableStateFlow<List<FamilyMember>>(emptyList())
    val familyMembers: StateFlow<List<FamilyMember>> = _familyMembers.asStateFlow()

    // Blocked Users state
    private val _blockedUserIds = MutableStateFlow<Set<String>>(emptySet())
    val blockedUserIds: StateFlow<Set<String>> = _blockedUserIds.asStateFlow()

    // Time offset for live testing 48-hour expiration logic
    private val _simulatedTimeOffsetMs = MutableStateFlow(0L)
    val simulatedTimeOffsetMs: StateFlow<Long> = _simulatedTimeOffsetMs.asStateFlow()

    // Message maps by family member id
    private val _messagesMap = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val messagesMap: StateFlow<Map<String, List<ChatMessage>>> = _messagesMap.asStateFlow()

    // Statuses flow (24-hour disappearing updates)
    private val _statuses = MutableStateFlow<List<StatusItem>>(emptyList())
    val statuses: StateFlow<List<StatusItem>> = _statuses.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        try {
            firestore = FirebaseFirestore.getInstance()
            Log.i(TAG, "Initialized Firebase Firestore for project $FIREBASE_PROJECT_ID")
            setupFirestorePresenceListener()
            setupFirestoreUsersVerificationListener()
        } catch (e: Exception) {
            Log.w(TAG, "Firebase Firestore init fallback mode: ${e.localizedMessage}")
        }
        loadInitialFamilyMembers()
        seedInitialFamilyChats()
        loadStatuses()
        loadBlockedUsers()
    }

    fun loadBlockedUsers() {
        val set = contactPrefs.getStringSet(KEY_BLOCKED_USERS, emptySet()) ?: emptySet()
        _blockedUserIds.value = set
    }

    fun blockUser(userId: String) {
        val updated = _blockedUserIds.value.toMutableSet()
        updated.add(userId)
        _blockedUserIds.value = updated
        contactPrefs.edit().putStringSet(KEY_BLOCKED_USERS, updated).apply()
    }

    fun unblockUser(userId: String) {
        val updated = _blockedUserIds.value.toMutableSet()
        updated.remove(userId)
        _blockedUserIds.value = updated
        contactPrefs.edit().putStringSet(KEY_BLOCKED_USERS, updated).apply()
    }

    fun isUserBlocked(userId: String): Boolean {
        return _blockedUserIds.value.contains(userId)
    }

    private fun loadInitialFamilyMembers() {
        val savedJson = contactPrefs.getString(KEY_SAVED_CONTACTS_JSON, null)
        val demoCleared = contactPrefs.getBoolean(KEY_DEMO_CLEARED, false)

        val list = mutableListOf<FamilyMember>()

        if (!demoCleared) {
            list.addAll(DEFAULT_FAMILY_MEMBERS)
        }

        if (!savedJson.isNullOrBlank()) {
            try {
                val jsonArray = org.json.JSONArray(savedJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val member = FamilyMember(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        relation = obj.optString("relation", "Contact"),
                        avatarUrl = if (obj.has("avatarUrl") && !obj.isNull("avatarUrl")) obj.getString("avatarUrl") else null,
                        status = obj.optString("status", "Available for call 💬"),
                        phone = obj.getString("phone"),
                        isOnline = obj.optBoolean("isOnline", true),
                        isTyping = false,
                        lastSeen = obj.optString("lastSeen", "Recently"),
                        unreadCount = obj.optInt("unreadCount", 0),
                        isPinned = obj.optBoolean("isPinned", false),
                        isRegisteredOnTalkly = obj.optBoolean("isRegisteredOnTalkly", false),
                        firebaseUid = if (obj.has("firebaseUid") && !obj.isNull("firebaseUid")) obj.getString("firebaseUid") else null
                    )
                    // Avoid duplicate IDs
                    if (list.none { it.id == member.id }) {
                        list.add(member)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse saved contacts JSON: ${e.message}")
            }
        }

        _familyMembers.value = list
    }

    private fun saveContactsToPrefs() {
        try {
            val jsonArray = org.json.JSONArray()
            _familyMembers.value.forEach { member ->
                val obj = org.json.JSONObject().apply {
                    put("id", member.id)
                    put("name", member.name)
                    put("relation", member.relation)
                    put("avatarUrl", member.avatarUrl)
                    put("status", member.status)
                    put("phone", member.phone)
                    put("isOnline", member.isOnline)
                    put("lastSeen", member.lastSeen)
                    put("unreadCount", member.unreadCount)
                    put("isPinned", member.isPinned)
                    put("isRegisteredOnTalkly", member.isRegisteredOnTalkly)
                    put("firebaseUid", member.firebaseUid)
                }
                jsonArray.put(obj)
            }
            contactPrefs.edit()
                .putString(KEY_SAVED_CONTACTS_JSON, jsonArray.toString())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving contacts to prefs: ${e.message}")
        }
    }

    fun searchTalklyUserByPhone(phone: String, onResult: (com.family.talkly.data.models.UserProfile?) -> Unit) {
        val cleanPhone = phone.trim().replace(" ", "").replace("-", "")
        if (cleanPhone.isBlank()) {
            onResult(null)
            return
        }

        try {
            firestore?.collection("users")
                ?.get()
                ?.addOnSuccessListener { snapshot ->
                    if (snapshot != null && !snapshot.isEmpty) {
                        for (doc in snapshot.documents) {
                            val userPhone = (doc.getString("phoneNumber") ?: "").trim().replace(" ", "").replace("-", "")
                            val docUid = doc.id
                            if (userPhone.contains(cleanPhone) || cleanPhone.contains(userPhone) || docUid == cleanPhone) {
                                val name = doc.getString("name") ?: "Talkly User"
                                val rawPhone = doc.getString("phoneNumber") ?: phone
                                val pic = doc.getString("profilePicUrl") ?: ""
                                val bio = doc.getString("bio") ?: "Available on Talkly 💬"
                                val profile = com.family.talkly.data.models.UserProfile(
                                    uid = docUid,
                                    name = name,
                                    phoneNumber = rawPhone,
                                    profilePicUrl = pic,
                                    bio = bio
                                )
                                onResult(profile)
                                return@addOnSuccessListener
                            }
                        }
                    }
                    onResult(null)
                }
                ?.addOnFailureListener {
                    onResult(null)
                } ?: onResult(null)
        } catch (e: Exception) {
            Log.w(TAG, "Search user exception: ${e.localizedMessage}")
            onResult(null)
        }
    }

    fun addNewContact(
        name: String,
        phone: String,
        relation: String = "Family Member",
        bio: String = "Available for call 💬",
        avatarUrl: String? = null,
        onComplete: ((FamilyMember) -> Unit)? = null
    ) {
        val cleanPhone = phone.trim()
        val customId = "contact_${cleanPhone.replace("+", "").replace(" ", "")}"

        val newMember = FamilyMember(
            id = customId,
            name = name.trim(),
            relation = relation.ifBlank { "Family Member" },
            avatarUrl = avatarUrl,
            status = bio.ifBlank { "Available on Talkly 💬" },
            phone = cleanPhone,
            isOnline = true,
            isTyping = false,
            lastSeen = "Online",
            unreadCount = 0,
            isPinned = false
        )

        val currentList = _familyMembers.value.toMutableList()
        // Remove existing if duplicate
        currentList.removeAll { it.id == customId || it.phone == cleanPhone }
        currentList.add(0, newMember) // Put at top
        _familyMembers.value = currentList

        saveContactsToPrefs()

        // Sync to Firestore 'family_members'
        try {
            firestore?.collection("family_members")
                ?.document(customId)
                ?.set(
                    mapOf(
                        "id" to customId,
                        "name" to name,
                        "relation" to relation,
                        "phone" to cleanPhone,
                        "status" to bio,
                        "avatarUrl" to avatarUrl,
                        "isOnline" to true
                    )
                )
        } catch (e: Exception) {
            Log.w(TAG, "Firestore sync contact failed: ${e.localizedMessage}")
        }

        onComplete?.invoke(newMember)
    }

    fun deleteContact(memberId: String) {
        val updatedList = _familyMembers.value.filter { it.id != memberId }
        _familyMembers.value = updatedList
        saveContactsToPrefs()

        try {
            firestore?.collection("family_members")?.document(memberId)?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting contact from Firestore: ${e.message}")
        }
    }

    fun clearDemoContacts() {
        contactPrefs.edit().putBoolean(KEY_DEMO_CLEARED, true).apply()
        val demoIds = setOf("mom", "dad", "grandma", "brother", "sister")
        val filteredList = _familyMembers.value.filter { it.id !in demoIds }
        _familyMembers.value = filteredList
        saveContactsToPrefs()
    }

    private fun setupFirestorePresenceListener() {
        try {
            membersListener = firestore?.collection("family_members")
                ?.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Listen failed for family_members: ${error.localizedMessage}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null && !snapshot.isEmpty) {
                        val updatedMembers = _familyMembers.value.map { member ->
                            val doc = snapshot.documents.firstOrNull { it.id == member.id }
                            if (doc != null) {
                                val online = doc.getBoolean("isOnline") ?: member.isOnline
                                val typing = doc.getBoolean("isTyping") ?: member.isTyping
                                val seen = doc.getString("lastSeen") ?: member.lastSeen
                                member.copy(
                                    isOnline = online,
                                    isTyping = typing,
                                    lastSeen = seen
                                )
                            } else {
                                member
                            }
                        }
                        _familyMembers.value = updatedMembers
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set up Firestore snapshot listener: ${e.localizedMessage}")
        }
    }

    fun setMemberTyping(memberId: String, isTyping: Boolean) {
        val currentList = _familyMembers.value.map { member ->
            if (member.id == memberId) {
                member.copy(isTyping = isTyping)
            } else {
                member
            }
        }
        _familyMembers.value = currentList

        try {
            firestore?.collection("family_members")
                ?.document(memberId)
                ?.set(mapOf("isTyping" to isTyping, "isOnline" to true), com.google.firebase.firestore.SetOptions.merge())
        } catch (e: Exception) {
            Log.w(TAG, "Firestore setTyping error: ${e.localizedMessage}")
        }
    }

    fun setMemberPresence(memberId: String, isOnline: Boolean, lastSeen: String = if (isOnline) "Online" else "Just now") {
        val currentList = _familyMembers.value.map { member ->
            if (member.id == memberId) {
                member.copy(isOnline = isOnline, lastSeen = lastSeen, isTyping = if (!isOnline) false else member.isTyping)
            } else {
                member
            }
        }
        _familyMembers.value = currentList

        try {
            firestore?.collection("family_members")
                ?.document(memberId)
                ?.set(
                    mapOf("isOnline" to isOnline, "lastSeen" to lastSeen, "isTyping" to if (!isOnline) false else false),
                    com.google.firebase.firestore.SetOptions.merge()
                )
        } catch (e: Exception) {
            Log.w(TAG, "Firestore setPresence error: ${e.localizedMessage}")
        }
    }

    fun toggleMemberPresence(memberId: String) {
        val member = _familyMembers.value.firstOrNull { it.id == memberId } ?: return
        val newOnline = !member.isOnline
        setMemberPresence(memberId, newOnline, if (newOnline) "Online" else "Today at 10:15 AM")
    }

    private var usersListener: ListenerRegistration? = null

    private fun setupFirestoreUsersVerificationListener() {
        try {
            usersListener = firestore?.collection("users")
                ?.addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Listen failed for users collection: ${error.localizedMessage}")
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        val registeredDocsMap = mutableMapOf<String, com.google.firebase.firestore.DocumentSnapshot>()
                        for (doc in snapshot.documents) {
                            val rawPhone = doc.getString("phoneNumber") ?: ""
                            val cleanPhone = rawPhone.trim().replace(" ", "").replace("-", "")
                            if (cleanPhone.isNotBlank()) {
                                registeredDocsMap[cleanPhone] = doc
                            }
                            registeredDocsMap[doc.id] = doc
                        }

                        val updatedMembers = _familyMembers.value.map { member ->
                            val cleanMemberPhone = member.phone.trim().replace(" ", "").replace("-", "")
                            val matchedDoc = registeredDocsMap[cleanMemberPhone] ?: registeredDocsMap[member.id]

                            if (matchedDoc != null) {
                                val uid = matchedDoc.id
                                val bio = matchedDoc.getString("bio") ?: member.status
                                val pic = matchedDoc.getString("profilePicUrl") ?: member.avatarUrl
                                val realName = matchedDoc.getString("name") ?: member.name
                                member.copy(
                                    name = if (realName.isNotBlank()) realName else member.name,
                                    isRegisteredOnTalkly = true,
                                    firebaseUid = uid,
                                    avatarUrl = if (!pic.isNull_or_empty_str(pic)) pic else member.avatarUrl,
                                    status = if (bio.isBlank()) "Available on Talkly 💬" else bio
                                )
                            } else {
                                member.copy(
                                    isRegisteredOnTalkly = false,
                                    firebaseUid = null,
                                    status = "User not registered on Talkly"
                                )
                            }
                        }
                        _familyMembers.value = updatedMembers
                        saveContactsToPrefs()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Could not set up users verification listener: ${e.localizedMessage}")
        }
    }

    private fun String?.isNull_or_empty_str(s: String?): Boolean = s == null || s.isEmpty()

    fun deleteChatHistory(memberId: String) {
        val updatedMap = _messagesMap.value.toMutableMap()
        updatedMap.remove(memberId)
        _messagesMap.value = updatedMap

        try {
            firestore?.collection("family_chats")
                ?.document(memberId)
                ?.collection("messages")
                ?.get()
                ?.addOnSuccessListener { snapshot ->
                    for (doc in snapshot.documents) {
                        doc.reference.delete()
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing chat history in Firestore: ${e.localizedMessage}")
        }
    }

    fun triggerSimulatedTypingReply(memberId: String) {
        // Disabled per requirements: No automated mock replies, bot responses, or local fallback test logic
    }

    private fun seedInitialFamilyChats() {
        // Disabled per requirements: No fake mock chats seeded locally
        _messagesMap.value = emptyMap()
    }

    fun getMessagesForMember(memberId: String): List<ChatMessage> {
        return _messagesMap.value[memberId] ?: emptyList()
    }

    fun markMessagesAsRead(memberId: String) {
        val currentMessages = _messagesMap.value[memberId] ?: return
        var updatedAny = false

        val updatedMessages = currentMessages.map { msg ->
            if (msg.senderId != "self" && !msg.isRead) {
                updatedAny = true
                val readMsg = msg.copy(isRead = true)

                // Sync read status to Firestore
                try {
                    firestore?.collection("family_chats")
                        ?.document(memberId)
                        ?.collection("messages")
                        ?.document(msg.id)
                        ?.update("isRead", true)
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating read receipt in Firestore: ${e.localizedMessage}")
                }

                readMsg
            } else {
                msg
            }
        }

        if (updatedAny) {
            val updatedMap = _messagesMap.value.toMutableMap()
            updatedMap[memberId] = updatedMessages
            _messagesMap.value = updatedMap
        }

        // Reset unread count for member in list and Firestore
        val member = _familyMembers.value.firstOrNull { it.id == memberId }
        if (member != null && member.unreadCount > 0) {
            val updatedMembers = _familyMembers.value.map { m ->
                if (m.id == memberId) m.copy(unreadCount = 0) else m
            }
            _familyMembers.value = updatedMembers

            try {
                firestore?.collection("family_members")
                    ?.document(memberId)
                    ?.update("unreadCount", 0)
            } catch (e: Exception) {
                Log.w(TAG, "Error resetting unread count in Firestore: ${e.localizedMessage}")
            }
        }
    }

    fun toggleMessageReaction(memberId: String, messageId: String, reactionEmoji: String) {
        val currentMessages = _messagesMap.value[memberId] ?: return
        val updatedMessages = currentMessages.map { msg ->
            if (msg.id == messageId) {
                val newReaction = if (msg.reaction == reactionEmoji) null else reactionEmoji
                val updatedMsg = msg.copy(reaction = newReaction)
                
                try {
                    firestore?.collection("family_chats")
                        ?.document(memberId)
                        ?.collection("messages")
                        ?.document(messageId)
                        ?.update("reaction", newReaction)
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating reaction in Firestore: ${e.localizedMessage}")
                }
                
                updatedMsg
            } else {
                msg
            }
        }
        
        val updatedMap = _messagesMap.value.toMutableMap()
        updatedMap[memberId] = updatedMessages
        _messagesMap.value = updatedMap
    }

    fun toggleStarMessage(memberId: String, messageId: String) {
        val currentMessages = _messagesMap.value[memberId] ?: return
        val updatedMessages = currentMessages.map { msg ->
            if (msg.id == messageId) {
                val newStarred = !msg.isStarred
                val updatedMsg = msg.copy(isStarred = newStarred)
                try {
                    firestore?.collection("family_chats")
                        ?.document(memberId)
                        ?.collection("messages")
                        ?.document(messageId)
                        ?.update("isStarred", newStarred)
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating star in Firestore: ${e.localizedMessage}")
                }
                updatedMsg
            } else {
                msg
            }
        }
        val updatedMap = _messagesMap.value.toMutableMap()
        updatedMap[memberId] = updatedMessages
        _messagesMap.value = updatedMap
    }

    fun togglePinMessage(memberId: String, messageId: String) {
        val currentMessages = _messagesMap.value[memberId] ?: return
        val updatedMessages = currentMessages.map { msg ->
            if (msg.id == messageId) {
                val newPinned = !msg.isPinned
                val updatedMsg = msg.copy(isPinned = newPinned)
                try {
                    firestore?.collection("family_chats")
                        ?.document(memberId)
                        ?.collection("messages")
                        ?.document(messageId)
                        ?.update("isPinned", newPinned)
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating pin message in Firestore: ${e.localizedMessage}")
                }
                updatedMsg
            } else {
                msg
            }
        }
        val updatedMap = _messagesMap.value.toMutableMap()
        updatedMap[memberId] = updatedMessages
        _messagesMap.value = updatedMap
    }

    fun togglePinMember(memberId: String) {
        val updatedMembers = _familyMembers.value.map { member ->
            if (member.id == memberId) {
                val newPinned = !member.isPinned
                try {
                    firestore?.collection("family_members")
                        ?.document(memberId)
                        ?.update("isPinned", newPinned)
                } catch (e: Exception) {
                    Log.w(TAG, "Error pinning member in Firestore: ${e.localizedMessage}")
                }
                member.copy(isPinned = newPinned)
            } else {
                member
            }
        }
        _familyMembers.value = updatedMembers
    }

    fun sendMessage(
        memberId: String,
        textContent: String,
        type: MessageType = MessageType.TEXT,
        mediaUrl: String? = null,
        forcedTimestamp: Long = System.currentTimeMillis(),
        replyToMessageId: String? = null,
        replyToSenderName: String? = null,
        replyToText: String? = null
    ) {
        val newMessage = ChatMessage(
            id = "msg_${System.currentTimeMillis()}",
            senderId = "self",
            senderName = "You",
            receiverId = memberId,
            messageType = type,
            textContent = textContent,
            mediaUrl = mediaUrl,
            timestamp = forcedTimestamp,
            replyToMessageId = replyToMessageId,
            replyToSenderName = replyToSenderName,
            replyToText = replyToText
        )

        val currentList = (_messagesMap.value[memberId] ?: emptyList()).toMutableList()
        currentList.add(newMessage)

        val updatedMap = _messagesMap.value.toMutableMap()
        updatedMap[memberId] = currentList
        _messagesMap.value = updatedMap

        // Sync to Firebase Firestore if available
        try {
            firestore?.collection("family_chats")
                ?.document(memberId)
                ?.collection("messages")
                ?.document(newMessage.id)
                ?.set(newMessage)
        } catch (e: Exception) {
            Log.w(TAG, "Firestore sync skipped: ${e.localizedMessage}")
        }
    }

    fun toggle48HourFastForward() {
        if (_simulatedTimeOffsetMs.value == 0L) {
            // Fast forward 50 hours into future
            _simulatedTimeOffsetMs.value = 50 * 60 * 60 * 1000L
        } else {
            // Reset to real time
            _simulatedTimeOffsetMs.value = 0L
        }
    }

    fun addExpiredMediaDemo(memberId: String) {
        val fiftyHoursAgo = System.currentTimeMillis() - (50 * 60 * 60 * 1000L)
        sendMessage(
            memberId = memberId,
            textContent = "Demo photo uploaded 50 hours ago",
            type = MessageType.IMAGE,
            mediaUrl = "https://images.unsplash.com/photo-1511895426328-dc8714191300?w=600&auto=format&fit=crop&q=80",
            forcedTimestamp = fiftyHoursAgo
        )
    }

    // --- 24-HOUR DISAPPEARING STATUS METHODS ---

    private fun loadStatuses() {
        val savedStatusesJson = contactPrefs.getString(KEY_STATUSES_JSON, null)
        val loadedList = mutableListOf<StatusItem>()

        if (!savedStatusesJson.isNullOrBlank()) {
            try {
                val jsonArray = org.json.JSONArray(savedStatusesJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val status = StatusItem(
                        id = obj.getString("id"),
                        userId = obj.getString("userId"),
                        userName = obj.getString("userName"),
                        userAvatarUrl = if (obj.has("userAvatarUrl") && !obj.isNull("userAvatarUrl")) obj.getString("userAvatarUrl") else null,
                        textContent = if (obj.has("textContent") && !obj.isNull("textContent")) obj.getString("textContent") else null,
                        photoUrl = if (obj.has("photoUrl") && !obj.isNull("photoUrl")) obj.getString("photoUrl") else null,
                        backgroundColorHex = obj.optString("backgroundColorHex", "#321C3B"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isSeen = obj.optBoolean("isSeen", false)
                    )
                    if (!status.isExpired(_simulatedTimeOffsetMs.value)) {
                        loadedList.add(status)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing saved statuses: ${e.message}")
            }
        }

        // Seed initial sample statuses if none exist
        if (loadedList.isEmpty()) {
            val now = System.currentTimeMillis()
            val twoHoursAgo = now - (2 * 60 * 60 * 1000L)
            val fourHoursAgo = now - (4 * 60 * 60 * 1000L)
            val sixHoursAgo = now - (6 * 60 * 60 * 1000L)

            val seedStatuses = listOf(
                StatusItem(
                    id = "status_safwan_1",
                    userId = "safwan",
                    userName = "Safwan",
                    userAvatarUrl = null,
                    textContent = "শুভ সকাল সবাইকে! 🌸 হ্যাভ আ গ্রেট ডে!",
                    backgroundColorHex = "#321C3B",
                    timestamp = twoHoursAgo,
                    isSeen = false
                ),
                StatusItem(
                    id = "status_dr_rashed_1",
                    userId = "dr_rashed",
                    userName = "Dr. Rashed",
                    userAvatarUrl = null,
                    textContent = "In medical consultation today. Stay safe & healthy! 🩺",
                    photoUrl = "https://images.unsplash.com/photo-1505751172876-fa1923c5c528?w=800&auto=format&fit=crop&q=80",
                    backgroundColorHex = "#004D40",
                    timestamp = fourHoursAgo,
                    isSeen = false
                ),
                StatusItem(
                    id = "status_sk_farid_1",
                    userId = "sk_farid",
                    userName = "Sk F A R I D",
                    userAvatarUrl = null,
                    textContent = "Working on new mobile app features! 💼💻",
                    backgroundColorHex = "#1A237E",
                    timestamp = sixHoursAgo,
                    isSeen = false
                )
            )
            loadedList.addAll(seedStatuses)
        }

        _statuses.value = loadedList
        saveStatusesToPrefs()
    }

    private fun saveStatusesToPrefs() {
        try {
            val jsonArray = org.json.JSONArray()
            _statuses.value.forEach { status ->
                val obj = org.json.JSONObject().apply {
                    put("id", status.id)
                    put("userId", status.userId)
                    put("userName", status.userName)
                    put("userAvatarUrl", status.userAvatarUrl)
                    put("textContent", status.textContent)
                    put("photoUrl", status.photoUrl)
                    put("backgroundColorHex", status.backgroundColorHex)
                    put("timestamp", status.timestamp)
                    put("isSeen", status.isSeen)
                }
                jsonArray.put(obj)
            }
            contactPrefs.edit().putString(KEY_STATUSES_JSON, jsonArray.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving statuses to prefs: ${e.message}")
        }
    }

    fun postStatus(
        userId: String = "self",
        userName: String = "You",
        userAvatarUrl: String? = null,
        textContent: String? = null,
        photoUrl: String? = null,
        backgroundColorHex: String = "#321C3B"
    ) {
        val sampleViewers: List<StatusViewer> = if (userId == "self") listOf(
            StatusViewer("dr_rashed", "Dr. Rashed", null, "10m ago"),
            StatusViewer("sk_farid", "Sk F A R I D", null, "25m ago"),
            StatusViewer("safwan", "Safwan", null, "1h ago")
        ) else emptyList()

        val sampleLikes: List<StatusLiker> = if (userId == "self") listOf(
            StatusLiker("dr_rashed", "Dr. Rashed", null),
            StatusLiker("safwan", "Safwan", null)
        ) else emptyList()

        val newStatus = StatusItem(
            id = "status_${System.currentTimeMillis()}",
            userId = userId,
            userName = userName,
            userAvatarUrl = userAvatarUrl,
            textContent = textContent,
            photoUrl = photoUrl,
            backgroundColorHex = backgroundColorHex,
            timestamp = System.currentTimeMillis(),
            isSeen = true, // own status is seen by self
            viewers = sampleViewers,
            likes = sampleLikes
        )

        val currentList = _statuses.value.toMutableList()
        currentList.add(0, newStatus)
        _statuses.value = currentList
        saveStatusesToPrefs()

        // Sync status to Firestore
        try {
            firestore?.collection("statuses")?.document(newStatus.id)?.set(newStatus)
        } catch (e: Exception) {
            Log.w(TAG, "Firestore status sync skipped: ${e.localizedMessage}")
        }
    }

    fun toggleStatusLike(statusId: String, currentUserId: String = "self", currentUserName: String = "You", currentUserAvatar: String? = null) {
        val updated = _statuses.value.map { status ->
            if (status.id == statusId) {
                val existingLike = status.likes.firstOrNull { it.userId == currentUserId }
                val newLikes = if (existingLike != null) {
                    status.likes.filter { it.userId != currentUserId }
                } else {
                    status.likes + StatusLiker(currentUserId, currentUserName, currentUserAvatar)
                }
                status.copy(likes = newLikes)
            } else {
                status
            }
        }
        _statuses.value = updated
        saveStatusesToPrefs()
    }

    fun markStatusAsSeen(statusId: String) {
        val updated = _statuses.value.map { status ->
            if (status.id == statusId) status.copy(isSeen = true) else status
        }
        _statuses.value = updated
        saveStatusesToPrefs()
    }

    fun getGroupedActiveStatuses(currentUserId: String = "self"): List<UserStatusGroup> {
        val activeStatuses = _statuses.value.filter { !it.isExpired(_simulatedTimeOffsetMs.value) }
        val groupedMap = activeStatuses.groupBy { it.userId }

        val groups = groupedMap.map { (uId, statusList) ->
            val firstItem = statusList.first()
            UserStatusGroup(
                userId = uId,
                userName = if (uId == currentUserId) "My Status" else firstItem.userName,
                userAvatarUrl = firstItem.userAvatarUrl,
                statuses = statusList.sortedBy { it.timestamp }
            )
        }.toMutableList()

        // Sort so "My Status" is first, then users with unseen status, then recent
        groups.sortWith { g1, g2 ->
            when {
                g1.userId == currentUserId -> -1
                g2.userId == currentUserId -> 1
                g1.hasUnseen && !g2.hasUnseen -> -1
                !g1.hasUnseen && g2.hasUnseen -> 1
                else -> (g2.statuses.lastOrNull()?.timestamp ?: 0L).compareTo(g1.statuses.lastOrNull()?.timestamp ?: 0L)
            }
        }

        return groups
    }
}
