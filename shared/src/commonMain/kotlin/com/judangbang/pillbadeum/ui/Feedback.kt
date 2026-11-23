package com.judangbang.pillbadeum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Public contact address from section 8 of docs/release/privacy-policy.md (SPEC-053). */
const val FEEDBACK_ADDRESS = "elddlwkd@gmail.com"

@Composable
internal fun FeedbackRow(onClick: () -> Unit) {
    Row(
        Modifier.row().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Words("의견 보내기", Ui.type(16, lineHeight = 1.25f))
            Words("메일 앱이 열려요. 앱 버전·Android 버전·기기 모델만 채워요.", Ui.type(14, color = Ui.Muted))
        }
        Canvas(Modifier.size(18.dp)) {
            val unit = size.width / 24f
            val stroke = 2 * unit
            drawRect(Ui.Muted, Offset(4 * unit, 6 * unit), Size(16 * unit, 12 * unit), style = Stroke(stroke))
            drawLine(Ui.Muted, Offset(4 * unit, 7 * unit), Offset(12 * unit, 13 * unit), stroke)
            drawLine(Ui.Muted, Offset(12 * unit, 13 * unit), Offset(20 * unit, 7 * unit), stroke)
        }
    }
}

/** SCR-009#nomail, below the settings over the dialog window's scrim. */
@Composable
internal fun NoMailSheet(onCopy: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Ui.Surface).verticalScroll(rememberScrollState())
                    .padding(start = Ui.Side, end = Ui.Side, top = 24.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Words(
                    "메일 앱을 열 수 없어요. 이 주소로 보내 주세요",
                    Ui.type(18, Ui.ExtraBold, lineHeight = 1.25f),
                    Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                )
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).border(1.dp, Ui.LineStrong, Ui.ButtonShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Words(FEEDBACK_ADDRESS, Ui.type(16))
                }
                ActionButton("복사", ButtonLook.Ink, onCopy)
                ActionButton("닫기", ButtonLook(null, Ui.Ink, weight = Ui.SemiBold, minHeight = 48.dp), onClose)
            }
        }
    }
}
