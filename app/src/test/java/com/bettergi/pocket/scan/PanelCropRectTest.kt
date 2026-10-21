package com.bettergi.pocket.scan

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/**
 * S1 裁片（#69 流水线第一步）的两道闸门。
 *
 * 背景：裁片漏一块 = 解析静默读空（OCR 返回 ""、vote 读 0），日志上只表现为"这一格没读到"，
 * 极难归因。2026-10-06 真机 A/B 还证明**导出互比不能当裁片对拍判据**——翻页落点不可控
 * （同版本两轮的 swipe 推进行数不同，跨页身份锚定会合法地跳过重叠格）⇒ 裁片正确性必须离线证。
 *
 * S2a（2026-10-06）：vote 步骤（锁/收藏/祝圣三 zone）搬进消费者读裁片 ⇒ 三 zone 已并入
 * [ScreenProfile.panelCropRect]，并有 [voteZonesContainment] 包含性断言（独立推导互为对照）。
 */
class PanelCropRectTest {

    companion object {
        fun assetsDir(): File {
            var dir = File(System.getProperty("user.dir"))
            repeat(4) {
                val candidate = File(dir, "src/main/assets/dsl")
                if (candidate.isDirectory) return candidate
                dir = dir.parentFile ?: return@repeat
            }
            error("src/main/assets/dsl not found from ${System.getProperty("user.dir")}")
        }

        fun profile(file: String, w: Int, h: Int): ScreenProfile {
            val p = ScreenProfile(JSONObject(File(assetsDir(), file).readText()))
            p.calibrate(w, h)
            return p
        }

        /** 独立遍历：panels.<key> 下所有「4 个整数的数组」叶子（与生产实现分开写，互为对照）。 */
        fun leafRects(p: ScreenProfile, panelKey: String): List<FrameRect> {
            val obj = p.rawObject("panels.$panelKey") ?: return emptyList()
            val out = ArrayList<FrameRect>()
            fun walk(node: Any?) {
                when (node) {
                    is JSONArray -> {
                        if (node.length() == 4 && (0..3).all { node.opt(it) is Int }) {
                            out += p.scaleRect(node.getInt(0), node.getInt(1), node.getInt(2), node.getInt(3))
                        } else {
                            for (i in 0 until node.length()) walk(node.opt(i))
                        }
                    }
                    is JSONObject -> for (k in node.keys()) walk(node.opt(k))
                }
            }
            walk(obj)
            return out
        }

        /** 独立复算 starBand 五格（与 countStars 同式）。 */
        fun starCells(p: ScreenProfile, panelKey: String): List<FrameRect> {
            val band = p.rawObject("panels.$panelKey")?.optJSONObject("starBand") ?: return emptyList()
            val ys = band.getJSONArray("y")
            val top = p.scale(ys.getInt(0), p.scaleY)
            val bottom = p.scale(ys.getInt(1), p.scaleY)
            val x0 = band.getInt("x0")
            val pitch = band.getInt("pitch")
            val cell = band.optInt("cell", 46)
            return (0 until 5).map { i ->
                FrameRect(
                    left = p.scale(x0 + i * pitch, p.scaleX),
                    top = top,
                    right = p.scale(x0 + i * pitch + cell, p.scaleX),
                    bottom = bottom,
                )
            }
        }

        fun allPanelRects(p: ScreenProfile, panelKey: String): List<FrameRect> =
            leafRects(p, panelKey) + starCells(p, panelKey)

        /**
         * S2a：独立复算 vote 三 zone 的帧坐标包围盒（与生产 panelCropRect 的并集逻辑分开写）。
         * lock/astral 是 rect；zhusheng 是采样点表（每点 5×5，±8px 包围）。
         */
        fun voteZones(p: ScreenProfile): List<Pair<String, FrameRect>> {
            val zones = p.rawObject("zones") ?: return emptyList()
            val out = ArrayList<Pair<String, FrameRect>>()
            fun eat(name: String, r: FrameRect) = out.add("$name" to r)
            for (key in listOf("artifact.panel.lock", "artifact.panel.astral")) {
                (zones.optJSONObject(key)?.optJSONArray("rect"))?.takeIf { it.length() == 4 }?.let { r ->
                    eat(key, p.scaleRect(r.getInt(0), r.getInt(1), r.getInt(2), r.getInt(3)))
                }
            }
            zones.optJSONObject("artifact.panel.zhusheng")?.optJSONArray("points")?.let { pts ->
                var box: FrameRect? = null
                for (i in 0 until pts.length()) {
                    val pt = pts.getJSONArray(i)
                    val cx = p.scale(pt.getInt(0), p.scaleX)
                    val cy = p.scale(pt.getInt(1), p.scaleY)
                    box = box?.let {
                        FrameRect(
                            minOf(it.left, cx - 8), minOf(it.top, cy - 8),
                            maxOf(it.right, cx + 8), maxOf(it.bottom, cy + 8),
                        )
                    } ?: FrameRect(cx - 8, cy - 8, cx + 8, cy + 8)
                }
                box?.let { eat("artifact.panel.zhusheng", it) }
            }
            return out
        }
    }

