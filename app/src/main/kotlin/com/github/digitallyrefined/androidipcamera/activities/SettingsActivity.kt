package com.github.digitallyrefined.androidipcamera.activities

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.R
import com.github.digitallyrefined.androidipcamera.StreamingService
import com.github.digitallyrefined.androidipcamera.helpers.InputValidator
import com.github.digitallyrefined.androidipcamera.helpers.IpAddressHelper
import com.github.digitallyrefined.androidipcamera.helpers.RecordingsHelper
import com.github.digitallyrefined.androidipcamera.helpers.SecureStorage
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.DynamicColors

/**
 * The app's home screen. Nothing here auto-starts the camera or the server - it just shows
 * whether the server is currently running and offers two explicit actions: start/stop it as a
 * background service (no camera preview), or open the full camera view on demand.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var statusText: android.widget.TextView
    private lateinit var statusDot: View
    private lateinit var ipAddressText: android.widget.TextView
    private lateinit var startStopButton: MaterialButton
    private var hasRequestedPermissions = false
    private var dotPulseAnimator: ObjectAnimator? = null

    // The displayed URL (protocol/port/IP) depends on tls_version, server_port and display_ip,
    // which are edited in the settings list below the hero - without this, the text only picked
    // up a change the next time the activity was recreated (e.g. an app restart).
    private val urlPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "tls_version" || key == "server_port" || key == "display_ip") refreshHeroState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lightweight Material You: this is the one line that pulls in wallpaper-derived colors
        // on Android 12+, on top of the fixed Theme.AndroidIpCamera fallback everywhere else.
        DynamicColors.applyToActivityIfAvailable(this)

        setContentView(R.layout.activity_settings)

        statusText = findViewById(R.id.heroStatusText)
        statusDot = findViewById(R.id.heroStatusDot)
        ipAddressText = findViewById(R.id.heroIpAddressText)
        startStopButton = findViewById(R.id.heroStartStopButton)
        val viewCameraButton = findViewById<MaterialButton>(R.id.heroViewCameraButton)

        startStopButton.setOnClickListener {
            if (isServiceRunning()) {
                stopCameraServer()
            } else {
                startCameraServer()
            }
        }

        viewCameraButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        ipAddressText.setOnClickListener { copyIpAddressToClipboard() }
    }

    private fun getLocalIpAddress(): String = IpAddressHelper.resolve(this)

    private fun copyIpAddressToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Camera URL", ipAddressText.text))
        // Android 13+ already shows its own "Copied" system toast for clipboard changes, so
        // showing our own here would just double up.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(urlPrefsListener)
        refreshHeroState()
    }

    override fun onPause() {
        super.onPause()
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(urlPrefsListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        dotPulseAnimator?.cancel()
    }

    /** Also called (with a short delay) after the start/stop button is tapped, and by the
     *  settings fragment's exit action - anything that can change whether the server is running
     *  routes back through here so the hero and the locked auth fields never go stale. */
    fun refreshHeroState() {
        val running = isServiceRunning()
        startStopButton.text = if (running) "Stop Camera Server" else "Start Camera Server"
        statusText.text = if (running) "Camera server is running" else "Camera server is stopped"

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val tlsVersion = prefs.getString("tls_version", "1.3") ?: "1.3"
        val protocol = if (tlsVersion == "disabled") "http" else "https"
        val port = prefs.getString("server_port", "4444")?.toIntOrNull() ?: 4444
        ipAddressText.text = "$protocol://${getLocalIpAddress()}:$port"

        val dotColor = if (running) 0xFF4CAF50.toInt() else 0xFF9E9E9E.toInt()
        ViewCompat.setBackgroundTintList(statusDot, ColorStateList.valueOf(dotColor))
        if (running) {
            if (dotPulseAnimator == null) {
                dotPulseAnimator = ObjectAnimator.ofFloat(statusDot, View.ALPHA, 1f, 0.25f, 1f).apply {
                    duration = 1400
                    repeatCount = ObjectAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        } else {
            dotPulseAnimator?.cancel()
            dotPulseAnimator = null
            statusDot.alpha = 1f
        }

        (supportFragmentManager.findFragmentById(R.id.settingsFragmentContainer) as? SettingsFragment)
            ?.refreshAuthFieldLockState(running)
    }

    fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == StreamingService::class.java.name
        }
    }

    /** Shared by the settings menu's "Exit App" entry and MainActivity's X button. */
    fun confirmExitApp() {
        AlertDialog.Builder(this)
            .setTitle("Exit app?")
            .setMessage("This stops the camera server and closes the app.")
            .setPositiveButton("Exit") { _, _ ->
                startService(Intent(this, StreamingService::class.java).apply {
                    action = StreamingService.ACTION_STOP_SERVICE
                })
                finishAndRemoveTask()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun allPermissionsGranted() = MainActivity.REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun startCameraServer() {
        if (!allPermissionsGranted()) {
            if (!hasRequestedPermissions) {
                hasRequestedPermissions = true
                // Request the optional permissions (RECORD_AUDIO in particular) alongside the
                // required ones, same as MainActivity used to on its old launch-time prompt - the
                // service can still start without them, but asking up front means the user gets
                // a real choice about audio instead of it silently never working.
                val toRequest = (MainActivity.REQUIRED_PERMISSIONS + MainActivity.OPTIONAL_PERMISSIONS)
                    .distinct().toTypedArray()
                ActivityCompat.requestPermissions(this, toRequest, REQUEST_CODE_PERMISSIONS)
            } else {
                Toast.makeText(this, "Camera permission is required. Enable it in App Settings.", Toast.LENGTH_LONG).show()
            }
            return
        }
        // Same path BootReceiver uses: the service starts the streaming server itself, no bound
        // activity or camera preview required.
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START_SERVER
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Starting camera server...", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ refreshHeroState() }, 800)
    }

    private fun stopCameraServer() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP_SERVICE
        }
        startService(intent)
        Toast.makeText(this, "Stopping camera server...", Toast.LENGTH_SHORT).show()
        Handler(Looper.getMainLooper()).postDelayed({ refreshHeroState() }, 800)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && allPermissionsGranted()) {
            startCameraServer()
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 11
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        companion object {
            private const val PICK_CERTIFICATE_FILE = 1
            private const val PICK_RECORDING_FOLDER = 2
        }

        // stream_res/stream_fps are also written from outside this screen entirely - the web
        // control UI writes them directly via its own HTTP control endpoint, in a different
        // process context (the service), not through these Preference widgets. Without this,
        // a change made there would only show up here the next time the screen is recreated.
        private val streamPrefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            when (key) {
                "stream_res" -> findPreference<ListPreference>("stream_res")?.apply {
                    val current = prefs.getString("stream_res", "auto") ?: "auto"
                    if (value != current) value = current
                    summary = streamResSummary(current)
                }
                "stream_fps" -> findPreference<EditTextPreference>("stream_fps")?.apply {
                    val current = prefs.getString("stream_fps", "30") ?: "30"
                    if (text != current) text = current
                    summary = "$current fps"
                }
            }
        }

        override fun onResume() {
            super.onResume()
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(streamPrefsListener)
            // Interfaces can come and go while this screen isn't open (e.g. Tailscale
            // connecting/disconnecting) - refresh the list every time it's shown again.
            findPreference<ListPreference>("display_ip")?.let { refreshIpEntries(it) }
        }

        override fun onPause() {
            super.onPause()
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(streamPrefsListener)
        }

        /** "auto" reads oddly on its own - pairs it with the quality preset it falls back to. */
        private fun streamResSummary(value: String): String =
            if (value == "auto") "Auto (matches the app's default quality)" else value

        /** Rebuilds the dropdown's options from whatever interfaces are actually up right now. */
        private fun refreshIpEntries(pref: ListPreference) {
            val addresses = IpAddressHelper.listAddresses()
            val entries = mutableListOf("Auto (first available)")
            val values = mutableListOf("auto")
            addresses.forEach { (name, ip) ->
                entries.add("$name  ·  $ip")
                values.add(name)
            }
            pref.entries = entries.toTypedArray()
            pref.entryValues = values.toTypedArray()
            pref.summary = ipSummaryFor(pref.value ?: "auto")
        }

        private fun ipSummaryFor(value: String): String {
            if (value == "auto") return "Auto (first available)"
            val match = IpAddressHelper.listAddresses().firstOrNull { it.first == value }
            return if (match != null) {
                "${match.first}  ·  ${match.second}"
            } else {
                // Picked interface isn't up right now (e.g. Tailscale disconnected) - still shown
                // as selected since it'll resolve again once it reconnects; the hero falls back
                // to Auto in the meantime (see IpAddressHelper.resolve).
                "$value (not connected right now - showing Auto until it reconnects)"
            }
        }

        /** Applies a resolution/fps change live, the same way the web UI's own controls do - see
         *  ACTION_RESTART_CAMERA. Only when the server's already running: startService() would
         *  otherwise start the service fresh just to deliver this intent, which is exactly the
         *  "only the hero button starts/stops the server" rule this app deliberately follows
         *  everywhere else - a settings tweak must never be what brings the server up. */
        private fun requestLiveCameraRestart() {
            val settingsActivity = activity as? SettingsActivity ?: return
            if (!settingsActivity.isServiceRunning()) return
            settingsActivity.startService(Intent(settingsActivity, StreamingService::class.java).apply {
                action = StreamingService.ACTION_RESTART_CAMERA
            })
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            // Username/password get locked while the server is running (see
            // refreshAuthFieldLockState below) - editing either one restarts the server anyway,
            // so this avoids someone bouncing the stream a couple of times while typing a new
            // credential pair instead of just stopping the server first.
            refreshAuthFieldLockState((activity as? SettingsActivity)?.isServiceRunning() == true)

            findPreference<Preference>("exit_app")?.setOnPreferenceClickListener {
                (activity as? SettingsActivity)?.confirmExitApp()
                true
            }

            // Set up certificate selection preference
            findPreference<Preference>("certificate_path")?.apply {
                setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    startActivityForResult(
                        Intent.createChooser(intent, "Select TLS Certificate"),
                        PICK_CERTIFICATE_FILE
                    )
                    true
                }
            }

            val secureStorage = SecureStorage(requireContext())

            // Configure authentication enable/disable checkbox
            findPreference<androidx.preference.CheckBoxPreference>("enable_auth")?.apply {
                // Initialize visibility based on current value
                val enabled = isChecked
                findPreference<EditTextPreference>("username")?.isVisible = enabled
                findPreference<EditTextPreference>("password")?.isVisible = enabled

                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    // Show/hide username and password preferences
                    findPreference<EditTextPreference>("username")?.isVisible = enabled
                    findPreference<EditTextPreference>("password")?.isVisible = enabled
                    // Whether username/password are "required" in their summary depends on this
                    // toggle, so refresh them too.
                    refreshAuthFieldLockState((activity as? SettingsActivity)?.isServiceRunning() == true)
                    // Takes effect next time the server is started from the hero button - nothing
                    // here touches the running server.
                    true
                }
            }

            // Configure TLS version preference to hide/show certificate options
            findPreference<androidx.preference.ListPreference>("tls_version")?.apply {
                // Initialize visibility based on current value
                val tlsEnabled = value != "disabled"
                findPreference<Preference>("certificate_path")?.isVisible = tlsEnabled
                findPreference<EditTextPreference>("certificate_password")?.isVisible = tlsEnabled
                findPreference<Preference>("test_certificate")?.isVisible = tlsEnabled

                setOnPreferenceChangeListener { _, newValue ->
                    val tlsEnabled = newValue != "disabled"
                    // Show/hide certificate preferences
                    findPreference<Preference>("certificate_path")?.isVisible = tlsEnabled
                    findPreference<EditTextPreference>("certificate_password")?.isVisible = tlsEnabled
                    findPreference<Preference>("test_certificate")?.isVisible = tlsEnabled
                    true
                }
            }

            // Configure username (optional - defaults available)
            findPreference<EditTextPreference>("username")?.apply {
                // Load current value from secure storage
                text = secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "")

                setOnPreferenceChangeListener { _, newValue ->
                    val username = newValue.toString()
                    if (username.isNotEmpty() && !InputValidator.isValidUsername(username)) {
                        Toast.makeText(requireContext(),
                            "Username must be 1-50 characters, letters/numbers/hyphens/underscores only",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    // Store securely (empty string means use default)
                    secureStorage.putSecureString(SecureStorage.KEY_USERNAME, username)
                    refreshAuthFieldLockState((activity as? SettingsActivity)?.isServiceRunning() == true)
                    true
                }
            }

            // Configure password (optional - defaults available)
            findPreference<EditTextPreference>("password")?.apply {
                // Do not show the existing password when editing
                setOnBindEditTextListener { editText ->
                    editText.text = null
                    editText.hint = "Enter new password"
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val password = newValue.toString()

                    // Empty input means "no change" – keep existing password
                    if (password.isEmpty()) {
                        return@setOnPreferenceChangeListener false
                    }

                    if (!InputValidator.isValidPassword(password)) {
                        Toast.makeText(
                            requireContext(),
                            "Password must be 8-128 characters",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Store securely only; do not persist plaintext in SharedPreferences
                    secureStorage.putSecureString(SecureStorage.KEY_PASSWORD, password)
                    refreshAuthFieldLockState((activity as? SettingsActivity)?.isServiceRunning() == true)
                    // Returning false prevents EditTextPreference from saving the plaintext
                    false
                }
            }

            // Configure streaming port preference
            findPreference<EditTextPreference>("server_port")?.apply {
                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val portStr = newValue.toString()
                    val port = portStr.toIntOrNull()
                    if (port == null || port !in 1..65535) {
                        Toast.makeText(
                            requireContext(),
                            "Port must be between 1 and 65535",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }
                    summary = "Port $port"
                    true
                }
            }

            // Which of this device's IP addresses to show/copy in the hero. Populated from
            // whatever interfaces are actually up right now (Wi-Fi LAN, tailscale0, ...) since
            // that list varies per device and per moment - "auto" keeps picking the first one
            // found, same as before this preference existed.
            findPreference<ListPreference>("display_ip")?.apply {
                refreshIpEntries(this)
                setOnPreferenceChangeListener { _, newValue ->
                    summary = ipSummaryFor(newValue.toString())
                    true
                }
            }

            // Resolution/fps: same "stream_res"/"stream_fps" prefs the web UI's own controls
            // write, and applied the same way - saved immediately, plus a live camera
            // reconfigure (ACTION_RESTART_CAMERA) if the server's already running, so this
            // behaves exactly like adjusting it from the web UI rather than like the locked
            // auth fields above (which require a full server restart to take effect at all).
            findPreference<ListPreference>("stream_res")?.apply {
                summary = streamResSummary(value ?: "auto")
                setOnPreferenceChangeListener { _, newValue ->
                    summary = streamResSummary(newValue.toString())
                    requestLiveCameraRestart()
                    true
                }
            }
            findPreference<EditTextPreference>("stream_fps")?.apply {
                summary = "${text ?: "30"} fps"
                setOnBindEditTextListener { editText ->
                    editText.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                }
                setOnPreferenceChangeListener { _, newValue ->
                    val fps = newValue.toString().toIntOrNull()
                    if (fps == null || fps !in 1..60) {
                        Toast.makeText(requireContext(), "Frame rate must be between 1 and 60", Toast.LENGTH_LONG).show()
                        return@setOnPreferenceChangeListener false
                    }
                    summary = "$fps fps"
                    requestLiveCameraRestart()
                    true
                }
            }

            // Add validation for certificate password
            findPreference<EditTextPreference>("certificate_password")?.apply {
                // Do not pre-fill the existing certificate password when editing
                setOnBindEditTextListener { editText ->
                    editText.text = null
                    editText.hint = "Enter certificate password"
                }

                setOnPreferenceChangeListener { _, newValue ->
                    val password = newValue.toString()
                    if (!InputValidator.isValidCertificatePassword(password)) {
                        Toast.makeText(
                            requireContext(),
                            "Certificate password too long (max 256 characters)",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Basic validation - check if password is not empty for certificate usage
                    if (password.isEmpty()) {
                        Toast.makeText(
                            requireContext(),
                            "Certificate password is required",
                            Toast.LENGTH_LONG
                        ).show()
                        return@setOnPreferenceChangeListener false
                    }

                    // Store securely only; do not persist plaintext in SharedPreferences
                    secureStorage.putSecureString(SecureStorage.KEY_CERT_PASSWORD, password)
                    Toast.makeText(
                        requireContext(),
                        "Certificate password saved, use 'Test Certificate Setup' to validate",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Returning false prevents EditTextPreference from saving the plaintext
                    false
                }
            }

            // Configure the video recording storage location (defaults to Movies/AndroidIPCamera)
            findPreference<Preference>("recording_storage_uri")?.apply {
                summary = recordingStorageSummary()
                setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        )
                    }
                    startActivityForResult(
                        Intent.createChooser(intent, "Select recording folder"),
                        PICK_RECORDING_FOLDER
                    )
                    true
                }
            }

            findPreference<Preference>("reset_recording_storage")?.apply {
                setOnPreferenceClickListener {
                    val current = RecordingsHelper.customStorageUri(requireContext())
                    if (current != null) {
                        try {
                            requireContext().contentResolver.releasePersistableUriPermission(
                                current,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                        } catch (_: Exception) {
                            // Permission may already be gone; nothing to release
                        }
                    }
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                        .remove(RecordingsHelper.PREF_RECORDING_STORAGE_URI)
                        .apply()
                    findPreference<Preference>("recording_storage_uri")?.summary = recordingStorageSummary()
                    Toast.makeText(requireContext(),
                        "Recordings will be saved to Movies/AndroidIPCamera",
                        Toast.LENGTH_SHORT).show()
                    true
                }
            }

            // Add test certificate functionality
            findPreference<Preference>("test_certificate")?.apply {
                setOnPreferenceClickListener {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    val certificatePath = prefs.getString("certificate_path", null)
                    val certPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")

                    if (certPassword.isNullOrEmpty()) {
                        Toast.makeText(requireContext(),
                            "Certificate password not configured, set it above first",
                            Toast.LENGTH_LONG).show()
                        return@setOnPreferenceClickListener true
                    }

                    val isValid = if (certificatePath != null) {
                        // Test custom certificate
                        val certUri = android.net.Uri.parse(certificatePath)
                        InputValidator.validateCertificateUsability(requireContext(), certUri, certPassword)
                    } else {
                        // Test built-in certificate
                        InputValidator.validateBuiltInCertificate(requireContext(), certPassword)
                    }

                    if (isValid) {
                        Toast.makeText(requireContext(),
                            "✅ Certificate configuration is valid",
                            Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(requireContext(),
                            "❌ Certificate validation failed, check password and certificate file",
                            Toast.LENGTH_LONG).show()
                    }

                    true
                }
            }

        }

        /** Username/password summary, at a glance: whether something is actually configured,
         *  not just whether the field is locked. Auth requires both to be set - if it's enabled
         *  with either one blank, every connection gets rejected (see StreamingServerHelper's
         *  403 path), so an empty field is flagged rather than just left blank-looking. */
        private fun authFieldSummary(filledLabel: String?, authEnabled: Boolean, locked: Boolean): String {
            val base = when {
                filledLabel != null -> "✓ $filledLabel"
                authEnabled -> "⚠ Not set — required while authentication is enabled"
                else -> "Not set"
            }
            return if (locked) "$base  ·  stop the server to change this" else base
        }

        /** Called on creation and whenever the hero's running state changes, or the auth
         *  toggle/username/password themselves change. Locks the auth options while the server
         *  is running: only the hero button starts/stops the server, so these can't take effect
         *  until then anyway - locking them avoids the false impression that a change here has
         *  any live effect on the running server. Also shows, at a glance, whether username and
         *  password are actually filled in. */
        fun refreshAuthFieldLockState(serverRunning: Boolean) {
            val authEnabled = findPreference<androidx.preference.CheckBoxPreference>("enable_auth")?.isChecked == true
            val secureStorage = SecureStorage(requireContext())
            val currentUsername = secureStorage.getSecureString(SecureStorage.KEY_USERNAME, "")
            val passwordIsSet = !secureStorage.getSecureString(SecureStorage.KEY_PASSWORD, "").isNullOrEmpty()

            findPreference<androidx.preference.CheckBoxPreference>("enable_auth")?.isEnabled = !serverRunning
            findPreference<EditTextPreference>("username")?.apply {
                isEnabled = !serverRunning
                summary = authFieldSummary(currentUsername?.takeIf { it.isNotEmpty() }, authEnabled, serverRunning)
            }
            findPreference<EditTextPreference>("password")?.apply {
                isEnabled = !serverRunning
                summary = authFieldSummary(if (passwordIsSet) "Password set" else null, authEnabled, serverRunning)
            }
            findPreference<EditTextPreference>("trusted_ips")?.isEnabled = !serverRunning
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode == PICK_CERTIFICATE_FILE && resultCode == Activity.RESULT_OK) {
                data?.data?.let { uri ->
                    val certificatePath = uri.toString()

                    // Enhanced certificate validation
                    if (!InputValidator.isValidCertificatePath(certificatePath)) {
                        Toast.makeText(requireContext(),
                            "Invalid certificate file, must be a valid .p12 or .pfx file under 10MB",
                            Toast.LENGTH_LONG).show()
                        return@let
                    }

                    // Validate certificate can actually be loaded and used
                    val secureStorage = SecureStorage(requireContext())
                    val certPassword = secureStorage.getSecureString(SecureStorage.KEY_CERT_PASSWORD, "")
                    val certificateUri = Uri.parse(certificatePath)

                    if (!InputValidator.validateCertificateUsability(requireContext(), certificateUri, certPassword)) {
                        Toast.makeText(requireContext(),
                            "Certificate cannot be loaded, check password and file integrity",
                            Toast.LENGTH_LONG).show()
                        return@let
                    }

                    // Store the certificate path
                    preferenceManager.sharedPreferences?.edit()?.apply {
                        putString("certificate_path", certificatePath)
                        apply()
                    }
                    // Update the preference summary
                    findPreference<Preference>("certificate_path")?.summary = certificatePath

                    Toast.makeText(requireContext(),
                        "Certificate configured - stop and start the camera server for this to take effect",
                        Toast.LENGTH_SHORT).show()
                }
            }

            if (requestCode == PICK_RECORDING_FOLDER && resultCode == Activity.RESULT_OK) {
                data?.data?.let { uri ->
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit()
                            .putString(RecordingsHelper.PREF_RECORDING_STORAGE_URI, uri.toString())
                            .apply()
                        findPreference<Preference>("recording_storage_uri")?.summary = recordingStorageSummary()
                        Toast.makeText(requireContext(),
                            "Recording location updated", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(),
                            "Could not use that folder: ${e.message}",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            super.onActivityResult(requestCode, resultCode, data)
        }

        /** Summary for the storage preference: the chosen folder name or the default path. */
        private fun recordingStorageSummary(): String {
            val uri = RecordingsHelper.customStorageUri(requireContext())
                ?: return "Default: ${RecordingsHelper.DEFAULT_RELATIVE_PATH}"
            val name = DocumentFile.fromTreeUri(requireContext(), uri)?.name
            return "Custom folder: ${name ?: uri}"
        }

    }
}
