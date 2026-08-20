package net.wastu.binderclip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceAutostartTest {
    @Test
    fun startsAfterBootWhenPaired() {
        assertTrue(ServiceAutostart.shouldStart("android.intent.action.BOOT_COMPLETED", paired = true))
        assertTrue(ServiceAutostart.shouldStart("android.intent.action.MY_PACKAGE_REPLACED", paired = true))
        assertTrue(ServiceAutostart.shouldStart("android.intent.action.QUICKBOOT_POWERON", paired = true))
        assertTrue(ServiceAutostart.shouldStart("com.htc.intent.action.QUICKBOOT_POWERON", paired = true))
    }

    @Test
    fun doesNotStartWhenUnpairedOrUnknownAction() {
        assertFalse(ServiceAutostart.shouldStart("android.intent.action.BOOT_COMPLETED", paired = false))
        assertFalse(ServiceAutostart.shouldStart("android.intent.action.SCREEN_ON", paired = true))
        assertFalse(ServiceAutostart.shouldStart(null, paired = true))
        assertFalse(ServiceAutostart.shouldStart("", paired = true))
    }

    @Test
    fun bootScriptStartsForegroundServiceAndWaitsForBoot() {
        val script = ServiceAutostart.bootScript("net.wastu.binderclip")
        assertTrue(script.startsWith("#!/system/bin/sh\n"))
        assertTrue(script.contains("PKG='net.wastu.binderclip'"))
        assertTrue(script.contains("SVC=\"\$PKG/.BinderClipService\""))
        assertTrue(script.contains("getprop sys.boot_completed"))
        assertTrue(script.contains("dumpsys deviceidle whitelist +\"\$PKG\""))
        assertTrue(script.contains("cmd appops set \"\$PKG\" RUN_ANY_IN_BACKGROUND allow"))
        assertTrue(script.contains("am start-foreground-service -n \"\$SVC\""))
        assertTrue(script.contains("am startservice -n \"\$SVC\""))
        assertFalse(script.contains("su "))
        assertFalse(script.contains("{{PKG}}"))
        assertFalse(script.contains("{{$}}"))
    }

    @Test
    fun bootScriptRejectsUnsafePackageName() {
        assertThrows(IllegalArgumentException::class.java) {
            ServiceAutostart.bootScript("net.wastu.binderclip; rm -rf /")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServiceAutostart.bootScript("binderclip")
        }
        assertEquals("net.wastu.binderclip", ServiceAutostart.requireSafePackageName("net.wastu.binderclip"))
    }
}
