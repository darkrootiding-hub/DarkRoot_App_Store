package com.example.view

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.viewmodel.StoreViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import android.util.Log

@Composable
fun AuthScreen(
    viewModel: StoreViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val computedSha1 = remember(context) { getCertificateSHA1Fingerprint(context) }
    
    // UI mode: "LOGIN", "SIGNUP", "FORGOT"
    var authMode by remember { mutableStateOf("LOGIN") }
    
    // Inputs State
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    
    // Progress Indicator
    var isLoading by remember { mutableStateOf(false) }

    // Terms Consent State
    var showTermsDialog by remember { mutableStateOf(false) }
    var pendingAuthAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshTermsAgreements()
    }
    
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_glow"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF121B2B), Color(0xFF090A0F)),
                    radius = 1200f
                )
            )
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(top = 40.dp, bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Header: Chic Logo with glow bubble
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(120.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(95.dp)
                        .scale(glowScale)
                        .background(
                            Brush.radialGradient(
                                listOf(Color(0xFF10B981).copy(alpha = 0.25f), Color.Transparent)
                            ),
                            shape = CircleShape
                        )
                )
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(listOf(Color(0xFF00AAFF), Color(0xFF10B981))),
                            shape = CircleShape
                        )
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_app_logo_new),
                        contentDescription = "Brand logo icon",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(14.dp))
            
            Text(
                text = "DarkRoot Store",
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                color = Color.White,
                letterSpacing = 1.5.sp
            )
            
            Text(
                text = "SECURED APPLICATION DEPLOYMENT REPOSITORY",
                fontSize = 9.sp,
                color = Color(0xFF00AAFF),
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(top = 4.dp),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(28.dp))
            
            // Central Auth Card with gorgeous border glow
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131722)),
                border = BorderStroke(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF232B3C),
                            Color(0xFF1F2937).copy(alpha = 0.5f)
                        )
                    )
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    
                    // Elegant Tab Segment Switcher (only show if not resetting password)
                    if (authMode != "FORGOT") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0B0E14), shape = RoundedCornerShape(14.dp))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            listOf("LOGIN" to "Sign In", "SIGNUP" to "Register").forEach { (mode, title) ->
                                val isSelected = authMode == mode
                                val bgCol by animateColorAsState(
                                    targetValue = if (isSelected) Color(0xFF1E2638) else Color.Transparent,
                                    animationSpec = tween(250),
                                    label = "tab_bg"
                                )
                                val textCol by animateColorAsState(
                                    targetValue = if (isSelected) Color(0xFF10B981) else Color.Gray,
                                    animationSpec = tween(250),
                                    label = "tab_text"
                                )
                                
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .background(bgCol, RoundedCornerShape(10.dp))
                                        .clickable { authMode = mode }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = title,
                                        color = textCol,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                            }
                        }
                    } else {
                        // Forgot Mode Back to Sign In button/header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { authMode = "LOGIN" }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "back btn",
                                tint = Color(0xFF00AAFF),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Back to Sign In",
                                color = Color(0xFF00AAFF),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    if (isLoading) {
                        Column(
                            modifier = Modifier.padding(vertical = 42.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFF10B981),
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Text(
                                text = when (authMode) {
                                    "LOGIN" -> "Signing in... please wait"
                                    "SIGNUP" -> "Registering account... please wait"
                                    else -> "Sending recovery email... please wait"
                                },
                                color = Color.Gray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.5.sp
                            )
                        }
                    } else {
                        // Dynamic slide-expand for Display Name
                        AnimatedVisibility(
                            visible = authMode == "SIGNUP",
                            enter = fadeIn(animationSpec = tween(300)) + expandVertically(),
                            exit = fadeOut(animationSpec = tween(250)) + shrinkVertically()
                        ) {
                            Column {
                                OutlinedTextField(
                                    value = displayName,
                                    onValueChange = { displayName = it },
                                    label = { Text("Display Name") },
                                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = "person") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("auth_name_field"),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF10B981),
                                        unfocusedBorderColor = Color(0xFF232B3C),
                                        focusedContainerColor = Color(0xFF0A0D14),
                                        unfocusedContainerColor = Color(0xFF0A0D14),
                                        focusedLabelColor = Color(0xFF10B981),
                                        unfocusedLabelColor = Color.Gray,
                                        focusedLeadingIconColor = Color(0xFF10B981),
                                        unfocusedLeadingIconColor = Color.Gray
                                    )
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                            }
                        }
                        
                        // Email Field
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email Address") },
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = "email") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_email_field"),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF10B981),
                                unfocusedBorderColor = Color(0xFF232B3C),
                                focusedContainerColor = Color(0xFF0A0D14),
                                unfocusedContainerColor = Color(0xFF0A0D14),
                                focusedLabelColor = Color(0xFF10B981),
                                unfocusedLabelColor = Color.Gray,
                                focusedLeadingIconColor = Color(0xFF10B981),
                                unfocusedLeadingIconColor = Color.Gray
                            )
                        )
                        
                        // Password Field (Auth only)
                        if (authMode != "FORGOT") {
                            Spacer(modifier = Modifier.height(14.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = "lock") },
                                trailingIcon = {
                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = "toggle password visibility",
                                            tint = if (isPasswordVisible) Color(0xFF10B981) else Color.Gray,
                                            modifier = Modifier.scale(if (isPasswordVisible) 1.1f else 0.95f)
                                        )
                                    }
                                },
                                singleLine = true,
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_password_field"),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF10B981),
                                    unfocusedBorderColor = Color(0xFF232B3C),
                                    focusedContainerColor = Color(0xFF0A0D14),
                                    unfocusedContainerColor = Color(0xFF0A0D14),
                                    focusedLabelColor = Color(0xFF10B981),
                                    unfocusedLabelColor = Color.Gray,
                                    focusedLeadingIconColor = Color(0xFF10B981),
                                    unfocusedLeadingIconColor = Color.Gray
                                )
                            )
                        }
                        
                        // Forgot password helper label
                        if (authMode == "LOGIN") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.End
                              ) {
                                Text(
                                    text = "Forgot Password?",
                                    color = Color(0xFF00AAFF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .clickable { authMode = "FORGOT" }
                                        .padding(4.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(28.dp))
                        
                        // Primary Action button with amazing tech metallic-neon gradient background
                        Button(
                            onClick = {
                                // Validation
                                if (email.isBlank() || !email.contains("@")) {
                                    Toast.makeText(context, "Please enter a valid email address.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (authMode != "FORGOT" && password.length < 6) {
                                    Toast.makeText(context, "Password must be at least 6 characters.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (authMode == "SIGNUP" && displayName.isBlank()) {
                                    Toast.makeText(context, "Please enter a public display name.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                
                                val performAuth = {
                                    isLoading = true
                                    when (authMode) {
                                        "LOGIN" -> {
                                            viewModel.signInWithEmail(email, password) { success, msg ->
                                                isLoading = false
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        "SIGNUP" -> {
                                            viewModel.signUpWithEmail(email, password, displayName) { success, msg ->
                                                isLoading = false
                                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                if (success) {
                                                    // On successful signup, save policy accept to Realtime Database
                                                    viewModel.recordTermsAgreementOnServer(
                                                        explicitEmail = email,
                                                        explicitName = displayName,
                                                        explicitUid = viewModel.userUid.value
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (authMode == "FORGOT") {
                                    isLoading = true
                                    viewModel.resetUserPassword(email) { success, msg ->
                                        isLoading = false
                                        Toast.makeText(context, msg ?: "Completed reset dispatch.", Toast.LENGTH_LONG).show()
                                        if (success) authMode = "LOGIN"
                                    }
                                } else if (authMode == "LOGIN") {
                                    // Direct submit for login: we fetch/validate terms with auth token post-login
                                    performAuth()
                                } else {
                                    val cleanEmail = email.lowercase().trim()
                                    if (viewModel.hasUserAgreedToTerms(cleanEmail)) {
                                         performAuth()
                                    } else {
                                         pendingAuthAction = {
                                             viewModel.markTermsAcceptedForEmail(
                                                 email = cleanEmail,
                                                 name = displayName
                                             )
                                             performAuth()
                                         }
                                         showTermsDialog = true
                                    }
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            contentPadding = PaddingValues(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF00AAFF), Color(0xFF10B981))
                                    ),
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .testTag("auth_submit_btn")
                        ) {
                            Text(
                                text = when (authMode) {
                                    "LOGIN" -> "SIGN IN"
                                    "SIGNUP" -> "SIGN UP"
                                    else -> "RESET PASSWORD"
                                },
                                color = Color(0xFF0B0D12),
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.sp,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Mode Flip controllers (bottom hint)
            if (!isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (authMode) {
                            "LOGIN" -> "Don't have an account?"
                            "SIGNUP" -> "Already have an account?"
                            else -> "Remembered your credentials?"
                        },
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when (authMode) {
                            "LOGIN" -> "Sign Up Now"
                            "SIGNUP" -> "Log In here"
                            else -> "Log In"
                        },
                        color = Color(0xFF10B981),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .clickable {
                                authMode = when (authMode) {
                                    "LOGIN" -> "SIGNUP"
                                    "SIGNUP" -> "LOGIN"
                                    else -> "LOGIN"
                                }
                            }
                            .padding(4.dp)
                    )
                }
            }
        }

        if (showTermsDialog) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                TermsAgreementDialog(
                    viewModel = viewModel,
                    onAccept = {
                        showTermsDialog = false
                        pendingAuthAction?.invoke()
                    }
                )
            }
        }
    }
}

private fun getCertificateSHA1Fingerprint(context: android.content.Context): String {
    try {
        val pm = context.packageManager
        val packageName = context.packageName
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            android.content.pm.PackageManager.GET_SIGNATURES
        }
        val packageInfo = pm.getPackageInfo(packageName, flags)
        val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        if (signatures != null && signatures.isNotEmpty()) {
            val cert = signatures[0].toByteArray()
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val publicKey = md.digest(cert)
            val hexString = StringBuilder()
            for (i in publicKey.indices) {
                val appendString = Integer.toHexString(0xFF and publicKey[i].toInt()).uppercase(java.util.Locale.US)
                if (appendString.length == 1) {
                    hexString.append("0")
                }
                hexString.append(appendString)
                if (i < publicKey.size - 1) {
                    hexString.append(":")
                }
            }
            return hexString.toString()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "E5:51:3D:E6:95:B8:68:B0:D8:2B:59:48:A0:3B:F8:FF:7F:D8:4F:7F"
}
