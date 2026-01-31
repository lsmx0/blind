package com.example.myapplication

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 🎯 调试可视化 Overlay View
 * 
 * 功能：在摄像头预览上叠加显示检测结果和算法过程
 * 
 * 可视化内容：
 * 1. YOLOv8-Seg 分割 mask（半透明绿色）
 * 2. Mask 像素重心（红色圆点）
 * 3. 画面中心参考线（白色虚线）
 * 4. PCA 主方向线（黄色箭头）
 * 5. 偏移状态文字提示
 * 6. 转向状态文字提示
 */
class DebugOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // ========== 数据源 ==========
    // 这些数据由外部（CameraManager）在推理完成后更新
    
    /**
     * 🎯 目标 3：分割 mask
     * 640×640 的浮点数组，值域 [0, 1]
     */
    var mask: FloatArray? = null
        set(value) {
            field = value
            println("DebugOverlay: mask 已更新, 长度=${value?.size}")
            invalidate() // 触发重绘
        }
    
    /**
     * 🎯 目标 4：像素重心
     * (x, y) 坐标，范围 [0, 640]
     */
    var centroid: Pair<Float, Float>? = null
        set(value) {
            field = value
            println("DebugOverlay: centroid 已更新, 值=$value")
            invalidate()
        }
    
    /**
     * 🎯 目标 5：PCA 主方向角度
     * 单位：度，正值表示左转，负值表示右转
     */
    var pcaAngle: Float? = null
        set(value) {
            field = value
            println("DebugOverlay: pcaAngle 已更新, 值=$value")
            invalidate()
        }
    
    /**
     * 偏移状态：-1=偏左, 0=居中, 1=偏右
     */
    var offsetStatus: Int = 0
        set(value) {
            field = value
            println("DebugOverlay: offsetStatus 已更新, 值=$value")
            invalidate()
        }
    
    /**
     * 转向状态：-1=右转, 0=直行, 1=左转
     */
    var turnStatus: Int = 0
        set(value) {
            field = value
            println("DebugOverlay: turnStatus 已更新, 值=$value")
            invalidate()
        }
    
    // ========== 绘制配置 ==========
    
    // Mask 尺寸（模型输出）
    private val maskSize = 640
    
    // 降采样步长（性能优化）
    private val sampleStep = 4
    
    // 🎯 目标 3：Mask 绘制画笔（半透明绿色）
    private val maskPaint = Paint().apply {
        color = Color.argb(80, 0, 255, 0) // 半透明绿色
        style = Paint.Style.FILL
    }
    
    // 🎯 目标 4：重心绘制画笔（红色圆点）
    private val centroidPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        strokeWidth = 8f
    }
    
    // 🎯 目标 4：中心参考线画笔（白色虚线）
    private val centerLinePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f) // 虚线
    }
    
    // 🎯 目标 5：PCA 方向线画笔（黄色箭头）
    private val pcaLinePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    
    // 文字画笔
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK) // 文字阴影
    }
    
    // 背景半透明遮罩（用于文字背景）
    private val textBgPaint = Paint().apply {
        color = Color.argb(150, 0, 0, 0)
        style = Paint.Style.FILL
    }
    
    /**
     * 核心绘制函数
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        println("=== DebugOverlay onDraw ===")
        println("View 尺寸: ${width}x${height}")
        println("mask: ${if (mask != null) "存在" else "null"}")
        println("centroid: $centroid")
        println("pcaAngle: $pcaAngle")
        println("offsetStatus: $offsetStatus")
        println("turnStatus: $turnStatus")
        
        // 🎯 目标 4：绘制画面中心参考线
        // 作用：让用户看到"居中"的标准位置
        drawCenterLine(canvas)
        
        // 🎯 目标 3：绘制分割 mask
        // 作用：显示模型实际识别到的盲道区域
        mask?.let { 
            println("开始绘制 mask")
            drawMask(canvas, it) 
        }
        
        // 🎯 目标 4：绘制像素重心
        // 作用：显示盲道的中心位置，用于偏移判断
        centroid?.let { 
            println("开始绘制 centroid")
            drawCentroid(canvas, it) 
        }
        
        // 🎯 目标 5：绘制 PCA 主方向线
        // 作用：显示盲道走向，用于转向判断
        centroid?.let { center ->
            pcaAngle?.let { angle ->
                println("开始绘制 PCA 方向")
                drawPCADirection(canvas, center, angle)
            }
        }
        
        // 绘制状态文字
        drawStatusText(canvas)
        
        println("onDraw 完成")
        println("======================")
    }
    
    /**
     * 🎯 目标 4：绘制画面中心参考线
     * 
     * 设计原因：
     * - 提供一个固定的"居中"参考
     * - 用户可以直观看到重心是否偏离中心
     * - 对应语音提示"请向左/右调整"
     */
    private fun drawCenterLine(canvas: Canvas) {
        val centerX = width / 2f
        canvas.drawLine(centerX, 0f, centerX, height.toFloat(), centerLinePaint)
    }
    
    /**
     * 🎯 目标 3：绘制分割 mask
     * 
     * 技术细节：
     * - 从 640×640 的 mask 映射到屏幕坐标
     * - 使用降采样（step=4）提升性能
     * - 只绘制前景像素（> 0.5）
     * - 半透明绿色，不遮挡摄像头画面
     * 
     * 设计原因：
     * - 让用户看到模型"实际识别到的区域"
     * - 验证模型是否正确检测盲道
     * - 调试模型准确性
     */
    private fun drawMask(canvas: Canvas, mask: FloatArray) {
        val scaleX = width.toFloat() / maskSize
        val scaleY = height.toFloat() / maskSize
        
        // 降采样绘制，提升性能
        for (y in 0 until maskSize step sampleStep) {
            for (x in 0 until maskSize step sampleStep) {
                val idx = y * maskSize + x
                if (mask[idx] > 0.5f) {
                    // 映射到屏幕坐标
                    val screenX = x * scaleX
                    val screenY = y * scaleY
                    val rectSize = sampleStep * scaleX
                    
                    canvas.drawRect(
                        screenX,
                        screenY,
                        screenX + rectSize,
                        screenY + rectSize,
                        maskPaint
                    )
                }
            }
        }
    }
    
    /**
     * 🎯 目标 4：绘制像素重心
     * 
     * 技术细节：
     * - 重心坐标从 [0, 640] 映射到屏幕坐标
     * - 绘制红色圆点标记
     * - 圆点大小固定，易于识别
     * 
     * 设计原因：
     * - 显示盲道的"中心位置"
     * - 与中心参考线对比，判断偏移
     * - 对应偏移判断算法的输入
     */
    private fun drawCentroid(canvas: Canvas, centroid: Pair<Float, Float>) {
        val scaleX = width.toFloat() / maskSize
        val scaleY = height.toFloat() / maskSize
        
        val screenX = centroid.first * scaleX
        val screenY = centroid.second * scaleY
        
        // 绘制红色圆点
        canvas.drawCircle(screenX, screenY, 15f, centroidPaint)
        
        // 绘制外圈（更明显）
        val outerPaint = Paint(centroidPaint).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(screenX, screenY, 25f, outerPaint)
    }
    
    /**
     * 🎯 目标 5：绘制 PCA 主方向线
     * 
     * 技术细节：
     * - 从重心出发，按 PCA 角度绘制方向线
     * - 线长固定（150 像素）
     * - 黄色箭头，带箭头标记
     * 
     * 设计原因：
     * - 显示盲道的"走向"
     * - 解释为什么判断为左转/右转/直行
     * - 对应 PCA 算法的输出
     * 
     * 角度说明：
     * - 正值（> 15°）：左转
     * - 负值（< -15°）：右转
     * - 接近 0°：直行
     */
    private fun drawPCADirection(canvas: Canvas, centroid: Pair<Float, Float>, angle: Float) {
        val scaleX = width.toFloat() / maskSize
        val scaleY = height.toFloat() / maskSize
        
        val startX = centroid.first * scaleX
        val startY = centroid.second * scaleY
        
        // 方向线长度
        val lineLength = 150f
        
        // 将角度转换为弧度（注意：Canvas 的 Y 轴向下）
        val radians = Math.toRadians(angle.toDouble())
        val endX = startX + lineLength * sin(radians).toFloat()
        val endY = startY - lineLength * cos(radians).toFloat() // Y 轴反向
        
        // 绘制方向线
        canvas.drawLine(startX, startY, endX, endY, pcaLinePaint)
        
        // 绘制箭头
        drawArrowHead(canvas, startX, startY, endX, endY, pcaLinePaint)
        
        // 绘制角度文字
        val angleText = "%.1f°".format(angle)
        canvas.drawText(angleText, endX + 10, endY, textPaint)
    }
    
    /**
     * 绘制箭头头部
     */
    private fun drawArrowHead(
        canvas: Canvas,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        paint: Paint
    ) {
        val arrowSize = 20f
        val angle = Math.atan2((endY - startY).toDouble(), (endX - startX).toDouble())
        
        val x1 = endX - arrowSize * cos(angle - Math.PI / 6).toFloat()
        val y1 = endY - arrowSize * sin(angle - Math.PI / 6).toFloat()
        val x2 = endX - arrowSize * cos(angle + Math.PI / 6).toFloat()
        val y2 = endY - arrowSize * sin(angle + Math.PI / 6).toFloat()
        
        canvas.drawLine(endX, endY, x1, y1, paint)
        canvas.drawLine(endX, endY, x2, y2, paint)
    }
    
    /**
     * 绘制状态文字
     * 
     * 作用：
     * - 显示当前的偏移和转向状态
     * - 与语音播报内容对应
     * - 方便调试和演示
     */
    private fun drawStatusText(canvas: Canvas) {
        val padding = 20f
        var yPos = padding + 50f
        
        // 偏移状态
        val offsetText = when (offsetStatus) {
            -1 -> "偏移: 偏左 ←"
            1 -> "偏移: 偏右 →"
            else -> "偏移: 居中 ✓"
        }
        val offsetColor = when (offsetStatus) {
            -1 -> Color.YELLOW
            1 -> Color.YELLOW
            else -> Color.GREEN
        }
        
        drawTextWithBackground(canvas, offsetText, padding, yPos, offsetColor)
        yPos += 60f
        
        // 转向状态
        val turnText = when (turnStatus) {
            -1 -> "转向: 右转 ↷"
            1 -> "转向: 左转 ↶"
            else -> "转向: 直行 ↑"
        }
        val turnColor = when (turnStatus) {
            -1 -> Color.CYAN
            1 -> Color.CYAN
            else -> Color.GREEN
        }
        
        drawTextWithBackground(canvas, turnText, padding, yPos, turnColor)
        
        // PCA 角度
        pcaAngle?.let { angle ->
            yPos += 60f
            val angleText = "PCA: %.1f°".format(angle)
            drawTextWithBackground(canvas, angleText, padding, yPos, Color.WHITE)
        }
    }
    
    /**
     * 绘制带背景的文字
     */
    private fun drawTextWithBackground(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        textColor: Int
    ) {
        val bounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, bounds)
        
        // 绘制背景
        canvas.drawRect(
            x - 10,
            y - bounds.height() - 10,
            x + bounds.width() + 10,
            y + 10,
            textBgPaint
        )
        
        // 绘制文字
        val paint = Paint(textPaint).apply {
            color = textColor
        }
        canvas.drawText(text, x, y, paint)
    }
    
    /**
     * 清除所有数据
     */
    fun clear() {
        mask = null
        centroid = null
        pcaAngle = null
        offsetStatus = 0
        turnStatus = 0
        invalidate()
    }
}
