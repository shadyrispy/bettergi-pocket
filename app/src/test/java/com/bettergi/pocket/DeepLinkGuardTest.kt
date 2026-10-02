package com.bettergi.pocket

import com.bettergi.pocket.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ★ A29 深链白名单的纯函数单测：`MainActivity.isLaunchDeepLink(scheme, host, path)`。
 * 覆盖：精确命中、scheme/host/path 各自被篡改、null、大小写、多段 path、带 query 的变形。
 * （Provider 错误 Bundle 的构造涉及 android.os.Bundle，JVM 单测无 Robolectric，真机 pending。）
 */
class DeepLinkGuardTest {

    private fun isAllowed(scheme: String?, host: String?, path: String?): Boolean =
        MainActivity.isLaunchDeepLink(scheme, host, path)

    @Test
    fun exact_launch_uri_allowed() {
        assertTrue(isAllowed("bettergi", "p", "/launch"))
    }

    @Test
    fun wrong_scheme_rejected() {
        assertFalse(isAllowed("https", "p", "/launch"))
        assertFalse(isAllowed("bettergii", "p", "/launch"))
        assertFalse(isAllowed(null, "p", "/launch"))
    }

    @Test
    fun wrong_host_rejected() {
        assertFalse(isAllowed("bettergi", "evil", "/launch"))
        assertFalse(isAllowed("bettergi", "p.evil.com", "/launch"))
        assertFalse(isAllowed("bettergi", null, "/launch"))
    }

    @Test
    fun wrong_path_rejected() {
        assertFalse(isAllowed("bettergi", "p", "/share"))
        assertFalse(isAllowed("bettergi", "p", "/launch/extra"))
        assertFalse(isAllowed("bettergi", "p", "/launch?x=1"))
        assertFalse(isAllowed("bettergi", "p", null))
    }

    @Test
    fun case_variation_rejected() {
        // 白名单按字面量精确比对：清单声明的就是小写字面量，任何变形都不放行
        assertFalse(isAllowed("BetterGI", "P", "/Launch"))
    }
}
