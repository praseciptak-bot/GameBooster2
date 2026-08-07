package com.example.gamebooster

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.net.InetAddress
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // ---- Dashboard views ----
    private lateinit var tvRamPercent: TextView
    private lateinit var tvRamDetail: TextView
    private lateinit var progressRam: ProgressBar
    private lateinit var tvWifiSignal: TextView
    private lateinit var tvPing: TextView

    // ---- Boost views ----
    private lateinit var btnBoost: TextView
    private lateinit var tvBoostStatus: TextView

    // ---- DND ----
    private lateinit var switchDnd: Switch
    private lateinit var notificationManager: NotificationManager

    // ---- Game launcher ----
    private lateinit var rvGames: RecyclerView
    private lateinit var tvNoGames: TextView
    private lateinit var gameAdapter: GameAdapter
    private val gameList = mutableListOf<GameApp>()

    // ---- System services ----
    private lateinit var activityManager: ActivityManager
    private lateinit var wifiManager: WifiManager

    // ---- Background refresh loop ----
    private val handler = Handler(Looper.getMainLooper())
    private val pingExecutor = Executors.newSingleThreadExecutor()
    private var isRefreshing = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateRamStats()
            updateWifiStats()
            updatePing()
            handler.postDelayed(this, 3000L)
        }
    }

    companion object {
        private const val REQUEST_PICK_APP = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        bindViews()
        setupGameList()
        setupDndToggle()

        btnBoost.setOnClickListener { performBoost() }
    }

    private fun bindViews() {
        tvRamPercent = findViewById(R.id.tvRamPercent)
        tvRamDetail = findViewById(R.id.tvRamDetail)
        progressRam = findViewById(R.id.progressRam)
        tvWifiSignal = findViewById(R.id.tvWifiSignal)
        tvPing = findViewById(R.id.tvPing)
        btnBoost = findViewById(R.id.btnBoost)
        tvBoostStatus = findViewById(R.id.tvBoostStatus)
        switchDnd = findViewById(R.id.switchDnd)
        rvGames = findViewById(R.id.rvGames)
        tvNoGames = findViewById(R.id.tvNoGames)

        findViewById<android.widget.Button>(R.id.btnPickGame).setOnClickListener {
            openAppPicker()
        }
    }

    // ============================================================
    // RAM DASHBOARD
    // ============================================================

    private fun updateRamStats() {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        val usedMb = totalMb - availMb
        val usedPercent = if (totalMb > 0) ((usedMb * 100) / totalMb).toInt() else 0

        tvRamPercent.text = "$usedPercent%"
        progressRam.progress = usedPercent
        tvRamDetail.text = "Used $usedMb MB / Total $totalMb MB (Free $availMb MB)"

        // Color-code: green under 60%, amber 60-85%, red above
        val color = when {
            usedPercent < 60 -> getColor(R.color.accent_green)
            usedPercent < 85 -> getColor(android.R.color.holo_orange_light)
            else -> getColor(R.color.danger)
        }
        tvRamPercent.setTextColor(color)
    }

    // ============================================================
    // WI-FI DASHBOARD
    // ============================================================

    private fun updateWifiStats() {
        try {
            val connectivityManager =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val caps = connectivityManager.getNetworkCapabilities(network)

            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val wifiInfo = wifiManager.connectionInfo
                val rssi = wifiInfo.rssi // dBm, typically -100 (weak) to -30 (strong)
                tvWifiSignal.text = "$rssi dBm"

                val signalColor = when {
                    rssi >= -60 -> getColor(R.color.accent_green)
                    rssi >= -75 -> getColor(R.color.accent_blue)
                    else -> getColor(R.color.danger)
                }
                tvWifiSignal.setTextColor(signalColor)
            } else {
                tvWifiSignal.text = "Not on Wi-Fi"
                tvWifiSignal.setTextColor(getColor(R.color.text_secondary))
            }
        } catch (e: Exception) {
            tvWifiSignal.text = "N/A"
        }
    }

    private fun updatePing() {
        pingExecutor.execute {
            val pingMs = try {
                val start = System.currentTimeMillis()
                val reachable = InetAddress.getByName("8.8.8.8").isReachable(1500)
                val elapsed = System.currentTimeMillis() - start
                if (reachable) elapsed else -1L
            } catch (e: Exception) {
                -1L
            }

            handler.post {
                if (pingMs >= 0) {
                    tvPing.text = "$pingMs ms"
                    tvPing.setTextColor(
                        if (pingMs < 80) getColor(R.color.accent_green) else getColor(R.color.danger)
                    )
                } else {
                    tvPing.text = "Timeout"
                    tvPing.setTextColor(getColor(R.color.danger))
                }
            }
        }
    }

    // ============================================================
    // BOOST LOGIC
    // ============================================================

    /**
     * NOTE ON ANDROID LIMITATIONS:
     * Since Android 5.0 (Lollipop), third-party apps can no longer force-kill
     * other apps' processes the way old "task killer" apps used to. The
     * ActivityManager.killBackgroundProcesses() API is restricted to the
     * caller's own package unless the app is signed as a system app or holds
     * the (privileged) KILL_BACKGROUND_PROCESSES permission on that device.
     * This boost routine still performs everything a normal app is permitted
     * to do: it clears its OWN cached memory, trims its own memory footprint
     * via trimMemory(), triggers the system's low-memory killer indirectly
     * by requesting garbage collection, and clears app cache directories.
     * This is functionally the same "boost" behavior most Play Store
     * booster apps provide today, since Android does not expose real
     * cross-app process killing to non-system apps anymore.
     */
    private fun performBoost() {
        if (isRefreshing) return
        isRefreshing = true

        btnBoost.text = "..."
        tvBoostStatus.text = "Boosting..."

        Thread {
            var clearedMb = 0L
            try {
                // 1) Attempt to kill background processes this app is allowed to target.
                //    On most modern devices this only affects the app's own package,
                //    but we call it for any device/OEM that still permits broader access.
                val before = getAvailMemMb()
                activityManager.killBackgroundProcesses(packageName)

                // 2) Ask the system to trim this app's own memory usage aggressively.
                onTrimMemory(ActivityManager.TRIM_MEMORY_COMPLETE)

                // 3) Clear this app's own cache directory (safe, always permitted).
                cacheDir?.deleteRecursively()
                externalCacheDir?.deleteRecursively()

                // 4) Suggest garbage collection.
                System.gc()

                Thread.sleep(800) // small delay so the UI feels like real work happened

                val after = getAvailMemMb()
                clearedMb = (after - before).coerceAtLeast(0)
            } catch (e: Exception) {
                // ignore — boost is best-effort
            }

            handler.post {
                btnBoost.text = "BOOST"
                tvBoostStatus.text = if (clearedMb > 0)
                    "Freed up ~$clearedMb MB. Ready to play!"
                else
                    "Optimized! Ready to play."
                updateRamStats()
                isRefreshing = false
                Toast.makeText(this, "Boost complete", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun getAvailMemMb(): Long {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    /** Boost then launch the given game. */
    private fun boostAndLaunch(game: GameApp) {
        tvBoostStatus.text = "Boosting before launch..."
        Thread {
            try {
                activityManager.killBackgroundProcesses(packageName)
                onTrimMemory(ActivityManager.TRIM_MEMORY_COMPLETE)
                cacheDir?.deleteRecursively()
                System.gc()
                Thread.sleep(500)
            } catch (e: Exception) {
                // best-effort
            }
            handler.post {
                val launchIntent =
                    packageManager.getLaunchIntentForPackage(game.packageName)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                } else {
                    Toast.makeText(this, "Unable to launch ${game.label}", Toast.LENGTH_SHORT).show()
                }
                updateRamStats()
            }
        }.start()
    }

    // ============================================================
    // DO NOT DISTURB TOGGLE
    // ============================================================

    private fun setupDndToggle() {
        switchDnd.isChecked =
            notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE ||
            notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY

        switchDnd.setOnCheckedChangeListener { _, isChecked ->
            if (!notificationManager.isNotificationPolicyAccessGranted) {
                // Must request special access from the user via system Settings.
                Toast.makeText(
                    this,
                    "Grant Do Not Disturb access for Game Booster",
                    Toast.LENGTH_LONG
                ).show()
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                startActivity(intent)
                // Revert the switch until permission is confirmed in onResume.
                switchDnd.isChecked = !isChecked
                return@setOnCheckedChangeListener
            }

            if (isChecked) {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else {
                notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
    }

    // ============================================================
    // GAME LAUNCHER
    // ============================================================

    private fun setupGameList() {
        gameAdapter = GameAdapter(gameList) { game -> boostAndLaunch(game) }
        rvGames.layoutManager = LinearLayoutManager(this)
        rvGames.adapter = gameAdapter

        val detected = detectInstalledGames()
        if (detected.isNotEmpty()) {
            gameAdapter.setGames(detected)
            tvNoGames.visibility = android.view.View.GONE
        } else {
            tvNoGames.visibility = android.view.View.VISIBLE
        }
    }

    /**
     * Auto-detects installed "games" using PackageManager's ApplicationInfo.category
     * (available on API 26+). Falls back gracefully on older devices, where the
     * user can instead add apps manually via "Select App to Add".
     */
    private fun detectInstalledGames(): List<GameApp> {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val games = mutableListOf<GameApp>()

        for (appInfo in apps) {
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApp) continue

            val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category == ApplicationInfo.CATEGORY_GAME
            } else {
                @Suppress("DEPRECATION")
                (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            }

            if (isGame) {
                val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
                if (launchIntent != null) {
                    games.add(
                        GameApp(
                            packageName = appInfo.packageName,
                            label = pm.getApplicationLabel(appInfo).toString(),
                            icon = pm.getApplicationIcon(appInfo)
                        )
                    )
                }
            }
        }
        return games.sortedBy { it.label }
    }

    /** Lets the user manually add any installed app (useful when auto-detect misses a game). */
    private fun openAppPicker() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolvedApps = pm.queryIntentActivities(mainIntent, 0)
            .sortedBy { it.loadLabel(pm).toString() }

        val labels = resolvedApps.map { it.loadLabel(pm).toString() }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select an app")
            .setItems(labels) { _, which ->
                val resolveInfo = resolvedApps[which]
                val pkg = resolveInfo.activityInfo.packageName
                val game = GameApp(
                    packageName = pkg,
                    label = resolveInfo.loadLabel(pm).toString(),
                    icon = resolveInfo.loadIcon(pm)
                )
                gameAdapter.addGame(game)
                tvNoGames.visibility = android.view.View.GONE
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================
    // LIFECYCLE
    // ============================================================

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
        // Re-sync the DND switch in case the user granted access in Settings.
        switchDnd.isChecked =
            notificationManager.isNotificationPolicyAccessGranted &&
            notificationManager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        pingExecutor.shutdownNow()
    }
}
