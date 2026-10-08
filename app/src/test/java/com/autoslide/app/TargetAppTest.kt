package com.autoslide.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetAppTest {

    // ---------- TC-19: 包名映射 ----------
    @Test
    fun `TC19 package names are correct`() {
        assertEquals("com.ss.android.ugc.aweme", TargetApp.DOUYIN.packageName)
        assertEquals("抖音", TargetApp.DOUYIN.label)
        assertEquals("com.smile.gifmaker", TargetApp.KUAISHOU.packageName)
        assertEquals("快手", TargetApp.KUAISHOU.label)
    }

    // ---------- TC-19b: 包名反查 ----------
    @Test
    fun `TC19b reverse lookup by package name`() {
        assertEquals(TargetApp.DOUYIN, TargetApp.fromPackageName("com.ss.android.ugc.aweme"))
        assertEquals(TargetApp.KUAISHOU, TargetApp.fromPackageName("com.smile.gifmaker"))
    }

    // ---------- TC-20: 未知包名 ----------
    @Test
    fun `TC20 unknown package returns null`() {
        assertNull(TargetApp.fromPackageName("com.unknown.app"))
        assertNull(TargetApp.fromPackageName(""))
    }
}
