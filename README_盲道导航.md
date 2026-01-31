# YOLOv8-Seg 盲道导航系统

## 功能概述

基于 YOLOv8-Seg 模型的实时盲道检测与语音导航系统，为视障人士提供智能导航辅助。

## 核心功能

### 🎯 目标 1：偏移检测
- **功能**：检测用户是否偏离盲道中心
- **算法**：计算 mask 像素重心，与图像中心对比
- **输出**：
  - 偏左 → "请向右调整"
  - 偏右 → "请向左调整"
  - 居中 → 无提示
- **参数**：死区宽度 8%（可调整）

### 🎯 目标 2：转向判断
- **功能**：预判盲道转向趋势
- **算法**：PCA（主成分分析）计算 mask 主方向
- **输出**：
  - 左转 → "前方左转"
  - 右转 → "前方右转"
  - 直行 → 无提示
- **参数**：转向角度阈值 ±15°（可调整）

### 🎯 目标 3：决策融合
- **优先级**：
  1. 转向提示（优先级最高）
  2. 偏移纠正
  3. 无动作
- **逻辑**：避免同时播报多个指令，确保清晰

### 🎯 目标 4：语音播报
- **技术**：Android TextToSpeech
- **语言**：中文
- **限频机制**：
  - 相同内容 2 秒内不重复播报
  - 防止"连续念经"
- **去重机制**：记录上次播报内容

### 🎯 目标 5：一键调用
- **主函数**：`processMaskAndGuide(mask: FloatArray)`
- **集成方式**：在推理完成后直接调用
- **无侵入**：不影响现有推理流程

## 使用方法

### 1. 基本使用

```kotlin
// 初始化
val blindPathGuide = BlindPathGuide(context)

// 在推理完成后调用
val result = yoloModel.runInference(bitmap)
if (result != null) {
    // 自动分析并语音导航
    blindPathGuide.processMaskAndGuide(result.maskArray)
}

// 释放资源
blindPathGuide.release()
```

### 2. 获取调试信息

```kotlin
val debugInfo = blindPathGuide.getDebugInfo(result.maskArray)
println(debugInfo)
```

输出示例：
```
=== 盲道导航调试信息 ===
重心: (320.5, 400.2)
偏移: 居中
PCA 角度: 18.5°
转向: 左转
决策: 前方左转
```

### 3. 手动控制

```kotlin
// 单独计算重心
val centroid = blindPathGuide.calculateCentroid(mask)

// 单独判断偏移
val offset = blindPathGuide.detectOffset(centroid.first)

// 单独计算转向
val turn = blindPathGuide.detectTurn(mask)

// 手动播报（无限频）
blindPathGuide.speak("自定义消息")
```

## 参数调整

### 偏移检测参数

```kotlin
// 在 BlindPathGuide.kt 中修改
private val offsetDeadZone = imageWidth * 0.08f  // 死区宽度（8%）
```

建议值：
- 宽松：10-12%（减少误报）
- 标准：8%（默认）
- 严格：5-6%（更敏感）

### 转向检测参数

```kotlin
// 在 BlindPathGuide.kt 中修改
private val turnAngleThreshold = 15f  // 转向角度阈值（度）
```

建议值：
- 宽松：20-25°（只提示明显转向）
- 标准：15°（默认）
- 严格：10-12°（更早提示）

### 播报间隔

```kotlin
// 在 BlindPathGuide.kt 中修改
private val speakInterval = 2000L  // 毫秒
```

建议值：
- 快速：1500ms（频繁提示）
- 标准：2000ms（默认）
- 慢速：3000ms（减少打扰）

## 算法说明

### 重心计算

```
Cx = Σ(x * mask[x,y]) / Σ(mask[x,y])
Cy = Σ(y * mask[x,y]) / Σ(mask[x,y])
```

其中 mask[x,y] > 0.5 的像素参与计算。

### PCA 主方向

1. 计算均值：`meanX, meanY`
2. 计算协方差矩阵：
   ```
   varX = Σ(x - meanX)²
   varY = Σ(y - meanY)²
   covXY = Σ(x - meanX)(y - meanY)
   ```
3. 计算主方向角度：
   ```
   θ = 0.5 * atan2(2 * covXY, varX - varY)
   ```

### 决策树

```
if (转向 != 直行):
    播报转向
else if (偏移 != 居中):
    播报偏移
else:
    无动作
```

## UI 功能

### 新增控件

1. **语音导航开关**
   - 位置：置信度滑动条下方
   - 功能：开启/关闭语音播报

2. **导航调试信息卡片**
   - 位置：结果图下方
   - 内容：重心、偏移、角度、转向、决策
   - 样式：等宽字体，便于查看数值

### 使用流程

1. 选择图片
2. 调整置信度阈值（可选）
3. 开启/关闭语音导航
4. 点击"执行分割"
5. 查看分割结果和导航信息
6. 听取语音提示（如果开启）

## 性能优化

### PCA 采样优化

```kotlin
// 在 calculatePCAAngle 中
sampleStep: Int = 2  // 隔点采样
```

- `sampleStep = 1`：全采样（精度高，速度慢）
- `sampleStep = 2`：隔点采样（默认，平衡）
- `sampleStep = 3`：稀疏采样（速度快，精度略降）

### 内存优化

- mask 数组复用，避免频繁分配
- TTS 单例模式，避免重复初始化
- 限频机制减少不必要的计算

## 集成到 CameraX

```kotlin
// 在 CameraX 的 ImageAnalyzer 中
override fun analyze(imageProxy: ImageProxy) {
    val bitmap = imageProxy.toBitmap()
    
    // 推理
    val result = yoloModel.runInference(bitmap)
    
    // 导航（在后台线程）
    if (result != null) {
        blindPathGuide.processMaskAndGuide(result.maskArray)
    }
    
    imageProxy.close()
}
```

## 注意事项

1. **TTS 初始化**
   - 需要等待 TTS 引擎就绪
   - 首次播报可能有延迟

2. **权限要求**
   - 无需额外权限
   - TTS 使用系统服务

3. **语言支持**
   - 默认中文
   - 需要系统安装中文 TTS 引擎

4. **线程安全**
   - `processMaskAndGuide` 可在后台线程调用
   - TTS 播报会自动切换到主线程

## 故障排查

### 问题：无语音播报

**检查**：
1. 系统是否安装中文 TTS 引擎
2. 音量是否开启
3. `isTtsReady` 是否为 true
4. 查看 Logcat 是否有 TTS 错误

### 问题：播报过于频繁

**解决**：
- 增加 `speakInterval` 值
- 调整死区和阈值参数

### 问题：转向判断不准确

**解决**：
- 调整 `turnAngleThreshold`
- 检查 mask 质量（是否有噪点）
- 增加 PCA 采样密度（减小 sampleStep）

## 扩展功能

### 添加震动反馈

```kotlin
val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
```

### 添加距离估计

基于 bbox 大小估算距离：
```kotlin
val distance = estimateDistance(det.w, det.h)
```

### 多语言支持

```kotlin
tts?.setLanguage(Locale.ENGLISH)  // 切换到英文
```

## 总结

完整的盲道导航系统已集成到你的应用中，包括：

✅ 偏移检测（重心计算）  
✅ 转向判断（PCA 分析）  
✅ 决策融合（优先级逻辑）  
✅ 语音播报（TTS + 限频）  
✅ 一键调用（无侵入集成）  
✅ 调试信息（实时反馈）  
✅ UI 控制（开关和显示）

可以直接运行测试，根据实际效果调整参数！
