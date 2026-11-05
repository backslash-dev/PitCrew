package ai.pitcrew.app.ui.reservations

import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.ui.components.ButtonKind
import ai.pitcrew.app.ui.components.ButtonSize
import ai.pitcrew.app.ui.components.FieldLabel
import ai.pitcrew.app.ui.components.PcButton
import ai.pitcrew.app.ui.components.PcTextField
import ai.pitcrew.app.ui.components.ScreenHeader
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.UUID

private val MOBILE = Regex("""^01[016789]-?\d{3,4}-?\d{4}$""")

/** 숫자만 받아 010-1234-5678 형태로 맞춘다. 형식이 아니면 null. */
internal fun normalizePhone(input: String): String? {
    val trimmed = input.trim()
    if (!MOBILE.matches(trimmed)) return null
    val digits = trimmed.filter(Char::isDigit)
    val middle = digits.length - 7
    return "${digits.take(3)}-${digits.substring(3, 3 + middle)}-${digits.takeLast(4)}"
}

@Composable
fun ContactEditScreen(contact: Contact?, onSave: (Contact) -> Unit, onDelete: (String) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(contact?.name.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(contact?.phone.orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val nameError = submitted && name.isBlank()
    val phoneError = submitted && normalizePhone(phone) == null

    Column(Modifier.fillMaxSize().padding(horizontal = PcSpacing.screen)) {
        ScreenHeader(if (contact == null) "일행 추가" else "일행 수정", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PcSpacing.xl)) {
            Column {
                FieldLabel("이름")
                PcTextField(name, { name = it }, "이름", Modifier.widthIn(max = 900.dp), isError = nameError)
                if (nameError) ErrorText("이름을 입력하세요.")
            }
            Column {
                FieldLabel("휴대폰 번호")
                PcTextField(phone, { phone = it }, "010-0000-0000", Modifier.widthIn(max = 900.dp), keyboardType = KeyboardType.Phone, isError = phoneError)
                if (phoneError) ErrorText("휴대폰 번호 형식이 아니에요.")
            }
            Text(
                "도착 시간 알림은 PitCrew 서비스 번호로 발송돼요. 받는 사람에게는 보낸 사람이 함께 표시돼요.",
                style = PcTheme.type.body,
                color = PcTheme.colors.textSecondary,
                modifier = Modifier.widthIn(max = 900.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(vertical = PcSpacing.l), horizontalArrangement = Arrangement.spacedBy(PcSpacing.m)) {
            if (contact != null) {
                PcButton("삭제", { onDelete(contact.id) }, kind = ButtonKind.BASIC, size = ButtonSize.LARGE, modifier = Modifier.weight(1f))
            }
            PcButton(
                "저장",
                {
                    submitted = true
                    val normalized = normalizePhone(phone)
                    if (name.isNotBlank() && normalized != null) {
                        onSave(Contact(contact?.id ?: UUID.randomUUID().toString(), name.trim(), normalized))
                    }
                },
                kind = ButtonKind.FILLED,
                size = ButtonSize.LARGE,
                modifier = Modifier.weight(2f),
            )
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, style = PcTheme.type.label, color = PcTheme.colors.negative, modifier = Modifier.padding(top = PcSpacing.xs))
}
