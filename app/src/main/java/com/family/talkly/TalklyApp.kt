package com.family.talkly

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

class TalklyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseApp.initializeApp(this)
            Log.d("TalklyApp", "FirebaseApp initialized successfully")
        } catch (e: Exception) {
            Log.e("TalklyApp", "Failed to initialize FirebaseApp safely: ${e.localizedMessage}")
        }
    }
}
