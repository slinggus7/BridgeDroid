package com.slinggus.bridgedroid

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var bridgeService: BridgeService? = null
    private var isBound = false
    private var isBridgeActive = false
    private var ignoreSwitchEvents = false

    private lateinit var tvBridgeState: TextView
    private lateinit var tvInterfaceDetails: TextView
    private lateinit var tvConsoleLog: TextView
    private lateinit var svConsoleLog: ScrollView
    private lateinit var switchBridge: SwitchMaterial
    private lateinit var switchInterceptDhcp: SwitchMaterial
    private lateinit var rgMode: RadioGroup
    private lateinit var etGuestIp: EditText
    private lateinit var etSubnetMask: EditText
    private lateinit var etRouter: EditText
    private lateinit var etCustomDns: EditText
    private lateinit var etWifiIface: EditText
    private lateinit var etEthIface: EditText

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(tvConsoleLog.text.toString().toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Logs saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra("msg") ?: return
            appendLog(msg)
        }
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BridgeService.LocalBinder
            bridgeService = binder.getService()
            isBound = true

            val running = bridgeService?.isBridgeRunning == true
            isBridgeActive = running
            ignoreSwitchEvents = true
            switchBridge.isChecked = running
            ignoreSwitchEvents = false
            updateStateBadge(running)

            refreshStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bridgeService = null
            isBound = false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvBridgeState = findViewById(R.id.tvBridgeState)
        tvInterfaceDetails = findViewById(R.id.tvInterfaceDetails)
        tvConsoleLog = findViewById(R.id.tvConsoleLog)
        svConsoleLog = findViewById(R.id.svConsoleLog)
        switchBridge = findViewById(R.id.switchBridge)
        switchInterceptDhcp = findViewById(R.id.switchInterceptDhcp)
        rgMode = findViewById(R.id.rgMode)
        etGuestIp = findViewById(R.id.etGuestIp)
        etSubnetMask = findViewById(R.id.etSubnetMask)
        etRouter = findViewById(R.id.etRouter)
        etCustomDns = findViewById(R.id.etCustomDns)
        etWifiIface = findViewById(R.id.etWifiIface)
        etEthIface = findViewById(R.id.etEthIface)

        svConsoleLog.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        Thread {
            val hasRoot = RootShell.isRootAvailable()
            runOnUiThread {
                if (!hasRoot) {
                    appendLog("FATAL: Root access not granted or su binary missing.")
                    Toast.makeText(this, "Root access required!", Toast.LENGTH_LONG).show()
                    switchBridge.isEnabled = false
                } else {
                    appendLog("Root check passed.")
                }
            }
        }.start()

        val bindIntent = Intent(this, BridgeService::class.java)
        bindService(bindIntent, serviceConn, Context.BIND_AUTO_CREATE)

        ContextCompat.registerReceiver(
            this,
            logReceiver,
            IntentFilter("com.netbridge.LOG"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        switchBridge.setOnCheckedChangeListener { _, isChecked ->
            if (ignoreSwitchEvents) return@setOnCheckedChangeListener
            if (isChecked && !isBridgeActive) {
                startBridging()
            } else if (!isChecked && isBridgeActive) {
                stopBridging()
            }
        }

        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("BridgeDroid Logs", tvConsoleLog.text.toString())
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSaveLog).setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            saveLogLauncher.launch("bridgedroid_log_$stamp.txt")
        }

        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            tvConsoleLog.text = ""
        }

        findViewById<Button>(R.id.btnRefreshIfaces).setOnClickListener {
            refreshStatus(verbose = true)
        }

        findViewById<Button>(R.id.btnDumpArp).setOnClickListener {
            Thread {
                val arp = RootShell.execute("cat /proc/net/arp")
                val rules = RootShell.execute("ip rule show")
                val routes = RootShell.execute("ip -4 route show table main")
                runOnUiThread {
                    appendLog("--- /proc/net/arp ---\n" + arp.stdout.joinToString("\n"))
                    appendLog("--- ip rule ---\n" + rules.stdout.take(8).joinToString("\n"))
                    appendLog("--- main routes ---\n" + routes.stdout.joinToString("\n"))
                }
            }.start()
        }

        findViewById<Button>(R.id.btnDumpIptables).setOnClickListener {
            Thread {
                val res = RootShell.execute(
                    "iptables -L FORWARD -v -n",
                    "iptables -t nat -L PREROUTING -v -n",
                    "iptables -t nat -L POSTROUTING -v -n"
                )
                runOnUiThread {
                    appendLog("--- IPTables Counters ---\n" + res.stdout.joinToString("\n"))
                }
            }.start()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && isBridgeActive &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            bridgeService?.promoteToForeground()
        }
    }

    private fun startBridging() {
        val mode = when (rgMode.checkedRadioButtonId) {
            R.id.rbProxyArp -> BridgeManager.Mode.PROXY_ARP
            R.id.rbNat -> BridgeManager.Mode.NAT
            else -> BridgeManager.Mode.AUTO
        }

        val interceptDhcp = switchInterceptDhcp.isChecked
        val wifi = etWifiIface.text.toString().trim()
        val eth = etEthIface.text.toString().trim()
        val guestIp = etGuestIp.text.toString().trim()
        val mask = etSubnetMask.text.toString().trim()
        val router = etRouter.text.toString().trim()
        val dns = etCustomDns.text.toString().trim()

        Thread {
            val success = bridgeService?.bridgeManager?.startBridge(
                mode, interceptDhcp, wifi, eth, guestIp, mask, router, dns
            ) ?: false
            runOnUiThread {
                if (success) {
                    isBridgeActive = true
                    updateStateBadge(true)
                    val fgIntent = Intent(this, BridgeService::class.java).apply {
                        action = BridgeService.ACTION_START_FOREGROUND
                    }
                    ContextCompat.startForegroundService(this, fgIntent)
                    bridgeService?.promoteToForeground("Bridging $eth ↔ $wifi")
                } else {
                    ignoreSwitchEvents = true
                    switchBridge.isChecked = false
                    ignoreSwitchEvents = false
                    isBridgeActive = false
                    updateStateBadge(false)
                }
            }
        }.start()
    }

    private fun stopBridging() {
        val wifi = etWifiIface.text.toString().trim()
        val eth = etEthIface.text.toString().trim()

        Thread {
            bridgeService?.bridgeManager?.stopBridge(wifi, eth)
            runOnUiThread {
                isBridgeActive = false
                updateStateBadge(false)
                bridgeService?.demoteAndStop()
                appendLog("Bridge stopped.")
            }
        }.start()
    }

    private fun updateStateBadge(active: Boolean) {
        if (active) {
            tvBridgeState.text = "ACTIVE"
            tvBridgeState.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            tvBridgeState.text = "INACTIVE"
            tvBridgeState.setTextColor(Color.parseColor("#F44336"))
        }
    }

    private fun refreshStatus(verbose: Boolean = false) {
        val wifi = etWifiIface.text.toString().trim()
        val eth = etEthIface.text.toString().trim()

        Thread {
            val info = bridgeService?.bridgeManager?.detectNetwork(wifi, eth)
            runOnUiThread {
                if (info != null) {
                    tvInterfaceDetails.text =
                        "WiFi: ${if (info.wifiIp.isNotEmpty()) info.wifiHostCidr else "Disconnected"}\n" +
                        "Eth: ${if (info.ethUp) "LINK UP" else "NO CARRIER"} (RX: ${info.ethRxPackets} | TX: ${info.ethTxPackets})"
                    if (verbose) {
                        appendLog("--- Detected Interfaces ---\n" + info.allInterfaces.joinToString("\n"))
                    }
                }
            }
        }.start()
    }

    private fun appendLog(msg: String) {
        val wasAtBottom = tvConsoleLog.height <= svConsoleLog.height ||
            (svConsoleLog.scrollY + svConsoleLog.height >= tvConsoleLog.height - 100)

        tvConsoleLog.append("$msg\n")
        val full = tvConsoleLog.text
        if (full.length > 20000) {
            val cut = full.indexOf('\n', full.length - 14000)
            if (cut > 0) {
                tvConsoleLog.text = full.subSequence(cut + 1, full.length)
            }
        }
        if (wasAtBottom) {
            svConsoleLog.post {
                svConsoleLog.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(logReceiver)
        } catch (_: Exception) {}
        if (isBound) unbindService(serviceConn)
    }
}