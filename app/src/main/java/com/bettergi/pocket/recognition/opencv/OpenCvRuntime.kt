package com.bettergi.pocket.recognition.opencv

import org.opencv.android.OpenCVLoader

object OpenCvRuntime {
    @Volatile
    private var loaded: Boolean = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            loaded = loadAndroid() || loadDesktop()
            return loaded
        }
    }

    private fun loadAndroid(): Boolean {
        return try {
            OpenCVLoader.initLocal()
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: NoClassDefFoundError) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * JVM 单测没有 Android 的 opencv.so，改加载 `nu.pattern.OpenCV`（org.openpnp:opencv）。
     */
    private fun loadDesktop(): Boolean {
        return try {
            val type = Class.forName("nu.pattern.OpenCV")
            val loadLocally = type.methods.firstOrNull { it.name == "loadLocally" && it.parameterCount == 0 }
            if (loadLocally != null) {
                loadLocally.invoke(null)
                true
            } else {
                val loadShared = type.methods.firstOrNull { it.name == "loadShared" && it.parameterCount == 0 }
                    ?: return false
                loadShared.invoke(null)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }
}
