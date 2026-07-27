@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.family.talkly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.People
import com.family.talkly.ui.theme.LocalIsDarkTheme
import com.family.talkly.ui.theme.PrimaryDarkPurple
import com.family.talkly.ui.theme.SecondaryLightSage
import com.family.talkly.ui.theme.ThemeMode
import com.family.talkly.ui.theme.WhatsappGreen
import com.family.talkly.ui.theme.WhatsappTeal
import com.family.talkly.data.models.CallType
import com.family.talkly.data.models.ChatMessage
import com.family.talkly.data.models.DEFAULT_FAMILY_MEMBERS
import com.family.talkly.data.models.FamilyMember
import com.family.talkly.data.models.UserProfile
import com.family.talkly.ui.components.ContactProfileDetailsDialog
import com.family.talkly.ui.components.UserProfileDetailsDialog
import com.family.talkly.ui.theme.WhatsappGreen
import com.family.talkly.ui.theme.WhatsappTeal

import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.FloatingActionButton
import com.family.talkly.ui.components.AddContactDialog

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatListScreen(
    familyMembers: List<FamilyMember>,
    messagesMap: Map<String, List<ChatMessage>>,
    simulatedTimeOffsetMs: Long,
    currentUserProfile: UserProfile? = null,
    currentThemeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: ((ThemeMode) -> Unit)? = null,
    onLogout: (() -> Unit)? = null,
    onSaveProfile: ((name: String, bio: String, photoUrl: String) -> Unit)? = null,
    onSelectMember: (FamilyMember) -> Unit,
    onStartCall: (FamilyMember, CallType) -> Unit,
    onTriggerIncomingDemo: (FamilyMember) -> Unit,
    onTogglePinMember: ((String) -> Unit)? = null,
    onSearchUserByPhone: ((phone: String, onResult: (UserProfile?) -> Unit) -> Unit)? = null,
    onAddContact: ((name: String, phone: String, relation: String, bio: String, avatarUrl: String?) -> Unit)? = null,
    onDeleteContact: ((String) -> Unit)? = null,
    onDeleteChatHistory: ((String) -> Unit)? = null,
    onClearDemoContacts: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var selectedContactForProfile by remember { mutableStateOf<FamilyMember?>(null) }
    var memberToDeleteHistory by remember { mutableStateOf<FamilyMember?>(null) }

    val isDark = LocalIsDarkTheme.current

    if (showAddContactDialog && onSearchUserByPhone != null && onAddContact != null) {
        AddContactDialog(
            onDismiss = { showAddContactDialog = false },
            onSearchUserByPhone = { phone, callback ->
                onSearchUserByPhone(phone, callback)
            },
            onAddContact = { name, phone, relation, bio, avatarUrl ->
                onAddContact(name, phone, relation, bio, avatarUrl)
            }
        )
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = WhatsappTeal
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose App Theme", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column {
                    val options = listOf(
                        Triple(ThemeMode.LIGHT, "Light Mode ☀️", "Bright visual theme"),
                        Triple(ThemeMode.DARK, "Dark Mode 🌙", "Eye-safe dark canvas"),
                        Triple(ThemeMode.SYSTEM, "System Default 📱", "Match device system setting")
                    )
                    options.forEach { (mode, label, subtitle) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onThemeModeChange?.invoke(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = (currentThemeMode == mode),
                                onClick = {
                                    onThemeModeChange?.invoke(mode)
                                    showThemeDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = WhatsappTeal)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = label,
                                    fontWeight = if (currentThemeMode == mode) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = subtitle,
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Close", color = WhatsappTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showProfileDialog && currentUserProfile != null) {
        UserProfileDetailsDialog(
            userProfile = currentUserProfile,
            onDismiss = { showProfileDialog = false },
            onSaveProfile = { name, bio, photoUrl ->
                onSaveProfile?.invoke(name, bio, photoUrl)
            },
            onLogout = onLogout
        )
    }

    if (selectedContactForProfile != null) {
        ContactProfileDetailsDialog(
            member = selectedContactForProfile!!,
            onDismiss = { selectedContactForProfile = null },
            onStartChat = { member ->
                selectedContactForProfile = null
                onSelectMember(member)
            },
            onStartCall = { member, callType ->
                selectedContactForProfile = null
                onStartCall(member, callType)
            },
            onDeleteContact = { id ->
                selectedContactForProfile = null
                onDeleteContact?.invoke(id)
            }
        )
    }

    if (memberToDeleteHistory != null) {
        AlertDialog(
            onDismissRequest = { memberToDeleteHistory = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = Color(0xFFD32F2F)
                )
            },
            title = {
                Text(
                    text = "Delete Chat History",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to delete all chat history with ${memberToDeleteHistory?.name}? This action cannot be undone.",
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetId = memberToDeleteHistory?.id
                        if (targetId != null) {
                            onDeleteChatHistory?.invoke(targetId)
                            Toast.makeText(context, "Chat history deleted", Toast.LENGTH_SHORT).show()
                        }
                        memberToDeleteHistory = null
                    }
                ) {
                    Text("Delete", color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToDeleteHistory = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // User Avatar Profile on Left
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFE0E0E0))
                                .clickable { showProfileDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            if (currentUserProfile?.profilePicUrl?.isNotBlank() == true) {
                                AsyncImage(
                                    model = currentUserProfile.profilePicUrl,
                                    contentDescription = "My Profile",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text(
                                    text = (currentUserProfile?.name?.take(1) ?: "U").uppercase(),
                                    color = Color(0xFF333333),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }

                        // Middle Header Tabs (Chat & Contacts)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(48.dp)
                        ) {
                            // Active Chat Tab
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { /* Active Chat Tab */ }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Chat,
                                    contentDescription = "Chats Tab",
                                    tint = PrimaryDarkPurple,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .width(72.dp)
                                        .height(3.dp)
                                        .background(PrimaryDarkPurple, RoundedCornerShape(2.dp))
                                )
                            }

                            // Inactive Contacts Tab
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { showAddContactDialog = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.People,
                                    contentDescription = "Contacts Tab",
                                    tint = Color(0xFFB0BEC5),
                                    modifier = Modifier.size(26.dp)
                                )
                                Spacer(modifier = Modifier.height(7.dp))
                            }
                        }

                        Spacer(modifier = Modifier.width(36.dp))
                    }
                },
                actions = {
                    IconButton(onClick = { showAddContactDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = PrimaryDarkPurple
                        )
                    }
                    IconButton(
                        onClick = {
                            val newMode = if (isDark) ThemeMode.LIGHT else ThemeMode.DARK
                            onThemeModeChange?.invoke(newMode)
                        }
                    ) {
                        Icon(
                            imageVector = if (isDark) Icons.Default.WbSunny else Icons.Default.DarkMode,
                            contentDescription = "Toggle Theme",
                            tint = PrimaryDarkPurple
                        )
                    }
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = PrimaryDarkPurple
                        )
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Add New Contact") },
                            leadingIcon = {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = PrimaryDarkPurple)
                            },
                            onClick = {
                                showMenu = false
                                showAddContactDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear Demo Contacts") },
                            leadingIcon = {
                                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color.Gray)
                            },
                            onClick = {
                                showMenu = false
                                onClearDemoContacts?.invoke()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("App Theme (${currentThemeMode.name})") },
                            leadingIcon = {
                                Icon(Icons.Default.Palette, contentDescription = null, tint = PrimaryDarkPurple)
                            },
                            onClick = {
                                showMenu = false
                                showThemeDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("My Profile & Phone") },
                            leadingIcon = {
                                Icon(Icons.Default.AccountCircle, contentDescription = null, tint = PrimaryDarkPurple)
                            },
                            onClick = {
                                showMenu = false
                                showProfileDialog = true
                            }
                        )
                        if (onLogout != null) {
                            DropdownMenuItem(
                                text = { Text("Log Out Session", color = Color.Red) },
                                leadingIcon = {
                                    Icon(Icons.Default.ExitToApp, contentDescription = null, tint = Color.Red)
                                },
                                onClick = {
                                    showMenu = false
                                    onLogout()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Family Quick Status / Stories Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(vertical = 12.dp)
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // "Add a story" item
                        item {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { showAddContactDialog = true }
                            ) {
                                Box(
                                    modifier = Modifier.size(62.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFE8ECEF)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CameraAlt,
                                            contentDescription = "Add a story",
                                            tint = Color(0xFF546E7A),
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                    // Plus Badge
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(PrimaryDarkPurple, CircleShape)
                                            .border(1.5.dp, Color.White, CircleShape)
                                            .align(Alignment.BottomEnd),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            tint = SecondaryLightSage,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Add a story",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 12.sp,
                                        color = Color.Black
                                    ),
                                    maxLines = 1
                                )
                            }
                        }

                        // Family member story avatars
                        items(familyMembers) { member ->
                            FamilyMemberAvatarStory(
                                member = member,
                                onClick = { selectedContactForProfile = member },
                                onLongClick = { onTriggerIncomingDemo(member) }
                            )
                        }
                    }
                }

                Divider(color = Color(0xFFF0F0F0))

                // Family Conversations List
                val sortedMembers = remember(familyMembers) {
                    familyMembers.sortedWith(compareByDescending { it.isPinned })
                }

                if (sortedMembers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Forum,
                                contentDescription = null,
                                tint = Color(0xFF0088FF),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No active chats yet",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        items(sortedMembers) { member ->
                            val memberMessages = messagesMap[member.id] ?: emptyList()
                            val lastMessage = memberMessages.lastOrNull()

                            FamilyChatRow(
                                member = member,
                                lastMessage = lastMessage,
                                simulatedTimeOffsetMs = simulatedTimeOffsetMs,
                                isDarkTheme = isDark,
                                onClick = { onSelectMember(member) },
                                onLongClick = { memberToDeleteHistory = member },
                                onAvatarClick = { selectedContactForProfile = member },
                                onAudioCall = { onStartCall(member, CallType.AUDIO) },
                                onVideoCall = { onStartCall(member, CallType.VIDEO) }
                            )
                            Divider(
                                color = Color(0xFFF0F0F0),
                                modifier = Modifier.padding(start = 76.dp)
                            )
                        }
                    }
                }
            }

            // Dual Floating Action Buttons matching layout requirements
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Bottom-Left Plus (+) FAB
                FloatingActionButton(
                    onClick = { showAddContactDialog = true },
                    containerColor = PrimaryDarkPurple,
                    contentColor = SecondaryLightSage,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomStart)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Contact"
                    )
                }

                // Bottom-Right Search FAB
                FloatingActionButton(
                    onClick = { showAddContactDialog = true },
                    containerColor = PrimaryDarkPurple,
                    contentColor = SecondaryLightSage,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search"
                    )
                }
            }
        }
    }
}

