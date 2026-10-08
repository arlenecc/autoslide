package com.autoslide.app

/**
 * 目标应用定义（TC-19/20）。
 * 抖音：com.ss.android.ugc.aweme；快手：com.smile.gifmaker。
 */
enum class TargetApp(val packageName: String, val label: String) {
    DOUYIN("com.ss.android.ugc.aweme", "抖音"),
    KUAISHOU("com.smile.gifmaker", "快手");

    companion object {
        fun fromPackageName(name: String): TargetApp? = entries.find { it.packageName == name }
    }
}
