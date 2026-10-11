package app.nullpointr.foxnav.convoy

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nullpointr.foxnav.shared.convoy.ConvoyMember
import app.nullpointr.foxnav.shared.convoy.ConvoyStatus
import app.nullpointr.foxnav.shared.convoy.Invite
import app.nullpointr.foxnav.appGraph
import app.nullpointr.foxnav.protocol.convoy.v0.Car
import app.nullpointr.foxnav.shared.convoy.toGeoPoint
import app.nullpointr.foxnav.shared.geo.GeoPoint
import app.nullpointr.foxnav.ui.icons.FoxIcons
import app.nullpointr.foxnav.ui.theme.ChakraPetch
import kotlinx.coroutines.launch

/** A destination someone in the convoy shares, to navigate there too. */
data class SharedDestination(val point: GeoPoint, val name: String, val by: String)

/** The owner's shared destination if they share one, otherwise anyone's. */
fun sharedDestination(members: Map<Long, ConvoyMember>, ownerId: Long?): SharedDestination? {
    val sharing = members.values.filter { !it.route?.waypoints.isNullOrEmpty() }
    val member = sharing.firstOrNull { it.id == ownerId } ?: sharing.firstOrNull() ?: return null
    val destination = member.route!!.waypoints.last()
    return SharedDestination(destination.toGeoPoint(), destination.name.ifBlank { "Shared destination" }, member.profile?.display_name ?: "A driver")
}

