package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * CameraX 管理器
 * 负责相机初始化、预览和实时图像分析
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val yoloModel: YoloSegModel,
    private val blindPathGuide: BlindPathGuide,
    private val overlayView: DebugOverlayView? = null  // 🎯 新增：可视化 Overlay
) {
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var preview: Preview? = null
    
    // 推理控制：避免帧堆积
    @Volatile
    private var isProcessing = false
    
    // 性能控制：限制推理频率
    private var lastProcessTime = 0L
    private val minProcessInterval = 150L // 150ms 一次推理（约 6-7 FPS）
    
    // 相机执行器
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    // 统计信息
    var totalFrames = 0
        private set
    var processedFrames = 0
        private set
    var lastInferenceTime = 0L
        private set
    
    /**
     * 🎯 目标 1：启动相机
     * @param previewView 预览视图
     * @param onError 错误回调
     */
    fun startCamera(previewView: PreviewView, onError: (String) -> Unit = {}) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(previewView)
            } catch (e: Exception) {
                onError("相机初始化失败: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * 🎯 目标 1：绑定相机用例
     * 包括 Preview（预览）和 ImageAnalysis（分析）
     */
    private fun bindCameraUseCases(previewView: PreviewView) {
        val cameraProvider = cameraProvider ?: return
        
        // 解绑之前的用例
        cameraProvider.unbindAll()
        
        // 相机选择器：后置摄像头
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        
        // 用例 1：Preview（预览）
        // 作用：实时显示相机画面，让用户看到当前视野
        preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
        
        // 用例 2：ImageAnalysis（图像分析）
        // 作用：获取每一帧图像数据，用于 YOLO 推理
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(android.util.Size(1280, 720)) // 分辨率
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 只保留最新帧
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, BlindPathAnalyzer())
            }
        
        try {
            // 绑定用例到生命周期
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * 🎯 目标 3 & 4：图像分析器
     * 核心逻辑：
     * 1. 从 ImageProxy 获取图像
     * 2. 转换为 Bitmap
     * 3. 调用已有的推理函数
     * 4. 将 mask 传递给导航系统
     */
    private inner class BlindPathAnalyzer : ImageAnalysis.Analyzer {
        
        override fun analyze(imageProxy: ImageProxy) {
            totalFrames++
            
            // 🎯 目标 3：性能控制 - 单线程推理锁
            // 原因：避免多个推理任务同时运行，导致内存溢出和卡顿
            if (isProcessing) {
                imageProxy.close()
                return
            }
            
            // 🎯 目标 3：性能控制 - 时间间隔限制
            // 原因：即使没有推理任务，也要控制推理频率，避免 CPU 过载
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessTime < minProcessInterval) {
                imageProxy.close()
                return
            }
            
            // 标记为处理中
            isProcessing = true
            lastProcessTime = currentTime
            processedFrames++
            
            try {
                val startTime = System.currentTimeMillis()
                
                // 🎯 目标 2：ImageProxy → Bitmap
                // 原因：YOLO 模型需要 Bitmap 输入，需要转换 YUV 格式
                val bitmap = imageProxyToBitmap(imageProxy)
                
                if (bitmap != null) {
                    println("Bitmap 尺寸: ${bitmap.width}x${bitmap.height}")
                    
                    // 🎯 目标 4：调用已有的推理函数
                    // 原因：复用现有逻辑，不重复实现
                    // 降低置信度阈值到 0.25，更容易检测到目标
                    val result = yoloModel.runInference(bitmap, confThreshold = 0.25f)
                    
                    println("=== 实时检测调试 ===")
                    println("推理结果: ${if (result != null) "成功" else "失败"}")
                    
                    if (result != null) {
                        println("置信度: ${result.confidence}")
                        println("maskArray 长度: ${result.maskArray.size}")
                        
                        // 统计 mask 前景像素
                        val foregroundCount = result.maskArray.count { it > 0.5f }
                        println("前景像素数: $foregroundCount / ${result.maskArray.size}")
                        
                        // 🎯 目标 4：将 mask 接入导航系统
                        // 原因：实时分析盲道位置，提供语音导航
                        blindPathGuide.processMaskAndGuide(result.maskArray)
                        
                        // 🎯 目标 6：更新可视化 Overlay
                        // 原因：显示检测结果和算法过程，用于调试和演示
                        if (overlayView != null) {
                            println("Overlay View: 存在")
                            
                            // 获取可视化数据
                            val vizData = blindPathGuide.analyzeForVisualization(result.maskArray)
                            
                            println("可视化数据: ${if (vizData != null) "成功" else "失败"}")
                            
                            if (vizData != null) {
                                println("重心: (${vizData.centroid.first}, ${vizData.centroid.second})")
                                println("偏移状态: ${vizData.offsetStatus}")
                                println("转向状态: ${vizData.turnStatus}")
                                println("PCA 角度: ${vizData.pcaAngle}")
                                
                                // 更新 Overlay 数据
                                overlayView.mask = result.maskArray
                                overlayView.centroid = vizData.centroid
                                overlayView.pcaAngle = vizData.pcaAngle
                                overlayView.offsetStatus = vizData.offsetStatus
                                overlayView.turnStatus = vizData.turnStatus
                                
                                println("Overlay 已更新")
                            }
                        } else {
                            println("Overlay View: 不存在")
                        }
                    } else {
                        println("未检测到目标")
                        // 未检测到目标，清空 Overlay
                        overlayView?.clear()
                    }
                    
                    println("==================")
                    
                    bitmap.recycle() // 释放 Bitmap 内存
                }
                
                lastInferenceTime = System.currentTimeMillis() - startTime
                
            } catch (e: Exception) {
                e.printStackTrace()
                println("推理异常: ${e.message}")
            } finally {
                // 释放锁，允许下一帧处理
                isProcessing = false
                imageProxy.close()
            }
        }
    }
    
    /**
     * 🎯 目标 2：ImageProxy 转 Bitmap（修复版）
     * 
     * 技术细节：
     * 1. ImageProxy 通常是 YUV_420_888 格式
     * 2. 需要转换为 RGB Bitmap
     * 3. 不使用 OpenCV，使用 Android 原生 API
     * 
     * 性能优化：
     * - 直接从 YUV 转 JPEG 再转 Bitmap（Android 原生支持）
     * - 正确处理 YUV 平面的 stride 和 pixel stride
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            // 方法 1：如果是 JPEG 格式（某些设备）
            if (imageProxy.format == ImageFormat.JPEG) {
                val buffer = imageProxy.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            
            // 方法 2：YUV 格式转换（修复版）
            val bitmap = yuv420ToBitmap(imageProxy)
            
            // 旋转校正（相机图像可能需要旋转）
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                rotateBitmap(bitmap, rotationDegrees.toFloat())
            } else {
                bitmap
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 🎯 修复：正确的 YUV420 转 Bitmap 方法
     */
    private fun yuv420ToBitmap(imageProxy: ImageProxy): Bitmap {
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        
        // 🎯 关键修复：正确处理 YUV 平面的 stride 和 pixel stride
        val width = imageProxy.width
        val height = imageProxy.height
        
        // 创建 NV21 格式数据
        val nv21 = ByteArray(width * height * 3 / 2)
        
        // 复制 Y 平面
        var pos = 0
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        
        for (row in 0 until height) {
            for (col in 0 until width) {
                nv21[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
            }
        }
        
        // 复制 UV 平面（交错存储为 VU，即 NV21 格式）
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        val uvWidth = width / 2
        val uvHeight = height / 2
        
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vIndex = row * uvRowStride + col * uvPixelStride
                val uIndex = row * uvRowStride + col * uvPixelStride
                
                nv21[pos++] = vBuffer.get(vIndex)
                nv21[pos++] = uBuffer.get(uIndex)
            }
        }
        
        // 转换为 Bitmap
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
    
    /**
     * 旋转 Bitmap
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        bitmap.recycle()
        return rotated
    }
    
    /**
     * 停止相机
     */
    fun stopCamera() {
        cameraProvider?.unbindAll()
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopCamera()
        cameraExecutor.shutdown()
    }
    
    /**
     * 获取性能统计信息
     */
    fun getPerformanceStats(): String {
        val fps = if (totalFrames > 0) {
            processedFrames.toFloat() / totalFrames * 30 // 假设相机 30fps
        } else 0f
        
        return buildString {
            appendLine("总帧数: $totalFrames")
            appendLine("处理帧数: $processedFrames")
            appendLine("推理 FPS: %.1f".format(fps))
            appendLine("最后推理耗时: ${lastInferenceTime}ms")
        }
    }
}