@Composable
private fun FamilyMemberAvatarStory(
    member: FamilyMember,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.size(62.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE0E0E0)),
                contentAlignment = Alignment.Center
            ) {
                if (member.avatarUrl != null) {
                    AsyncImage(
                        model = member.avatarUrl,
                        contentDescription = member.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = member.name.take(2).uppercase(),
                        color = Color(0xFF333333),
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }
            // Green badge for unread story count
            if (member.unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color(0xFF4CAF50), CircleShape)
                        .border(1.5.dp, Color.White, CircleShape)
                        .align(Alignment.TopEnd),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = member.unreadCount.toString(),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = member.name,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                color = Color.Black
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FamilyChatRow(
    member: FamilyMember,
    lastMessage: ChatMessage?,
    simulatedTimeOffsetMs: Long,
    isDarkTheme: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onAvatarClick: () -> Unit,
    onAudioCall: () -> Unit,
    onVideoCall: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with real-time Online/Offline indicator badge
        Box(
            modifier = Modifier
                .size(54.dp)
                .clickable { onAvatarClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE0E0E0)),
                contentAlignment = Alignment.Center
            ) {
                if (member.avatarUrl != null) {
                    AsyncImage(
                        model = member.avatarUrl,
                        contentDescription = member.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = member.name.take(2).uppercase(),
                        color = Color(0xFF333333),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
            }
            // Online Status Checkmark / Dot Indicator
            if (member.isOnline) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(Color(0xFF25D366), CircleShape)
                        .border(1.5.dp, Color.White, CircleShape)
                        .align(Alignment.BottomEnd),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Online",
                        tint = Color.White,
                        modifier = Modifier.size(11.dp)
                    )
                }
            } else {
                // Offline dotted/dashed grey indicator ring
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .border(1.5.dp, Color(0xFFB0BEC5), CircleShape)
                        .align(Alignment.BottomEnd)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Center Chat Details
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    if (member.isPinned) {
                        Text(text = "📌 ", fontSize = 14.sp)
                    }
                    Text(
                        text = member.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color(0xFF111111)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                val displayTime = if (lastMessage != null) lastMessage.formattedTime else member.lastSeen
                Text(
                    text = if (member.isTyping) "typing..." else displayTime,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (member.isTyping) PrimaryDarkPurple else Color(0xFF888888),
                        fontWeight = if (member.isTyping) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 12.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (member.isTyping) {
                    Text(
                        text = "typing...",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = PrimaryDarkPurple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    val rawText = lastMessage?.textContent ?: member.status
                    val isMissedCall = rawText.contains("Missed", ignoreCase = true)

                    if (isMissedCall) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallMissed,
                                contentDescription = "Missed Call",
                                tint = Color(0xFFE53935),
                                modifier = Modifier
                                    .size(18.dp)
                                    .padding(end = 4.dp)
                            )
                            Text(
                                text = rawText,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = Color(0xFFE53935),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        val previewText = when {
                            lastMessage != null && lastMessage.isMediaExpired(simulatedTimeOffsetMs) -> "⚠️ Media expired after 48h"
                            lastMessage?.mediaUrl != null -> "📷 Photo / Media"
                            lastMessage != null -> lastMessage.textContent
                            else -> member.status
                        }

                        Text(
                            text = previewText,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = if (lastMessage?.isMediaExpired(simulatedTimeOffsetMs) == true) Color(0xFF856404) else Color(0xFF666666),
                                fontSize = 14.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                if (member.unreadCount > 0 && !member.isTyping) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(Color(0xFF4CAF50), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = member.unreadCount.toString(),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Phone Call Action Button in Primary Color
        IconButton(
            onClick = onAudioCall,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = "Audio Call",
                tint = PrimaryDarkPurple,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
