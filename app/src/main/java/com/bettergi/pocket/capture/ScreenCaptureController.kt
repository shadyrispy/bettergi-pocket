package com.bettergi.pocket.capture

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.bettergi.pocket.core.IntRect
import com.bettergi.pocket.core.image.MatOps
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

private data class DisplaySpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

class ScreenCaptureController(
    private val context: Context,
    private val onStoppedExternally: () -> Unit = {},
) {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var callback: MediaProjection.Callback? = null
    private var densityDpi: Int = DisplayMetrics.DENSITY_DEVICE_STABLE
    private var hasCachedFrame = false
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedTimestampNs = 0L
    private var lastFrameElapsedMs = 0L
    private var lastRecoverElapsedMs = 0L
    private var displayCreatedElapsedMs = 0L
    private var firstFrameLogged = false

    private var cachedRgba = ByteArray(0)

    // ---- GC P0（工单 A）：poller 侧常驻转换缓冲 ----

    /** poller 线程私有的 RGBA 4 通道中转 Mat（线程封闭，无并发；见 convertAndPublishFrame）。 */
    private var pollStageRgba: Mat? = null

    /**
     * 缓存帧的每像素字节步长（RGBA_8888 通常 = 4，但**不可硬编码**）。
     *
     * `copyRgbaImage` 已在 `rowStride != rowBytes` 时逐行压缩，故缓存内**行距 = pixelStride**。
     * 供「就绪信号直采」与帧路径基准使用（见 `dsl/verify/_audit/IMAGE-PATH-COST.md` §5.3 坑 1）。
     */
    @Volatile
    private var cachedPixelStride = 4
    @Volatile
    private var frameThreadRunning = false
    private var frameThread: Thread? = null

    /**
     * #109：轮询线程的**代次**。循环条件必须是"我这一代还是当前代"，不能只看
     * [frameThreadRunning] 这个共享开关 —— 否则会发生两件事：
     *
     * ① 旧版 stopFramePollerLocked 的 `join(1000)` 超时（线程正阻塞在 `acquireLatestImage()` 上很常见）
     *    后就把线程判死并置 `frameThread=null`，可它其实还活着；下一次 start 把
     *    [frameThreadRunning] 又置回 true ⇒ **旧线程下一圈读到 true，永远不退出** ⇒ 两条轮询并存。
     *    2026-09-28 实机就是这样：一条 `frame acquired` 正常出帧，另一条
     *    `frame poll null (count=…)` 单调涨到上千且永远拿不到帧（帧都被新一代消费了）。
     * ② 线程体的 `finally` 无条件把 [frameThreadRunning] 置 false ⇒ 一具**旧代尸体**
     *    可以把**新一代**刚置起来的开关抹掉，于是 `ensureFramePollerLocked` 再拉一条，
     *    变成三条并存。同一个根因：开关是共享的，退出条件却该是每线程自己的。
     */
    @Volatile
    private var pollerGeneration = 0

    /**
     * 独立消费线程：循环 acquireLatestImage()，始终把最新帧拷进缓存。
     *
     * 不依赖 OnImageAvailableListener —— 华为 EMUI 上该回调经常不触发，导致
     * 永远收不到首帧。poll 循环（scrcpy 同款范式）在所有 ROM 上都能稳定出帧：
     * 内容变化时系统持续产帧，我们持续消费；内容静态时不再产帧，缓存保留上一帧，
     * OCR 服务到的正是当前屏幕，符合预期。
     */
    private fun startFramePollerLocked() {
        if (frameThreadRunning) return
        // ⚠️ 顺序要紧：**先换代再抬开关**。反过来的话中间那段窗口里仍有一条旧尸体的
        //   `finally` 能看到 `gen == pollerGeneration`（还没换），把刚抬起来的开关又抹掉。
        val gen = ++pollerGeneration
        frameThreadRunning = true
        val thread = Thread({
            var nullCount = 0
            // ★ #46 ⑥：这两条 D 级逐帧日志原来**每次状态跳变都打**。实测 40 分钟一轮
            //   `frame poll null (count=1)` 6.5 万行 + `frame acquired` 6.5 万行 ≈ 11MB，
            //   是全量 logcat 里**我方 tag 中最大的一项**（游戏/系统 tag 另计）。
            //   诊断价值只在"帧率/卡顿趋势"，按秒限流足够（8ms 轮询下 1 秒可跳变上百次）。
            var lastFrameLogMs = 0L
            fun frameLogDue(): Boolean {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameLogMs < FRAME_LOG_MIN_INTERVAL_MS) return false
                lastFrameLogMs = now
                return true
            }
            try {
                while (frameThreadRunning && gen == pollerGeneration) {
                    val reader = synchronized(lock) { imageReader } ?: break
                    val image = try {
                        reader.acquireLatestImage()
                    } catch (_: Throwable) {
                        null
                    }
                    if (image != null) {
                        try {
                            val w = image.width
                            val h = image.height
                            val plane = image.planes.firstOrNull()
                            if (plane != null) {
                                val rowBytes = w * plane.pixelStride
                                val size = h * rowBytes
                                var rgbaSnap: ByteArray? = null
                                synchronized(lock) {
                                    if (cachedRgba.size != size) cachedRgba = ByteArray(size)
                                    MatOps.copyRgbaImage(image, cachedRgba)
                                    cachedWidth = w
                                    cachedHeight = h
                                    cachedPixelStride = plane.pixelStride
                                    cachedTimestampNs = image.timestamp
                                    hasCachedFrame = true
                                    lastFrameElapsedMs = SystemClock.elapsedRealtime()
                                    // GC P0（工单 A）：快照引用（poller 单线程写，转换段在锁外
                                    // 读完之前不会有下一圈拷进来——顺序执行天然互斥）。
                                    rgbaSnap = cachedRgba
                                }
                                // GC P0（工单 A）：RGBA→BGR 转换移到**轮询线程自身**、锁外执行：
                                // 每个新帧只转换一次（旧行为是每次 acquireLatestBgr 调用各转一次，
                                // TriggerEngine 100ms tick + 扫描 settle 轮询叠加出每秒数十次），
                                // 转换结果发布进小型 BGR 槽池，调用方按引用取走（见 acquireLatestBgr）。
                                // 失败只丢本帧，不影响线程存活（与拷帧段同一 catch 兜底）。
                                convertAndPublishFrame(w, h, image.timestamp, rgbaSnap)
                                if (!firstFrameLogged) {
                                    firstFrameLogged = true
                                    Log.i(TAG, "first frame acquired ${w}x$h ts=${image.timestamp}")
                                } else if (nullCount > 0 && frameLogDue()) {
                                    Log.d(TAG, "frame acquired ${w}x$h after $nullCount nulls")
                                    nullCount = 0
                                }
                            }
                        } catch (t: Throwable) {
                            // ★ 2026-09-24：**这一帧跳过，不能让线程死掉**。
                            //   原先此处只有 `finally` 没有 `catch` ⇒ 任何单帧异常都会穿透 while、
                            //   被下面的外层兜底 catch 收掉 ⇒ **整条取帧线程终止**
                            //   （实测：授权后 VirtualDisplay 重建的 ~3ms 窗口内抛
                            //   `IllegalStateException: Image is already closed`，此后 4s 内 0 帧，
                            //    而 `isRunning()` 仍报 true、`screenShare=true` ⇒ 从状态上完全看不出坏了）。
                            //   外层 catch 仍保留，作为"这帧之外还有救不了的东西"的最后防线。
                            Log.w(TAG, "frame sample failed, skipped", t)
                        } finally {
                            runCatching { image.close() }
                        }
                    } else {
                        nullCount++
                        // ★ #46 ⑥：原来 `count==1 || count%100==0` ⇒ 每段无帧期开头都必打一行
                        //   （8ms 轮询下"1 个 null"极常见）⇒ 实测 6.5 万行。改为纯按秒限流：
                        //   连续无帧时长本身就是"卡住多久"的信号，不必逐次记录。
                        if (frameLogDue()) {
                            Log.d(TAG, "frame poll null (count=$nullCount)")
                        }
                        try {
                            Thread.sleep(FRAME_POLL_MS)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                // 采样段会抛穿 while：分辨率突变时 `ByteArray(size)` 可以 OOM，`copyRgbaImage`
                // 可以越界。历史行为是线程到此为止、**flag 留在 true** ⇒ 下次 start 在
                // `if (frameThreadRunning) return` 处空转，而 `isRunning()` 只看三个对象非空
                // 仍报 true ⇒ 缓存帧永久停在最后一帧（下游"正常"跑完整轮，其实读的是旧画）。
                Log.e(TAG, "frame poller crashed", t)
            } finally {
                // 不持 [lock]：A4 两阶段停机后 join 已移到锁外，这里没有需要锁的状态
                //（frameThreadRunning 是 @Volatile）。⚠️ 只允许**当前代**清这个共享开关：
                //   一具被换代淘汰的旧尸体若把它抹掉，`ensureFramePollerLocked` 就会再拉一条，
                //   变成三条并存（#109 同一根因的另一半）。
                if (gen == pollerGeneration) frameThreadRunning = false
            }
            Log.d(TAG, "frame poller exited (gen=$gen, current=$pollerGeneration)")
        }, "BetterGICaptureFrames")
        thread.start()
        frameThread = thread
    }

    /**
     * 投影还在、轮询线程没了 ⇒ 拉起来（配合线程体的 `finally` 复位，崩溃后能自愈）。
     *
     * 挂在**每一次取帧/采样**的持锁入口上，而不是只做一次性检查：本类的失效模式是"静默拿旧帧"，
     * 没有任何下游报错会提醒去重建。守卫用 [imageReader] 而非 [frameThread] ——
     * [releaseDisplayLocked] 会在停线程后把它置 null，正常收尾路径不会被这里复活。
     */
    private fun ensureFramePollerLocked() {
        if (imageReader == null || frameThreadRunning) return
        Log.w(TAG, "frame poller dead while projection alive, restarting")
        startFramePollerLocked()
    }

    /**
     * 【停机阶段一，必须持 [lock]】A4 两阶段停机的"退场条件"段：只置标志，**绝不 join**。
     *
     * 事故依据（旧实现 join 在锁内）：stop() → stopLocked() → releaseDisplayLocked() →
     * 旧 stopFramePollerLocked 全程持 [lock]，而轮询线程每圈第一步 `synchronized(lock){imageReader}`
     * 与拷帧段都要拿同一把锁 ⇒ 被 join 的线程在 join 窗口内永远进不了临界区 ⇒ `join(1000)`
     * **必然等满**，期间 acquireLatestBgr/stop 全部排队；调用点（TriggerForegroundService）几乎都
     * 在主线程 ⇒ 每次停投影概率性 1s 卡顿。
     *
     * 修法：本段只做"让线程自己走到终点"的三件事，把待收尸线程返回给调用方，由调用方
     * **在锁外** [joinRetiredPoller]。资源 close 仍留在锁内且**先于** join：reader 一关，
     * 阻塞在 acquireLatestImage 的线程立刻拿异常退出 ⇒ 锁外 join 通常毫秒级返回；
     * imageReader 已为 null 也保证 `ensureFramePollerLocked` 不会在 join 窗口里复活新轮询。
     *
     * #109 教训**不回退**：仍是"先换代再清标志"——被淘汰的旧代尸体醒来读到
     * `gen != pollerGeneration` 必然自退，不会因下一轮 start 抬回 frameThreadRunning 而复活。
     *
     * @return 需要锁外 join 的旧轮询线程（没有则 null）
     */
    private fun retirePollerLocked(): Thread? {
        pollerGeneration++
        frameThreadRunning = false
        val t = frameThread
        frameThread = null
        return t
    }

    /**
     * 【停机阶段二，必须**锁外**调用】回收阶段一退场的轮询线程。带上限地等：
     * 正常路径 reader 已关，线程最迟一圈（8ms 轮询 + 一次拷帧）自退；超时只意味着
     * native 侧异常阻塞，放它自生自灭（代次已失效，它醒来即退），绝不在调用线程上无限等。
     */
    private fun joinRetiredPoller(t: Thread?) {
        if (t == null) return
        try {
            t.join(POLLER_JOIN_TIMEOUT_MS)
        } catch (_: Throwable) {
        }
    }

    fun isRunning(): Boolean = synchronized(lock) {
        mediaProjection != null && virtualDisplay != null && imageReader != null
    }

    fun capturedSize(): Pair<Int, Int>? = synchronized(lock) {
        val reader = imageReader ?: return null
        if (reader.width <= 0 || reader.height <= 0) return null
        reader.width to reader.height
    }

    /**
     * 用授权结果启动投影。
     *
     * ★ A27：本函数**保证不抛**。授权 token 失效 / Android 14+ FGS 时序不满足时
     * `getMediaProjection` 抛 SecurityException，异常 ROM 下 `defaultDisplaySpec()` 抛
     * IllegalStateException（`error("DEFAULT_DISPLAY is missing")`）——旧实现直接裸穿到
     * 调用点（TriggerForegroundService 的 ACTION_CAPTURE_RESULT：主线程、无 try/catch）
     * ⇒ 主进程崩溃。现在就地捕获、回滚半建状态（projection/callback/reader 可能只建了一半）
     * 并返回失败摘要，由调用方决定提醒与开关落回。
     *
     * 入口 [stopLocked] 返回的旧轮询线程**不 join**（A4）：上代尸体已被换代 + 关 reader，
     * 最迟一圈自退；为它阻塞 start 只会复刻旧的 1s 卡顿。
     *
     * @return null = 启动成功（此后 [isRunning] 为 true）；非 null = 失败原因摘要（已落错误日志）
     */
    fun start(resultCode: Int, data: Intent): String? {
        return try {
            synchronized(lock) {
                stopLocked()

                val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = manager.getMediaProjection(resultCode, data)
                if (projection == null) {
                    return@synchronized "getMediaProjection 返回 null（授权结果不可用）"
                }
                mediaProjection = projection

                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        handleProjectionStoppedExternally()
                    }

                    override fun onCapturedContentResize(width: Int, height: Int) {
                        synchronized(lock) {
                            resizeCapturedContentLocked(width, height)
                        }
                    }
                }
                callback = cb
                projection.registerCallback(cb, mainHandler)

                val spec = defaultDisplaySpec()
                densityDpi = spec.densityDpi
                createVirtualDisplayLocked(spec.width, spec.height, spec.densityDpi)
                null
            }
        } catch (t: Throwable) {
            // 回滚半建状态：走到这里时 projection/callback/reader 可能只建了一半。
            // stopLocked 与启动入口同一段清理（幂等），保证 isRunning() 不报假 true。
            synchronized(lock) { stopLocked() }
            Log.e(TAG, "capture start failed", t)
            "${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
        }
    }

    fun stop() {
        val retired = synchronized(lock) { stopLocked() }
        // A4：join 在锁外（事故依据见 retirePollerLocked）。线程已在锁内被断粮
        //（reader 关闭 + 代次失效），这里通常毫秒级返回，主线程调用点不再被必现的等满拖住。
        joinRetiredPoller(retired)
    }

    /**
     * 丢掉积压帧（保留缓存）。VirtualDisplay 仍按 vsync 产出，ImageReader 缓存上限内
     * 多余的由系统丢弃。poll 循环会立即补回最新一帧。
     */
    fun discardLatestImages() {
        synchronized(lock) {
            try {
                imageReader?.acquireLatestImage()?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * ★ A28：整帧 RGBA→BGR（2~17MB 分配 + ~2ms）**移出临界区** →（GC P0 工单 A 再演进）→
     * **移进轮询线程**。转换现在由 poller 在锁外对每个**新帧**执行一次，结果发布进常驻
     * BGR 槽池（[bgrPool]）；本函数只做**引用快照 + 引用计数 +1**，全程零分配、零转换：
     * - 旧 A28 的「快照 + 代数复核 + 重试」 dance 在这里**结构性消失**，原因（本工单的
     *   核心正确性论证）：旧路径的转换源是 poller 会原地覆写的 `cachedRgba`，快照读到一半
     *   可能被同尺寸新帧覆盖 ⇒ 必须靠时间戳复核排除撕裂帧。新路径转换源由 poller 独占
     *   （见 [convertAndPublishFrame]），发布进池的 BGR 槽在「已发布」期间**绝不会被
     *   poller 写入**（poller 只取「非当前发布 + outstanding==0」的槽来写下一帧），调用方
     *   在持引用期间看到的是一帧完整、不可变的像素 ⇒ 单次临界区内取引用 + 计数已天然
     *   原子，无需复核重试。旧路径「复核打满放行未复核撕裂帧」的病态分支随之不复存在
     *   （新路径不存在能撕裂的快照，严格优于旧语义）。
     * - 返回帧的 [CapturedBgrFrame.timestampNs] 是**该 BGR 槽自己**的帧时间戳（而非取用
     *   时刻的缓存代数）：若调用方取用时 poller 已在转换更新的帧，返回的是上一已发布帧
     *   及其真实时间戳 ⇒ 扫描路径 `grabFresh` 的 isFresh 判定按真实帧代数重试（25ms 轮询），
     *   语义与旧「撕裂帧丢弃重试」一致，不会误把旧帧标成新帧。
     * - 池耗尽（同屏并发持有 > [BGR_POOL_MAX]）或尺寸刚切换时，poller 侧走「新建-即弃」
     *   兜底，语义等同旧逐次分配路径——只慢不错。
     */
    fun acquireLatestBgr(): CapturedBgrFrame? {
        synchronized(lock) {
            ensureFramePollerLocked()
            maybeRecoverStalledReaderLocked()
            val slot = publishedBgr
            if (slot == null || slot.mat.empty()) {
                // 首帧发布前的极短窗口（拷帧元数据已就绪、首轮转换 ~2ms 未完成）：旧实现此时
                // 会同步等一次转换，新实现直接空手返回，调用方按各自节奏重试（grabFresh 25ms、
                // TriggerEngine 下一 tick 100ms）——只晚一个转换周期，语义不变。
                // empty() 防御（2026-10-01 深测回归）：任何生命周期滑漏产生的空发布槽按
                // 「无帧」处理，调用方重试，绝不把空 Mat 交付给识别层。
                Log.d(TAG, "acquireLatestBgr no published frame yet")
                return null
            }
            slot.outstanding++
            return CapturedBgrFrame(
                width = slot.frameWidth,
                height = slot.frameHeight,
                bgr = slot.mat,
                timestampNs = slot.timestampNs,
            )
        }
    }

    // ================= GC P0（工单 A）：BGR 帧槽池与 poller 侧常驻转换 =================

    /**
     * 池内一个 BGR 帧：一块常驻 CV_8UC3 native 内存 + 引用清点。
     *
     * 生命周期状态（全部由 [lock] 守护）：
     * - **已发布**（`publishedBgr === this`）：内容 = 当前缓存帧的完整 BGR；调用方可经
     *   [acquireLatestBgr] 取引用（outstanding+1）。poller 绝不写入已发布槽。
     * - **空闲**（在 [bgrPool] 中且 outstanding==0）：可被 poller 取走写入下一帧。
     * - **在途**（outstanding>0 且非发布）：调用方解析中；poller 不触碰。归还
     *   （release 钩子 → [onPooledBgrReleased]）后回到空闲。
     *
     * ⚠️ release 钩子的所有权审计（为什么能安全接上，本工单最关键的前提）：
     * 交付的 Mat 穿过 FrameSource.grabFresh / TriggerEngine → CaptureContent.fromBgr(...)，
     * 最终由 `ImageRegion.releaseOwnedMats()`（ownsMat=true）**恰好一次** release：
     * - TriggerEngine tick：`CaptureContent.use` close → release 一次；
     * - grabFresh 新鲜帧：返回给扫描层包进 CaptureContent，close 时 release 一次；
     *   非新鲜帧：FrameSource 内 `frame.bgr.release()` 一次；
     * - ScanGridDomain 超时回退 `acquireLatestBgr()?.bgr`：同 CaptureContent 路径；
     * - ScriptRunner 探针：finally `frame.release()` 一次。
     * 没有任何调用点二次 release 或越过 release 直接持有（旧路径同样依赖恰好一次
     * release 的约定，故该前提与既有代码一致，非新增约束）。
     */
    private class BgrSlot(
        val frameWidth: Int,
        val frameHeight: Int,
        owner: ScreenCaptureController,
    ) {
        val mat = PooledBgrMat(frameWidth, frameHeight) { owner.onPooledBgrReleased(this) }

        /** 已交付未归还的引用数（发布期自身不计入；0 = poller 可复用）。 */
        var outstanding: Int = 0

        /** 本槽内容对应的帧时间戳（发布时写入）。 */
        var timestampNs: Long = 0L

        /** 是否已在空闲池中（防重复 release 把同一槽入池两次——2026-10-01 深测回归修复）。 */
        var inPool: Boolean = false
    }

    /**
     * 池上限的并发依据（仿 OnnxPaddleOcrService 的 PreprocWorkspace / WS_POOL_MAX 取舍）：
     * 常态单持有者（TriggerEngine tick 或扫描单协程），瞬间并发 ≤2（tick 与扫描并存窗口、
     * 探针 ScriptRunner.ocrDetProbe 任意界面可触发）；上限 4 覆盖已知并发面 + 一个余量，
     * 池耗尽回退「新建-即弃」，只慢不错。1920×1080 BGR ≈ 6MB/槽，常驻上限 ~24MB native。
     */
    private val bgrPool = ArrayDeque<BgrSlot>()

    /** 当前发布的帧槽（null ⇔ hasCachedFrame=false 的旧语义，二者始终同临界区更新）。 */
    private var publishedBgr: BgrSlot? = null

    /** poller 线程日志去重：尺寸失配重建只打一行。 */
    private var lastPoolResizeLogMs = 0L

    /**
     * poller 独占：把本帧从 [rgbaBytes] 转成 BGR 并发布。
     *
     * 并发与生命周期依据：
     * - 本函数**只被 poller 线程调用**，且单线程顺序执行：读 [rgbaBytes]（本圈刚拷完的
     *   数组）期间不可能有下一圈拷帧写入（下一圈从本函数返回后才开始）⇒ 锁外读安全，
     *   不需要 A28 式复核。
     * - 目标槽选取：空闲池中按尺寸匹配取一（顺带清掉尺寸失配的空闲槽——旋转/resize
     *   场景的重建点）；池空则新建。新帧永远写进「非发布」槽，写完才原子换入发布位，
     *   旧发布槽 outstanding==0 直接入池、>0 则等 release 钩子归还（[onPooledBgrReleased]）。
     * - RGBA 4 通道中转 Mat（[pollStageRgba]）为 poller 线程私有常驻：消掉旧路径每次
     *   转换的 10~17MB 临时分配；线程退出随 finalize 释放（poller 是唯一使用者）。
     */
    private fun convertAndPublishFrame(width: Int, height: Int, ts: Long, rgbaBytes: ByteArray?) {
        val snap = rgbaBytes ?: return
        var slot: BgrSlot? = null
        try {
            var stage = pollStageRgba
            if (stage == null) {
                stage = Mat(height, width, CvType.CV_8UC4)
                pollStageRgba = stage
            }
            synchronized(lock) {
                slot = takeFreeSlotLocked(width, height)
            }
            MatOps.rgbaToBgrInto(width, height, snap, slot!!.mat, stage)
                synchronized(lock) {
                    slot!!.timestampNs = ts
                    val old = publishedBgr
                    publishedBgr = slot
                    if (old != null && old !== slot) {
                        if (old.outstanding == 0) {
                            if (old.frameWidth == width && old.frameHeight == height && bgrPool.size < BGR_POOL_MAX) {
                                bgrPool.addLast(old)
                                old.inPool = true
                            } else {
                                old.mat.disposeNow()
                            }
                        }
                    }
                    // outstanding>0：在途槽由 release 钩子回收（见 onPooledBgrReleased）
                }
        } catch (t: Throwable) {
            // 转换/发布失败只丢本帧（缓存 RGBA 元数据已发布，行为与旧「单帧异常跳过」一致）
            Log.w(TAG, "frame convert failed, skipped", t)
            slot?.let { s ->
                synchronized(lock) {
                    if (publishedBgr !== s) s.mat.disposeNow()
                }
            }
        }
    }

    /** 【持 [lock]】取一个尺寸匹配的空闲槽；顺带清掉失配/已空槽。池空/全失配 ⇒ 新建。 */
    private fun takeFreeSlotLocked(width: Int, height: Int): BgrSlot {
        if (bgrPool.isNotEmpty()) {
            var resized = false
            val it = bgrPool.iterator()
            while (it.hasNext()) {
                val s = it.next()
                if (s.frameWidth != width || s.frameHeight != height || s.mat.empty()) {
                    // 空 Mat 防御：任何生命周期滑漏产生的空槽在这里退役，绝不进入写入/交付路径
                    it.remove()
                    s.inPool = false
                    s.mat.disposeNow()
                    resized = true
                }
            }
            if (resized) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastPoolResizeLogMs > FRAME_LOG_MIN_INTERVAL_MS) {
                    lastPoolResizeLogMs = now
                    Log.i(TAG, "bgr pool rebuilt for ${width}x$height")
                }
            }
        }
        return bgrPool.removeFirstOrNull()?.also { it.inPool = false } ?: newSlot(width, height)
    }

    private fun newSlot(width: Int, height: Int): BgrSlot = BgrSlot(width, height, this)

    /**
     * PooledBgrMat 的 release 钩子（簿记先行，返回值 = 是否允许真释放原生数据）。
     *
     * 2026-10-01 深测回归修复：原实现 `release()` 先 `super.release()` 再簿记——被释放的槽
     * 恰好是发布位且屏幕静止（无新帧刷新发布位）时，共享发布帧被清空 ⇒ 下一个
     * `acquireLatestBgr` 交付空 Mat。现语义：release = 「我不再持有」，数据由池裁决：
     * - 仍有其他持有者 / 是发布位 / 已在池中 ⇒ 保留数据（false）；
     * - 真正归还入池 ⇒ 保留数据（false，池内槽内容是否被覆写由 poller 独占写决定）；
     * - 尺寸失配 / 池满（收缩丢弃）⇒ 真释放（true）。
     */
    private fun onPooledBgrReleased(slot: BgrSlot): Boolean {
        synchronized(lock) {
            if (slot.mat.disposed) return true
            if (slot.outstanding > 0) slot.outstanding--
            if (slot.outstanding > 0) return false          // 还有别的持有者
            if (publishedBgr === slot) return false         // 发布缓存：内容必须保持可读
            if (slot.inPool) return false                   // 重复 release：已在池中，空操作
            if (slot.frameWidth == currentBgrWidthLocked() &&
                slot.frameHeight == currentBgrHeightLocked() &&
                bgrPool.size < BGR_POOL_MAX
            ) {
                bgrPool.addLast(slot)
                slot.inPool = true
                return false
            }
            return true                                     // 收缩丢弃：允许真释放
        }
    }

    /** 【持 [lock]】当前发布帧的尺寸（无发布帧时返回 -1 ⇒ 任何槽都失配而丢弃）。 */
    private fun currentBgrWidthLocked(): Int = publishedBgr?.frameWidth ?: -1

    private fun currentBgrHeightLocked(): Int = publishedBgr?.frameHeight ?: -1

    /** 【持 [lock]】停机/重建：释放当前发布位与全部空闲槽；在途槽留给 release 钩子收尾。 */
    private fun resetBgrPoolLocked() {
        publishedBgr?.let { old ->
            if (old.outstanding == 0) old.mat.disposeNow()
        }
        publishedBgr = null
        while (bgrPool.isNotEmpty()) bgrPool.removeFirst().mat.disposeNow()
    }


    /**
     * ★ 就绪信号直采（2026-09-12）：从**当前缓存帧**直接采样 [rect] 的**分块 RGB 均值签名**。
     *
     * 取代「全帧解码 + OCR」的就绪轮询（见 `dsl/verify/_audit/IMAGE-PATH-COST.md` §5）：
     * **零 Mat、零分配**（写入调用方缓冲），成本 ≈ `blocksX*blocksY*9` 次字节读 ≈ **~1µs**；
     * 而被它取代的每轮轮询要付「全帧 RGBA→BGR（实测 2ms）+ 一次 rec（2~9ms）+ delay(120ms)」。
     *
     * 输出格式**刻意对齐 [VoteJudges.thumbChangedFraction]**：每 3 字节 = 1 个块（R,G,B 均值，0..255），
     * 长度 = `blocksX*blocksY*3` ⇒ 可直接复用既有的 `tol=THUMB_DIFF_TOL(2)` 容差经验。
     *
     * ⚠️ 两个坑：
     * 1. 行距用 [cachedPixelStride]（RGBA_8888 通常 4，但**不可硬编码**）；
     *    缓存内每行已由 `copyRgbaImage` 压缩为紧凑（row pitch = pixelStride）。
     * 2. **不量化**：现有容差 `tol=2` 是作用在 0..255 上的（0.8%）；若 `shr 4` 到 0..15 会让容差变成 13%，失真。
     *
     * @param out 长度须 ≥ `blocksX*blocksY*3`
     * @return false = 无缓存帧 / 参数非法（**调用方必须回退旧路径**）
     */
    fun sampleSignature(
        rect: IntRect,
        out: ByteArray,
        blocksX: Int = SIG_BLOCKS_X,
        blocksY: Int = SIG_BLOCKS_Y,
    ): Boolean {
        if (out.size < blocksX * blocksY * 3) return false
        var bufRef: ByteArray? = null
        var w = 0
        var h = 0
        var ps = 4
        synchronized(lock) {
            ensureFramePollerLocked()
            if (!hasCachedFrame) return false
            bufRef = cachedRgba
            w = cachedWidth
            h = cachedHeight
            ps = cachedPixelStride
        }
        val src = bufRef ?: return false
        if (w <= 0 || h <= 0 || ps < 3) return false
        val x0 = rect.x.coerceIn(0, w - 1)
        val y0 = rect.y.coerceIn(0, h - 1)
        val rw = rect.width.coerceIn(1, w - x0)
        val rh = rect.height.coerceIn(1, h - y0)

        var o = 0
        for (by in 0 until blocksY) {
            val ya = y0 + rh * by / blocksY
            val yb = (y0 + rh * (by + 1) / blocksY).coerceAtLeast(ya + 1)
            for (bx in 0 until blocksX) {
                val xa = x0 + rw * bx / blocksX
                val xb = (x0 + rw * (bx + 1) / blocksX).coerceAtLeast(xa + 1)
                var sr = 0
                var sg = 0
                var sb = 0
                var n = 0
                for (sy in 0 until SIG_SAMPLES_PER_AXIS) {
                    val y = (ya + (yb - ya) * sy / SIG_SAMPLES_PER_AXIS).coerceIn(0, h - 1)
                    for (sx in 0 until SIG_SAMPLES_PER_AXIS) {
                        val x = (xa + (xb - xa) * sx / SIG_SAMPLES_PER_AXIS).coerceIn(0, w - 1)
                        val p = (y * w + x) * ps
                        sr += src[p].toInt() and 0xFF       // RGBA：R 在前
                        sg += src[p + 1].toInt() and 0xFF
                        sb += src[p + 2].toInt() and 0xFF
                        n++
                    }
                }
                val k = if (n > 0) n else 1
                out[o] = (sr / k).toByte()
                out[o + 1] = (sg / k).toByte()
                out[o + 2] = (sb / k).toByte()
                o += 3
            }
        }
        return true
    }

    /**
     * 帧代数（= 缓存帧时间戳 ns）。**同一帧的两个样本必然相同** ⇒ 用它排除
     * 「拿同一帧自己比自己」造成的**假稳定**（轮询步长 40ms < 帧间隔 ~33ms 时必现）。
     */
    fun frameGeneration(): Long = synchronized(lock) { cachedTimestampNs }

    /**
     * ★ ROI **稳定性**探针（2026-09-12，只读）：对每个候选 ROI 连续采集 [frames] 个**跨帧**签名，
     * 统计「相邻样本在容差内完全相同」的比例与最长连续不变段。
     *
     * **为什么需要它**：就绪信号要求 ROI 在渲染完成后**跨帧像素恒等**；而"坐标标定"只保证
     * 那个位置是对的，不保证它静止（小文字 ROI 会因抗锯齿/亚像素抖动/页面动画永远在变 ——
     * 实测 `char_profile.name` / `char_talent.lvRois[0]` 在 700ms 内从未静止）。
     * 见 `dsl/verify/_audit/PIPELINE-FEASIBILITY.md` §11.3。
     *
     * 输出（每个 ROI 一段）：
     * `name same=NN.N% avgChanged=n.n maxRun=NN verdict=USABLE|UNSTABLE`
     * 判定：`same ≥ 95% 且 maxRun ≥ 15` ⇒ USABLE（足够做就绪锚）。
     *
     * ⚠️ 只在**当前显示的界面**上有意义：若 ROI 指向的界面没在屏上，量到的是别的内容（可能"假稳"）。
     */
    fun probeRoiStability(rois: List<Pair<String, IntRect>>, frames: Int = 60): String {
        if (rois.isEmpty()) return "no rois"
        val n = frames.coerceIn(10, 300)
        val bufA = ByteArray(SIG_BLOCKS_X * SIG_BLOCKS_Y * 3)
        val bufB = ByteArray(bufA.size)
        val sb = StringBuilder("roi stability: frames=$n rois=${rois.size}")
        for ((name, rect) in rois) {
            val a = ByteArray(bufA.size)
            val b = ByteArray(bufA.size)
            var prevRef: ByteArray? = null
            var writeIdx = 0
            var taken = 0
            var sameCnt = 0
            var changedSum = 0
            var run = 0
            var maxRun = 0
            var lastGen = Long.MIN_VALUE
            var ok = true
            val deadline = SystemClock.elapsedRealtime() + STABILITY_PROBE_MAX_MS
            while (taken < n && SystemClock.elapsedRealtime() < deadline) {
                val cur = if (writeIdx == 0) a else b
                if (!sampleSignature(rect, cur, SIG_BLOCKS_X, SIG_BLOCKS_Y)) {
                    ok = false
                    break
                }
                val gen = frameGeneration()
                if (gen == lastGen) {
                    Thread.sleep(STABILITY_PROBE_IDLE_MS) // 同一帧：等下一帧（跨帧才算一个样本）
                    continue
                }
                lastGen = gen
                val p = prevRef
                if (p != null) {
                    val d = changedBlocks(p, cur)
                    taken++
                    changedSum += d
                    if (d == 0) {
                        sameCnt++
                        run++
                        if (run > maxRun) maxRun = run
                    } else {
                        run = 0
                    }
                }
                prevRef = cur
                writeIdx = 1 - writeIdx // 下一轮写另一个缓冲（绝不复用 prevRef）
            }
            if (!ok) {
                sb.append("\n  ").append(name).append(" SAMPLE_FAILED")
                continue
            }
            val samePct = if (taken > 0) sameCnt * 100f / taken else 0f
            val avgChanged = if (taken > 0) changedSum.toFloat() / taken else 0f
            val usable = samePct >= 95f && maxRun >= 15
            sb.append("\n  ").append(name)
                .append(" same=").append("%.1f".format(samePct)).append("%")
                .append(" avgChanged=").append("%.2f".format(avgChanged))
                .append(" maxRun=").append(maxRun).append("/").append(taken)
                .append(" verdict=").append(if (usable) "USABLE" else "UNSTABLE")
        }
        return sb.toString()
    }

    /** 两签名之间「超出容差」的块数（容差语义与生产一致：单通道 |Δ|>2）。 */
    private fun changedBlocks(a: ByteArray, b: ByteArray, tol: Int = SIG_BLOCK_TOL): Int {
        if (a.size != b.size) return Int.MAX_VALUE / 2
        var d = 0
        var i = 0
        while (i + 2 < a.size) {
            if (kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)) > tol ||
                kotlin.math.abs((a[i + 1].toInt() and 0xFF) - (b[i + 1].toInt() and 0xFF)) > tol ||
                kotlin.math.abs((a[i + 2].toInt() and 0xFF) - (b[i + 2].toInt() and 0xFF)) > tol
            ) {
                d++
            }
            i += 3
        }
        return d
    }

    /**
     * 帧路径基准（**只读探针**，不改任何状态、不干扰帧流）：用当前缓存帧拆测各段耗时。
     *
     * 回答两个问题（见 `dsl/verify/_audit/IMAGE-PATH-COST.md` §8）：
     * 1. `acquireLatestBgr()` 的 16.97MB 分配 / 26.7MB 流量**到底值多少 ms**；
     * 2. 「只把 ROI 转 BGR」相对全帧路径快多少倍。
     *
     * @param rois 真实 ROI 集合（帧坐标）；传空则跳过 ROI 段。由调用方从 profile 读出，避免跨层依赖。
     */
    /**
     * ★ 帧率探针（2026-09-12，**只读**）：高频轮询 [frameGeneration]，统计**相邻新帧的墙钟间隔**。
     *
     * 为什么需要：`sigWait` 里测到的"帧间隔"会与**轮询步长**混淆（步长 40ms 时量到的就是 40ms），
     * 无法判断"等待 80ms 是游戏只出 25fps"还是"我们采样太慢"。本探针以 [pollMs]（默认 2ms）
     * 独立轮询 ⇒ 量到的是**真实出帧间隔**。
     *
     * ⚠️ 静态画面时系统可能**不产新帧**（缓存保留上一帧）⇒ 报告里会出现超长间隔，属正常；
     *    判帧率要看**众数/中位**而不是 max。
     */
    fun probeFrameRate(durationMs: Long = 1200, pollMs: Long = 2): String {
        val dur = durationMs.coerceIn(200, 10_000)
        val step = pollMs.coerceIn(1, 50)
        val gaps = ArrayList<Long>(512)
        var lastGen = frameGeneration()
        var lastWall = SystemClock.elapsedRealtime()
        val t0 = lastWall
        while (SystemClock.elapsedRealtime() - t0 < dur) {
            val g = frameGeneration()
            val wall = SystemClock.elapsedRealtime()
            if (g != lastGen) {
                gaps.add(wall - lastWall)
                lastGen = g
                lastWall = wall
            }
            Thread.sleep(step)
        }
        if (gaps.isEmpty()) return "frameRate: 窗口 ${dur}ms 内**未产生任何新帧**（静态画面？）"
        val sorted = gaps.sorted()
        val med = sorted[sorted.size / 2]
        val hist = IntArray(13)
        gaps.forEach { hist[(it / 5).toInt().coerceIn(0, 12)]++ }
        val hs = (0 until hist.size).filter { hist[it] > 0 }
            .joinToString(" ") { "${it * 5}-${it * 5 + 4}ms:${hist[it]}" }
        return "frameRate: 窗口=${dur}ms 新帧=${gaps.size} fps=${"%.1f".format(gaps.size * 1000.0 / dur)} " +
            "间隔 min=${sorted.first()} 中位=$med p90=${sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]} max=${sorted.last()}" +
            "\n  直方图(5ms 分箱): $hs"
    }

    fun benchFramePath(runs: Int = 30, rois: List<IntRect> = emptyList()): String {
        var bufRef: ByteArray? = null
        var w = 0
        var h = 0
        var ps = 4
        synchronized(lock) {
            if (!hasCachedFrame) return "no cached frame"
            bufRef = cachedRgba
            w = cachedWidth
            h = cachedHeight
            ps = cachedPixelStride
        }
        val buf = bufRef ?: return "no cached frame"
        if (w <= 0 || h <= 0) return "bad frame size ${w}x$h"
        val n = runs.coerceIn(3, 200)

        fun med(block: () -> Unit): Long {
            val t = LongArray(n) {
                val t0 = System.nanoTime()
                block()
                (System.nanoTime() - t0) / 1_000_000
            }
            return t.sorted()[t.size / 2]
        }

        // A) 拆段：4 通道 Mat 分配 / put(整帧) / cvtColor(整帧)
        val alloc4 = med { Mat(h, w, CvType.CV_8UC4).release() }
        val put4 = med {
            val m = Mat(h, w, CvType.CV_8UC4)
            m.put(0, 0, buf)
            m.release()
        }
        val rgbaMat = Mat(h, w, CvType.CV_8UC4)
        rgbaMat.put(0, 0, buf)
        val cvtFull = med {
            val b = Mat()
            Imgproc.cvtColor(rgbaMat, b, Imgproc.COLOR_RGBA2BGR)
            b.release()
        }
        rgbaMat.release()
        // B) 生产口径（= MatOps.rgbaToBgr，alloc+put+cvt 一条链）
        val full = med { MatOps.rgbaToBgr(w, h, buf).release() }

        // C) ROI 口径：Java 侧按行收集 → 小 4 通道 Mat → cvtColor（tmp 缓冲预分配，对优化版公平）
        var roiPx = 0
        var roiMs = -1L
        var roiBytes = 0
        if (rois.isNotEmpty()) {
            val clamped = rois.map { r ->
                val x = r.x.coerceIn(0, w - 1)
                val y = r.y.coerceIn(0, h - 1)
                val rw = r.width.coerceIn(1, w - x)
                val rh = r.height.coerceIn(1, h - y)
                intArrayOf(x, y, rw, rh)
            }
            roiPx = clamped.sumOf { it[2] * it[3] }
            roiBytes = roiPx * ps
            val maxRow = clamped.maxOf { it[2] } * ps
            val maxRows = clamped.maxOf { it[3] }
            val tmp = ByteArray(maxRow * maxRows)
            roiMs = med {
                for (r in clamped) {
                    val (x, y, rw, rh) = r
                    val rowBytes = rw * ps
                    for (row in 0 until rh) {
                        System.arraycopy(buf, ((y + row) * w + x) * ps, tmp, row * rowBytes, rowBytes)
                    }
                    val m4 = Mat(rh, rw, CvType.CV_8UC4)
                    m4.put(0, 0, tmp, 0, rowBytes * rh)
                    val b3 = Mat()
                    Imgproc.cvtColor(m4, b3, Imgproc.COLOR_RGBA2BGR)
                    m4.release()
                    b3.release()
                }
            }
        }
        return "frame=${w}x$h stride=$ps runs=$n | full: alloc4=$alloc4 put4=$put4 cvtFull=$cvtFull " +
            "prod=$full ms | roi: rects=${rois.size} px=$roiPx bytes=$roiBytes $roiMs ms" +
            (if (roiMs > 0) " (x${"%.1f".format(full.toDouble() / roiMs.toDouble())})" else "")
    }

    private fun handleProjectionStoppedExternally() {
        val (notify, retired) = synchronized(lock) {
            val projection = mediaProjection
            if (projection == null) {
                false to null
            } else {
                val cb = callback
                mediaProjection = null
                callback = null
                val r = releaseDisplayLocked()
                if (cb != null) {
                    try {
                        projection.unregisterCallback(cb)
                    } catch (_: Throwable) {
                    }
                }
                true to r
            }
        }
        // A4：join 移到锁外——本函数跑在 mainHandler（投影 onStop 回调）上，
        // 持锁 join 同样会把主线程钉死 1s。
        joinRetiredPoller(retired)
        if (notify) {
            onStoppedExternally()
        }
    }

    private fun createVirtualDisplayLocked(width: Int, height: Int, densityDpi: Int) {
        val projection = mediaProjection ?: return
        // 防御性复位：正常路径 start() 入口的 stopLocked 已清场，这里 retire 返回 null。
        // 即便真有上代尸体也不 join（A4）——换代 + 关 reader 已保证它最迟一圈自退。
        releaseDisplayLocked()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        imageReader = reader
        displayCreatedElapsedMs = SystemClock.elapsedRealtime()
        hasCachedFrame = false
        resetBgrPoolLocked()
        firstFrameLogged = false

        virtualDisplay = projection.createVirtualDisplay(
            "BetterGIPocketShare",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null,
        )
        Log.i(TAG, "ImageReader+VirtualDisplay ready ${width}x$height")
        startFramePollerLocked()
    }

    /**
     * 同一 MediaProjection 只能 createVirtualDisplay 一次。
     * 选单个应用或旋转时，系统会回调新尺寸，只能 resize 现有 VirtualDisplay 并换 ImageReader。
     */
    private fun resizeCapturedContentLocked(width: Int, height: Int) {
        val display = virtualDisplay ?: return
        if (width <= 0 || height <= 0) return
        val current = imageReader
        if (current != null && current.width == width && current.height == height) return

        Log.i(TAG, "captured content resized to ${width}x$height")
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        display.setSurface(newReader.surface)
        display.resize(width, height, densityDpi)
        imageReader = newReader
        lastRecoverElapsedMs = SystemClock.elapsedRealtime()
        hasCachedFrame = false
        resetBgrPoolLocked() // 尺寸切换：旧帧池整批重建（在途槽由 release 钩子自行收尾）
        firstFrameLogged = false
        try {
            current?.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * 始终未收到首帧时（如 ImageReader 在某 ROM 下死掉），用同一 VirtualDisplay 换一块
     * surface。每个 MediaProjection 只能 createVirtualDisplay 一次，不能整段重建。
     * 注意：静态界面本就不产帧，hasCachedFrame 一旦为 true 绝不触发此处，避免无谓抖动。
     */
    private fun maybeRecoverStalledReaderLocked() {
        if (virtualDisplay == null || imageReader == null) return
        if (hasCachedFrame) return
        val stalledSince = if (displayCreatedElapsedMs > 0) displayCreatedElapsedMs else lastFrameElapsedMs
        if (stalledSince <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (now - stalledSince < RECOVER_AFTER_MS) return
        if (now - lastRecoverElapsedMs < RECOVER_COOLDOWN_MS) return
        lastRecoverElapsedMs = now
        Log.w(TAG, "no first frame for ${now - stalledSince}ms, recreating ImageReader")
        val current = imageReader ?: return
        val width = current.width
        val height = current.height
        if (width <= 0 || height <= 0) return
        val display = virtualDisplay ?: return
        val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, IMAGE_READER_MAX_IMAGES)
        display.setSurface(newReader.surface)
        imageReader = newReader
        displayCreatedElapsedMs = now
        hasCachedFrame = false
        resetBgrPoolLocked()
        firstFrameLogged = false
        try {
            current.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * 【必须持 [lock]】停机阶段一：退投影 + 拆显示链（含轮询线程退场，见 [retirePollerLocked]）。
     * @return 待**锁外** join 的旧轮询线程（A4；调用方不关心时可忽略，代次已失效它会自退）
     */
    private fun stopLocked(): Thread? {
        val projection = mediaProjection
        val cb = callback
        mediaProjection = null
        callback = null
        val retired = releaseDisplayLocked()
        if (projection != null && cb != null) {
            try {
                projection.unregisterCallback(cb)
            } catch (_: Throwable) {
            }
        }
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        return retired
    }

    /**
     * 【必须持 [lock]】拆显示链并复位帧缓存。
     * @return [retirePollerLocked] 的待收尸线程，由调用方决定是否锁外 join（A4）
     */
    private fun releaseDisplayLocked(): Thread? {
        val retired = retirePollerLocked()

        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        } finally {
            virtualDisplay = null
        }

        try {
            imageReader?.close()
        } catch (_: Throwable) {
        } finally {
            imageReader = null
        }

        hasCachedFrame = false
        resetBgrPoolLocked()
        cachedWidth = 0
        cachedHeight = 0
        cachedTimestampNs = 0L
        lastFrameElapsedMs = 0L
        lastRecoverElapsedMs = 0L
        displayCreatedElapsedMs = 0L
        firstFrameLogged = false
        cachedRgba = ByteArray(0)
        return retired
    }

    private fun defaultDisplaySpec(): DisplaySpec {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("DEFAULT_DISPLAY is missing")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayContext = context.createDisplayContext(display)
            val windowManager = displayContext.getSystemService(WindowManager::class.java)
            val bounds = windowManager.maximumWindowMetrics.bounds
            return DisplaySpec(
                width = bounds.width(),
                height = bounds.height(),
                densityDpi = displayContext.resources.displayMetrics.densityDpi,
            )
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        return DisplaySpec(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private companion object {
        const val TAG = "BetterGI.Capture"
        const val IMAGE_READER_MAX_IMAGES = 2
        const val FRAME_POLL_MS = 8L
        /**
         * A4：锁外 join 旧轮询线程的上限。正常路径 reader 已关，线程毫秒级自退；
         * 上限只兜 native 侧异常阻塞，绝不在调用线程（多为主线程）上无限等。
         */
        const val POLLER_JOIN_TIMEOUT_MS = 1000L
        /**
         * GC P0（工单 A）：常驻 BGR 帧槽池上限（并发依据见 [bgrPool] KDoc）。
         * 旧 A28 的 BGR_SNAPSHOT_MAX_RETRIES 随快照+复核 dance 一起消失：
         * 新路径交付的帧不可撕裂，无需重试上限。
         */
        const val BGR_POOL_MAX = 4
        /**
         * 取帧循环逐帧 D 级日志的**最小间隔**（#46 ⑥）。
         *
         * 实测未限流时一轮 40 分钟打出 6.5 万行 `frame poll null` + 6.5 万行 `frame acquired`
         * ≈ 11MB，是全量 logcat 里我方 tag 的最大项。诊断只需"帧率/卡顿趋势"，1 秒一行足够。
         */
        const val FRAME_LOG_MIN_INTERVAL_MS = 1000L
        const val RECOVER_AFTER_MS = 800L
        const val RECOVER_COOLDOWN_MS = 2500L

        // ── 就绪信号签名（sampleSignature）──
        /** 分块数（列×行）。8×4 = 32 块 ⇒ 签名 96 字节；块足够大 ⇒ 均值抗噪。 */
        const val SIG_BLOCKS_X = 8
        const val SIG_BLOCKS_Y = 4
        /** 每块每轴的采样点数（3 ⇒ 9 点/块，共 288 次字节读 ≈ 1µs）。 */
        const val SIG_SAMPLES_PER_AXIS = 3
        /** 签名比较容差（单通道 |Δ|>2 记为"变了"）；与 `VoteJudges.THUMB_DIFF_TOL` 同值。 */
        const val SIG_BLOCK_TOL = 2

        // ── 稳定性探针（probeRoiStability）──
        /** 单个 ROI 的采样总时限（超时即报已有结果，避免探针挂死）。 */
        const val STABILITY_PROBE_MAX_MS = 12_000L
        /** 同一帧时等待下一帧的间隔。 */
        const val STABILITY_PROBE_IDLE_MS = 3L
    }
}