    private fun check(file: String, w: Int, h: Int, panelKey: String) {
        val p = profile(file, w, h)
        val crop = p.panelCropRect(panelKey)
        assertTrue("$file/$panelKey 必须有裁片并集", crop != null)
        crop!!
        for ((i, r) in allPanelRects(p, panelKey).withIndex()) {
            assertTrue(
                "$file/$panelKey 裁片漏了第 $i 个 rect $r（crop=$crop）",
                r.left >= crop.left && r.top >= crop.top && r.right <= crop.right && r.bottom <= crop.bottom,
            )
        }
        assertTrue("$file/$panelKey 裁片越出帧界", crop.left >= 0 && crop.top >= 0 && crop.right <= w && crop.bottom <= h)
        val frameArea = w.toLong() * h
        val cropArea = crop.width.toLong() * crop.height
        assertTrue(
            "$file/$panelKey 裁片面积占比 ${cropArea * 100 / frameArea}% ≥ 50% ⇒ 遍历疑似把整屏并进去了",
            cropArea * 2 < frameArea,
        )
    }

    @Test
    fun `三档面板裁片并集罩住全部解析rect与星带`() {
        check("profiles.json", 3200, 1440, "artifact_backpack")
        check("profiles.json", 3200, 1440, "artifact_manage")
        check("profiles.json", 3200, 1440, "weapon_backpack")
        check("profiles_2560x1440.json", 2560, 1440, "artifact_backpack")
        check("profiles_2560x1440.json", 2560, 1440, "weapon_backpack")
        check("profiles_2244x1080.json", 2244, 1080, "artifact_backpack")
        check("profiles_2244x1080.json", 2244, 1080, "weapon_backpack")
    }

    @Test
    fun `裁片并集带缓存且幂等`() {
        val p = profile("profiles_2244x1080.json", 2244, 1080)
        val a = p.panelCropRect("artifact_backpack")
        val b = p.panelCropRect("artifact_backpack")
        assertTrue("缓存应返回同一实例", a === b)
    }

    /**
     * S2a 闸门：vote 三 zone（锁钮/收藏星/祝圣采样点）必须落在 artifact 面板裁片并集内。
     * 漏任何一个 ⇒ vote 在消费者侧静默读 0（锁态/收藏态/祝圣全丢，导出字段错但不报错）。
     */
    @Test
    fun `vote三zone落在artifact裁片并集内`() {
        for ((file, w, h) in listOf(
            Triple("profiles.json", 3200, 1440),
            Triple("profiles_2560x1440.json", 2560, 1440),
            Triple("profiles_2244x1080.json", 2244, 1080),
        )) {
            val p = profile(file, w, h)
            val crop = p.panelCropRect("artifact_backpack")
            assertTrue("$file 必须有裁片并集", crop != null)
            crop!!
            for ((name, zr) in voteZones(p)) {
                assertTrue(
                    "$file vote zone $name ($zr) 落在裁片并集外（crop=$crop）⇒ 消费者侧 vote 会静默读 0",
                    zr.left >= crop.left && zr.top >= crop.top && zr.right <= crop.right && zr.bottom <= crop.bottom,
                )
            }
        }
    }
}

