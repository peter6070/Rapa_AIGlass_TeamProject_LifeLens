package com.example.kimchi_r1.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel as composeViewModel
import com.example.kimchi_r1.camera.CameraViewModel
import com.example.kimchi_r1.speech.SpeechToTextViewModel
import com.example.kimchi_r1.stream.StreamingService
import com.example.kimchi_r1.wearables.WearablesViewModel
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraAccessScaffold(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    onRequestRecordAudioPermission: suspend () -> Boolean,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  // Do not instantiate CameraViewModel before Wearables.initialize() completed.
  if (!uiState.canRegister) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize()) {
        HomeScreen(viewModel = viewModel)
      }
    }
    return
  }

  val cameraViewModel: CameraViewModel = composeViewModel(
      factory = CameraViewModel.Factory(
          application = (androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity).application,
          wearablesViewModel = viewModel,
      ),
  )
  val cameraUiState by cameraViewModel.uiState.collectAsStateWithLifecycle()
  val isSessionEnabled by cameraViewModel.isSessionEnabled.collectAsStateWithLifecycle()
  val speechViewModel: SpeechToTextViewModel = composeViewModel()
  val speechUiState by speechViewModel.uiState.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }
  var showLiveVision by remember { mutableStateOf(false) }
  LaunchedEffect(uiState.isRegistered, isSessionEnabled, cameraUiState.isStreaming) {
    if (uiState.isRegistered && isSessionEnabled && cameraUiState.isStreaming && !speechUiState.isListening && onRequestRecordAudioPermission()) {
      speechViewModel.startListening()
    } else if ((!cameraUiState.isStreaming || !isSessionEnabled) && speechUiState.isListening) {
      speechViewModel.stopListening()
    }
  }
  LaunchedEffect(uiState.recentError?.id) {
    uiState.recentError?.let { error ->
      snackbarHostState.showSnackbar(error.message)
      viewModel.clearRecentError(error.id)
    }
  }
  LaunchedEffect(cameraUiState.iotShortcutSequence) {
    cameraUiState.iotShortcutAction?.let(WebAppBackNavigation::dispatchIotGesture)
  }

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize()) {
      if (!uiState.isRegistered) {
        HomeScreen(viewModel = viewModel)
      } else {
        WebAppScreen(
            page = "home",
            gestureName = cameraUiState.gestureName,
            gestureConfidence = cameraUiState.gestureConfidence,
            isGestureActive = cameraUiState.isStreaming,
            isSessionEnabled = isSessionEnabled,
            isMicrophoneOn = speechUiState.isListening,
            latestTranscript = speechUiState.partialText.ifBlank { speechUiState.transcripts.lastOrNull()?.text.orEmpty() },
            transcripts = speechUiState.transcripts,
            previewFrame = cameraUiState.videoFrame,
            onToggleMicrophone = {
              scope.launch {
                if (speechUiState.isListening || onRequestRecordAudioPermission()) {
                  speechViewModel.toggleListening()
                }
              }
            },
            onToggleSession = {
              scope.launch {
                if (isSessionEnabled) {
                  if (speechUiState.isListening) speechViewModel.stopListening()
                  cameraViewModel.toggleSessionEnabled()
                  StreamingService.stop(context.applicationContext)
                } else if (onRequestRecordAudioPermission()) {
                  cameraViewModel.toggleSessionEnabled()
                  speechViewModel.startListening()
                }
              }
            },
            onStartStream = { cameraViewModel.startLifeLensSession() },
            onOpenLiveVision = {
              cameraViewModel.showPreview()
              showLiveVision = true
            },
        )
        // Keep the WebView alive below the native camera. Gesture shortcuts can then control
        // Matter devices even while Live Vision is displayed full-screen.
        if (showLiveVision) {
          CameraScreen(
              wearablesViewModel = viewModel,
              onRequestWearablesPermission = onRequestWearablesPermission,
              onRequestRecordAudioPermission = onRequestRecordAudioPermission,
              cameraViewModel = cameraViewModel,
              onClose = { showLiveVision = false },
              onReconnect = cameraViewModel::startLifeLensSession,
          )
        }
      }

      if (!showLiveVision && cameraUiState.showCameraPermissionRedirectConfirm) {
        AlertDialog(
            onDismissRequest = { cameraViewModel.cancelCameraPermissionRedirect() },
            title = { Text("카메라 권한 필요") },
            text = { Text("글래스 카메라 스트림과 손 제스처 인식을 위해 Meta AI에서 카메라 권한을 허용하세요.") },
            confirmButton = {
              TextButton(onClick = { cameraViewModel.confirmCameraPermissionRedirect(onRequestWearablesPermission) }) {
                Text("권한 허용")
              }
            },
            dismissButton = {
              TextButton(onClick = { cameraViewModel.cancelCameraPermissionRedirect() }) { Text("나중에") }
            },
        )
      }
    }
  }
}
