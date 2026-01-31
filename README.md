# 盲道导航 Android 应用

基于 YOLOv8-Seg 深度学习模型的实时盲道检测与语音导航系统。

## 📋 项目概述

本项目是一个 Android 应用，旨在帮助视障人士通过智能手机实时识别盲道并提供语音导航指引。应用使用 YOLOv8-Seg 实例分割模型检测盲道，通过中轴线算法分析盲道走向，并通过 TTS（文字转语音）提供实时语音提示。

## ✨ 核心功能

### 1. 实时盲道检测
- **YOLOv8-Seg 模型推理**：使用 ONNX Runtime 在设备端运行 YOLOv8-Seg 模型
- **实例分割**：精确识别盲道区域，生成像素级分割 Mask
- **实时处理**：约 6-7 FPS 的推理速度，满足实时导航需求

### 2. 智能导航算法
- **中轴线提取**：从分割 Mask 中提取盲道的几何中轴线
- **线性拟合**：使用最小二乘法拟合中轴线，计算盲道走向
- **转向判断**：基于中轴线角度判断左转、右转或直行
- **偏移检测**：判断用户是否偏离盲道中心

### 3. 语音导航
- **实时语音播报**：使用 Android TTS 提供中文语音指引
- **智能限频**：2 秒内相同内容不重复播报，避免干扰
- **优先级决策**：转向提示优先于偏移提示

### 4. 可视化调试
- **实时 Overlay**：在相机预览上叠加显示检测结果
- **分割 Mask 显示**：半透明绿色显示识别的盲道区域
- **重心标记**：红色圆点标记盲道重心位置
- **中心参考线**：白色虚线显示画面中心
- **方向箭头**：黄色箭头显示盲道走向
- **状态文字**：实时显示偏移和转向状态

### 5. 双模式支持
- **图片检测模式**：用于调试和测试，可选择图片进行检测
- **实时检测模式**：主要功能，实时相机检测和导航

## 🏗️ 技术架构

### 技术栈
- **开发语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **相机框架**：CameraX
- **深度学习推理**：ONNX Runtime
- **模型**：YOLOv8-Seg (ONNX 格式)
- **语音合成**：Android TextToSpeech

### 核心组件

#### 1. YoloSegModel.kt
**功能**：YOLOv8-Seg 模型推理引擎

**关键实现**：
```kotlin
// 模型加载
- 使用 ONNX Runtime 加载 best.onnx 模型
- 配置优化级别为 ALL_OPT

// 图像预处理
- Bitmap → FloatBuffer
- RGB 格式，CHW 排列
- 归一化到 [0, 1]
- 缩放到 640×640

// 推理执行
- 输入：[1, 3, 640, 640] 的 float32 tensor
- 输出：
  * det: [1, 8400, 37] - 检测结果（bbox + conf + mask_coeff）
  * proto: [1, 32, 160, 160] - mask 原型

// Mask 生成
- 公式：mask = sigmoid(mask_coeff × proto)
- Bbox 裁剪：将 160×160 的 mask 裁剪到检测框区域
- 输出：640×640 的分割 mask
```

**输出格式解析**：
- 每个检测：37 个特征
  - [0-3]: bbox 坐标 (cx, cy, w, h)
  - [4]: 置信度
  - [5-36]: 32 维 mask 系数

#### 2. CameraManager.kt
**功能**：相机管理和图像采集

**关键实现**：
```kotlin
// CameraX 配置
- Preview: 实时预览
- ImageAnalysis: 图像分析（推理）
- 目标分辨率：1280×720
- 目标宽高比：4:3

// 性能控制
- 单线程推理锁：避免帧堆积
- 时间间隔限制：150ms 一次推理（约 6-7 FPS）
- 背压策略：KEEP_ONLY_LATEST

// YUV 转 Bitmap（关键修复）
- 正确处理 YUV 平面的 stride 和 pixel stride
- 逐像素复制，避免数据错位
- 支持图像旋转校正
```

**YUV 转换修复**：
原来的错误方法会导致图像变成横向条纹，修复后正确处理每个像素的位置：
```kotlin
// 错误方法（直接复制 buffer）
yBuffer.get(nv21, 0, ySize)  // ❌

// 正确方法（按 stride 逐像素复制）
for (row in 0 until height) {
    for (col in 0 until width) {
        nv21[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)  // ✅
    }
}
```

#### 3. BlindPathGuide.kt
**功能**：盲道导航算法和语音播报

**核心算法**：

