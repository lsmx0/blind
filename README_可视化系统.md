# YOLOv8-Seg 实时检测可视化系统

## 系统概述

在原有实时检测系统基础上，添加了完整的算法过程可视化功能，用于调试、演示和答辩说明。

### 核心特性

✅ **不破坏现有逻辑**：所有推理和导航代码保持不变  
✅ **透明叠加显示**：Overlay 覆盖在相机预览上，不影响性能  
✅ **实时可视化**：同步显示检测结果和算法中间过程  
✅ **可开关控制**：支持显示/隐藏可视化层  

## 架构设计

### 层次结构

```
┌─────────────────────────────────┐
│   DebugOverlayView (透明)       │  ← 🎯 目标 2：Overlay 层
│   - 分割 mask (绿色)            │
│   - 重心 (红点)                 │
│   - 中心线 (白虚线)             │
│   - PCA 方向 (黄箭头)           │
│   - 状态文字                    │
├─────────────────────────────────┤
│   PreviewView                   │  ← 🎯 目标 1：相机预览
│   (CameraX 实时画面)            │
└─────────────────────────────────┘
```

### 数据流

```
相机帧 → YOLO推理 → mask + 结果
                      ↓
            ┌─────────┴─────────┐
            ↓                   ↓
    BlindPathGuide      VisualizationData
    (语音导航)          (可视化数据)
            ↓                   ↓
        TTS播报          DebugOverlayView
                         (实时绘制)
```

## 🎯 目标实现详解

### 目标 1：确认显示摄像头视角

**实现位置**：`MainActivity.kt` - `RealtimeDetectionScreen()`

**关键代码**：
```kotlin
// PreviewView 作为底层
AndroidView(
    factory = { PreviewView(it) },
    modifier = Modifier.fillMaxSize()
)
```

**设计原因**：
- 保持 CameraX 原生预览性能
- 不转换为 Bitmap（避免性能损失）
- 用户看到的是真实相机画面

### 目标 2：Overlay View 叠加显示

**实现位置**：`DebugOverlayView.kt` + `MainActivity.kt`

**布局结构**：
```kotlin
Box {
    // 底层：相机预览
    AndroidView { PreviewView(it) }
    
    // 上层：透明 Overlay
    AndroidView { DebugOverlayView(it) }
}
```

**设计原因**：
- `Box` 布局实现层叠
- Overlay 透明背景，不遮挡相机
- 所有检测结果绘制在 Overlay 上

### 目标 3：实时可视化分割 mask

**实现位置**：`DebugOverlayView.kt` - `drawMask()`

**技术细节**：
```kotlin
// 降采样绘制（step=4）
for (y in 0 until 640 step 4) {
    for (x in 0 until 640 step 4) {
        if (mask[y * 640 + x] > 0.5f) {
            // 绘制半透明绿色方块
            canvas.drawRect(...)
        }
    }
}
```

**设计原因**：
- 降采样提升性能（640×640 → 160×160 绘制点）
- 半透明绿色（alpha=80），不完全遮挡
- 清楚显示模型识别的盲道区域

**可视化效果**：
- ✅ 用户能看到"模型眼中的盲道"
- ✅ 验证模型是否正确检测
- ✅ 调试模型准确性

### 目标 4：可视化偏离判断过程

**实现位置**：`DebugOverlayView.kt` - `drawCentroid()` + `drawCenterLine()`

**绘制元素**：

1. **画面中心参考线**（白色虚线）
```kotlin
val centerX = width / 2f
canvas.drawLine(centerX, 0f, centerX, height, centerLinePaint)
```

2. **Mask 像素重心**（红色圆点）
```kotlin
canvas.drawCircle(centroidX, centroidY, 15f, centroidPaint)
```

**设计原因**：
- 中心线：提供"居中"的参考标准
- 重心点：显示盲道的实际中心位置
- 对比关系：直观看出偏移方向和程度

**可视化效果**：
- ✅ 重心在中心线左侧 → 偏左 → "请向右调整"
- ✅ 重心在中心线右侧 → 偏右 → "请向左调整"
- ✅ 重心接近中心线 → 居中 → 无提示

