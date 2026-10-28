package com.firehome.launcher

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/*
 * Android exempts the active assistant from the ~5 s launch freeze that follows a
 * Home press. FireHome registers as the assistant purely to get that exemption, so
 * HomeRedirectService can bring the home screen back instantly. None of these do anything.
 */

class FireVoiceService : VoiceInteractionService()

class FireVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?) = object : VoiceInteractionSession(this) {
        override fun onShow(args: Bundle?, showFlags: Int) {
            // Logged for Dream Search's voice work: shows whether Fire OS ever opens a session (e.g. from the mic).
            android.util.Log.w(DreamVoice.TAG, "assistant session shown flags=$showFlags")
            finish()
        }
    }
}

class FireRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        listener.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
