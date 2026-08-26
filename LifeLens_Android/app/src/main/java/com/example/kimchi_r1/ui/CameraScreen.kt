/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CameraScreen - DAT camera capture screen
//
// A full-bleed camera preview with controls overlaid on a scrim. Walks the SDK's camera lifecycle
// as explicit steps (Start Session -> Start Preview -> Capture / Record -> Stop Preview -> End
// Session) and shows the live DeviceSessionState / StreamState so the state machine is legible.

package com.example.kimchi_r1.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.example.kimchi_r1.R
import com.example.kimchi_r1.camera.CameraUiState
import com.example.kimchi_r1.camera.CameraViewModel
import com.example.kimchi_r1.speech.SpeechToTextViewModel
import com.example.kimchi_r1.wearables.WearablesViewModel
import kotlinx.coroutines.launch

// Scrims behind the top/bottom bars so the white controls stay legible over the live feed. Hoisted
// so they're allocated once instead of on every bar recomposition (the recording timer ticks).
private val TopScrimBrush =
    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))
private val BottomScrimBrush =
    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))

@Composable
fun CameraScreen(
    wearablesViewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    onRequestRecordAudioPermission: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    onReconnect: () -> Unit = {},
    cameraViewModel: CameraViewModel = viewModel(
        factory =
            CameraViewModel.Factory(
                application = (LocalActivity.current as ComponentActivity).application,
                wearablesViewModel = wearablesViewModel,
            ),
    ),
) {
  val ui by cameraViewModel.uiState.collectAsStateWithLifecycle()
  val speechViewModel: SpeechToTextViewModel = viewModel()
  val speechUi by speechViewModel.uiState.collectAsStateWithLifecycle()
  val wearablesUi by wearablesViewModel.uiState.collectAsStateWithLifecycle()
  val activity = LocalActivity.current
  val scope = rememberCoroutineScope()
  var showSettingsMenu by remember { mutableStateOf(false) }

  val isUpdateRequired = wearablesUi.isFirmwareUpdateRequired

  Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
    PreviewBackground(
        ui = ui,
        isPreviewVisible = ui.isPreviewVisible,
        hasActiveDevice = wearablesUi.hasActiveDevice,
        isUpdateRequired = isUpdateRequired,
        onSurfaceChanged = cameraViewModel::setSurface,
    )

    if (ui.isStreaming) {
      GestureBadge(
          gesture = ui.gestureName,
          confidence = ui.gestureConfidence,
          modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 58.dp),
      )
    }

    SpeechCaption(
        text = if (speechUi.partialText.isNotBlank()) speechUi.partialText else speechUi.transcripts.lastOrNull()?.text.orEmpty(),
        isListening = speechUi.isListening,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(start = 28.dp, end = 28.dp, bottom = 96.dp),
    )

    // Tap outside the open settings menu dismisses it.
    if (showSettingsMenu) {
      Box(
          modifier =
              Modifier.fillMaxSize().clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
              ) {
                showSettingsMenu = false
              }
      )
    }

    Column(modifier = Modifier.fillMaxSize()) {
      TopBar(
          ui = ui,
          isDisconnectEnabled = wearablesUi.registrationState == RegistrationState.REGISTERED,
          showSettingsMenu = showSettingsMenu,
          onToggleSettings = { showSettingsMenu = !showSettingsMenu },
          onDisconnect = {
            activity?.let { wearablesViewModel.startUnregistration(it) }
            showSettingsMenu = false
          },
          onClose = onClose,
          onReconnect = onReconnect,
      )

      Spacer(modifier = Modifier.weight(1f))

      BottomBar(
          ui = ui,
          isUpdateRequired = isUpdateRequired,
          onTogglePreview = cameraViewModel::togglePreviewVisibility,
          onCapturePhoto = cameraViewModel::capturePhoto,
          isSpeechListening = speechUi.isListening,
          onToggleSpeech = {
            scope.launch {
              if (speechUi.isListening || onRequestRecordAudioPermission()) {
                speechViewModel.toggleListening()
              }
            }
          },
          onUpdateFirmware = { activity?.let { wearablesViewModel.openFirmwareUpdate(it) } },
      )
    }

    ui.activePreview?.let { preview ->
      CapturePreviewScreen(
          preview = preview,
          onDismiss = { cameraViewModel.dismissCapturePreview() },
      )
    }

    if (ui.showCameraPermissionRedirectConfirm) {
      AlertDialog(
          onDismissRequest = { cameraViewModel.cancelCameraPermissionRedirect() },
          title = { Text(stringResource(R.string.camera_permission_redirect_title)) },
          text = { Text(stringResource(R.string.camera_permission_redirect_message)) },
          confirmButton = {
            TextButton(
                onClick = {
                  cameraViewModel.confirmCameraPermissionRedirect(onRequestWearablesPermission)
                }
            ) {
              Text(stringResource(R.string.camera_permission_continue))
            }
          },
          dismissButton = {
            TextButton(onClick = { cameraViewModel.cancelCameraPermissionRedirect() }) {
              Text(stringResource(R.string.camera_permission_cancel))
            }
          },
      )
    }
  }
}

