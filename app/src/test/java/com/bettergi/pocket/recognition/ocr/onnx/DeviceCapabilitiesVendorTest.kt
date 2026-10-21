package com.bettergi.pocket.recognition.ocr.onnx

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-10-03 小米 15（2410DPN6CC / SM8750）实测回归：QNN 那道双闸的**第一闸**判错了厂商。
 *
 * 设备事实（adb 量到，不是推断）：`ro.soc.manufacturer=QTI`、`ro.soc.model=SM8750`、
 * `ro.hardware=qcom`、`ro.board.platform=sun`。而 `readSoc()` 只要 `SOC_MANUFACTURER` 非空且
 * 不是 "unknown" 就直接用它归一、**不走** `ro.hardware` 回退路 ⇒ 旧写法把 "qti" 归成 `unknown`，
 * 日志里就是 `soc=unknown/SM8750 ... qnn=false`。
 *
 * 为什么这条值得钉住而不是一句"改个字符串"：厂商判成 unknown 时
 * [DeviceCapabilities.collect] 里的 `probeQnnLibs()` **提前返回 false**（连 loadLibrary 都没试），
 * [EpTierPicker.candidatesFor] 的双闸也因此永不放行 QNN ⇒ 从此在日志上分不清
 * "这机器的 QAIRT 库没对应用公开" 与 "厂商根本没认出来"两种成因。而小米 15 恰好**两条同时成立**
 * （库在 /odm/lib64、不在任何 public.libraries.txt 里），不修就只能永远靠猜。
 */
class DeviceCapabilitiesVendorTest {

    @Test
    fun `QTI 是高通 —— 小米 15 实测值，旧写法返回 unknown`() {
        assertEquals("qualcomm", DeviceCapabilities.normalizeVendor("qti"))
        // 原有三条不许退化
        assertEquals("qualcomm", DeviceCapabilities.normalizeVendor("qualcomm"))
        assertEquals("qualcomm", DeviceCapabilities.normalizeVendor("qcom"))
        assertEquals("qualcomm", DeviceCapabilities.normalizeVendor("snapdragon 8 elite"))
    }

    @Test
    fun `qti 用精确等值 —— 别把不相干的串收成高通`() {
        // 反例守卫：写成 contains("qti") 会把 "qtix" 也算成高通
        assertEquals("unknown", DeviceCapabilities.normalizeVendor("qtix"))
        assertEquals("hisilicon", DeviceCapabilities.normalizeVendor("kirin970"))
        assertEquals("mediatek", DeviceCapabilities.normalizeVendor("mt6893"))
    }

    @Test
    fun `QNN 双闸：厂商对但库加载不了 ⇒ 仍不进候选（第二闸独立生效）`() {
        val qualcommNoLib = DeviceCapabilities(
            socVendor = "qualcomm", socModel = "SM8750", isArm64 = true,
            cpuCount = 8, bigCoreCount = 6, bigCoreMaxFreqKHz = 2_227_200,
            qnnLibsLoadable = false,
        )
        assertEquals(listOf(EpTierPicker.Tier.CPU), EpTierPicker.candidatesFor(qualcommNoLib))

        val libReady = qualcommNoLib.copy(qnnLibsLoadable = true)
        assertEquals(
            listOf(EpTierPicker.Tier.QNN, EpTierPicker.Tier.CPU),
            EpTierPicker.candidatesFor(libReady),
        )

        // 厂商没认出来（本次修的 bug 的现状）⇒ 即便库能加载也不放行，双闸各自独立
        val unknownVendor = libReady.copy(socVendor = "unknown")
        assertEquals(listOf(EpTierPicker.Tier.CPU), EpTierPicker.candidatesFor(unknownVendor))
    }
}
