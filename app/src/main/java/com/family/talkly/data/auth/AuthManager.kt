package com.family.talkly.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.family.talkly.data.models.UserProfile
import com.family.talkly.util.MediaCompressorAndUploader
import com.family.talkly.util.PhoneUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AuthState {
    object InitialCheck : AuthState()
    object Unauthenticated : AuthState()
    data class VerificationInProgress(val message: String = "Authenticating...") : AuthState()
    data class ProfileSetupRequired(val uid: String, val phoneNumber: String) : AuthState()
    data class Authenticated(val profile: UserProfile) : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthManager(private val context: Context) {

    companion object {
        private const val TAG = "AuthManager"
        private const val PREFS_NAME = "talkly_auth_session"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_UID = "user_uid"
        private const val KEY_NAME = "user_name"
        private const val KEY_PHONE = "user_phone"
        private const val KEY_PROFILE_PIC = "user_profile_pic"
        private const val KEY_BIO = "user_bio"

        /**
         * Converts phone number into a deterministic internal email address for Firebase Auth
         */
        fun getInternalEmail(phoneNumber: String): String {
            val suffix = PhoneUtils.extractPhoneSuffix(phoneNumber)
            return if (suffix.length >= 7) {
                "p_${suffix}@talkly.app"
            } else {
                val cleanNumber = phoneNumber.replace("+", "").replace(" ", "").replace("-", "").trim()
                "${cleanNumber}@talkly.app"
            }
        }

        fun getCandidateInternalEmails(phoneNumber: String): List<String> {
            val suffix = PhoneUtils.extractPhoneSuffix(phoneNumber)
            val cleanNumber = phoneNumber.replace("+", "").replace(" ", "").replace("-", "").trim()
            val candidates = mutableListOf<String>()

            if (suffix.length >= 7) {
                candidates.add("p_${suffix}@talkly.app")
            }
            if (cleanNumber.isNotBlank()) {
                candidates.add("${cleanNumber}@talkly.app")
            }
            if (suffix.isNotBlank()) {
                candidates.add("0${suffix}@talkly.app")
                candidates.add("880${suffix}@talkly.app")
                candidates.add("+880${suffix}@talkly.app")
            }
            return candidates.distinct()
        }
    }

    private fun ensureFirebase() {
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            try {
                com.google.firebase.FirebaseApp.initializeApp(context)
            } catch (e: Exception) {
                try {
                    val options = com.google.firebase.FirebaseOptions.Builder()
                        .setApplicationId("1:688875089801:android:07f27e3cf40ca2af913b58")
                        .setGcmSenderId("688875089801")
                        .setProjectId("familycallapp-e6b21")
                        .setApiKey("AIzaSyCmmYWBqRREKmhNaBvc1drcTJib0EuMgF0")
                        .build()
                    com.google.firebase.FirebaseApp.initializeApp(context, options)
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed fallback Firebase init in AuthManager: ${ex.message}")
                }
            }
        }
    }

    private fun getFirebaseAuth(): FirebaseAuth {
        ensureFirebase()
        return FirebaseAuth.getInstance()
    }

    private fun getFirestore(): FirebaseFirestore {
        ensureFirebase()
        return FirebaseFirestore.getInstance()
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow<AuthState>(AuthState.InitialCheck)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        checkCurrentSession()
    }

    fun clearError() {
        if (_authState.value is AuthState.Error) {
            _authState.value = AuthState.Unauthenticated
        }
    }

    /**
     * Checks local session and Firebase Auth current user to resume session
     */
    fun checkCurrentSession() {
        try {
            val isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
            val savedUid = prefs.getString(KEY_UID, null)
            val firebaseUser = try { getFirebaseAuth().currentUser } catch (e: Exception) { null }

            if (isLoggedIn && !savedUid.isNullOrEmpty()) {
                val name = prefs.getString(KEY_NAME, "") ?: ""
                val phone = prefs.getString(KEY_PHONE, "") ?: ""
                var pic = prefs.getString(KEY_PROFILE_PIC, "") ?: ""
                val bio = prefs.getString(KEY_BIO, "Available on Talkly 💬") ?: "Available on Talkly 💬"

                // Check if pic is a content:// URI and convert to persistent internal avatar file if available
                if (pic.startsWith("content://") || pic.isBlank()) {
                    val avatarDir = File(context.filesDir, "profile_avatars")
                    val internalFile = File(avatarDir, "avatar_${savedUid}.jpg")
                    if (internalFile.exists()) {
                        pic = Uri.fromFile(internalFile).toString()
                    }
                }

                if (name.isNotBlank()) {
                    val profile = UserProfile(
                        uid = savedUid,
                        name = name,
                        phoneNumber = phone,
                        profilePicUrl = pic,
                        bio = bio
                    )
                    _authState.value = AuthState.Authenticated(profile)
                } else {
                    _authState.value = AuthState.ProfileSetupRequired(savedUid, phone)
                }

                // Always sync latest user profile from Firestore in background
                checkUserProfileInFirestore(savedUid, phone)
            } else if (firebaseUser != null) {
                val uid = firebaseUser.uid
                val phone = firebaseUser.phoneNumber ?: prefs.getString(KEY_PHONE, "") ?: ""
                checkUserProfileInFirestore(uid, phone)
            } else {
                _authState.value = AuthState.Unauthenticated
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking current session: ${e.message}")
            _authState.value = AuthState.Unauthenticated
        }
    }

    /**
     * Registers a new user with Mobile Phone Number and Password
     */
    fun signUpWithPhoneAndPassword(
        phoneNumber: String,
        password: String,
        name: String,
        profilePicUrl: String = "https://images.unsplash.com/photo-1534528741775-53994a69daeb?w=300&auto=format&fit=crop",
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (phoneNumber.isBlank() || password.isBlank() || name.isBlank()) {
            val err = "Please enter your name, phone number, and password."
            _authState.value = AuthState.Error(err)
            onError(err)
            return
        }

        if (password.length < 6) {
            val err = "Password must be at least 6 characters long."
            _authState.value = AuthState.Error(err)
            onError(err)
            return
        }

        val internalEmail = getInternalEmail(phoneNumber)
        _authState.value = AuthState.VerificationInProgress("Creating user account...")

        try {
            getFirebaseAuth().createUserWithEmailAndPassword(internalEmail, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val user = task.result?.user
                        val uid = user?.uid
                        if (uid != null) {
                            Log.d(TAG, "Registration successful for phone $phoneNumber ($internalEmail), UID: $uid")
                            saveUserProfileAndAuthenticate(uid, name, phoneNumber, profilePicUrl, onSuccess, onError)
                        } else {
                            val err = "Account created, but user session was null."
                            _authState.value = AuthState.Error(err)
                            onError(err)
                        }
                    } else {
                        val rawErr = task.exception?.message ?: "Registration failed."
                        val formatted = when {
                            rawErr.contains("already in use", ignoreCase = true) ||
                            rawErr.contains("email-already-in-use", ignoreCase = true) ->
                                "An account with this phone number already exists. Please sign in instead."
                            rawErr.contains("badly formatted", ignoreCase = true) ||
                            rawErr.contains("invalid-email", ignoreCase = true) ->
                                "Invalid mobile phone number format."
                            rawErr.contains("weak-password", ignoreCase = true) ->
                                "Password is too weak. Please use at least 6 characters."
                            else -> rawErr
                        }
                        _authState.value = AuthState.Error(formatted)
                        onError(formatted)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Sign up exception: ${e.localizedMessage}", e)
            val err = e.localizedMessage ?: "Registration error. Please try again."
            _authState.value = AuthState.Error(err)
            onError(err)
        }
    }

    /**
     * Signs in an existing user with Mobile Phone Number and Password, with fallback for legacy email formats
     */
    fun signInWithPhoneAndPassword(
        phoneNumber: String,
        password: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (phoneNumber.isBlank() || password.isBlank()) {
            val err = "Please enter both mobile phone number and password."
            _authState.value = AuthState.Error(err)
            onError(err)
            return
        }

        val candidateEmails = getCandidateInternalEmails(phoneNumber)
        _authState.value = AuthState.VerificationInProgress("Signing in...")

        fun trySignIn(index: Int) {
            if (index >= candidateEmails.size) {
                val formatted = "No account found with this phone number or incorrect password. Please check your credentials."
                _authState.value = AuthState.Error(formatted)
                onError(formatted)
                return
            }

            val currentEmail = candidateEmails[index]
            try {
                getFirebaseAuth().signInWithEmailAndPassword(currentEmail, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            val user = task.result?.user
                            val uid = user?.uid
                            if (uid != null) {
                                Log.d(TAG, "Sign in successful for phone $phoneNumber ($currentEmail), UID: $uid")
                                checkUserProfileInFirestore(uid, phoneNumber)
                                onSuccess()
                            } else {
                                val err = "Authentication succeeded but user session is null."
                                _authState.value = AuthState.Error(err)
                                onError(err)
                            }
                        } else {
                            val rawErr = task.exception?.message ?: ""
                            val isNotFoundOrInvalid = rawErr.contains("no user record", ignoreCase = true) ||
                                    rawErr.contains("user-not-found", ignoreCase = true) ||
                                    rawErr.contains("invalid-credential", ignoreCase = true) ||
                                    rawErr.contains("wrong-password", ignoreCase = true) ||
                                    rawErr.contains("invalid password", ignoreCase = true)

                            if (isNotFoundOrInvalid && index + 1 < candidateEmails.size) {
                                Log.d(TAG, "Email $currentEmail failed, trying next candidate...")
                                trySignIn(index + 1)
                            } else {
                                val formatted = when {
                                    rawErr.contains("no user record", ignoreCase = true) ||
                                    rawErr.contains("user-not-found", ignoreCase = true) ->
                                        "No account found with this phone number. Please register first."
                                    rawErr.contains("invalid-credential", ignoreCase = true) ||
                                    rawErr.contains("wrong-password", ignoreCase = true) ||
                                    rawErr.contains("invalid password", ignoreCase = true) ->
                                        "Incorrect password. Please try again."
                                    else -> rawErr.ifBlank { "Authentication failed." }
                                }
                                _authState.value = AuthState.Error(formatted)
                                onError(formatted)
                            }
                        }
                    }
            } catch (e: Exception) {
                if (index + 1 < candidateEmails.size) {
                    trySignIn(index + 1)
                } else {
                    Log.e(TAG, "Sign in exception: ${e.localizedMessage}", e)
                    val err = e.localizedMessage ?: "Sign in error. Please try again."
                    _authState.value = AuthState.Error(err)
                    onError(err)
                }
            }
        }

        trySignIn(0)
    }

    /**
     * Triggers Firebase sendPasswordResetEmail using mapped internal email address for phone number
     */
    fun sendPasswordResetForPhone(
        phoneNumber: String,
        onSuccess: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (phoneNumber.isBlank()) {
            val err = "Please enter your mobile phone number."
            onError(err)
            return
        }

        val candidateEmails = getCandidateInternalEmails(phoneNumber)

        fun tryReset(index: Int) {
            if (index >= candidateEmails.size) {
                onError("No registered account found with mobile number $phoneNumber.")
                return
            }

            val currentEmail = candidateEmails[index]
            try {
                getFirebaseAuth().sendPasswordResetEmail(currentEmail)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(TAG, "Password reset email sent to internal email: $currentEmail for phone $phoneNumber")
                            onSuccess("Password reset instructions sent for account linked to $phoneNumber.")
                        } else {
                            if (index + 1 < candidateEmails.size) {
                                tryReset(index + 1)
                            } else {
                                val rawErr = task.exception?.message ?: "Password reset failed."
                                val formatted = when {
                                    rawErr.contains("no user record", ignoreCase = true) ||
                                    rawErr.contains("user-not-found", ignoreCase = true) ->
                                        "No registered account found with mobile number $phoneNumber."
                                    rawErr.contains("invalid-email", ignoreCase = true) ->
                                        "Invalid phone number format."
                                    else -> rawErr
                                }
                                onError(formatted)
                            }
                        }
                    }
            } catch (e: Exception) {
                if (index + 1 < candidateEmails.size) {
                    tryReset(index + 1)
                } else {
                    onError(e.localizedMessage ?: "Failed to request password reset. Please try again.")
                }
            }
        }

        tryReset(0)
    }

    private fun saveUserProfileAndAuthenticate(
        uid: String,
        name: String,
        phoneNumber: String,
        profilePicUrl: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val bio = "Available on Talkly 💬"
        val phoneSuffix = PhoneUtils.extractPhoneSuffix(phoneNumber)
        saveLocalSession(uid, name, phoneNumber, profilePicUrl, bio)
        val profile = UserProfile(
            uid = uid,
            name = name,
            phoneNumber = phoneNumber,
            phoneSuffix = phoneSuffix,
            profilePicUrl = profilePicUrl,
            bio = bio
        )
        _authState.value = AuthState.Authenticated(profile)
        onSuccess()

        val profileMap = mapOf(
            "uid" to uid,
            "name" to name,
            "phoneNumber" to phoneNumber,
            "phoneSuffix" to phoneSuffix,
            "email" to getInternalEmail(phoneNumber),
            "profilePicUrl" to profilePicUrl,
            "bio" to bio,
            "createdAt" to System.currentTimeMillis(),
            "updatedAt" to System.currentTimeMillis()
        )

        try {
            getFirestore().collection("users").document(uid)
                .set(profileMap, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "User profile saved to Firestore successfully")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to write user profile to Firestore: ${e.localizedMessage}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore write exception: ${e.localizedMessage}")
        }
    }

    /**
     * Checks Firestore 'users/{uid}' collection to see if user has completed profile setup
     */
    private fun checkUserProfileInFirestore(uid: String, phoneNumber: String) {
        // Upsert user phone and suffix on login
        val digitsOnly = phoneNumber.filter { it.isDigit() }
        val suffix = if (digitsOnly.length >= 10) digitsOnly.takeLast(10) else digitsOnly

        val userData = mapOf(
            "uid" to uid,
            "phoneNumber" to phoneNumber,
            "phoneSuffix" to suffix,
            "updatedAt" to System.currentTimeMillis()
        )

        try {
            getFirestore().collection("users").document(uid)
                .set(userData, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Upserted login phone info for uid $uid (phone: $phoneNumber, suffix: $suffix)")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed upserting login phone info: ${e.localizedMessage}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Login upsert exception: ${e.localizedMessage}")
        }

        try {
            val firebaseUser = getFirebaseAuth().currentUser
            val firebaseAuthName = firebaseUser?.displayName ?: ""
            val localName = prefs.getString(KEY_NAME, "") ?: ""

            val db = getFirestore()
            db.collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    val docName = if (doc != null && doc.exists()) (doc.getString("name") ?: "") else ""
                    val finalName = if (docName.isNotBlank()) docName else if (firebaseAuthName.isNotBlank()) firebaseAuthName else localName

                    if (finalName.isNotBlank()) {
                        val phone = (if (doc != null && doc.exists()) doc.getString("phoneNumber") else null) ?: phoneNumber
                        val docSuffix = (if (doc != null && doc.exists()) doc.getString("phoneSuffix") else null) ?: PhoneUtils.extractPhoneSuffix(phone)
                        val docPic = (if (doc != null && doc.exists()) doc.getString("profilePicUrl") else null) ?: ""
                        val bio = (if (doc != null && doc.exists()) doc.getString("bio") else null) ?: "Available on Talkly 💬"

                        // Local stored picture fallback check
                        val localStoredPic = prefs.getString(KEY_PROFILE_PIC, "") ?: ""
                        val finalPic = if (docPic.startsWith("http://") || docPic.startsWith("https://") || docPic.startsWith("data:")) {
                            docPic
                        } else if (localStoredPic.isNotBlank() && !localStoredPic.startsWith("content://")) {
                            localStoredPic
                        } else {
                            docPic
                        }

                        val profile = UserProfile(
                            uid = uid,
                            name = finalName,
                            phoneNumber = phone,
                            phoneSuffix = docSuffix,
                            profilePicUrl = finalPic,
                            bio = bio
                        )
                        saveLocalSession(uid, finalName, phone, finalPic, bio)
                        _authState.value = AuthState.Authenticated(profile)

                        // If Firestore was missing name, sync it back
                        if (docName.isBlank()) {
                            db.collection("users").document(uid).update("name", finalName)
                        }
                    } else {
                        saveLocalSession(uid, "", phoneNumber, "", "")
                        _authState.value = AuthState.ProfileSetupRequired(uid, phoneNumber)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Firestore user profile read failed: ${e.localizedMessage}")
                    val fallbackName = if (firebaseAuthName.isNotBlank()) firebaseAuthName else localName
                    if (fallbackName.isNotBlank()) {
                        val profile = UserProfile(
                            uid = uid,
                            name = fallbackName,
                            phoneNumber = phoneNumber,
                            phoneSuffix = PhoneUtils.extractPhoneSuffix(phoneNumber),
                            profilePicUrl = prefs.getString(KEY_PROFILE_PIC, "") ?: "",
                            bio = "Available on Talkly 💬"
                        )
                        saveLocalSession(uid, fallbackName, phoneNumber, profile.profilePicUrl, profile.bio)
                        _authState.value = AuthState.Authenticated(profile)
                    } else {
                        _authState.value = AuthState.ProfileSetupRequired(uid, phoneNumber)
                    }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore user profile exception: ${e.localizedMessage}")
        }
    }

    /**
     * Saves name, bio and profile picture to Firestore 'users' collection and local session
     */
    fun saveUserProfile(
        name: String,
        profilePicUrl: String,
        bio: String = "Available on Talkly 💬",
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val currentState = _authState.value
        var uid = ""
        var phone = ""

        if (currentState is AuthState.ProfileSetupRequired) {
            uid = currentState.uid
            phone = currentState.phoneNumber
        } else {
            uid = prefs.getString(KEY_UID, "") ?: ""
            phone = prefs.getString(KEY_PHONE, "") ?: ""
        }

        if (uid.isBlank()) {
            val err = "User session invalid. Please sign in again."
            _authState.value = AuthState.Error(err)
            onError(err)
            return
        }

        val phoneSuffix = PhoneUtils.extractPhoneSuffix(phone)

        // Persistent image processing for local Uri strings (content:// or file://)
        var persistentLocalPicUrl = profilePicUrl
        var localImageFile: File? = null

        if (profilePicUrl.startsWith("content://") || (profilePicUrl.startsWith("file://") && !profilePicUrl.contains("profile_avatars"))) {
            try {
                val uri = Uri.parse(profilePicUrl)
                val avatarDir = File(context.filesDir, "profile_avatars").apply { mkdirs() }
                val destFile = File(avatarDir, "avatar_${uid}.jpg")

                val inputStream = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(context.contentResolver.openInputStream(uri), null, options)

                    val maxDim = maxOf(options.outWidth, options.outHeight)
                    var sampleSize = 1
                    while (maxDim / sampleSize > 1080) { sampleSize *= 2 }

                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions)
                    inputStream.close()

                    if (bitmap != null) {
                        val outStream = FileOutputStream(destFile)
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outStream)
                        outStream.flush()
                        outStream.close()
                        bitmap.recycle()

                        localImageFile = destFile
                        persistentLocalPicUrl = Uri.fromFile(destFile).toString()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to copy/compress local profile image: ${e.localizedMessage}")
            }
        }

        // Prepare universally viewable URL/data string for Firestore remote users
        var firestorePicUrl = persistentLocalPicUrl
        val targetUploadFile = localImageFile ?: if (persistentLocalPicUrl.startsWith("file://")) {
            try { File(Uri.parse(persistentLocalPicUrl).path ?: "") } catch (e: Exception) { null }
        } else null

        if (targetUploadFile != null && targetUploadFile.exists()) {
            firestorePicUrl = MediaCompressorAndUploader(context).encodeFileToBase64(targetUploadFile)
        }

        // Save local session immediately with persistent local file or HTTP URL
        saveLocalSession(uid, name, phone, persistentLocalPicUrl, bio)

        // Update FirebaseAuth displayName
        try {
            getFirebaseAuth().currentUser?.updateProfile(
                com.google.firebase.auth.UserProfileChangeRequest.Builder()
                    .setDisplayName(name)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseAuth updateProfile exception: ${e.localizedMessage}")
        }

        val profile = UserProfile(
            uid = uid,
            name = name,
            phoneNumber = phone,
            phoneSuffix = phoneSuffix,
            profilePicUrl = persistentLocalPicUrl,
            bio = bio
        )
        _authState.value = AuthState.Authenticated(profile)
        onSuccess()

        // Write document to Firestore using universally accessible firestorePicUrl
        val profileMap = mutableMapOf<String, Any>(
            "uid" to uid,
            "name" to name,
            "phoneNumber" to phone,
            "phoneSuffix" to phoneSuffix,
            "email" to getInternalEmail(phone),
            "profilePicUrl" to firestorePicUrl,
            "bio" to bio,
            "updatedAt" to System.currentTimeMillis()
        )

        val familyMemberMap = mutableMapOf<String, Any>(
            "id" to uid,
            "firebaseUid" to uid,
            "name" to name,
            "phone" to phone,
            "avatarUrl" to firestorePicUrl,
            "status" to bio,
            "lastSeen" to System.currentTimeMillis()
        )

        try {
            getFirestore().collection("users").document(uid)
                .set(profileMap, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Saved user profile to Firestore users successfully")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Firestore user profile write failed: ${e.localizedMessage}")
                }

            getFirestore().collection("family_members").document(uid)
                .set(familyMemberMap, SetOptions.merge())
                .addOnSuccessListener {
                    Log.d(TAG, "Saved user profile to Firestore family_members successfully")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Firestore family_members write failed: ${e.localizedMessage}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore user save exception: ${e.localizedMessage}")
        }

        // Upload to Firebase Storage in background coroutine so other users receive HTTP URL
        if (targetUploadFile != null && targetUploadFile.exists()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val uploader = MediaCompressorAndUploader(context)
                    val remotePath = "profile_pictures/${uid}.jpg"
                    val downloadUrl = uploader.uploadToFirebaseStorage(targetUploadFile, remotePath) { _, _ -> }

                    if (downloadUrl.startsWith("http://") || downloadUrl.startsWith("https://") || downloadUrl.startsWith("data:")) {
                        Log.d(TAG, "Uploaded profile picture to Firebase Storage / Encoded: $downloadUrl")
                        // Update local session
                        saveLocalSession(uid, name, phone, downloadUrl, bio)
                        val updatedProfile = profile.copy(profilePicUrl = downloadUrl)
                        _authState.value = AuthState.Authenticated(updatedProfile)

                        // Update Firestore user and family_members document
                        getFirestore().collection("users").document(uid)
                            .update("profilePicUrl", downloadUrl)
                            .addOnSuccessListener {
                                Log.d(TAG, "Updated Firestore users profilePicUrl")
                            }

                        getFirestore().collection("family_members").document(uid)
                            .update("avatarUrl", downloadUrl)
                            .addOnSuccessListener {
                                Log.d(TAG, "Updated Firestore family_members avatarUrl")
                            }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Firebase Storage profile upload failed: ${e.localizedMessage}")
                }
            }
        }
    }

    private fun saveLocalSession(uid: String, name: String, phone: String, pic: String, bio: String = "Available on Talkly 💬") {
        prefs.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_UID, uid)
            .putString(KEY_NAME, name)
            .putString(KEY_PHONE, phone)
            .putString(KEY_PROFILE_PIC, pic)
            .putString(KEY_BIO, bio)
            .apply()
    }

    fun logout() {
        try {
            getFirebaseAuth().signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Sign out exception: ${e.localizedMessage}")
        }
        prefs.edit().clear().apply()
        _authState.value = AuthState.Unauthenticated
    }
}
