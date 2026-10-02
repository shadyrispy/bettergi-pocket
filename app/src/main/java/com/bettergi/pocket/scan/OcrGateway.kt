package com.bettergi.pocket.scan

import org.opencv.core.Mat

/** OCR 网关：ML Kit（P1-b）/ ONNX PaddleOCR（后续替换）双实现同接口。 */
interface OcrGateway {
    /** 定点数字 OCR（readCount 总数 N/M 等）。 */
    suspend fun readNumber(frame: Mat, rect: FrameRect): Int?

    /** rec-only 字段槽读取（parsePanel 字段行）。 */
    suspend fun readLines(frame: Mat, rects: List<FrameRect>): List<String>

    /**
     * 一次批量读全部槽位（parsePanel 的 name/slot/main/level/subStats 合一次调用）。
     * 返回与 rects 一一对应的文本（无内容为空串）。
     */
    suspend fun readRois(frame: Mat, rects: List<FrameRect>): List<String> =
        rects.map { rect -> readLines(frame, listOf(rect)).firstOrNull().orEmpty() }
}
