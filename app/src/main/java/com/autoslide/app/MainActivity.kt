package com.autoslide.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvAccessibility: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var tvEngine: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var rbDouyin: RadioButton
    private lateinit var rbKuaishou: RadioButton

    private val engineListener: (SwipeEngine.State, SwipeEngine.State) -> Unit = { _, _ ->
        runOnUiThread { refreshEngineState() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccessibility = findViewById(R.id.tvAccessibility)
        tvOverlay = findViewById(R.id.tvOverlay)
        tvEngine = findViewById(R.id.tvEngine)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        rbDouyin = findViewById(R.id.rbDouyin)
        rbKuaishou = findViewById(R.id.rbKuaishou)

        findViewById<Button>(R.id.btnAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOverlaySettings).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }

        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener {
            SwipeEngine.stop()
            refreshEngineState()
        }
    }

    override fun onResume() {
        super.onResume()
        SwipeEngine.addListener(engineListener)
        refreshStatus()
        refreshEngineState()
    }

    override fun onPause() {
        SwipeEngine.removeListener(engineListener)
        super.onPause()
    }

    private fun selectedTarget(): TargetApp =
        if (rbKuaishou.isChecked) TargetApp.KUAISHOU else TargetApp.DOUYIN

    private fun onStartClicked() {
        val target = selectedTarget()
        if (!SwipeEngine.dispatcherReady) {
            toast("请先在无障碍设置中开启「自动滑刷」服务")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            toast("未授权悬浮窗：将无法在目标应用内暂停/停止")
        }
        if (isTargetInstalled(target)) {
            SwipeEngine.target = target
            val launch = packageManager.getLaunchIntentForPackage(target.packageName)
            launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            toast("${target.label} 未安装，请在应用商店安装后再试")
        }
        val started = SwipeEngine.start()
        refreshEngineState()
        if (!started) {
            toast("启动失败：无障碍服务未连接")
        }
    }

    private fun isTargetInstalled(target: TargetApp): Boolean =
        packageManager.getLaunchIntentForPackage(target.packageName) != null

    private fun refreshStatus() {
        tvAccessibility.text = if (isAccessibilityEnabled()) "无障碍服务：已开启" else "无障碍服务：未开启（必须）"
        tvOverlay.text = if (Settings.canDrawOverlays(this)) "悬浮窗权限：已授权" else "悬浮窗权限：未授权（建议）"
        rbDouyin.text = getString(
            R.string.target_with_state,
            TargetApp.DOUYIN.label,
            if (isTargetInstalled(TargetApp.DOUYIN)) "" else "（未安装）",
        )
        rbKuaishou.text = getString(
            R.string.target_with_state,
            TargetApp.KUAISHOU.label,
            if (isTargetInstalled(TargetApp.KUAISHOU)) "" else "（未安装）",
        )
    }

    private fun refreshEngineState() {
        val text = when (SwipeEngine.state) {
            SwipeEngine.State.IDLE -> "状态：未启动"
            SwipeEngine.State.RUNNING -> "状态：自动滑动中（每 5~30 秒随机切换）"
            SwipeEngine.State.PAUSED -> "状态：已暂停"
        }
        tvEngine.text = text
        btnStart.visibility = if (SwipeEngine.state == SwipeEngine.State.IDLE) View.VISIBLE else View.GONE
        btnStop.visibility = if (SwipeEngine.state == SwipeEngine.State.IDLE) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val cn = ComponentName(this, AutoSwipeAccessibilityService::class.java)
        return enabled.contains(cn.flattenToString(), ignoreCase = true) ||
            enabled.contains("${packageName}/.AutoSwipeAccessibilityService", ignoreCase = true)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
