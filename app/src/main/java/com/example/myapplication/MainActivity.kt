package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    
    private lateinit var yoloModel: YoloSegModel
    private lateinit var blindPathGuide: BlindPathGuide
    private var cameraManager: CameraManager? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化模型
        yoloModel = YoloSegModel(this)
        
        // 初始化盲道导航
        blindPathGuide = BlindPathGuide(this)
        
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        yoloModel = yoloModel,
                        blindPathGuide = blindPathGuide,
                        onCameraManagerCreated = { cameraManager = it },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        cameraManager?.release()
        yoloModel.close()
        blindPathGuide.release()
    }
}

@Composable
fun MainScreen(
    yoloModel: YoloSegModel,
    blindPathGuide: BlindPathGuide,
    onCameraManagerCreated: (CameraManager) -> Unit,
    modifier: Modifier = Modifier
) {
    // 模式选择：图片检测 / 实时检测
    var detectionMode by remember { mutableStateOf(DetectionMode.IMAGE) }
    
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 模式切换标签页
        TabRow(selectedTabIndex = detectionMode.ordinal) {
            Tab(
                selected = detectionMode == DetectionMode.IMAGE,
                onClick = { detectionMode = DetectionMode.IMAGE },
                text = { Text("图片检测") }
            )
            Tab(
                selected = detectionMode == DetectionMode.REALTIME,
                onClick = { detectionMode = DetectionMode.REALTIME },
                text = { Text("实时检测") }
            )
        }
        
        // 根据模式显示不同界面
        when (detectionMode) {
            DetectionMode.IMAGE -> {
                ImageDetectionScreen(
                    yoloModel = yoloModel,
                    blindPathGuide = blindPathGuide
                )
            }
            DetectionMode.REALTIME -> {
                RealtimeDetectionScreen(
                    yoloModel = yoloModel,
                    blindPathGuide = blindPathGuide,
                    onCameraManagerCreated = onCameraManagerCreated
                )
            }
        }
    }
}

/**
 * 检测模式枚举
 */
enum class DetectionMode {
    IMAGE,      // 图片检测（调试用）
    REALTIME    // 实时检测（主要功能）
}

/**
 * 🎯 保留原有的图片检测界面（用于调试）
 */
@Composable
fun ImageDetectionScreen(
    yoloModel: YoloSegModel,
    blindPathGuide: BlindPathGuide,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf("") }
    var confThreshold by remember { mutableStateOf(0.5f) }
    var debugInfo by remember { mutableStateOf("") }
    var enableVoiceGuide by remember { mutableStateOf(true) }
    
    // 图片选择器
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val bitmap = loadBitmapFromUri(context, it)
                    selectedImageBitmap = bitmap
                    resultBitmap = null
                    resultText = ""
                    debugInfo = ""
                } catch (e: Exception) {
                    resultText = "加载图片失败: ${e.message}"
                }
            }
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        
        Text(
            text = "图片检测模式（调试用）",
            style = MaterialTheme.typography.titleMedium
        )
        
        // 选择图片按钮
        Button(
            onClick = { imagePickerLauncher.launch("image/*") },
            enabled = !isProcessing
        ) {
            Text("选择图片")
        }
        
        // 置信度阈值调整
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "置信度阈值: ${String.format("%.2f", confThreshold)}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = confThreshold,
                    onValueChange = { confThreshold = it },
                    valueRange = 0.1f..0.9f,
                    enabled = !isProcessing
                )
            }
        }
        
        // 语音导航开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("语音导航", style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = enableVoiceGuide,
                onCheckedChange = { enableVoiceGuide = it },
                enabled = !isProcessing
            )
        }
        
        // 显示原图
        selectedImageBitmap?.let { bitmap ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("原图", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "原图",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            // 执行分割按钮
            Button(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        resultText = "处理中..."
                        debugInfo = ""
                        
                        val result = withContext(Dispatchers.Default) {
                            yoloModel.runInference(bitmap, confThreshold)
                        }
                        
                        if (result != null) {
                            resultBitmap = overlayMask(bitmap, result.maskBitmap)
                            resultText = "检测成功！\n置信度: %.2f".format(result.confidence)
                            
                            // 统计 mask 信息
                            val foregroundCount = result.maskArray.count { it > 0.5f }
                            resultText += "\n前景像素: $foregroundCount"
                            
                            // 盲道导航分析
                            if (enableVoiceGuide) {
                                blindPathGuide.processMaskAndGuide(result.maskArray)
                            }
                            
                            // 获取调试信息
                            debugInfo = blindPathGuide.getDebugInfo(result.maskArray)
                            
                        } else {
                            resultText = "未检测到目标\n尝试降低置信度阈值"
                        }
                        
                        isProcessing = false
                    }
                },
                enabled = !isProcessing
            ) {
                Text(if (isProcessing) "处理中..." else "执行分割")
            }
        }
        
        // 显示结果
        resultBitmap?.let { bitmap ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("分割结果", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "分割结果",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        
        // 结果文本
        if (resultText.isNotEmpty()) {
            Text(
                text = resultText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (resultText.contains("失败") || resultText.contains("未检测")) 
                    MaterialTheme.colorScheme.error 
                else 
                    MaterialTheme.colorScheme.primary
            )
        }
        
        // 导航调试信息
        if (debugInfo.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = debugInfo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }
        }
        
        if (isProcessing) {
            CircularProgressIndicator()
        }
    }
}

