package nl.koen.autoscherm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/** Luistert naar inkomende en uitgaande gesprekken en toont dan het grote belscherm. */
class PhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val number = incomingNumber(intent)
        CallActivity.onPhoneState(context, state, number)
    }

    @Suppress("DEPRECATION")
    private fun incomingNumber(intent: Intent): String? =
        intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
}