### 目标 5：可视化 PCA 主方向

**实现位置**：`DebugOverlayView.kt` - `drawPCADirection()`

**技术细节**：
```kotlin
// 从重心出发，按 PCA 角度绘制方向线
val radians = Math.toRadians(angle)
val endX = startX + length * sin(radians)
val endY = startY - length * cos(radians)

canvas.drawLine(startX, startY, endX, endY, pcaLinePaint)
drawArrowHead(...)  // 绘制箭头
```

**设计原因**：
- 黄色箭头：醒目，易于识别
- 从重心出发：表示盲道走向
- 箭头方向：直观显示转向趋势

**可视化效果**：
- ✅ 箭头向左偏 → 左转 → "前方左转"
- ✅ 箭头向右偏 → 右转 → "前方右转"
- ✅ 箭头向上 → 直行 → 无提示

**角度说明**：
- `angle > 15°`：左转
- `angle < -15°`：右转
- `-15° ≤ angle ≤ 15°`：直行

### 目标 6：数据流与代码结构

**实现位置**：`CameraManager.kt` - `BlindPathAnalyzer.analyze()`

**完整数据流**：
```kotlin
// 1. YOLO 推理（复用现有）
val result = yoloModel.runInference(bitmap, 0.5f)

// 2. 语音导航（复用现有）
blindPathGuide.processMaskAndGuide(result.maskArray)

// 3. 获取可视化数据（新增）
val vizData = blindPathGuide.analyzeForVisualization(result.maskArray)

// 4. 更新 Overlay（新增）
overlayView?.mask = result.maskArray
overlayView?.centroid = vizData.centroid
overlayView?.pcaAngle = vizData.pcaAngle
overlayView?.offsetStatus = vizData.offsetStatus
overlayView?.turnStatus = vizData.turnStatus
// invalidate() 自动触发重绘
```

**设计原则**：
- ✅ 不修改推理逻辑
- ✅ 不修改导航算法
- ✅ 只添加数据传递
- ✅ Overlay 自动重绘

### 目标 7：完整可运行示例

**文件清单**：

1. **DebugOverlayView.kt**（新增）
   - 自定义 View
   - 完整的 onDraw() 实现
   - 所有可视化元素

2. **BlindPathGuide.kt**（修改）
   - 新增 `analyzeForVisualization()` 方法
   - 新增 `VisualizationData` 数据类
   - 不影响原有功能

3. **CameraManager.kt**（修改）
   - 构造函数添加 `overlayView` 参数
   - 推理完成后更新 Overlay
   - 不影响推理流程

4. **MainActivity.kt**（修改）
   - Box 布局叠加 PreviewView 和 Overlay
   - 添加"显示/隐藏可视化"按钮
   - 传递 Overlay 引用给 CameraManager

## 使用方法

### 1. 启动实时检测

1. 打开应用
2. 切换到"实时检测"标签
3. 授予相机权限
4. 点击"开始检测"

### 2. 查看可视化

默认开启可视化，你会看到：

- **绿色半透明区域**：模型识别的盲道
- **红色圆点**：盲道重心位置
- **白色虚线**：画面中心参考
- **黄色箭头**：盲道走向（PCA 方向）
- **左上角文字**：偏移和转向状态

### 3. 开关可视化

- 点击"隐藏可视化"：只显示相机画面
- 点击"显示可视化"：重新显示所有元素
- 注意：需要在停止检测时切换

### 4. 调试和演示

**调试场景**：
- 验证模型是否正确识别盲道
- 检查重心计算是否准确
- 确认 PCA 方向是否合理
- 对比语音提示与可视化结果

**演示场景**：
- 向他人展示系统工作原理
- 答辩时解释算法过程
- 录制演示视频

## 可视化元素说明

### 1. 分割 Mask（绿色）

**含义**：模型识别出的盲道区域

**判断标准**：
- ✅ 绿色区域完整覆盖盲道 → 检测准确
- ❌ 绿色区域不连续 → 可能有遮挡
- ❌ 绿色区域偏移 → 模型误检

### 2. 重心（红点）

