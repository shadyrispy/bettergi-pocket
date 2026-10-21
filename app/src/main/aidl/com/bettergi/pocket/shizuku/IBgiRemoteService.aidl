package com.bettergi.pocket.shizuku;

/**
 * ★ #310/M1b 后台模式（virtualdisplay 分支）：BgiRemoteService 的 AIDL 契约。
 *
 * 跑在 Shizuku（shell/root uid）拉起的 UserService 进程里，app 侧经
 * `Shizuku.bindUserService` 拿到本接口的 binder 代理（见 BgiRemoteManager）。
 *
 * ## 官方依据（Shizuku-API README「UserService」+ 官方 demo IUserService.aidl）
 * - 服务类必须实现 `IBinder` 接口，常规形态 = 继承本接口的 `Stub`
 *   （demo：`public class UserService extends IUserService.Stub`）。
 * - **destroy 方法的 transact code 是 Shizuku server 定死的**：
 *   `ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy = 16777115`，而 AIDL 的
 *   方法编号 = FIRST_CALL_TRANSACTION(1) + 声明值，所以 AIDL 里写 `= 16777114`
 *   （1 + 16777114 = 16777115）。server 的 UserServiceRecord.destroy() 用
 *   `transact(16777115, FLAG_ONEWAY)` 调它 —— version 更新/解绑 remove 时由
 *   server 侧触发，实现方（BgiRemoteService）负责清理后 `System.exit()`。
 *   这与官方 demo `void destroy() = 16777114;` 逐字一致。
 * - **接口演进策略**：后续里程碑（M2 加 canCreateDisplay/createVirtualDisplay，
 *   M3 加输入注入）只在本接口**尾部追加方法、同时 SERVICE_VERSION++**。
 *   version 变了 Shizuku server 自动拉起新进程并调旧进程的 destroy()，
 *   app 侧 binder 代理随之换成新进程的 —— 接口无需拆分，靠 version 隔离新旧。
 *
 * ## 线程
 * Stub 默认在 binder 线程池被调；M1b 四个方法均无共享状态，天然线程安全。
 *
 * ## 方法编号（transaction id）
 * AIDL 要求**全部方法要么都带 id、要么都不带**（aidl 编译器硬约束，官方 demo 全带）。
 * 全显式编号把线上契约冻结：实际 transact code = FIRST_CALL_TRANSACTION(1) + id。
 * 追加新方法时只能**新增未用过的 id**（顺延 4、5、…），已发布编号永不复用/重排。
 */
interface IBgiRemoteService {

    /**
     * 存活 + 链路校验：返回 `token + 1`。
     *
     * 绑定方（BgiRemoteManager）在 onServiceConnected 后自动发一次 ping：
     * pong == 发出的 token + 1 即认为双向 binder 活着（防「onServiceConnected
     * 已回调但远端半死」的假连接）。调用方可随时重发，规则见
     * [com.bettergi.pocket.shizuku.BgiRemoteManager.isPongValid]。
     */
    long ping(long token) = 1;

    /**
     * 服务端构建版本（BgiRemoteService.SERVICE_VERSION，当前 3）。
     * 绑定方核对它来确认服务进程跑的是预期版本的代码。
     */
    String serviceVersion() = 2;

    /**
     * 服务进程的 `Build.VERSION.SDK_INT`（注意：是 UserService 进程的系统，
     * 与 app 进程同机，但语义上「提权进程报告自身环境」）。
     * 后续里程碑用它判能力（如 API 33+ 的 display 相关接口形态）。
     */
    int apiLevel() = 3;

    /**
     * 官方销毁约定（Shizuku server 调用）：transact code = 16777115
     * （= FIRST_CALL_TRANSACTION + 本声明值 16777114，见类 KDoc）。
     * 实现方做清理后必须 `System.exit(0)`，否则 UserService 进程不会被杀
     * （README 原文："the user service process will NOT be killed automatically"）。
     */
    // ── ★ #310/M3：虚拟显示器与输入注入（version 2 追加，id 4..8 顺延）──

    /**
     * 创建虚拟显示器并返回 displayId（-1 = 失败）。分辨率/密度由服务端默认档
     * （1280x720@160，MAA 同款），Surface 由服务端 ImageReader 持有。
     */
    int createDisplay() = 4;

    /** 启动游戏到指定 displayId（ActivityOptions.setLaunchDisplayId）。返回受理与否。 */
    boolean startAppOnDisplay(String packageName, int displayId) = 5;

    /** 把游戏 task 拉回指定 displayId（三级防漂移，服务端实现）。返回最终成功与否。 */
    boolean repinToDisplay(String packageName, int displayId) = 6;

    /** VD 上的单指点击（injectInputEvent + setDisplayId）。 */
    boolean tapOnDisplay(int displayId, int x, int y) = 7;

    /** VD 上的线性插值滑动（DOWN→MOVE×n→UP）。 */
    boolean swipeOnDisplay(int displayId, int fromX, int fromY, int toX, int toY) = 8;

    // ── ★ #310/M5：帧流与监视（version 3 追加，id 9..11 顺延）──

    /**
     * 服务进程把 VD 最新帧压成 JPEG 返回（长边 ≤1280、quality 70）。
     * **帧缓存为空 / VD 未建 ⇒ 返回 null**，调用方按「预览不可用」处理。
     */
    byte[] capturePreviewJpeg() = 9;

    /**
     * 游戏主进程是否存活（服务进程内 `pidof com.miHoYo.Yuanshen`，输出非空即活）。
     * **异常 / 超时按保守 true**（MAA 语义「宁可漏报不误杀」）。
     */
    boolean isGameAlive() = 10;

    /**
     * 服务端 fps 估计值（服务进程内 1s 采一次 frameGeneration 差分；未在采 = -1）。
     */
    int getGameFps() = 11;

    void destroy() = 16777114;
}
