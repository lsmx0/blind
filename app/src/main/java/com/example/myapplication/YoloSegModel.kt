package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import ai.onnxruntime.*
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * 检测结果数据类
 */
data class Detection(
    val cx: Float,      // 中心点 x
    val cy: Float,      // 中心点 y
    val w: Float,       // 宽度
    val h: Float,       // 高度
    val conf: Float,    // 置信度
    val maskCoeff: FloatArray  // mask 系数 (32维)
)

class YoloSegModel(context: Context) {
    
    private lateinit var env: OrtEnvironment
    private lateinit var session: OrtSession
    
    init {
        loadModel(context)
    }
    
    /**
     * 加载 ONNX 模型
     */
    private fun loadModel(context: Context) {
        env = OrtEnvironment.getEnvironment()
        
        val modelBytes = context.assets.open("best.onnx").readBytes()
        
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        
        session = env.createSession(modelBytes, opts)
    }
    
    /**
     * 图像预处理：Bitmap -> FloatBuffer
     * YOLOv8 输入格式：RGB, CHW, float32, 归一化到 [0,1]
     */
    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val resized = Bitmap.createScaledBitmap(bitmap, 640, 640, true)
        val buffer = FloatBuffer.allocate(1 * 3 * 640 * 640)
        
        val pixels = IntArray(640 * 640)
        resized.getPixels(pixels, 0, 640, 0, 0, 640, 640)
        
        // R 通道
        for (y in 0 until 640) {
            for (x in 0 until 640) {
                val px = pixels[y * 640 + x]
                buffer.put(((px shr 16) and 0xFF) / 255f)
            }
        }
        
        // G 通道
        for (y in 0 until 640) {
            for (x in 0 until 640) {
                val px = pixels[y * 640 + x]
                buffer.put(((px shr 8) and 0xFF) / 255f)
            }
        }
        
        // B 通道
        for (y in 0 until 640) {
            for (x in 0 until 640) {
                val px = pixels[y * 640 + x]
                buffer.put((px and 0xFF) / 255f)
            }
        }
        