/**
 * Everything about convoys in one sheet: start or join one, and once in, quick messages, the
 * regroup point, the shared destination, the invite, the members, and leaving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvoySheet(
    convoy: ConvoyController,
    serverUrl: String,
    /** The server's convoy page for invite links (its discovery `invites`), if it has one. */
    invitePage: String?,
    onNavigateTo: (point: GeoPoint, name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val active by convoy.active.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = active) {
                null -> StartOrJoin(convoy, serverUrl)
                else -> InConvoy(
                    convoy,
                    current,
                    // Only our own server's page knows our convoys' server: others' convoys keep the app link.
                    linkBase = { invite -> invitePage?.takeIf { sameServer(invite.server, serverUrl) } ?: Invite.DEFAULT_BASE },
                    onNavigateTo = { point, name -> onNavigateTo(point, name); onDismiss() },
                    onLeft = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun StartOrJoin(convoy: ConvoyController, serverUrl: String) {
    val colors = MaterialTheme.colorScheme
    val profile by convoy.profiles.profile.collectAsState()
    var link by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val starter = rememberConvoyStarter()
    val invite = remember(link) { Invite.parse(link) }

    SheetTitle("Convoy")
    Text(
        "Drive together: everyone sees the others' cars and speeds live. Positions are end-to-end encrypted, so the server can't read them.",
        color = colors.onSurfaceVariant,
    )
    ProfileEditor(profile, onChange = convoy.profiles::save)
    Button(
        onClick = { starter.create(serverUrl) },
        enabled = profile.name.isNotBlank() && !starter.busy,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text("Start a convoy")
    }

    HorizontalDivider(color = colors.outline)
    Text("Got an invite? Scan its QR code, open its link, or paste the link here.", color = colors.onSurfaceVariant)
    OutlinedButton(onClick = { scanning = true }, enabled = !starter.busy, modifier = Modifier.fillMaxWidth()) {
        Icon(FoxIcons.Scan, contentDescription = null, Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Scan a QR code")
    }
    if (scanning) {
        InviteScannerDialog(
            onInvite = { scanned ->
                scanning = false
                link = scanned.toLink()
                if (profile.name.isNotBlank()) starter.join(scanned)
            },
            onDismiss = { scanning = false },
        )
    }
    OutlinedTextField(
        value = link,
        onValueChange = { link = it },
        label = { Text("Invite link") },
        singleLine = true,
        isError = link.isNotBlank() && invite == null,
        supportingText = if (link.isNotBlank() && invite == null) ({ Text("That isn't a FoxNav invite link") }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = { invite?.let(starter.join) },
        enabled = invite != null && profile.name.isNotBlank() && !starter.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Join")
    }
    if (starter.busy) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
    }
    starter.error?.let { Text(it, color = colors.error) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InConvoy(
    convoy: ConvoyController,
    active: ActiveConvoy,
    linkBase: (Invite) -> String,
    onNavigateTo: (GeoPoint, String) -> Unit,
    onLeft: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = active.session
    val status by session.status.collectAsState()
    val members by session.members.collectAsState()
    val ownerId by session.ownerId.collectAsState()
    val invite by session.invite.collectAsState()
    val regroup by convoy.regroupPoint.collectAsState()
    val profile by convoy.profiles.profile.collectAsState()
    val myId = (status as? ConvoyStatus.Live)?.memberId
    var editingProfile by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<ConvoyMember?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SheetTitle("Convoy", Modifier.weight(1f))
        val (label, color) = when (status) {
            is ConvoyStatus.Live -> "LIVE · ${members.size + 1}" to colors.secondary
            ConvoyStatus.Connecting -> "CONNECTING" to colors.tertiary
            is ConvoyStatus.Reconnecting -> "RECONNECTING" to colors.tertiary
            is ConvoyStatus.Ended -> "ENDED" to colors.onSurfaceVariant
        }
        Text(label, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = color)
    }

    // Big targets: these get pressed on the move.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickMessageKinds.forEach { kind ->
            Button(
                onClick = { convoy.sendQuickMessage(kind) },
                enabled = status is ConvoyStatus.Live,
                colors = if (kind.isWarning) {
                    ButtonDefaults.buttonColors(containerColor = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer)
                } else {
                    ButtonDefaults.buttonColors(containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer)
                },
                modifier = Modifier.heightIn(min = 52.dp),
            ) {
                Text(kind.label, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = convoy::dropRegroupPoint, enabled = status is ConvoyStatus.Live, modifier = Modifier.weight(1f)) {
            Icon(FoxIcons.Flag, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Regroup here")
        }
        regroup?.let { point ->
            OutlinedButton(onClick = { onNavigateTo(point.point, point.name) }, modifier = Modifier.weight(1f)) { Text("Go to regroup") }
        }
    }
    regroup?.let { point ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Regroup point from ${point.by}", Modifier.weight(1f), color = colors.onSurfaceVariant)
            TextButton(onClick = convoy::clearRegroupPoint) { Text("Clear") }
        }
    }

    sharedDestination(members, ownerId)?.let { destination ->
        Surface(shape = RoundedCornerShape(16.dp), color = colors.secondary.copy(alpha = 0.12f), border = BorderStroke(1.dp, colors.secondary)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(destination.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${destination.by}'s destination", color = colors.onSurfaceVariant)
                }
                Button(onClick = { onNavigateTo(destination.point, destination.name) }) { Text("Navigate") }
            }
        }
    }

    HorizontalDivider(color = colors.outline)
    invite?.let { InviteSection(it, linkBase(it)) } ?: Text("Getting the invite ready…", color = colors.onSurfaceVariant)

    HorizontalDivider(color = colors.outline)
    Text("In the convoy", style = MaterialTheme.typography.titleMedium)
    MemberRow(name = "${profile.name.ifBlank { "You" }} (you)", detail = carText(profile.carMake, profile.carModel), colorRgb = profile.colorRgb, isOwner = myId != null && myId == ownerId)
    members.values.sortedBy { it.profile?.display_name.orEmpty().lowercase() }.forEach { member ->
        MemberRow(
            name = member.profile?.display_name?.ifBlank { null } ?: "Joining…",
            detail = carText(member.profile?.car?.make.orEmpty(), member.profile?.car?.model.orEmpty()),
            colorRgb = member.profile?.car?.color_rgb ?: ConvoyPalette.first(),
            isOwner = member.id == ownerId,
            onRemove = if (myId != null && myId == ownerId) ({ removing = member }) else null,
        )
    }
    removing?.let { member ->
        val name = member.profile?.display_name?.ifBlank { null } ?: "this driver"
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $name?") },
            text = { Text("They leave the convoy at once. Everyone else gets a new invite link and key, so the old link stops working for $name and anyone else who has it.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        session.remove(member.id)
                        removing = null
                    },
                ) { Text("Remove", color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    if (editingProfile) {
        ProfileEditor(profile, onChange = convoy.profiles::save)
    } else {
        TextButton(onClick = { editingProfile = true }) { Text("Edit your name, car and colour") }
    }

    OutlinedButton(
        onClick = { scope.launch { convoy.leave(); onLeft() } },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
        border = BorderStroke(1.dp, colors.error),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text("Leave the convoy")
    }
}

@Composable
private fun InviteSection(invite: Invite, base: String) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    // A web link opens in any browser: the page there offers the app, or follows the convoy itself.
    val link = remember(invite, base) { invite.toLink(base) }
    val qr = remember(link) { qrCode(link) }

    Text("Invite", style = MaterialTheme.typography.titleMedium)
    Text("Anyone with this link can join and see everyone's position, so share it only with the people driving with you.", color = colors.onSurfaceVariant)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Image(
            bitmap = qr,
            contentDescription = "Invite QR code",
            filterQuality = FilterQuality.None, // crisp modules
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(220.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { shareInvite(context, link) }, modifier = Modifier.weight(1f)) {
            Icon(FoxIcons.Share, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Share")
        }
        OutlinedButton(onClick = { copyInvite(context, link) }, modifier = Modifier.weight(1f)) {
            Icon(FoxIcons.Copy, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Copy link")
        }
    }
}

@Composable
private fun MemberRow(name: String, detail: String, colorRgb: Int, isOwner: Boolean, onRemove: (() -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(14.dp).background(Color(0xFF000000.toInt() or colorRgb), CircleShape))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = listOfNotNull(detail.ifBlank { null }, if (isOwner) "owner" else null).joinToString(" · ")
            if (subtitle.isNotEmpty()) Text(subtitle, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove", color = colors.error) }
    }
}

/** Name, car and colour, saved as they change. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditor(profile: DriverProfile, onChange: (DriverProfile) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileNameField(profile.name, onChange = { onChange(profile.copy(name = it)) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = profile.carMake,
                onValueChange = { onChange(profile.copy(carMake = it.take(MAX_FIELD_LENGTH))) },
                label = { Text("Car make") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = profile.carModel,
                onValueChange = { onChange(profile.copy(carModel = it.take(MAX_FIELD_LENGTH))) },
                label = { Text("Model") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f),
            )
        }
        Text("Your colour on the others' maps", color = colors.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ConvoyPalette.forEach { rgb ->
                val selected = rgb == profile.colorRgb
                Box(
                    Modifier
                        .size(40.dp)
                        .border(if (selected) 3.dp else 0.dp, if (selected) colors.onSurface else Color.Transparent, CircleShape)
                        .padding(5.dp)
                        .background(Color(0xFF000000.toInt() or rgb), CircleShape)
                        .clickable { onChange(profile.copy(colorRgb = rgb)) },
                )
            }
        }
        Text("Its shape on the map", color = colors.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val tint = Color(0xFF000000.toInt() or profile.colorRgb)
            Car.BodyStyle.entries.forEach { style ->
                val selected = style == profile.bodyStyle
                Surface(
                    onClick = { onChange(profile.copy(bodyStyle = style)) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) colors.primary.copy(alpha = 0.16f) else colors.surfaceVariant,
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline),
                    modifier = Modifier.size(width = 76.dp, height = 72.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(style.icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
                        Text(style.label.ifEmpty { "Arrow" }, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Your name and car, as the convoy sees them; from the settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarProfileSheet(onDismiss: () -> Unit) {
    val profiles = LocalContext.current.appGraph.profiles
    val profile by profiles.profile.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Your car", style = MaterialTheme.typography.titleLarge)
            Text("This is how you show up in convoys, and your car on your own map.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProfileEditor(profile, onChange = profiles::save)
        }
    }
}

@Composable
fun ProfileNameField(name: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = { onChange(it.take(MAX_FIELD_LENGTH)) },
        label = { Text("Your name in the convoy") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.headlineSmall)
}

private fun carText(make: String, model: String) = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")

private fun shareInvite(context: Context, link: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "Join my FoxNav convoy: $link")
    context.startActivity(Intent.createChooser(send, "Invite to the convoy"))
}

private fun copyInvite(context: Context, link: String) {
    val clip = ClipData.newPlainText("FoxNav convoy invite", link)
    // The link carries the convoy's key: keep it out of the clipboard preview.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private const val MAX_FIELD_LENGTH = 32

/** Whether two server addresses, as typed, are the same server. */
private fun sameServer(a: String, b: String): Boolean {
    fun normal(address: String) = address.trim().trimEnd('/').lowercase().let { if ("://" in it) it else "https://$it" }
    return normal(a) == normal(b)
}
