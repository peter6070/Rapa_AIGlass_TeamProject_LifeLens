package com.example.kimchi_r1.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kimchi_r1.R

@Composable
fun AnimatedSplashScreen() {
  val transition = rememberInfiniteTransition(label = "splash")
  val pulse by transition.animateFloat(
      initialValue = 0.86f,
      targetValue = 1.08f,
      animationSpec = infiniteRepeatable(tween(780), RepeatMode.Reverse),
      label = "pulse",
  )
  val glow by transition.animateFloat(
      initialValue = 0.25f,
      targetValue = 0.7f,
      animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
      label = "glow",
  )

  Box(
      modifier = Modifier.fillMaxSize().background(
          Brush.linearGradient(listOf(Color(0xFFF9D8D4), Color(0xFFF0BEB8), Color(0xFFF7DCD7)))
      ),
      contentAlignment = Alignment.Center,
  ) {
    Box(Modifier.size(236.dp).scale(pulse).alpha(glow).background(Color(0xFFFFF4F1), CircleShape))
    Box(Modifier.size(170.dp).scale(1.1f - (pulse - .86f)).alpha(.26f).background(Color(0xFFFFB5AB), CircleShape))
    Surface(
        modifier = Modifier.size(116.dp).scale(pulse),
        shape = RoundedCornerShape(38.dp),
      color = Color(0xFFFFFBFA),
      shadowElevation = 18.dp,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Image(painter = painterResource(R.drawable.ic_lifelens_mark), contentDescription = "LifeLens", modifier = Modifier.size(76.dp))
      }
    }
    Column(
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Text("LIFELENS", color = Color(0xFF443C40), fontWeight = FontWeight.Black, letterSpacing = 4.sp)
      Spacer(Modifier.height(8.dp))
      Text("Ray-Ban Meta × 팀 김치찌개", color = Color(0xFFC2716A), fontSize = 13.sp)
    }
  }
}