**含义**：盲道的中心位置

**判断标准**：
- 红点在中心线上 → 居中
- 红点在中心线左侧 → 偏左
- 红点在中心线右侧 → 偏右

### 3. 中心参考线（白虚线）

**含义**：画面的垂直中心

**作用**：
- 提供"居中"的参考标准
- 与重心对比判断偏移

### 4. PCA 方向（黄箭头）

**含义**：盲道的主方向

**判断标准**：
- 箭头向上 → 直行
- 箭头向左上 → 左转
- 箭头向右上 → 右转

**角度标注**：
- 箭头末端显示角度值
- 正值 = 左转，负值 = 右转

### 5. 状态文字（左上角）

**显示内容**：
- 偏移状态：偏左/居中/偏右
- 转向状态：左转/直行/右转
- PCA 角度：具体数值

**颜色说明**：
- 绿色：正常状态
- 黄色：需要调整
- 青色：转向提示

## 性能优化

### 1. 降采样绘制

```kotlin
// Mask 绘制：640×640 → 160×160 点
for (y in 0 until 640 step 4) {
    for (x in 0 until 640 step 4) {
        // 只绘制 1/16 的点
    }
}
```

**效果**：
- 绘制点数：从 409,600 降到 25,600
- 性能提升：约 16 倍
- 视觉效果：几乎无差异

### 2. 按需重绘

```kotlin
var mask: FloatArray? = null
    set(value) {
        field = value
        invalidate()  // 只在数据更新时重绘
    }
```

**效果**：
- 避免无效重绘
- 降低 CPU 占用

### 3. 硬件加速

```kotlin
// View 默认启用硬件加速
setLayerType(View.LAYER_TYPE_HARDWARE, null)
```

**效果**：
- GPU 加速绘制
- 提升流畅度

## 常见问题

### Q1: Overlay 不显示？

**检查**：
1. 是否点击了"显示可视化"
2. 是否有检测结果（mask 不为 null）
3. 查看 Logcat 是否有错误

### Q2: 绘制卡顿？

**解决**：
- 增加降采样步长（step=4 → step=8）
- 减少绘制元素
- 检查设备性能

### Q3: 坐标不准确？

**原因**：
- Mask 坐标（640×640）需要映射到屏幕坐标
- 不同设备屏幕比例不同

**解决**：
```kotlin
val scaleX = width.toFloat() / 640
val scaleY = height.toFloat() / 640
val screenX = maskX * scaleX
val screenY = maskY * scaleY
```

### Q4: 如何录制演示视频？

**方法**：
1. 使用 Android 屏幕录制功能
2. 或使用 ADB：`adb shell screenrecord /sdcard/demo.mp4`
3. 确保可视化已开启

## 扩展功能

### 1. 添加更多可视化元素

```kotlin
// 在 DebugOverlayView 中添加
fun drawBoundingBox(canvas: Canvas, bbox: FloatArray) {
    // 绘制检测框
}

fun drawConfidence(canvas: Canvas, conf: Float) {
    // 绘制置信度
}
```

### 2. 自定义颜色和样式

```kotlin
// 修改画笔配置
private val maskPaint = Paint().apply {
    color = Color.argb(80, 255, 0, 0)  // 改为红色
    style = Paint.Style.FILL
}
```

### 3. 添加历史轨迹

```kotlin
private val trajectoryPoints = mutableListOf<Pair<Float, Float>>()

fun drawTrajectory(canvas: Canvas) {
    // 绘制重心移动轨迹
}
```

## 总结

✅ **完整实现了所有目标**：
1. 确认显示摄像头视角
2. Overlay 透明叠加
3. 实时可视化 mask
4. 可视化偏离判断
5. 可视化 PCA 方向
6. 数据流清晰
7. 完整可运行

✅ **不破坏现有功能**：
- 推理逻辑不变
- 导航算法不变
- 只添加可视化层

✅ **适用场景**：
- 调试模型准确性
- 演示系统原理
- 答辩讲解算法
- 录制演示视频

现在你可以直接运行应用，实时查看完整的检测过程和算法中间结果！
