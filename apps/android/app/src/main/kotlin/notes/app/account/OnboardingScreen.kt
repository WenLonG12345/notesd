package notes.app.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import notes.app.ui.LocalTokens
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Connecting to a server. [onSkip] is offered only on first run ("Use without
 * a server"); [onDone] runs once signed in.
 */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel, onDone: () -> Unit, onSkip: (() -> Unit)?, onExit: (() -> Unit)?) {
    val t = LocalTokens.current
    val s = vm.state
    LaunchedEffect(s.done) { if (s.done) onDone() }
    BackHandler(enabled = s.step != Step.Server && !(vm.lockedServer != null && s.step == Step.SignIn)) { vm.back() }

    Column(
        Modifier
            .fillMaxSize()
            .background(t.bg)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        when (s.step) {
            Step.Server -> {
                Heading("Connect to your server")
                Lede(
                    "Notes keeps your writing on a server you run yourself, and in sync on every device you sign in on."
                )
                Input(
                    vm,
                    Field.Server,
                    "Server address",
                    s.server,
                    { copy(server = it) },
                    placeholder = "notes.example.com",
                    keyboard = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Go
                    ),
                    onDone = vm::submitServer
                )
                Error(s.error)
                Primary(if (s.busy) "Connecting…" else "Continue", s.busy, vm::submitServer)
                if (onSkip != null) {
                    TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Use without a server", color = t.ink2)
                    }
                    Lede("Your notes stay on this phone. You can connect later in Settings.")
                }
            }

            Step.Token -> {
                Heading("Paste your setup token")
                Lede(
                    "When the server starts without an account, it prints a one-time token to its log. " +
                        "It proves you're the person who installed it."
                )
                Input(
                    vm,
                    Field.Token,
                    "Setup token",
                    s.token,
                    { copy(token = it) },
                    keyboard = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    onDone = vm::submitToken
                )
                Error(s.error)
                Primary("Continue", false, vm::submitToken)
                BackLink(vm)
            }

            Step.Account -> {
                Heading("Create your account")
                Lede("This is the only account on this server.")
                Input(vm, Field.Username, "Username", s.username, {
                    copy(username = it)
                }, type = ContentType.NewUsername)
                Input(
                    vm,
                    Field.Password,
                    "Password",
                    s.password,
                    { copy(password = it) },
                    placeholder = "At least 8 characters",
                    secret = true,
                    type = ContentType.NewPassword
                )
                Input(
                    vm,
                    Field.Confirm,
                    "Confirm password",
                    s.confirm,
                    { copy(confirm = it) },
                    secret = true,
                    type = ContentType.NewPassword,
                    onDone = vm::submitAccount
                )
                Error(s.error)
                Primary(if (s.busy) "Creating account…" else "Create account", s.busy, vm::submitAccount)
                BackLink(vm)
            }

            Step.SignIn -> {
                Heading(if (vm.lockedServer != null) "Sign in again" else "Sign in")
                Lede("to ${s.server.toHttpUrlOrNull()?.host ?: s.server}")
                Input(vm, Field.Username, "Username", s.username, { copy(username = it) }, type = ContentType.Username)
                Input(
                    vm,
                    Field.Password,
                    "Password",
                    s.password,
                    { copy(password = it) },
                    secret = true,
                    type = ContentType.Password,
                    onDone = vm::submitSignIn
                )
                val wait = rateLimitSeconds(s.retryAt)
                Error(if (wait > 0) "Too many attempts. Try again in $wait s." else s.error)
                Primary(if (s.busy) "Signing in…" else "Sign in", s.busy || wait > 0, vm::submitSignIn)
                if (vm.lockedServer == null) {
                    TextButton(onClick = { vm.back() }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Use a different server", color = t.ink2)
                    }
                } else if (onExit != null) {
                    TextButton(onClick = onExit, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Not now", color = t.ink2)
                    }
                }
            }
        }
    }
}

/** Seconds left until [retryAt], ticking. */
@Composable
private fun rateLimitSeconds(retryAt: Long?): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAt) {
        while (retryAt != null && now < retryAt) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    return if (retryAt == null) 0 else ((retryAt - now + 999) / 1000).coerceAtLeast(0)
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        color = LocalTokens.current.ink,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
private fun Lede(text: String) {
    Text(text, fontSize = 16.sp, color = LocalTokens.current.ink2)
}

@Composable
private fun Error(text: String?) {
    if (text == null) return
    Text(
        text,
        fontSize = 15.sp,
        color = LocalTokens.current.danger,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    )
}

@Composable
private fun Primary(label: String, busy: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Button(
        onClick = onClick,
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = t.solid, contentColor = t.onSolid),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) { Text(label) }
}

@Composable
private fun BackLink(vm: OnboardingViewModel) {
    TextButton(onClick = { vm.back() }, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Back", color = LocalTokens.current.ink2)
    }
}

@Composable
private fun Input(
    vm: OnboardingViewModel,
    field: Field,
    label: String,
    value: String,
    update: OnboardingState.(String) -> OnboardingState,
    placeholder: String? = null,
    secret: Boolean = false,
    type: ContentType? = null,
    keyboard: KeyboardOptions? = null,
    onDone: (() -> Unit)? = null
) {
    val error = vm.state.fieldErrors[field]
    OutlinedTextField(
        value = value,
        onValueChange = { v -> vm.edit { update(v) } },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = keyboard ?: KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next
        ),
        // Only Done and Go submit; Next keeps its default of moving to the next field.
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (type != null) contentType = type }
    )
}
