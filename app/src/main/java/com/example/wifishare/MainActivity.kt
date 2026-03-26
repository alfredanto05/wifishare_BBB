package com.example.wifishare

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*

import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var isWriteMode by mutableStateOf(false)
    private var wifiData by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        setContent {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MaterialTheme(colorScheme = dynamicDarkColorScheme(this)) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        WifiShareScreen(
                            isWriteMode = isWriteMode,
                            onModeChange = { isWriteMode = it },
                            onDataChange = { wifiData = it }
                        )
                    }
                }
            }
        }
    }

    // --- NFC Logic ---

    override fun onResume() {
        super.onResume()
        // Enable foreground dispatch so this app handles NFC tags first
        val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        val filter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            addDataType("application/vnd.com.example.wifishare")
        }
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, arrayOf(filter), null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)

        if (isWriteMode) {
            writeToTag(tag, wifiData)
        } else {
            readFromTag(intent)
        }
    }

    private fun writeToTag(tag: Tag?, data: String) {
        val record = NdefRecord.createMime("application/vnd.com.example.wifishare", data.toByteArray())
        val message = NdefMessage(arrayOf(record))

        tag?.let {
            val ndef = Ndef.get(it)
            ndef?.let { n ->
                n.connect()
                n.writeNdefMessage(message)
                n.close()
                Toast.makeText(this, "Wi-Fi Data Sent!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun readFromTag(intent: Intent) {
        val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        if (rawMsgs != null) {
            val msg = rawMsgs[0] as NdefMessage
            val payload = String(msg.records[0].payload)
            Toast.makeText(this, "Received: $payload", Toast.LENGTH_LONG).show()
            // Logic to parse SSID/Password and connect goes here
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiShareScreen(
    isWriteMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onDataChange: (String) -> Unit
) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // --- EXPRESSIVE ANIMATIONS ---

    // Bouncy scale effect for the Hero Card
    val cardScale by animateFloatAsState(
        targetValue = if (isWriteMode) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "HeroScale"
    )

    // Smooth color transition between modes
    val containerColor by animateColorAsState(
        targetValue = if (isWriteMode)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.secondaryContainer,
        animationSpec = spring(stiffness = Spring.StiffnessVeryLow)
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        "Wi-Fi Tap",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Normal // Reduced weight
                    )
                }
            )
        }
    ) { innerPadding ->
        // Use the innerPadding from Scaffold to prevent overlap with the Top Bar
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 28.dp), // Increased horizontal padding
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // HERO CARD: Animated Scale and Color
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .scale(cardScale) // Applies the bouncy animation
                    .graphicsLayer { shadowElevation = 8f },
                shape = RoundedCornerShape(56.dp), // Extreme roundedness
                colors = CardDefaults.cardColors(containerColor = containerColor)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isWriteMode) "Broadcasting" else "Ready",
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Medium // Less bold
                        )
                        Text(
                            text = if (isWriteMode) "Tap to share info" else "Waiting for host...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (isWriteMode) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = ssid,
                        onValueChange = { ssid = it; onDataChange("$ssid|$password") },
                        label = { Text("Network SSID") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; onDataChange("$ssid|$password") },
                        label = { Text("Password") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // THE PRIMARY ACTION BUTTON
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
                    fontWeight = FontWeight.Normal // Not bold
                )
            }
        }
    }
}