/**
 * 从 Uri 加载 Bitmap
 */
private suspend fun loadBitmapFromUri(context: android.content.Context, uri: Uri): Bitmap {
    return withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream)
        } ?: throw Exception("无法打开图片")
    }
}

/**
 * 将 mask 叠加到原图上
 */
private fun overlayMask(original: Bitmap, mask: Bitmap): Bitmap {
    val result = original.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = android.graphics.Canvas(result)
    canvas.drawBitmap(mask, 0f, 0f, null)
    return result
}


/**
 * 🎯 实时检测界面（主要功能）
 */
@Composable
fun RealtimeDetectionScreen(
    yoloModel: YoloSegModel,
    blindPathGuide: BlindPathGuide,
    onCameraManagerCreated: (CameraManager) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    var isCameraStarted by remember { mutableStateOf(false) }
    var performanceStats by remember { mutableStateOf("") }
    var cameraManager: CameraManager? by remember { mutableStateOf(null) }
    var showOverlay by remember { mutableStateOf(true) } // 🎯 新增：Overlay 开关
    
    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            Toast.makeText(context, "需要相机权限才能使用实时检测", Toast.LENGTH_LONG).show()
        }
    }
    
    // 性能统计更新
    LaunchedEffect(isCameraStarted) {
        if (isCameraStarted) {
            while (true) {
                delay(1000) // 每秒更新一次
                cameraManager?.let {
                    performanceStats = it.getPerformanceStats()
                }
            }
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        
        Text(
            text = "实时盲道导航",
            style = MaterialTheme.typography.titleLarge
        )
        
        if (!hasCameraPermission) {
            // 请求权限界面
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "需要相机权限",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "实时检测功能需要访问相机",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    ) {
                        Text("授予权限")
                    }
                }
            }
        } else {
            // 🎯 目标 1 & 2：相机预览 + Overlay 叠加
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RectangleShape  // 🎯 使用矩形，避免圆角裁剪
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 用于存储 Overlay 引用
                    var overlayViewRef: DebugOverlayView? by remember { mutableStateOf(null) }
                    
                    // 🎯 目标 1：CameraX PreviewView（底层）
                    // 作用：显示实时相机画面
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                                scaleType = PreviewView.ScaleType.FILL_CENTER  // 🎯 填充中心，避免黑边
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { previewView ->
                            if (isCameraStarted && cameraManager == null) {
                                // 🎯 目标 5：完整调用链
                                // 初始化 CameraManager → 启动相机 → 自动开始推理 → 更新 Overlay
                                val manager = CameraManager(
                                    context = context,
                                    lifecycleOwner = lifecycleOwner,
                                    yoloModel = yoloModel,
                                    blindPathGuide = blindPathGuide,
                                    overlayView = if (showOverlay) overlayViewRef else null
                                )
                                cameraManager = manager
                                onCameraManagerCreated(manager)
                                
                                manager.startCamera(previewView) { error ->
                                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                    
                    // 🎯 目标 2：透明 Overlay View（上层）
                    // 作用：叠加显示检测结果和算法过程
                    if (showOverlay) {
                        AndroidView(
                            factory = { ctx ->
                                DebugOverlayView(ctx).apply {
                                    overlayViewRef = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    
                    // 状态指示器
                    if (isCameraStarted) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            shape = MaterialTheme.shapes.small
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "实时检测中",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            
            // 控制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = {
                        isCameraStarted = !isCameraStarted
                        if (!isCameraStarted) {
                            cameraManager?.stopCamera()
                            cameraManager = null
                        }
                    }
                ) {
                    Text(if (isCameraStarted) "停止检测" else "开始检测")
                }
                
                // 🎯 Overlay 开关
                Button(
                    onClick = { showOverlay = !showOverlay },
                    enabled = !isCameraStarted
                ) {
                    Text(if (showOverlay) "隐藏可视化" else "显示可视化")
                }
            }
            
            // 性能统计
            if (performanceStats.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "性能统计",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = performanceStats,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}