@Composable
private fun SpeechCaption(text: String, isListening: Boolean, modifier: Modifier = Modifier) {
  if (!isListening && text.isBlank()) return
  Row(
      modifier = modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.70f)).padding(horizontal = 14.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
        imageVector = if (isListening) Icons.Filled.Mic else Icons.Filled.MicOff,
        contentDescription = null,
        tint = if (isListening) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.65f),
        modifier = Modifier.size(18.dp),
    )
    Text(
        text = text.ifBlank { "음성을 듣고 있습니다" },
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun GestureBadge(gesture: String, confidence: Int, modifier: Modifier = Modifier) {
  Column(
      modifier =
          modifier
              .clip(RoundedCornerShape(18.dp))
              .background(Color(0xE8F4877B))
              .padding(horizontal = 16.dp, vertical = 10.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text("손 제스처", color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
    Text(gesture, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    if (confidence > 0) {
      Text("신뢰도 $confidence%", color = Color(0xFF7D3E3A), fontSize = 11.sp)
    }
  }
}

// MARK: - Preview background

@Composable
private fun PreviewBackground(
    ui: CameraUiState,
    isPreviewVisible: Boolean,
    hasActiveDevice: Boolean,
    isUpdateRequired: Boolean,
    onSurfaceChanged: (android.view.Surface?) -> Unit,
) {
  val liveDescription = stringResource(R.string.live_preview)
  Box(modifier = Modifier.fillMaxSize()) {
    if (!isPreviewVisible && ui.isStreaming) {
      Box(
          modifier = Modifier.fillMaxSize().background(Color.Black),
          contentAlignment = Alignment.Center,
      ) {
        Text("미리보기가 꺼져 있습니다", color = Color.White.copy(alpha = 0.75f), fontSize = 16.sp)
      }
    } else if (ui.videoFrame != null) {
      Image(
          bitmap = ui.videoFrame.asImageBitmap(),
          contentDescription = liveDescription,
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Crop,
      )
    } else if (ui.hasStream) {
      // The decoder renders into this Surface. AndroidExternalSurface is Compose's native,
      // SurfaceView-backed sink — drawn behind (default zOrder) so the scrim and controls
      // composite on top.
      AndroidExternalSurface(
          modifier = Modifier.fillMaxSize().semantics { contentDescription = liveDescription }
      ) {
        onSurface { surface, _, _ ->
          onSurfaceChanged(surface)
          surface.onDestroyed { onSurfaceChanged(null) }
        }
      }
    } else if (!ui.isBusy) {
      StatusPlaceholder(
          ui = ui,
          hasActiveDevice = hasActiveDevice,
          isUpdateRequired = isUpdateRequired,
      )
    }

    // Paused (device-initiated): the surface stays mounted (hasStream is true) so the last frame
    // freezes; dim it and badge it so a held frame reads as intentionally paused, not stalled.
    if (ui.isPaused) {
      Box(
          modifier =
              Modifier.fillMaxSize()
                  .background(Color.Black.copy(alpha = 0.35f))
                  .testTag("paused_overlay"),
          contentAlignment = Alignment.Center,
      ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          Icon(
              imageVector = Icons.Filled.Pause,
              contentDescription = null,
              tint = Color.White,
              modifier = Modifier.size(36.dp),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
              text = stringResource(R.string.paused_title),
              color = Color.White,
              fontSize = 20.sp,
              fontWeight = FontWeight.SemiBold,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
              text = stringResource(R.string.paused_subtitle),
              color = Color.White.copy(alpha = 0.7f),
              fontSize = 15.sp,
              textAlign = TextAlign.Center,
          )
        }
      }
    }

    if ((ui.isBusy || (ui.hasStream && !ui.hasReceivedFirstFrame)) && !ui.isPaused) {
      Box(
          modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
          contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(color = Color.White)
      }
    }
  }
}

@Composable
private fun StatusPlaceholder(
    ui: CameraUiState,
    hasActiveDevice: Boolean,
    isUpdateRequired: Boolean,
) {
  val title: String
  val subtitle: String?
  val showWaitingRow: Boolean
  when {
    !hasActiveDevice -> {
      title = stringResource(R.string.placeholder_put_on_glasses)
      subtitle = null
      showWaitingRow = true
    }
    isUpdateRequired -> {
      title = stringResource(R.string.update_required_title)
      subtitle = stringResource(R.string.update_required_subtitle)
      showWaitingRow = false
    }
    !ui.hasSession -> {
      title = stringResource(R.string.placeholder_ready_title)
      subtitle = stringResource(R.string.placeholder_ready_subtitle)
      showWaitingRow = false
    }
    else -> {
      title = stringResource(R.string.placeholder_session_started_title)
      subtitle = stringResource(R.string.placeholder_session_started_subtitle)
      showWaitingRow = false
    }
  }

  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Icon(
        painter = painterResource(id = R.drawable.camera_access_icon),
        contentDescription = stringResource(R.string.camera_access_icon_description),
        tint = Color.White,
        modifier = Modifier.size(88.dp),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    if (subtitle != null) {
      Spacer(modifier = Modifier.height(8.dp))
      Text(text = subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
    }
    if (showWaitingRow) {
      Spacer(modifier = Modifier.height(12.dp))
      Text(
          text = stringResource(R.string.waiting_for_active_device),
          color = Color.White.copy(alpha = 0.7f),
          fontSize = 14.sp,
      )
    }
  }
}

// MARK: - Top bar

@Composable
private fun TopBar(
    ui: CameraUiState,
    isDisconnectEnabled: Boolean,
    showSettingsMenu: Boolean,
    onToggleSettings: () -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
    onReconnect: () -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .background(TopScrimBrush)
              .statusBarsPadding()
              .padding(horizontal = 20.dp, vertical = 16.dp),
      verticalAlignment = Alignment.Top,
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Live Vision", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
      StatusChip(
          label = stringResource(R.string.status_session),
          value = ui.sessionStateText,
          active = ui.isSessionActive,
          present = ui.hasSession,
      )
      StatusChip(
          label = stringResource(R.string.status_stream),
          value = ui.streamStateText,
          active = ui.isStreaming,
          present = ui.hasStream,
      )
    }

    Spacer(modifier = Modifier.weight(1f))

    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
      Text("재연결", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onReconnect))
      Text("닫기", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onClose))
      Box {
      // Pinned to TopEnd so it stays put when the Disconnect popover below widens this Box.
      Icon(
          imageVector = Icons.Filled.LinkOff,
          contentDescription = stringResource(R.string.unregister_button_title),
          tint = Color.White,
          modifier =
              Modifier.align(Alignment.TopEnd).size(28.dp).clickable(onClick = onToggleSettings),
      )
      if (showSettingsMenu) {
        SwitchButton(
            label = stringResource(R.string.unregister_button_title),
            onClick = onDisconnect,
            modifier = Modifier.align(Alignment.TopEnd).offset(y = 40.dp).width(150.dp),
            isDestructive = true,
            enabled = isDisconnectEnabled,
        )
      }
    }
    }
  }
}

