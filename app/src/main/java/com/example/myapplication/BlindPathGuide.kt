package com.example.myapplication

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.*
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 盲道导航系统
 * 基于 YOLOv8-Seg mask 输出，提供偏移检测、转向判断和语音导航
 */
class BlindPathGuide(context: Context) {
    
    // TTS 语音播报
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    
    // 限频机制
    private var lastSpeakTime = 0L
    private var lastSpeakContent = ""
    private val speakInterval = 2000L // 2秒内不重复播报
    
    // 配置参数
    private val imageWidth = 640
    private val imageHeight = 640
    private val centerX = imageWidth / 2f
    private val offsetDeadZone = imageWidth * 0.15f // 🎯 调整：15% 死区（更宽容）
    private val turnAngleThreshold = 10f // 🎯 调整：10° 阈值（中轴线方法更准确，可以用更小的阈值）
    
    // 🎯 新增：中轴线历史（用于平滑）
    private val centerlineHistory = mutableListOf<List<Pair<Float, Float>>>()
    private val maxHistorySize = 3
    
    init {
        initTTS(context)
    }
    
    /**
     * 初始化 TTS
     */
    private fun initTTS(context: Context) {
        println("🔊 初始化 TTS...")
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                isTtsReady = result != TextToSpeech.LANG_MISSING_DATA && 
                             result != TextToSpeech.LANG_NOT_SUPPORTED
                
                if (isTtsReady) {
                    println("✅ TTS 初始化成功")
                } else {
                    println("❌ TTS 语言设置失败: $result")
                }
                
                // 设置播报监听器
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        println("🔊 开始播报: $utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        println("✅ 播报完成: $utteranceId")
                    }
                    override fun onError(utteranceId: String?) {
                        println("❌ 播报错误: $utteranceId")
                    }
                })
            } else {
                println("❌ TTS 初始化失败: $status")
            }
        }
    }
    
    /**
     * 🎯 目标 5：主处理函数（使用中轴线方法）
     * 处理 mask 并输出语音导航
     */
    fun processMaskAndGuide(mask: FloatArray) {
        println("\n========== 盲道导航分析 ==========")
        
        // 1. 判断偏移（使用中轴线方法）
        val offset = detectOffsetFromCenterline(mask)
        
        // 2. 计算转向（使用中轴线方法）
        val turn = detectTurn(mask)
        
        // 3. 决策融合
        val action = decideAction(offset, turn)
        
        // 4. 语音播报
        if (action != null) {
            println("🔊 语音播报: $action")
            speakIfNeeded(action)
        } else {
            println("✅ 保持当前方向")
        }
        
        println("===================================\n")
    }
    
    /**
     * 🎯 新增：获取完整的分析结果（用于可视化）
     * 
     * 作用：
     * - 提供中间计算结果给 Overlay View
     * - 不影响原有的语音导航逻辑
     * - 用于调试和演示
     */
    fun analyzeForVisualization(mask: FloatArray): VisualizationData? {
        val centroid = calculateCentroid(mask) ?: return null
        val offset = detectOffset(centroid.first)
        val turn = detectTurn(mask)
        val angle = calculatePCAAngle(mask)
        
        return VisualizationData(
            centroid = centroid,
            offsetStatus = offset,
            turnStatus = turn,
            pcaAngle = angle
        )
    }
    
    /**
     * 🎯 目标 1：计算 mask 重心
     * @return Pair(Cx, Cy) 或 null（无前景像素）
     */
    fun calculateCentroid(mask: FloatArray, threshold: Float = 0.5f): Pair<Float, Float>? {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        
        for (y in 0 until imageHeight) {
            for (x in 0 until imageWidth) {
                val idx = y * imageWidth + x
                if (mask[idx] > threshold) {
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }
        
        if (count == 0) return null
        
        return Pair(sumX / count, sumY / count)
    }
    
    /**
     * 🎯 目标 1：判断偏移方向
     * @param cx 重心 x 坐标
     * @return -1: 偏左, 0: 居中, 1: 偏右
     */
    fun detectOffset(cx: Float): Int {
        val offset = cx - centerX
        
        return when {
            offset < -offsetDeadZone -> -1  // 偏左
            offset > offsetDeadZone -> 1    // 偏右
            else -> 0                        // 居中
        }
    }
    
    /**
     * 🎯 新算法：提取 Mask 的中轴线
     * 
     * 方法：
     * 1. 将图像按行分段（每 N 行一段）
     * 2. 找到每段中前景像素的左右边界
     * 3. 计算中心点作为中轴线上的点
     * 
     * @return 中轴线点列表 [(x, y), ...]，从上到下排列
     */
    private fun extractCenterline(mask: FloatArray, threshold: Float = 0.5f): List<Pair<Float, Float>> {
        val centerline = mutableListOf<Pair<Float, Float>>()
        val rowStep = 20 // 每 20 行采样一次
        
        for (y in 0 until imageHeight step rowStep) {
            var minX = imageWidth
            var maxX = 0
            var hasPixel = false
            
            // 扫描这一行，找到左右边界
            for (x in 0 until imageWidth) {
                val idx = y * imageWidth + x
                if (mask[idx] > threshold) {
                    hasPixel = true
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                }
            }
            
            // 如果这一行有前景像素，计算中心点
            if (hasPixel && maxX > minX) {
                val centerX = (minX + maxX) / 2f
                centerline.add(Pair(centerX, y.toFloat()))
            }
        }
        
        return centerline
    }
    
    /**
     * 🎯 新算法：对中轴线进行线性拟合
     * 
     * 使用最小二乘法拟合直线：x = a * y + b
     * （注意：这里 x 是因变量，y 是自变量，因为盲道是竖直方向）
     * 
     * @return Pair(斜率 a, 截距 b)，如果点太少返回 null
     */
    private fun fitCenterline(centerline: List<Pair<Float, Float>>): Pair<Float, Float>? {
        if (centerline.size < 3) return null
        
        val n = centerline.size
        var sumX = 0f
        var sumY = 0f
        var sumXY = 0f
        var sumYY = 0f
        
        for (point in centerline) {
            val x = point.first
            val y = point.second
            sumX += x
            sumY += y
            sumXY += x * y
            sumYY += y * y
        }
        
        // 最小二乘法：x = a * y + b
        val denominator = n * sumYY - sumY * sumY
        if (abs(denominator) < 0.001f) return null
        
        val a = (n * sumXY - sumX * sumY) / denominator  // 斜率
        val b = (sumX * sumYY - sumY * sumXY) / denominator  // 截距
        
        return Pair(a, b)
    }
    
    /**
     * 🎯 新算法：从拟合直线计算角度
     * 
     * 直线方程：x = a * y + b
     * 斜率 a 的含义：
     * - a = 0：垂直向上（直行）
     * - a > 0：向右倾斜（从用户视角看是左转）
     * - a < 0：向左倾斜（从用户视角看是右转）
     * 
     * @return 角度（度），正值表示左转，负值表示右转
     */
    private fun calculateAngleFromSlope(slope: Float): Float {
        // 将斜率转换为角度
        // atan(slope) 给出的是直线与 Y 轴的夹角
        val angleRad = atan(slope)
        val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
        
        // 🎯 角度含义：
        // 0° = 垂直向上（直行）
        // 正值 = 向右倾斜 = 左转
        // 负值 = 向左倾斜 = 右转
        
        return angleDeg
    }
    
    /**
     * 🎯 新算法：基于中轴线计算角度
     * @return 角度（度），正值表示左转，负值表示右转
     */
    fun calculatePCAAngle(mask: FloatArray, threshold: Float = 0.5f, sampleStep: Int = 2): Float? {
        // 1. 提取中轴线
        val centerline = extractCenterline(mask, threshold)
        if (centerline.isEmpty()) return null
        
        // 2. 线性拟合
        val fit = fitCenterline(centerline) ?: return null
        val (slope, intercept) = fit
        
        // 3. 计算角度
        val angle = calculateAngleFromSlope(slope)
        
        println("🎯 中轴线分析:")
        println("  中轴线点数: ${centerline.size}")
        println("  拟合斜率: %.4f".format(slope))
        println("  拟合截距: %.1f".format(intercept))
        println("  计算角度: %.1f°".format(angle))
        
        return angle
    }
    
    /**
     * 🎯 新算法：基于中轴线判断偏移
     * 
     * 方法：计算拟合直线在画面中部的 X 坐标，与画面中心比较
     * 
     * @return -1: 偏左, 0: 居中, 1: 偏右
     */
    fun detectOffsetFromCenterline(mask: FloatArray): Int {
        val centerline = extractCenterline(mask)
        if (centerline.isEmpty()) {
            // 如果没有中轴线，回退到重心方法
            val centroid = calculateCentroid(mask) ?: return 0
            return detectOffset(centroid.first)
        }
        
        val fit = fitCenterline(centerline)
        if (fit == null) {
            // 如果拟合失败，回退到重心方法
            val centroid = calculateCentroid(mask) ?: return 0
            return detectOffset(centroid.first)
        }
        
        val (slope, intercept) = fit
        
        // 计算直线在画面中部（y = imageHeight / 2）的 x 坐标
        val midY = imageHeight / 2f
        val midX = slope * midY + intercept
        
        // 与画面中心比较
        val offset = midX - centerX
        
        println("🎯 偏移分析:")
        println("  画面中心: $centerX")
        println("  中轴线中点: %.1f".format(midX))
        println("  偏移量: %.1f".format(offset))
        
        return when {
            offset < -offsetDeadZone -> {
                println("  判断: 偏左")
                -1  // 偏左
            }
            offset > offsetDeadZone -> {
                println("  判断: 偏右")
                1   // 偏右
            }
            else -> {
                println("  判断: 居中")
                0   // 居中
            }
        }
    }
    
    /**
     * 🎯 目标 2：判断转向（基于中轴线）
     * @return -1: 右转, 0: 直行, 1: 左转
     */
    fun detectTurn(mask: FloatArray): Int {
        val angle = calculatePCAAngle(mask) ?: return 0
        
        return when {
            angle > turnAngleThreshold -> {
                println("  转向判断: 左转")
                1   // 左转
            }
            angle < -turnAngleThreshold -> {
                println("  转向判断: 右转")
                -1  // 右转
            }
            else -> {
                println("  转向判断: 直行")
                0   // 直行
            }
        }
    }
    
    /**
     * 🎯 目标 3：决策融合
     * 优先级：转向 > 偏移 > 无动作
     */
    fun decideAction(offset: Int, turn: Int): String? {
        // 优先级 1：转向
        if (turn != 0) {
            return when (turn) {
                1 -> "前方左转"
                -1 -> "前方右转"
                else -> null
            }
        }
        
        // 优先级 2：偏移
        if (offset != 0) {
            return when (offset) {
                -1 -> "请向右调整"
                1 -> "请向左调整"
                else -> null
            }
        }
        
        // 无需播报
        return null
    }
    
    /**
     * 🎯 目标 4：语音播报（带限频和去重）
     */
    fun speakIfNeeded(message: String) {
        println("🔊 尝试播报: $message")
        println("  TTS 就绪: $isTtsReady")
        
        if (!isTtsReady) {
            println("  ❌ TTS 未就绪，跳过播报")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        
        // 限频：相同内容 2 秒内不重复播报
        if (message == lastSpeakContent && 
            currentTime - lastSpeakTime < speakInterval) {
            println("  ⏭️ 限频跳过（上次播报: ${currentTime - lastSpeakTime}ms 前）")
            return
        }
        
        // 播报
        println("  ✅ 执行播报")
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "BlindPathGuide")
        
        // 更新状态
        lastSpeakTime = currentTime
        lastSpeakContent = message
    }
    
    /**
     * 手动播报（无限频）
     */
    fun speak(message: String) {
        if (!isTtsReady) return
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "BlindPathGuide")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
    
    /**
     * 设置播报间隔（毫秒）
     */
    fun setSpeakInterval(intervalMs: Long) {
        // 可以添加动态调整功能
    }
    
    /**
     * 获取调试信息
     */
    fun getDebugInfo(mask: FloatArray): String {
        val centroid = calculateCentroid(mask)
        val offset = centroid?.let { detectOffset(it.first) } ?: 0
        val turn = detectTurn(mask)
        val angle = calculatePCAAngle(mask)
        
        return buildString {
            appendLine("=== 盲道导航调试信息 ===")
            appendLine("重心: ${centroid?.let { "(%.1f, %.1f)".format(it.first, it.second) } ?: "未检测"}")
            appendLine("偏移: ${when(offset) { -1 -> "偏左" 1 -> "偏右" else -> "居中" }}")
            appendLine("PCA 角度: ${angle?.let { "%.1f°".format(it) } ?: "N/A"}")
            appendLine("转向: ${when(turn) { -1 -> "右转" 1 -> "左转" else -> "直行" }}")
            appendLine("决策: ${decideAction(offset, turn) ?: "无动作"}")
        }
    }
}


/**
 * 可视化数据类
 * 
 * 包含所有需要在 Overlay 上显示的中间结果
 */
data class VisualizationData(
    val centroid: Pair<Float, Float>,  // 重心坐标
    val offsetStatus: Int,              // 偏移状态：-1=偏左, 0=居中, 1=偏右
    val turnStatus: Int,                // 转向状态：-1=右转, 0=直行, 1=左转
    val pcaAngle: Float?                // PCA 角度（度）
)
