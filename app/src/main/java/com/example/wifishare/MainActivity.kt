package com.example.wifishare

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null

    // BUG FIX 1: These must be mutableStateOf so Compose can observe them,
    // but they also need to be readable from onNewIntent without going through
    // the composable. Using a backing property pair is the cleanest fix.
    private var isWriteMode by mutableStateOf(false)
    private var wifiData by mutableStateOf("")

    // BUG FIX 2: Track a message to show in the UI (instead of just Toasts)
    private var statusMessage by mutableStateOf("Ready to tap")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            Toast.makeText(this, "This device does not support NFC", Toast.LENGTH_LONG).show()
        }

        setContent {
            // BUG FIX 3: Removed the SDK >= S guard. dynamicDarkColorScheme requires API 31+,
            // but wrapping the ENTIRE setContent in that if-block means the app shows a
            // blank screen on Android 11 and below. Provide a fallback theme instead.
            val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dynamicDarkColorScheme(this)
            } else {
                darkColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WifiShareScreen(
                        isWriteMode = isWriteMode,
                        statusMessage = statusMessage,
                        onModeChange = { isWriteMode = it },
                        onDataChange = { wifiData = it }
                    )
                }
            }
        }
    }

    // --- NFC Lifecycle ---

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_MUTABLE
        )

        // BUG FIX 4: The IntentFilter used a try-catch-less addDataType which throws
        // a checked exception. Wrap it. Also add TAG_DISCOVERED as a fallback so
        // unformatted/blank tags can still be written to.
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try {
                addDataType("application/vnd.com.example.wifishare")
            } catch (e: IntentFilter.MalformedMimeTypeException) {
                Log.e("NFC", "Bad MIME type", e)
            }
        }
        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)

        nfcAdapter?.enableForegroundDispatch(
            this,
            pendingIntent,
            arrayOf(ndefFilter, tagFilter),
            null
        )
        statusMessage = if (isWriteMode) "Tap receiver device now" else "Waiting for host tap…"
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // BUG FIX 5: getParcelableExtra is deprecated in API 33+. Use the typed overload.
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        if (isWriteMode) {
            if (wifiData.contains("|")) {
                writeToTag(tag, wifiData)
            } else {
                statusMessage = "Enter SSID and password first"
                Toast.makeText(this, "Please enter Wi-Fi details first", Toast.LENGTH_SHORT).show()
            }
        } else {
            readFromTag(intent)
        }
    }

    // --- NFC Write ---

    private fun writeToTag(tag: Tag?, data: String) {
        if (tag == null) {
            statusMessage = "No tag detected"
            return
        }
        val record = NdefRecord.createMime(
            "application/vnd.com.example.wifishare",
            data.toByteArray(Charsets.UTF_8)
        )
        // BUG FIX 6: Always include an Android Application Record so the correct
        // app is launched on the receiver even if it's in the background.
        val aar = NdefRecord.createApplicationRecord("com.example.wifishare")
        val message = NdefMessage(arrayOf(record, aar))

        try {
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                statusMessage = "Tag is not NDEF-formatted"
                Toast.makeText(this, "Tag cannot be written to", Toast.LENGTH_SHORT).show()
                return
            }
            ndef.connect()
            if (!ndef.isWritable) {
                statusMessage = "Tag is read-only"
                Toast.makeText(this, "This tag is read-only", Toast.LENGTH_SHORT).show()
                ndef.close()
                return
            }
            ndef.writeNdefMessage(message)
            ndef.close()
            statusMessage = "Wi-Fi details sent! ✓"
            Toast.makeText(this, "Wi-Fi Data Sent!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // BUG FIX 7: The original code had no error handling — a failed write
            // silently did nothing.
            statusMessage = "Write failed: ${e.message}"
            Log.e("NFC", "Write failed", e)
            Toast.makeText(this, "Write failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- NFC Read + Wi-Fi Connect ---

    private fun readFromTag(intent: Intent) {
        // BUG FIX 8: getParcelableArrayExtra is deprecated in API 33+.
        val rawMsgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }

        if (rawMsgs == null || rawMsgs.isEmpty()) {
            statusMessage = "No data on tag"
            Toast.makeText(this, "No Wi-Fi data found on tag", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val msg = rawMsgs[0] as NdefMessage
            // BUG FIX 9: The original code decoded the first record's payload which for
            // a MIME record does NOT have a language-code prefix — that's only for
            // NdefRecord.TNF_WELL_KNOWN / RTD_TEXT records. The raw payload is the
            // data we wrote, so this is correct — but must use the same charset.
            val payload = String(msg.records[0].payload, Charsets.UTF_8)
            val parts = payload.split("|")

            if (parts.size < 2) {
                statusMessage = "Malformed Wi-Fi data"
                Toast.makeText(this, "Could not parse Wi-Fi data", Toast.LENGTH_SHORT).show()
                return
            }

            val ssid = parts[0]
            val password = parts[1]
            statusMessage = "Received \"$ssid\" — connecting…"
            Toast.makeText(this, "Connecting to $ssid…", Toast.LENGTH_SHORT).show()

            // BUG FIX 10: The original code had a comment saying "logic goes here"
            // but never actually connected. This is the real connection logic.
            connectToWifi(ssid, password)

        } catch (e: Exception) {
            statusMessage = "Read error: ${e.message}"
            Log.e("NFC", "Read failed", e)
            Toast.makeText(this, "Read failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // --- Wi-Fi Connection (API 29+) ---

    private fun connectToWifi(ssid: String, password: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()

            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            connectivityManager.requestNetwork(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        // BUG FIX 11: Bind the process to this network so traffic
                        // actually routes through the new Wi-Fi (not the old connection).
                        connectivityManager.bindProcessToNetwork(network)
                        runOnUiThread {
                            statusMessage = "Connected to \"$ssid\" ✓"
                            Toast.makeText(
                                this@MainActivity,
                                "Connected to $ssid!",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onUnavailable() {
                        runOnUiThread {
                            statusMessage = "Could not connect to \"$ssid\""
                            Toast.makeText(
                                this@MainActivity,
                                "Could not connect to $ssid",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            )
        } else {
            // Fallback for API 28 and below (WifiManager approach)
            @Suppress("DEPRECATION")
            val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
            }
            @Suppress("DEPRECATION")
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val netId = wifiManager.addNetwork(wifiConfig)
            @Suppress("DEPRECATION")
            wifiManager.enableNetwork(netId, true)
            statusMessage = "Connecting to \"$ssid\"…"
        }
    }
}

// --- Composable UI ---

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiShareScreen(
    isWriteMode: Boolean,
    statusMessage: String,
    onModeChange: (Boolean) -> Unit,
    onDataChange: (String) -> Unit
) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val cardScale by animateFloatAsState(
        targetValue = if (isWriteMode) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "HeroScale"
    )

    val containerColor by animateColorAsState(
        targetValue = if (isWriteMode)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.secondaryContainer,
        animationSpec = spring(stiffness = Spring.StiffnessVeryLow),
        label = "ContainerColor"
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        "Wi-Fi Tap",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Normal
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .scale(cardScale)
                    .graphicsLayer { shadowElevation = 8f },
                shape = RoundedCornerShape(56.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isWriteMode) "Broadcasting" else "Receiver",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        // BUG FIX 12: Show live status in the card so user knows what's happening
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (isWriteMode) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = {
                            ssid = it
                            onDataChange("$ssid|$password")
                        },
                        label = { Text("Network SSID") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            onDataChange("$ssid|$password")
                        },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { onModeChange(!isWriteMode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Text(
                    text = if (isWriteMode) "Switch to Receiver" else "Become Host",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}