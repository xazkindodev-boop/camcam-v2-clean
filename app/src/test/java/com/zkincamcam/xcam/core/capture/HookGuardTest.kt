package com.zkincamcam.xcam.core.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class HookGuardTest {

    @Test
    fun defaultStateIsNotIgnoring() {
        assertFalse(HookGuard.isIgnoring())
    }

    @Test
    fun withGuardSetsFlagInsideAndRestoresAfter() {
        var observedInside = true
        HookGuard.withGuard {
            observedInside = HookGuard.isIgnoring()
        }
        assertTrue("flag harus true di dalam withGuard", observedInside)
        assertFalse("flag harus pulih setelah withGuard", HookGuard.isIgnoring())
    }

    @Test
    fun withGuardRestoresEvenWhenBlockThrows() {
        try {
            HookGuard.withGuard { error("boom") }
        } catch (_: IllegalStateException) {
            // expected
        }
        assertFalse("exception tidak boleh meninggalkan flag nyala", HookGuard.isIgnoring())
    }

    @Test
    fun nestedWithGuardKeepsFlagUntilOutermostExits() {
        HookGuard.withGuard {
            HookGuard.withGuard {
                assertTrue(HookGuard.isIgnoring())
            }
            // guard bersarang tidak boleh mematikan flag milik guard luar
            assertTrue("nested exit tidak boleh mematikan guard luar", HookGuard.isIgnoring())
        }
        assertFalse(HookGuard.isIgnoring())
    }

    @Test
    fun guardIsPerThread() {
        val pool = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        HookGuard.withGuard {
            pool.execute {
                assertFalse("thread lain tidak boleh terpengaruh guard thread ini", HookGuard.isIgnoring())
                latch.countDown()
            }
            Thread.sleep(200)
        }
        pool.shutdown()
        latch.await()
    }

    @Test
    fun withGuardReturnsBlockValue() {
        val result = HookGuard.withGuard { 42 }
        assertTrue(result == 42)
    }

    // --- enter/exit: legacy paired setter semantics (setIgnoringHooks(true)/(false)) ---

    @Test
    fun enterExitPairReturnsToNotIgnoring() {
        HookGuard.enter()
        assertTrue(HookGuard.isIgnoring())
        HookGuard.exit()
        assertFalse(HookGuard.isIgnoring())
    }

    @Test
    fun enterIsReentrantSoJavaTryFinallyPairsStayBalanced() {
        HookGuard.enter()
        HookGuard.enter()
        HookGuard.exit()
        assertTrue("satu exit tidak boleh mematikan dua enter", HookGuard.isIgnoring())
        HookGuard.exit()
        assertFalse(HookGuard.isIgnoring())
    }

    @Test
    fun exitWithoutEnterNeverGoesNegative() {
        HookGuard.exit()
        HookGuard.exit()
        assertFalse(HookGuard.isIgnoring())
        HookGuard.enter()
        assertTrue("counter tidak boleh negatif lalu butuh 3 enter untuk aktif", HookGuard.isIgnoring())
        HookGuard.exit()
        assertFalse(HookGuard.isIgnoring())
    }

    @Test
    fun enterExitAndWithGuardAreInterchangeable() {
        HookGuard.enter()
        HookGuard.withGuard {
            assertTrue(HookGuard.isIgnoring())
        }
        assertTrue("withGuard tidak boleh mematikan enter yang belum di-exit", HookGuard.isIgnoring())
        HookGuard.exit()
        assertFalse(HookGuard.isIgnoring())
    }
}
