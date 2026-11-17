package app.fanfichub

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Écran affiché après un plantage : montre l'erreur pour pouvoir la transmettre. */
@Composable
fun CrashScreen(text: String, onContinue: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
        Text("L'app a planté", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Copie ce rapport et envoie-le pour que le problème soit corrigé.", color = Color(0xFF9A9AA8), fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Copier le rapport", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF8B5CF6)).clickable {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("crash", text))
                }.padding(horizontal = 16.dp, vertical = 12.dp))
            Text("Relancer l'app", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF2A2A33)).clickable { onContinue() }.padding(horizontal = 16.dp, vertical = 12.dp))
        }
        Text(text, color = Color(0xFFE4E4EE), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(Color(0xFF15151C))
                .verticalScroll(rememberScrollState()).padding(12.dp))
    }
}