        buffer.rewind()
        return buffer
    }
    
    /**
     * 执行推理
     */
    fun runInference(bitmap: Bitmap, confThreshold: Float = 0.5f): SegmentationResult? {
        try {
            // 预处理
            val buffer = bitmapToFloatBuffer(bitmap)
            
            // 创建输入 tensor
            val inputName = session.inputNames.iterator().next()
            val tensor = OnnxTensor.createTensor(
                env,
                buffer,
                longArrayOf(1, 3, 640, 640)
            )
            
            // 执行推理
            val outputs = session.run(mapOf(inputName to tensor))
            
            // YOLOv8-seg 输出
            val detOutput = outputs[0].value
            val protoOutput = outputs[1].value
            
            // 解析最佳检测
            val bestDet = parseBestDetection(detOutput, confThreshold = confThreshold) ?: run {
                tensor.close()
                outputs.close()
                return null
            }
            
            // 生成 mask
            val rawMask = generateMask(bestDet, protoOutput)
            
            // 裁剪到 bbox
            val croppedMask = cropMask(rawMask, bestDet)
            
            // 转换为 Bitmap
            val maskBitmap = maskToBitmap(croppedMask, 640)
            
            // 缩放到原图大小
            val finalMask = Bitmap.createScaledBitmap(
                maskBitmap, 
                bitmap.width, 
                bitmap.height, 
                true
            )
            
            tensor.close()
            outputs.close()
            
            return SegmentationResult(
                maskBitmap = finalMask,
                confidence = bestDet.conf,
                classId = 0,
                bbox = floatArrayOf(bestDet.cx, bestDet.cy, bestDet.w, bestDet.h),
                maskArray = croppedMask  // 添加 mask 数组
            )
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    /**
     * Step 1：从 det 输出中取置信度最高的目标
     */
    private fun parseBestDetection(
        detOutput: Any,
        numClasses: Int = 1,
        confThreshold: Float = 0.5f
    ): Detection? {
        
        // 展平数组
        val detData = flattenArray(detOutput)
        
        // YOLOv8 输出格式: [1, N, ?] 其中 N 是检测数量
        // 每个检测: [cx, cy, w, h, conf, ...mask_coeff...]
        
        // 推断格式
        val numDetections = 8400
        val numFeatures = detData.size / numDetections
        
        var bestConf = 0f
        var best: Detection? = null
        
        for (i in 0 until numDetections) {
            // 提取置信度 (第5个位置，索引4)
            val conf = detData[4 * numDetections + i]
            
            if (conf < confThreshold) continue
            
            if (conf > bestConf) {
                val cx = detData[0 * numDetections + i]
                val cy = detData[1 * numDetections + i]
                val w = detData[2 * numDetections + i]
                val h = detData[3 * numDetections + i]
                
                // mask coefficients 起始位置: 5 (紧跟在 conf 后面)
                val maskStart = 5
                val maskCoeff = FloatArray(32)
                for (j in 0 until 32) {
                    maskCoeff[j] = detData[(maskStart + j) * numDetections + i]
                }
                
                bestConf = conf
                best = Detection(cx, cy, w, h, conf, maskCoeff)
            }
        }
        
        return best
    }
    
    /**
     * Step 2：计算分割 mask
     * 公式: mask = sigmoid(mask_coeff × proto)
     */
    private fun generateMask(
        det: Detection,
        protoOutput: Any
    ): FloatArray {
        
        // proto: [1, 32, 160, 160]
        val protoData = flattenArray(protoOutput)
        val mask = FloatArray(160 * 160)
        
        // 矩阵乘法: mask_coeff × proto
        for (i in 0 until 32) {
            val coeff = det.maskCoeff[i]
            for (y in 0 until 160) {
                for (x in 0 until 160) {
                    val protoIdx = i * 160 * 160 + y * 160 + x
                    mask[y * 160 + x] += coeff * protoData[protoIdx]
                }
            }
        }
        
        // 应用 sigmoid
        for (i in mask.indices) {
            mask[i] = 1f / (1f + exp(-mask[i]))
        }
        
        return mask
    }
    
    /**
     * Step 3：裁剪 mask 到 bbox 区域
     * YOLOv8-Seg 的 mask 是全图，需要裁剪到检测框
     */
    private fun cropMask(
        mask: FloatArray,
        det: Detection,
        imgSize: Int = 640
    ): FloatArray {
        
        val out = FloatArray(imgSize * imgSize)
        
        // bbox 坐标已经是像素值（0-640），不需要归一化
        val x1 = (det.cx - det.w / 2).toInt().coerceIn(0, imgSize - 1)
        val y1 = (det.cy - det.h / 2).toInt().coerceIn(0, imgSize - 1)
        val x2 = (det.cx + det.w / 2).toInt().coerceIn(0, imgSize - 1)
        val y2 = (det.cy + det.h / 2).toInt().coerceIn(0, imgSize - 1)
        
        // 将 160x160 的 mask 映射到 bbox 区域
        for (y in y1 until y2) {
            for (x in x1 until x2) {
                val mx = (x * 160) / imgSize
                val my = (y * 160) / imgSize
                out[y * imgSize + x] = mask[my * 160 + mx]
            }
        }
        
        return out
    }
    
    /**
     * Step 4：Mask 转 Bitmap（可视化）
     */
    private fun maskToBitmap(mask: FloatArray, size: Int = 640): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = mask[y * size + x]
                if (v > 0.5f) {
                    // 半透明绿色
                    bmp.setPixel(x, y, Color.argb(120, 0, 255, 0))
                } else {
                    bmp.setPixel(x, y, Color.TRANSPARENT)
                }
            }
        }
        
        return bmp
    }
    
    /**
     * 递归展平多维数组
     */
    private fun flattenArray(arr: Any): FloatArray {
        val result = mutableListOf<Float>()
        
        fun flatten(obj: Any) {
            when (obj) {
                is Array<*> -> obj.forEach { if (it != null) flatten(it) }
                is FloatArray -> result.addAll(obj.toList())
                is Float -> result.add(obj)
                is Double -> result.add(obj.toFloat())
                is Int -> result.add(obj.toFloat())
            }
        }
        
        flatten(arr)
        return result.toFloatArray()
    }
    
    /**
     * 释放资源
     */
    fun close() {
        if (::session.isInitialized) {
            session.close()
        }
        if (::env.isInitialized) {
            env.close()
        }
    }
}

/**
 * 分割结果数据类
 */
data class SegmentationResult(
    val maskBitmap: Bitmap,      // 分割 mask（用于显示）
    val confidence: Float,        // 置信度
    val classId: Int,            // 类别 ID
    val bbox: FloatArray?,       // 边界框 [cx, cy, w, h]
    val maskArray: FloatArray    // mask 数组（用于导航分析）
)
