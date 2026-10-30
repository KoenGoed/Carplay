package nl.koen.autoscherm

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Opent het autoscherm zodra je telefoon verbindt met de Bluetooth van je auto
 * (standaard: WEGMAN CARKIT).
 */
class CarConnectReceiver : BroadcastReceiver() {

    companion object {
        const val DEFAULT_CAR_NAME = "WEGMAN CARKIT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        val device = deviceFrom(intent) ?: return
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        } ?: return

        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val wanted = prefs.getString("car_bt_name", DEFAULT_CAR_NAME) ?: DEFAULT_CAR_NAME
        val isCar = name.trim().equals(wanted, ignoreCase = true) || name.contains("wegman", ignoreCase = true)
        if (!isCar) return

        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (ignored: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
