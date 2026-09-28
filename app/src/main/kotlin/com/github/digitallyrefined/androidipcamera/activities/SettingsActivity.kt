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
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.github.digitallyrefined.androidipcamera.R
import com.github.digitallyrefined.androidipcamera.StreamingService
import com.github.digitallyrefined.androidipcamera.helpers.InputValidator
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
    private lateinit var startStopButton: MaterialButton
    private var hasRequestedPermissions = false
    private var dotPulseAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lightweight Material You: this is the one line that pulls in wallpaper-derived colors
        // on Android 12+, on top of the fixed Theme.AndroidIpCamera fallback everywhere else.
        DynamicColors.applyToActivityIfAvailable(this)

        setContentView(R.layout.activity_settings)

        statusText = findViewById(R.id.heroStatusText)
        statusDot = findViewById(R.id.heroStatusDot)
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
    }

    override fun onResume() {
        super.onResume()
        refreshHeroState()
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
                ActivityCompat.requestPermissions(this, MainActivity.REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
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

                setOnPreferenceChangeListener { _, _ ->
                    // Restart server when certificate path changes
                    restartStreamingServer()
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
                    // Restart server when authentication setting changes
                    restartStreamingServer()
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
                    // Restart server when TLS version changes
                    restartStreamingServer()
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
                    // Restart server when username changes
                    restartStreamingServer()
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
                    // Restart server when password changes
                    restartStreamingServer()
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
                    restartStreamingServer()
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
                    // Restart server when certificate password changes
                    restartStreamingServer()
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

        /** Called on creation and whenever the hero's running state changes. Locks username/
         *  password while the server is running instead of letting each edit trigger its own
         *  restart - stop the server, make the change, start it again. */
        fun refreshAuthFieldLockState(serverRunning: Boolean) {
            findPreference<EditTextPreference>("username")?.apply {
                isEnabled = !serverRunning
                summary = if (serverRunning) "Stop the server to change this" else null
            }
            findPreference<EditTextPreference>("password")?.apply {
                isEnabled = !serverRunning
                summary = if (serverRunning) "Stop the server to change this" else null
            }
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
                        "Certificate configured, restart the app for changes to take effect",
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

        private fun restartStreamingServer() {
            val intent = Intent(requireContext(), StreamingService::class.java).apply {
                action = StreamingService.ACTION_RESTART_SERVER
            }
            requireContext().startService(intent)
            Toast.makeText(requireContext(), "Server restarting...", Toast.LENGTH_SHORT).show()
        }
    }
}
