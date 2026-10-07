package com.sanskystream.android

// ---------------------------------------------------------------------------
// ServiceAdvertiser.kt — M18: mDNS/NSD Advertisement
//
// Phase 8: Device discovery integration.
//
// Advertises "SanskyStream-Android <device-name>._sanskystream._tcp" on the
// local network using Android's NsdManager (Network Service Discovery).
//
// TXT records (API 21+, so always available given our minSdk 26):
//   ver=1             Protocol version (matches Protocol.PROTOCOL_VERSION)
//   role=sender       Identifies this as a source device
//   platform=android  Allows Windows UI to show "[Android]" badge
//
// The Windows DeviceDiscovery.cpp (M16) discovers this advertisement
// via DnsServiceBrowse() — no changes needed on the Windows side.
//
// Threading: start/stop must be called from the main thread.
//
// IMPORTANT: start() is designed to be called immediately when the app opens,
// BEFORE any TCP connection.  The NSD advertisement is the signal Windows uses
// to populate its device list — it must be active independently of streaming.
// ---------------------------------------------------------------------------

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import android.util.Log

private const val TAG = "SanskyServiceAdvertiser"

class ServiceAdvertiser(private val context: Context) {

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    var isAdvertising = false
        private set

    private var registeredServiceName: String = ""

    /**
     * Start advertising on the local network.
     * Uses the device's user-visible name as the service instance name.
     * Port is always CONTROL_TCP_PORT (5000).
     *
     * Call this immediately when the app starts — NOT only when streaming begins.
     */
    fun start() {
        if (isAdvertising) return

        val deviceName = getDeviceName()
        // Prefix with "Android" so Windows UI can also parse platform from the
        // service name (fallback if TXT records are missing).
        val serviceName = "SanskyStream-Android $deviceName"

        val serviceInfo = NsdServiceInfo().apply {
            this.serviceName = serviceName
            // NsdManager requires the service type WITHOUT a trailing dot for
            // registration.  The ".local" domain is implied by the mDNS stack.
            // Format: "_sanskystream._tcp" (no leading dot, no .local, no trailing dot)
            serviceType = Protocol.SERVICE_TYPE  // "_sanskystream._tcp"
            port        = Protocol.CONTROL_TCP_PORT  // 5000

            // TXT records — available on all API levels >= 21 (our minSdk is 26).
            // These allow Windows to identify the role and platform.
            setAttribute(Protocol.TXT_VER_KEY,      Protocol.TXT_VER_VALUE)      // ver=1
            setAttribute(Protocol.TXT_ROLE_KEY,     Protocol.TXT_ROLE_SENDER)    // role=sender
            setAttribute(Protocol.TXT_PLATFORM_KEY, Protocol.TXT_PLATFORM_ANDROID) // platform=android
        }

        Log.i(TAG, "[DISCOVERY] Starting NSD registration")
        Log.i(TAG, "[DISCOVERY] Service type: ${Protocol.SERVICE_TYPE}")
        Log.i(TAG, "[DISCOVERY] Service name: $serviceName")
        Log.i(TAG, "[DISCOVERY] Port: ${Protocol.CONTROL_TCP_PORT}")
        Log.i(TAG, "[DISCOVERY] TXT: ${Protocol.TXT_ROLE_KEY}=${Protocol.TXT_ROLE_SENDER}" +
                   ", ${Protocol.TXT_PLATFORM_KEY}=${Protocol.TXT_PLATFORM_ANDROID}")

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registeredServiceName = info.serviceName
                isAdvertising = true
                Log.i(TAG, "[DISCOVERY] Registration successful: '${info.serviceName}' on port ${Protocol.CONTROL_TCP_PORT}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[DISCOVERY] Registration failed: errorCode=$errorCode for '${info.serviceName}'")
                isAdvertising = false
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                isAdvertising = false
                Log.i(TAG, "[DISCOVERY] Service unregistered: '${info.serviceName}'")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "[DISCOVERY] Unregistration failed: errorCode=$errorCode")
            }
        }

        try {
            val mgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = mgr
            registrationListener = listener
            mgr.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "[DISCOVERY] NSD start failed: ${e.message}")
        }
    }

    /** Stop advertising. */
    fun stop() {
        val listener = registrationListener ?: return
        try {
            nsdManager?.unregisterService(listener)
            Log.i(TAG, "[DISCOVERY] Unregistering NSD service")
        } catch (e: Exception) {
            Log.e(TAG, "[DISCOVERY] NSD stop error: ${e.message}")
        }
        registrationListener = null
        nsdManager           = null
        isAdvertising        = false
    }

    private fun getDeviceName(): String {
        // Use the user-visible device name (same as iOS UIDevice.current.name).
        return try {
            Settings.Global.getString(context.contentResolver, "device_name")
                ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
                ?: Build.MODEL
        } catch (_: Exception) {
            Build.MODEL
        }
    }
}
