package nl.koen.autoscherm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build

/**
 * Instappen: opent het autoscherm zodra je telefoon verbindt met de carkit (standaard WEGMAN CARKIT).
 * Uitstappen: onthoudt waar je geparkeerd hebt.
 */
class CarConnectReceiver : BroadcastReceiver() {

    companion object {
        const val DEFAULT_CAR_NAME = "WEGMAN CARKIT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED && action != BluetoothDevice.ACTION_ACL_DISCONNECTED) return
        if (!isCarKit(context, intent)) return

        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            // Eén keer per rit Flitsmeister en muziek starten (niet bij een korte haper in de verbinding)
            val now = System.currentTimeMillis()
            val firstThisTrip = now - prefs.getLong("car_start_time", 0L) > 2 * 60_000
            prefs.edit().putBoolean("car_connected", true).putLong("car_start_time", now).apply()
            try {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra(MainActivity.EXTRA_CAR_START, firstThisTrip)
                )
            } catch (ignored: Exception) { }
        } else {
            prefs.edit().putBoolean("car_connected", false).apply()
            LocationStore.markParked(context, freshLocation(context))
            context.stopService(Intent(context, BubbleService::class.java))
        }
    }

    private fun isCarKit(context: Context, intent: Intent): Boolean {
        val device = deviceFrom(intent) ?: return false
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        } ?: return false
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        // Zelf gekozen apparaat in Instellen: herkennen aan het vaste adres
        prefs.getString("car_bt_address", null)?.let { return device.address.equals(it, ignoreCase = true) }
        val wanted = prefs.getString("car_bt_name", DEFAULT_CAR_NAME) ?: DEFAULT_CAR_NAME
        return name.trim().equals(wanted, ignoreCase = true)
    }

    @SuppressLint("MissingPermission")
    private fun freshLocation(context: Context): Location? = try {
        val lm = context.getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (e: Exception) { null } }
            .maxByOrNull { it.time }
    } catch (e: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
}
