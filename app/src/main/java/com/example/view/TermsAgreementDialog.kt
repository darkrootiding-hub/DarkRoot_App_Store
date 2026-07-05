package com.example.view

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.viewmodel.StoreViewModel

@Composable
fun TermsAgreementDialog(
    viewModel: StoreViewModel,
    onAccept: () -> Unit
) {
    val appPolicy by viewModel.appPolicy.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()
    
    val agreementPoints = remember(appPolicy.content) {
        val parsed = appPolicy.content.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (parsed.isNotEmpty()) parsed else listOf(
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
    }

    // Use a state list / array to keep track of checked states
    val checkedStates = remember(agreementPoints) {
        mutableStateListOf(*Array(agreementPoints.size) { false })
    }
    
    val allChecked = checkedStates.all { it }
    val checkCount = checkedStates.count { it }
    val totalCount = agreementPoints.size
    val progressByState = if (totalCount > 0) checkCount.toFloat() / totalCount else 0f
    
    val animatedProgress by animateFloatAsState(
        targetValue = progressByState,
        animationSpec = tween(durationMillis = 300),
        label = "progress"
    )

    // A gorgeous deep slate gradient backdrop for a futuristic signup system experience
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF04060B),
                        Color(0xFF0B101E),
                        Color(0xFF05070D)
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(16.dp)
            .testTag("terms_agreement_onboarding_root")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            // Upper Setup Badge (Signup System Vibe)
            Box(
                modifier = Modifier
                    .background(Color(0xFF102A24), RoundedCornerShape(50.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color(0xFF34D399), RoundedCornerShape(50.dp))
                    )
                    Text(
                        text = "ONBOARDING STEP: DEVELOPER SECURITY AGREEMENT",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF34D399),
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Icon & Welcome Heading
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF34D399).copy(alpha = 0.25f),
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .background(Color(0xFF111E1A), RoundedCornerShape(20.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Shield Icon",
                    tint = Color(0xFF34D399),
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Dark Store Terms & Security Sync",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Account: $userEmail",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF6B7280),
                textAlign = TextAlign.Center,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Glowing Progress & Checklist Content Area
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("terms_agreement_onboarding_card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF070B13).copy(alpha = 0.85f)
                ),
                border = BorderStroke(1.dp, Color(0xFF1F2937))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Modern Progress Tracker inside the Container
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "COMPLIANCE CHECKLIST",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF9CA3AF),
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "$checkCount of $totalCount agreed",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (allChecked) Color(0xFF34D399) else Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = animatedProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .testTag("terms_progress_bar"),
                        color = Color(0xFF34D399),
                        trackColor = Color.White.copy(alpha = 0.06f),
                        strokeCap = StrokeCap.Round
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Scrollable Checklist points Custom Cards
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 2.dp)
                        ) {
                            itemsIndexed(agreementPoints) { index, term ->
                                val isChecked = checkedStates[index]
                                val containerBgColor by animateColorAsState(
                                    targetValue = if (isChecked) Color(0xFF10211A) else Color(0xFF0D111A),
                                    animationSpec = tween(150),
                                    label = "bg_anim"
                                )
                                val borderTint by animateColorAsState(
                                    targetValue = if (isChecked) Color(0xFF34D399).copy(alpha = 0.5f) else Color(0xFF1F2937),
                                    animationSpec = tween(150),
                                    label = "border_anim"
                                )
                                val textTint by animateColorAsState(
                                    targetValue = if (isChecked) Color.White else Color(0xFF9EAFBC),
                                    animationSpec = tween(150),
                                    label = "text_anim"
                                )

                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            checkedStates[index] = !checkedStates[index]
                                        }
                                        .testTag("terms_row_container_$index"),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(containerColor = containerBgColor),
                                    border = BorderStroke(1.dp, borderTint)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        // Replace tick box checkbox with beautiful circular custom status token
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isChecked) Color(0xFF34D399) else Color.White.copy(alpha = 0.05f)
                                                )
                                                .border(
                                                    width = 1.5.dp,
                                                    color = if (isChecked) Color(0xFF34D399) else Color.White.copy(alpha = 0.25f),
                                                    shape = CircleShape
                                                )
                                                .testTag("terms_checkbox_$index"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isChecked) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = term,
                                            fontSize = 12.sp,
                                            color = textTint,
                                            lineHeight = 16.5.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Select/Deselect All Toggle row
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                val targetState = !allChecked
                                for (i in checkedStates.indices) {
                                    checkedStates[i] = targetState
                                }
                            }
                            .testTag("terms_toggle_all_row"),
                        color = Color(0xFF111726),
                        border = BorderStroke(1.dp, Color(0xFF1F2937))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Match Check",
                                tint = Color(0xFF34D399),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (allChecked) "DESELECT ALL STATEMENTS" else "I AGREE TO ALL $totalCount STATEMENTS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF34D399),
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Footer confirmation text Statement Info
            Text(
                text = "By continuing, you bind your account ($userEmail) permanently to the Dark Store Publisher Agreement rules and community conditions.",
                fontSize = 11.sp,
                color = Color.LightGray.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            val scaleFactor by animateFloatAsState(
                targetValue = if (allChecked) 1.02f else 1.0f,
                animationSpec = tween(200),
                label = "scale"
            )

            // CTA Button with Scale visual feedback when unlocked
            Button(
                onClick = {
                    if (allChecked) {
                        viewModel.setTermsAccepted(true)
                        onAccept()
                    }
                },
                enabled = allChecked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .scale(scaleFactor)
                    .testTag("terms_accept_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF34D399),
                    contentColor = Color.Black,
                    disabledContainerColor = Color.White.copy(alpha = 0.05f),
                    disabledContentColor = Color.White.copy(alpha = 0.25f)
                ),
                shape = RoundedCornerShape(14.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 4.dp,
                    pressedElevation = 8.dp
                )
            ) {
                Text(
                    text = "I AGREE & CONTINUE TO DASHBOARD",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    letterSpacing = 0.5.sp
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}
