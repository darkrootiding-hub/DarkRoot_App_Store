package com.example.view

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppPolicyEntity
import com.example.viewmodel.StoreViewModel

@Composable
fun EcosystemPolicyDialog(
    viewModel: StoreViewModel,
    isMandatoryAccept: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appPolicy by viewModel.appPolicy.collectAsStateWithLifecycle()
    val userRole by viewModel.userRole.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    val userUid by viewModel.userUid.collectAsStateWithLifecycle()
    
    val isAdmin = userRole == "admin" || userEmail.equals("davidstha900@gmail.com", ignoreCase = true) || userUid == "JN4BPhEKBBRUb5hpMdQJQmRrjiq1"

    var isEditingMode by remember { mutableStateOf(false) }
    var editedTitle by remember(appPolicy) { mutableStateOf(appPolicy.title) }
    var editedContent by remember(appPolicy) { mutableStateOf(appPolicy.content) }
    
    var isSaving by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = {
            if (!isMandatoryAccept) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = !isMandatoryAccept,
            dismissOnClickOutside = !isMandatoryAccept,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .padding(vertical = 12.dp)
                .testTag("ecosystem_policy_dialog_card"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF0C0E14)
            ),
            border = BorderStroke(1.5.dp, Color(0xFF34D399).copy(alpha = 0.4f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with close button if optional
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isMandatoryAccept) {
                        Spacer(modifier = Modifier.width(36.dp))
                    } else {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("policy_dialog_close_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close policies view",
                                tint = Color.Gray
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Policy Logo",
                            tint = Color(0xFF34D399),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "TERMS & AGREEMENT",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF34D399),
                            letterSpacing = 1.2.sp
                        )
                    }

                    if (isAdmin && !isMandatoryAccept && !isEditingMode) {
                        IconButton(
                            onClick = { isEditingMode = true },
                            modifier = Modifier.testTag("policy_dialog_edit_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Policy (Admin)",
                                tint = Color(0xFF34D399)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(36.dp))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isEditingMode) {
                    // Edit Layout for Admins
                    Text(
                        text = "Edit System Policy",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = editedTitle,
                            onValueChange = { editedTitle = it },
                            label = { Text("Policy Title", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF34D399),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("policy_edit_title_field")
                        )

                        OutlinedTextField(
                            value = editedContent,
                            onValueChange = { editedContent = it },
                            label = { Text("Policy Markdown Content", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF34D399),
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 180.dp)
                                .testTag("policy_edit_content_field")
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                editedTitle = appPolicy.title
                                editedContent = appPolicy.content
                                isEditingMode = false
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = BorderStroke(1.dp, Color.Gray),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("CANCEL")
                        }

                        Button(
                            onClick = {
                                if (editedTitle.isBlank() || editedContent.isBlank()) {
                                    Toast.makeText(context, "Fields cannot be blank", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                isSaving = true
                                viewModel.saveAppPolicy(editedTitle, editedContent, userEmail) { success ->
                                    isSaving = false
                                    if (success) {
                                        isEditingMode = false
                                        Toast.makeText(context, "System policy saved & distributed!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Server update encountered an error.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981),
                                contentColor = Color.Black
                            ),
                            modifier = Modifier.weight(1f).testTag("policy_edit_save_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = "Save edit")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isSaving) "SAVING" else "SAVE POLICY", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // Normal Viewer / Consumer Mode
                    Text(
                        text = appPolicy.title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    
                    if (appPolicy.lastUpdated > 0L) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Last updated: " + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", appPolicy.lastUpdated) + " by admin",
                            fontSize = 10.sp,
                            color = Color(0xFF34D399),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "Important guidelines, developer responsibilities & security standards:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF9CA3AF),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Scrollable content area showing modern layout
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(Color(0xFF070B13), RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val parsedLines = appPolicy.content.lines().map { it.trim() }.filter { it.isNotBlank() }
                            val displayLines = if (parsedLines.isNotEmpty()) parsedLines else listOf(
                                "I am responsible for the apps and content I upload.",
                                "I will not upload malware, viruses, spyware, ransomware, or any harmful software.",
                                "I will not upload apps that infringe copyrights, trademarks, or other intellectual property rights.",
                                "I will not upload illegal, deceptive, or fraudulent content.",
                                "I understand that every submitted app will be reviewed by the Dark Store team before publication.",
                                "I understand that Dark Store may reject or remove any app that violates these terms.",
                                "I will not impersonate another person, developer, or organization.",
                                "I understand that my account may be suspended or permanently banned for repeated violations.",
                                "I acknowledge that I download and install apps at my own discretion and responsibility.",
                                "I agree to follow all Dark Store rules and future policy updates."
                            )

                            displayLines.forEach { line ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    // Elegant emerald accent bullet instead of checkbox
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 6.dp)
                                            .size(6.dp)
                                            .background(Color(0xFF34D399), RoundedCornerShape(50))
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = line,
                                        fontSize = 12.5.sp,
                                        color = Color(0xFFD1D5DB),
                                        lineHeight = 17.5.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Footer confirmation statement
                    Text(
                        text = "I confirm that I acknowledge and adhere to the Dark Store Ecosystem Rules & Community Safeguards.",
                        fontSize = 11.sp,
                        color = Color.LightGray.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (isMandatoryAccept) {
                        Button(
                            onClick = {
                                viewModel.acceptEcosystemPolicy(userEmail)
                                onDismiss()
                                Toast.makeText(context, "Policy accepted. Thank you!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF34D399),
                                contentColor = Color.Black
                              ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("policy_accept_button")
                        ) {
                            Text(
                                text = "AGREE & CONTINUE",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E293B),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        ) {
                            Text(
                                "DONE & CLOSE", 
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