/**
 * 裁片像素对拍：`crop@(rect−origin)` 必须与 `frame@rect` **逐像素相同**。
 *
 * OCR/vote 只读 rect 内的像素，模型对同输入确定 ⇒ 像素恒等即解析恒等，不需要跑 OCR。
 * 两种帧：
 * - **合成编码帧**：每像素值 = 坐标的确定函数 ⇒ 任何 origin/rect 算术错（差 1px、轴反、
 *   缩放混用）都会立刻在某个 rect 上显形，且三档都跑；
 * - **真机帧夹具** `artifact_panel_2244.jpg`（2026-10-06 华为 panelshots 首格）：
 *   走真实 calibrate(2244,1080) 与 JPEG 解码路径，挡住"合成帧太规整"的盲区。
 */
class CropOriginParityTest {

    private fun patternFrame(w: Int, h: Int): Mat {
        val m = Mat(h, w, CvType.CV_8UC3)
        val row = ByteArray(w * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                row[x * 3] = ((x * 7 + y * 13) % 251).toByte()
                row[x * 3 + 1] = ((x * 11 + y * 5) % 251).toByte()
                row[x * 3 + 2] = ((x * 3 + y * 17) % 251).toByte()
            }
            m.put(y, 0, row)
        }
        return m
    }

    /** 对每个面板 rect 比左缘整列、底缘整行、右缘整列：frame@rect 与 crop@(rect−origin)。 */
    private fun assertPixelParity(frame: Mat, panelKey: String, file: String, w: Int, h: Int) {
        val p = PanelCropRectTest.profile(file, w, h)
        val cropRect = p.panelCropRect(panelKey)!!
        val x0 = cropRect.left.coerceIn(0, frame.cols() - 1)
        val y0 = cropRect.top.coerceIn(0, frame.rows() - 1)
        val x1 = cropRect.right.coerceIn(x0 + 1, frame.cols())
        val y1 = cropRect.bottom.coerceIn(y0 + 1, frame.rows())
        val view = Mat(frame, Rect(x0, y0, x1 - x0, y1 - y0))
        val crop = Mat()
        try {
            view.copyTo(crop)
            val a = ByteArray(3)
            val b = ByteArray(3)
            for (r in PanelCropRectTest.allPanelRects(p, panelKey)) {
                for (y in r.top until r.bottom) {
                    frame.get(y, r.left, a)
                    crop.get(y - y0, r.left - x0, b)
                    assertTrue("$file/$panelKey rect=$r 左缘 (${y},${r.left}) 不一致", a.contentEquals(b))
                }
                val yLast = r.bottom - 1
                val rowF = ByteArray(r.width * 3)
                val rowC = ByteArray(r.width * 3)
                frame.get(yLast, r.left, rowF)
                crop.get(yLast - y0, r.left - x0, rowC)
                assertTrue("$file/$panelKey rect=$r 底缘行不一致", rowF.contentEquals(rowC))
                val xLast = r.right - 1
                for (y in r.top until r.bottom) {
                    frame.get(y, xLast, a)
                    crop.get(y - y0, xLast - x0, b)
                    assertTrue("$file/$panelKey rect=$r 右缘 (${y},${xLast}) 不一致", a.contentEquals(b))
                }
            }
        } finally {
            view.release()
            crop.release()
        }
    }

    @Test
    fun `合成编码帧三档裁片与整帧逐像素一致`() {
        nu.pattern.OpenCV.loadLocally()
        Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        for ((file, w, h) in listOf(
            Triple("profiles.json", 3200, 1440),
            Triple("profiles_2560x1440.json", 2560, 1440),
            Triple("profiles_2244x1080.json", 2244, 1080),
        )) {
            val frame = patternFrame(w, h)
            try {
                assertPixelParity(frame, "artifact_backpack", file, w, h)
                assertPixelParity(frame, "weapon_backpack", file, w, h)
            } finally {
                frame.release()
            }
        }
    }

    @Test
    fun `真机2244面板帧裁片与整帧逐像素一致`() {
        nu.pattern.OpenCV.loadLocally()
        Mat(2, 2, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0)).release()
        val f = File.createTempFile("artifact_panel_2244", ".jpg").also {
            javaClass.getResourceAsStream("/artifact_panel_2244.jpg")!!.copyTo(it.outputStream())
        }
        val frame = Imgcodecs.imread(f.absolutePath)
        assertTrue("真机帧加载失败", !frame.empty())
        try {
            assertPixelParity(frame, "artifact_backpack", "profiles_2244x1080.json", frame.cols(), frame.rows())
        } finally {
            frame.release()
        }
    }
}
