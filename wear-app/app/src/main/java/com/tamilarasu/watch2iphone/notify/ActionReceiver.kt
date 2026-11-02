package com.tamilarasu.watch2iphone.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tamilarasu.watch2iphone.BleLog
import com.tamilarasu.watch2iphone.service.AncsService

/** Receives taps on notification actions (dismiss, accept, decline) and forwards them to the running service. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PERFORM) return
        val session = intent.getIntExtra(EXTRA_SESSION, -1)
        val uid = intent.getLongExtra(EXTRA_UID, -1L)
        val action = intent.getIntExtra(EXTRA_ACTION, -1)
        BleLog.svc("watch action received: uid=$uid action=$action session=$session")
        val controller = AncsService.controller
        if (controller == null) {
            BleLog.svc("service is not running; action ignored")
            return
        }
        controller.performAction(session, uid, action)
    }

    companion object {
        const val ACTION_PERFORM = "com.tamilarasu.watch2iphone.PERFORM_ACTION"
        const val EXTRA_SESSION = "session"
        const val EXTRA_UID = "uid"
        const val EXTRA_ACTION = "action"
    }
}