##### 3.1 中轴线提取
```kotlin
方法：
1. 将图像按行分段（每 20 行一段）
2. 扫描每行，找到前景像素的左右边界
3. 计算中心点：centerX = (minX + maxX) / 2
4. 得到中轴线点列表：[(x1, y1), (x2, y2), ...]
```

##### 3.2 线性拟合
```kotlin
方法：最小二乘法拟合直线 x = a * y + b
- a: 斜率（表示盲道倾斜程度）
- b: 截距（表示盲道位置）

公式：
denominator = n * sumYY - sumY * sumY
a = (n * sumXY - sumX * sumY) / denominator
b = (sumX * sumYY - sumY * sumXY) / denominator
```

##### 3.3 转向判断
```kotlin
方法：从斜率计算角度
angle = atan(slope) * 180 / π

判断规则：
- angle > 10°  → 左转
- angle < -10° → 右转
- 否则        → 直行

角度含义：
- 0° = 垂直向上（直行）
- 正值 = 向右倾斜（左转）
- 负值 = 向左倾斜（右转）
```

##### 3.4 偏移判断
```kotlin
方法：计算中轴线在画面中部的位置
midY = imageHeight / 2
midX = slope * midY + intercept
offset = midX - centerX

判断规则：
- offset < -15% 画面宽度 → 偏左
- offset > +15% 画面宽度 → 偏右
- 否则                  → 居中
```

##### 3.5 决策融合
```kotlin
优先级：
1. 转向提示（左转/右转）
2. 偏移提示（偏左/偏右）
3. 无提示（保持当前方向）

语音映射：
- 左转 → "前方左转"
- 右转 → "前方右转"
- 偏左 → "请向右调整"
- 偏右 → "请向左调整"
```

##### 3.6 语音播报
```kotlin
特性：
- 使用 Android TTS（中文）
- 限频机制：相同内容 2 秒内不重复播报
- 队列模式：QUEUE_FLUSH（立即播报）
```

#### 4. DebugOverlayView.kt
**功能**：可视化调试界面

**绘制内容**：
```kotlin
1. 分割 Mask
   - 半透明绿色（alpha=80）
   - 降采样绘制（step=4）提升性能
   - 只绘制前景像素（> 0.5）

2. 重心标记
   - 红色圆点（半径 15px）
   - 外圈描边（半径 25px）

3. 中心参考线
   - 白色虚线
   - 垂直居中

4. PCA 方向箭头
   - 黄色箭头（长度 150px）
   - 从重心出发
   - 指向盲道走向

5. 状态文字
   - 偏移状态（偏左/居中/偏右）
   - 转向状态（左转/直行/右转）
   - PCA 角度值
   - 带半透明黑色背景
```

#### 5. MainActivity.kt
**功能**：主界面和模式切换

**界面结构**：
```kotlin
TabRow（模式切换）
├── 图片检测
│   ├── 图片选择器
│   ├── 置信度调节
│   ├── 语音导航开关
│   ├── 原图显示
│   ├── 分割结果显示
│   └── 导航调试信息
│
└── 实时检测
    ├── 相机预览（PreviewView）
    ├── 可视化 Overlay（DebugOverlayView）
    ├── 控制按钮（开始/停止）
    ├── Overlay 开关
    └── 性能统计
```

## 📊 数据流程

### 完整调用链

```
相机帧 (ImageProxy)
    ↓
YUV → Bitmap 转换 (CameraManager)
    ↓
YOLO 推理 (YoloSegModel)
    ↓
Mask 生成 (640×640 FloatArray)
    ↓
┌─────────────────┬─────────────────┐
│                 │                 │
导航分析          可视化更新
(BlindPathGuide)  (DebugOverlayView)
│                 │
├─ 中轴线提取     ├─ 绘制 Mask
├─ 线性拟合       ├─ 绘制重心
├─ 转向判断       ├─ 绘制方向箭头
├─ 偏移判断       └─ 绘制状态文字
├─ 决策融合
└─ 语音播报 (TTS)
```

### 性能指标

- **推理速度**：约 6-7 FPS
- **推理耗时**：150-200ms/帧
- **处理率**：约 20-30%（性能控制）
- **内存占用**：模型约 5MB，运行时约 50-100MB

## 🚀 使用说明

### 环境要求
- Android 7.0 (API 24) 或更高版本
- 相机权限
- 中文 TTS 语音包（可选）

### 安装步骤
1. 克隆项目
2. 使用 Android Studio 打开
3. 将训练好的 `best.onnx` 模型放入 `app/src/main/assets/`
4. 编译并安装到设备

