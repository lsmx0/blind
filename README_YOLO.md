# YOLOv8-Seg Android 实现说明

## 已实现功能

### 1. 模型加载 (YoloSegModel.kt)
- ✅ 使用 ONNX Runtime 加载 `best.onnx` 模型
- ✅ 优化配置：`OptLevel.ALL_OPT`

### 2. 图像预处理
- ✅ Bitmap 缩放到 640x640
- ✅ RGB 格式转换
- ✅ CHW 通道顺序（Channel-Height-Width）
- ✅ Float32 归一化到 [0,1]

### 3. 模型推理
- ✅ 创建输入 Tensor (1, 3, 640, 640)
- ✅ 执行推理获取两个输出：
  - `det`: 检测结果 (bbox + mask coefficients)
  - `proto`: mask prototype

### 4. 结果解析（简化版）
- ✅ 提取置信度最高的目标
- ✅ 获取类别 ID 和置信度
- ✅ 提取 32 维 mask coefficients

### 5. Mask 生成
- ✅ 计算公式：`mask = sigmoid(mask_coeff × proto)`
- ✅ 生成 160x160 mask
- ✅ 应用 sigmoid 激活函数
- ✅ 阈值过滤（> 0.5）
- ✅ 缩放到原图尺寸

### 6. UI 界面 (MainActivity.kt)
- ✅ 图片选择功能
- ✅ 原图显示
- ✅ 执行分割按钮
- ✅ 结果叠加显示
- ✅ 置信度和类别信息展示

## 使用方法

1. **运行应用**
   - 点击"选择图片"按钮
   - 从相册选择一张图片

2. **执行分割**
   - 点击"执行分割"按钮
   - 等待处理完成（会显示进度）

3. **查看结果**
   - 分割结果会以半透明绿色 mask 叠加在原图上
   - 显示检测置信度和类别 ID

## 技术细节

### 输入格式
- 尺寸：640 × 640
- 格式：RGB
- 数据类型：Float32
- 通道顺序：CHW (Channel-Height-Width)
- 归一化：[0, 1]

### 输出格式
- **det**: [1, 116, 8400]
  - 前 4 维：bbox 坐标 (x, y, w, h)
  - 中间 80 维：类别置信度
  - 后 32 维：mask coefficients
  
- **proto**: [1, 32, 160, 160]
  - 32 个 mask prototype 通道
  - 每个通道 160×160

### Mask 计算
```kotlin
// 1. 矩阵乘法
for (i in 0 until 32) {
    mask += coeff[i] * proto[i]
}

// 2. Sigmoid 激活
mask = 1 / (1 + exp(-mask))

// 3. 阈值过滤
if (mask > 0.5) -> 显示
```

## 优化建议

### 当前实现（简化版）
- ✅ 只处理置信度最高的单个目标
- ✅ 无 NMS（非极大值抑制）
- ✅ 适合快速验证和演示

### 进阶优化
如需处理多目标，可以添加：
1. **NMS 算法**：过滤重叠检测框
2. **多目标处理**：同时显示多个分割 mask
3. **性能优化**：使用 GPU 加速
4. **实时处理**：相机预览 + 实时分割

## 依赖项

```gradle
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
```

## 注意事项

1. **内存管理**：及时释放 Bitmap 和 Tensor 资源
2. **线程安全**：推理在后台线程执行，避免阻塞 UI
3. **错误处理**：捕获异常并提供友好提示
4. **模型兼容**：确保 `best.onnx` 是 YOLOv8-Seg 格式

## 故障排查

### 问题：未检测到目标
- 检查置信度阈值（当前 0.5）
- 确认模型训练的类别
- 尝试不同的测试图片

### 问题：Mask 显示异常
- 检查 proto 输出维度
- 确认 mask coefficients 提取正确
- 调整 sigmoid 阈值

### 问题：性能慢
- 降低输入图片分辨率
- 使用 GPU 后端（需要额外配置）
- 优化 Bitmap 处理流程