@Composable
private fun StatusChip(label: String, value: String, active: Boolean, present: Boolean) {
  val dotColor = if (active) AppColor.Green else if (present) AppColor.Yellow else Color.Gray
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
    Text(
        text = "$label: $value",
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
    )
  }
}

// MARK: - Bottom bar

@Composable
private fun BottomBar(
    ui: CameraUiState,
    isUpdateRequired: Boolean,
    onTogglePreview: () -> Unit,
    onCapturePhoto: () -> Unit,
    isSpeechListening: Boolean,
    onToggleSpeech: () -> Unit,
    onUpdateFirmware: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .background(BottomScrimBrush)
              .navigationBarsPadding()
              .padding(horizontal = 24.dp, vertical = 24.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    if (isUpdateRequired) {
      UpdateRequiredMessage()
      SwitchButton(
          label = stringResource(R.string.update_firmware_button_title),
          onClick = onUpdateFirmware,
      )
    } else {
      CaptureRow(
          ui = ui,
          onTogglePreview = onTogglePreview,
          onCapturePhoto = onCapturePhoto,
          isSpeechListening = isSpeechListening,
          onToggleSpeech = onToggleSpeech,
      )
    }
  }
}

@Composable
private fun CaptureRow(
    ui: CameraUiState,
    onTogglePreview: () -> Unit,
    onCapturePhoto: () -> Unit,
    isSpeechListening: Boolean,
    onToggleSpeech: () -> Unit,
) {
  val previewVisible = ui.isPreviewVisible
  val captureEnabled = ui.isStreaming

  Row(
      modifier = Modifier.fillMaxWidth().alpha(if (ui.isSessionActive) 1f else 0f),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    CapturePill(
        modifier =
            Modifier.weight(1f)
                .testTag(if (previewVisible) "hide_preview_button" else "show_preview_button"),
        icon = if (previewVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
        label = stringResource(R.string.preview_label),
        contentDescription =
            if (previewVisible) stringResource(R.string.hide_preview)
            else stringResource(R.string.show_preview),
        enabled = ui.isStreaming,
        onClick = onTogglePreview,
    )

    // Photo capture.
    CircleIconButton(
        modifier = Modifier.testTag("speech_to_text_button"),
        icon = if (isSpeechListening) Icons.Filled.Mic else Icons.Filled.MicOff,
        contentDescription = if (isSpeechListening) stringResource(R.string.mic_on) else stringResource(R.string.mic_off),
        enabled = captureEnabled || isSpeechListening,
        onClick = onToggleSpeech,
        tint = if (isSpeechListening) AppColor.RecordAccent else Color.White,
    )

    CircleIconButton(
        modifier = Modifier.testTag("capture_button"),
        icon = Icons.Filled.PhotoCamera,
        contentDescription = stringResource(R.string.capture_photo),
        enabled = captureEnabled,
        onClick = onCapturePhoto,
    )

  }
}

@Composable
private fun CapturePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier =
          modifier
              .height(50.dp)
              .clip(RoundedCornerShape(percent = 50))
              .background(Color.White.copy(alpha = if (enabled) 0.18f else 0.08f))
              .clickable(enabled = enabled, onClick = onClick)
              .semantics { this.contentDescription = contentDescription }
              .padding(horizontal = 12.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
        modifier = Modifier.size(20.dp),
    )
    Spacer(modifier = Modifier.width(8.dp))
    Text(
        text = label,
        color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun RecordPill(
    isRecording: Boolean,
    elapsedSeconds: Long,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val background =
      if (isRecording) AppColor.RecordAccent.copy(alpha = 0.5f)
      else Color.White.copy(alpha = if (enabled) 0.18f else 0.08f)
  Row(
      modifier =
          modifier
              .height(50.dp)
              .clip(RoundedCornerShape(percent = 50))
              .background(background)
              .clickable(enabled = enabled, onClick = onClick),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (isRecording) {
      Icon(
          imageVector = Icons.Filled.Stop,
          contentDescription = stringResource(R.string.stop_recording),
          tint = Color.White,
          modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      val minutes = elapsedSeconds / 60
      val seconds = elapsedSeconds % 60
      Text(
          text = String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds),
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          fontFamily = FontFamily.Monospace,
          modifier = Modifier.testTag("recording_indicator"),
      )
    } else {
      Icon(
          imageVector = Icons.Filled.Videocam,
          contentDescription = stringResource(R.string.record_video),
          tint = if (enabled) AppColor.RecordAccent else Color.White.copy(alpha = 0.45f),
          modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
          text = stringResource(R.string.record_label),
          color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
  Box(
      modifier =
          modifier
              .size(50.dp)
              .clip(CircleShape)
              .background(Color.White.copy(alpha = if (enabled) 0.18f else 0.08f))
              .clickable(enabled = enabled, onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (enabled) tint else Color.White.copy(alpha = 0.45f),
        modifier = Modifier.size(22.dp),
    )
  }
}

@Composable
private fun UpdateRequiredMessage(modifier: Modifier = Modifier) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(20.dp))
              .background(AppColor.UpdateRequiredBackground)
              .padding(16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.Top,
  ) {
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = null,
        tint = AppColor.UpdateRequiredForeground,
        modifier = Modifier.size(24.dp),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
          text = stringResource(R.string.update_required_title),
          color = AppColor.UpdateRequiredForeground,
          fontWeight = FontWeight.SemiBold,
          fontSize = 16.sp,
      )
      Text(
          text = stringResource(R.string.update_required_firmware_message),
          color = AppColor.UpdateRequiredForeground,
          fontSize = 15.sp,
      )
    }
  }
}