### 使用流程

#### 图片检测模式（调试）
1. 切换到"图片检测"标签页
2. 点击"选择图片"
3. 调整置信度阈值（建议 0.5）
4. 点击"执行分割"
5. 查看分割结果和导航信息

#### 实时检测模式（主功能）
1. 切换到"实时检测"标签页
2. 授予相机权限
3. 点击"开始检测"
4. 将相机对准盲道
5. 听取语音导航指引
6. 可选：开启"显示可视化"查看检测过程

### 调试技巧

#### 查看日志
使用 Logcat 过滤以下标签：
```
🎯 中轴线分析  - 中轴线提取和拟合结果
🔊 语音播报    - TTS 状态和播报信息
🔄 YUV 转换    - 图像转换过程
✅/❌         - 成功/失败状态
```

#### 常见问题

**1. 未检测到目标**
- 降低置信度阈值（0.5 → 0.3）
- 确保光线充足
- 对准明显的盲道
- 距离保持在 1-2 米

**2. 语音不播报**
- 查看 Logcat 中的 TTS 初始化日志
- 检查设备是否安装中文语音包
- 在设置中启用 TTS 功能

**3. 图像显示异常**
- 检查 YUV 转换是否正确
- 查看保存的相机帧图片
- 确认图像旋转角度

**4. 转向判断不准确**
- 查看 Logcat 中的中轴线分析日志
- 检查拟合斜率和角度值
- 调整 `turnAngleThreshold` 参数

## 📁 项目结构

```
app/src/main/
├── assets/
│   └── best.onnx                    # YOLOv8-Seg 模型
├── java/com/example/myapplication/
│   ├── MainActivity.kt              # 主界面
│   ├── YoloSegModel.kt              # 模型推理
│   ├── CameraManager.kt             # 相机管理
│   ├── BlindPathGuide.kt            # 导航算法
│   ├── DebugOverlayView.kt          # 可视化界面
│   └── ui/theme/                    # UI 主题
├── res/                             # 资源文件
└── AndroidManifest.xml              # 应用配置
```

## 🔧 配置参数

### YoloSegModel.kt
```kotlin
val confThreshold = 0.5f        // 置信度阈值
val inputSize = 640             // 模型输入尺寸
val maskSize = 160              // Mask 原型尺寸
```

### CameraManager.kt
```kotlin
val minProcessInterval = 150L   // 推理间隔（ms）
val targetResolution = Size(1280, 720)  // 相机分辨率
```

### BlindPathGuide.kt
```kotlin
val offsetDeadZone = 0.15f      // 偏移死区（15%）
val turnAngleThreshold = 10f    // 转向角度阈值（度）
val speakInterval = 2000L       // 播报间隔（ms）
val rowStep = 20                // 中轴线采样步长
```

### DebugOverlayView.kt
```kotlin
val sampleStep = 4              // Mask 绘制降采样
val maskAlpha = 80              // Mask 透明度
val lineLength = 150f           // 方向箭头长度
```

## 🎯 核心算法优势

### 中轴线方法 vs PCA 方法

**中轴线方法**（当前使用）：
- ✅ 更准确：直接提取盲道几何特征
- ✅ 更稳定：线性拟合可以平滑噪声
- ✅ 更直观：角度和偏移含义清晰
- ✅ 更鲁棒：有回退机制

**PCA 方法**（已弃用）：
- ❌ 对噪声敏感
- ❌ 角度含义不明确
- ❌ 容易误判直行为转向

## 📝 开发日志

### 主要里程碑

1. **YOLOv8-Seg 模型集成**
   - ONNX Runtime 配置
   - 图像预处理实现
   - Mask 生成算法

2. **YUV 转换修复**
   - 发现横向条纹问题
   - 修复 stride 处理
   - 验证图像质量

3. **导航算法优化**
   - 从 PCA 方法迁移到中轴线方法
   - 调整阈值参数
   - 添加调试日志

4. **可视化系统**
   - 实现透明 Overlay
   - 添加多种可视化元素
   - 优化绘制性能

5. **语音导航**
   - TTS 集成
   - 限频机制
   - 决策融合

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

### 开发建议
- 遵循 Kotlin 编码规范
- 添加必要的注释
- 保持代码简洁
- 测试后再提交

## 📄 许可证

本项目仅供学习和研究使用。

## 👥 作者

盲道导航项目团队

---

**最后更新**：2026-01-29
