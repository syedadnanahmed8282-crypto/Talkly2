package com.family.talkly.data.auth

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.family.talkly.data.models.UserProfile
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

sealed class AuthState {
    object InitialCheck : AuthState()
    object Unauthenticated : AuthState()
    data class CodeSent(
        val verificationId: String,
        val phoneNumber: String,
        val error: String? = null
    ) : AuthState()
    data class VerificationInProgress(val message: String = "Verifying OTP...") : AuthState()
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
    }

    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow<AuthState>(AuthState.InitialCheck)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private var currentVerificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    init {
        checkCurrentSession()
    }

    /**
     * Checks local session and Firebase Auth current user to resume session
     */
    fun checkCurrentSession() {
        try {
            val isLoggedIn = prefs.getBoolean(KEY_IS_LOGGED_IN, false)
            val savedUid = prefs.getString(KEY_UID, null)
            val firebaseUser = try { firebaseAuth.currentUser } catch (e: Exception) { null }

            if (isLoggedIn && !savedUid.isNullOrEmpty()) {
                val name = prefs.getString(KEY_NAME, "") ?: ""
                val phone = prefs.getString(KEY_PHONE, "") ?: ""
                val pic = prefs.getString(KEY_PROFILE_PIC, "") ?: ""
                val bio = prefs.getString(KEY_BIO, "Available on Talkly 💬") ?: "Available on Talkly 💬"

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
            } else if (firebaseUser != null) {
                val uid = firebaseUser.uid
                val phone = firebaseUser.phoneNumber ?: ""
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
     * Sends 6-digit OTP code to mobile number via Firebase Phone Auth
     */
    fun sendOtp(
        activity: Activity,
        phoneNumber: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        _authState.value = AuthState.VerificationInProgress("Sending OTP to $phoneNumber...")

        try {
            val optionsBuilder = PhoneAuthOptions.newBuilder(firebaseAuth)
                .setPhoneNumber(phoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                        Log.d(TAG, "Phone verification completed automatically")
                        val code = credential.smsCode
                        if (code != null && currentVerificationId != null) {
                            verifyOtp(code, onSuccess, onError)
                        } else {
                            signInWithPhoneCredential(credential, phoneNumber, onSuccess, onError)
                        }
                    }

                    override fun onVerificationFailed(e: FirebaseException) {
                        Log.w(TAG, "Firebase phone verification failed: ${e.localizedMessage}", e)
                        val errorMsg = e.localizedMessage ?: "Phone verification failed. Please check the number."
                        _authState.value = AuthState.Error(errorMsg)
                        onError(errorMsg)
                    }

                    override fun onCodeSent(
                        verificationId: String,
                        token: PhoneAuthProvider.ForceResendingToken
                    ) {
                        Log.d(TAG, "OTP Code sent successfully. VerificationId: $verificationId")
                        currentVerificationId = verificationId
                        resendToken = token
                        _authState.value = AuthState.CodeSent(verificationId, phoneNumber)
                        onSuccess()
                    }
                })

            resendToken?.let { token ->
                optionsBuilder.setForceResendingToken(token)
            }

            PhoneAuthProvider.verifyPhoneNumber(optionsBuilder.build())
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating phone verification: ${e.localizedMessage}", e)
            val errorMsg = e.localizedMessage ?: "Unable to send OTP. Please try again."
            _authState.value = AuthState.Error(errorMsg)
            onError(errorMsg)
        }
    }

    /**
     * Verifies the 6-digit OTP entered by the user
     */
    fun verifyOtp(
        otpCode: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val verId = currentVerificationId
        val currentState = _authState.value
        val phone = if (currentState is AuthState.CodeSent) currentState.phoneNumber else ""

        if (otpCode.length != 6 || !otpCode.all { it.isDigit() }) {
            val msg = "Please enter a valid 6-digit OTP code"
            if (verId != null) {
                _authState.value = AuthState.CodeSent(verId, phone, error = msg)
            } else {
                _authState.value = AuthState.Error(msg)
            }
            onError(msg)
            return
        }

        if (verId == null) {
            val msg = "Verification ID missing. Please resend code."
            _authState.value = AuthState.Error(msg)
            onError(msg)
            return
        }

        _authState.value = AuthState.VerificationInProgress("Verifying 6-digit OTP...")

        try {
            val credential = PhoneAuthProvider.getCredential(verId, otpCode)
            signInWithPhoneCredential(credential, phone, onSuccess, onError)
        } catch (e: Exception) {
            Log.w(TAG, "Phone credential creation failed: ${e.localizedMessage}", e)
            val errorMsg = e.localizedMessage ?: "Invalid OTP code"
            _authState.value = AuthState.CodeSent(verId, phone, error = errorMsg)
            onError(errorMsg)
        }
    }

    private fun signInWithPhoneCredential(
        credential: PhoneAuthCredential,
        phoneNumber: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val verId = currentVerificationId ?: ""
        try {
            firebaseAuth.signInWithCredential(credential)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val user = task.result?.user
                        if (user != null) {
                            val uid = user.uid
                            val phone = user.phoneNumber ?: phoneNumber
                            Log.d(TAG, "Firebase phone sign in successful. UID: $uid, Phone: $phone")
                            checkUserProfileInFirestore(uid, phone)
                            onSuccess?.invoke()
                        } else {
                            val errorMsg = "Authentication succeeded but user is null."
                            if (verId.isNotEmpty()) {
                                _authState.value = AuthState.CodeSent(verId, phoneNumber, error = errorMsg)
                            } else {
                                _authState.value = AuthState.Error(errorMsg)
                            }
                            onError?.invoke(errorMsg)
                        }
                    } else {
                        val e = task.exception
                        Log.w(TAG, "Firebase sign in with credential failed: ${e?.localizedMessage}", e)
                        val errorMsg = e?.localizedMessage ?: "Invalid OTP code or verification failed."
                        if (verId.isNotEmpty()) {
                            _authState.value = AuthState.CodeSent(verId, phoneNumber, error = errorMsg)
                        } else {
                            _authState.value = AuthState.Error(errorMsg)
                        }
                        onError?.invoke(errorMsg)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Auth sign-in exception: ${e.localizedMessage}", e)
            val errorMsg = e.localizedMessage ?: "Authentication failed. Please try again."
            if (verId.isNotEmpty()) {
                _authState.value = AuthState.CodeSent(verId, phoneNumber, error = errorMsg)
            } else {
                _authState.value = AuthState.Error(errorMsg)
            }
            onError?.invoke(errorMsg)
        }
    }

    /**
     * Checks Firestore 'users/{uid}' collection to see if user has completed profile setup
     */
    private fun checkUserProfileInFirestore(uid: String, phoneNumber: String) {
        try {
            firestore.collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    if (doc != null && doc.exists() && !doc.getString("name").isNullOrBlank()) {
                        val name = doc.getString("name") ?: ""
                        val phone = doc.getString("phoneNumber") ?: phoneNumber
                        val pic = doc.getString("profilePicUrl") ?: ""
                        val bio = doc.getString("bio") ?: "Available on Talkly 💬"
                        val profile = UserProfile(
                            uid = uid,
                            name = name,
                            phoneNumber = phone,
                            profilePicUrl = pic,
                            bio = bio
                        )
                        saveLocalSession(uid, name, phone, pic, bio)
                        _authState.value = AuthState.Authenticated(profile)
                    } else {
                        saveLocalSession(uid, "", phoneNumber, "", "")
                        _authState.value = AuthState.ProfileSetupRequired(uid, phoneNumber)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Firestore user profile read failed: ${e.localizedMessage}")
                    saveLocalSession(uid, "", phoneNumber, "", "")
                    _authState.value = AuthState.ProfileSetupRequired(uid, phoneNumber)
                }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore user profile exception: ${e.localizedMessage}")
            saveLocalSession(uid, "", phoneNumber, "", "")
            _authState.value = AuthState.ProfileSetupRequired(uid, phoneNumber)
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

        // Save local session immediately & authenticate to prevent hanging on network/Firestore latency
        saveLocalSession(uid, name, phone, profilePicUrl, bio)
        val profile = UserProfile(uid, name, phone, profilePicUrl, bio)
        _authState.value = AuthState.Authenticated(profile)
        onSuccess()

        val profileMap = mapOf(
            "uid" to uid,
            "name" to name,
            "phoneNumber" to phone,
            "profilePicUrl" to profilePicUrl,
            "bio" to bio,
            "createdAt" to System.currentTimeMillis()
        )

        try {
            firestore.collection("users").document(uid)
                .set(profileMap)
                .addOnSuccessListener {
                    Log.d(TAG, "Saved user profile to Firestore successfully")
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Firestore user profile write failed: ${e.localizedMessage}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Firestore user save exception: ${e.localizedMessage}")
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
            firebaseAuth.signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Sign out exception: ${e.localizedMessage}")
        }
        prefs.edit().clear().apply()
        _authState.value = AuthState.Unauthenticated
    }
}
