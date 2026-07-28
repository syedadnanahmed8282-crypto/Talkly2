package com.family.talkly

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp

class TalklyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                val app = FirebaseApp.initializeApp(this)
                Log.d("TalklyApp", "FirebaseApp initialized successfully in Application.onCreate: ${app?.name}")
            } else {
                Log.d("TalklyApp", "FirebaseApp auto-initialized by Google Services ContentProvider")
            }
        } catch (e: Exception) {
            Log.e("TalklyApp", "Failed to initialize FirebaseApp safely: ${e.localizedMessage}", e)
        }
    }
}
