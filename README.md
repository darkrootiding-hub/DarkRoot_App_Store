# DarkRoot Store 📱⚡

A high-performance, visually striking, custom Android App Marketplace build with Kotlin, Jetpack Compose, and Google Firebase. DarkRoot Store allows users to securely discover, download, install, and manage applications in a beautiful dark-mode interface designed to mimic professional-grade game stores.

---

## 🎨 Visual Identity & Design Philosophy
DarkRoot Store is themed to represent an elite utility tool. Featuring a unified **Slate & Cyberpunk Dark Theme**, the app leverages Material Design 3 (M3) components to create a premium visual style.

- **Negative Space & Contrast**: Dark-gray and pure-black containers paired with high-contrast electric-blue borders and subtle gradients.
- **Interactive States**: Dynamic card ripples, smooth button transitions, intuitive content loading templates, and visual icon status indicator pairs.
- **Interactive Media**: Seamless screenshot carousel viewers, customizable developer avatars, and download progress gauges.

---

## 🚀 Key Functional Modules

### 1. Dynamic App Marketplace Explorer
- Discover apps organized by category (e.g., Tools, System, Utilities, Emulators) or search directly with active character filtering.
- Implements responsive screen divisions, carousel screenshot galleries, developer badges, file sizes, and detailed change-logs.
- Includes a dedicated "Featured" banner to spotlight administrators' highlights.

### 2. Intelligent Download & Automated Installation Engine
- **Active Size/Download Multi-threading**: Robust, multi-threaded progress calculation notifying users of instantaneous rates (MB/s) and completed percentage.
- **Root & ADB Silent Background Installs**: Attempts execution of automated, unattended package installs (`pm install -r`) over superuser (`su`) or standard shell contexts.
- **Fallback Safe-Install Wizard**: Seamless transition to high-compatibility standard package launcher schemes using Android's modern `FileProvider` with runtime authority permissions for older or non-rooted clients.
- **Archive Validation**: Pre-checks target package signatures and structure integrity to proactively delete corrupted downloads and avoid generic "Problem parsing package" dialog hangs.

### 3. Native Firebase Authentication & Developer Submissions
- Features a secure, responsive Login and Registration screen styled with modern animated fields.
- Registered users can submit app metadata (logos, descriptions, APK links, version numbers) to the administrator dashboard for verification.
- Implements multi-level permission rules where users oversee their submission queues while administrators retain complete store management approvals.

### 4. Real-time Firebase Sync Core
Tracks data seamlessly across two distinct Firebase storage environments:
- **Firebase Realtime Database (RTDB)**: Powering instantaneous marketplace catalog rendering, submission status modifications, dynamic category grouping, and active download configurations.
- **Cloud Firestore**: Safeguarding secondary structural registries, historical logs, index queries, and app categories.

### 5. Multi-channel FCM Announcements & Notification Banners
- Supports **Firebase Cloud Messaging (FCM)**. Administrators can fill out and broadcast a Global Broadcast Announcement form complete with detailed rich text, direct images, and deep-link indicators.
- Employs a dedicated local background caching layer using `AppDao` and `NoticeEntity` so that missed announcements appear inside the notification inbox even on subsequent logins.
- Generates system-level push notifications utilizing Android's custom notification channels with adaptive pending deep links back into the app.

### 6. Dynamic Launcher Widget
- An interactive, lightweight Home screen Widget (`DarkStoreWidget`) so users can check active notices, updates, or launch search fields directly.

---

## 🛠️ Technology Stack Used

| Aspect | Technology & Libraries |
| :--- | :--- |
| **Language** | Kotlin 1.9+ |
| **UI Framework** | Jetpack Compose (Material Design 3) |
| **Concurrency** | Kotlin Coroutines & Asynchronous Flows |
| **Database & Caching** | Android Room Database & SharedPreferences |
| **Backend Suite** | Firebase Auth, Realtime Database, Firestore, Legacy FCM |
| **Networking** | OkHttp & Retrofit |
| **Serialization** | Moshi JSON Serialization Adapter |
| **Platform Utilities** | Device Administration API, System Package Manager Services |

---

## 📁 Project Architecture & Structure

```
/app/src/main/java/com/example/
│
├── data/                         # Data & Core Database Providers
│   ├── AppEntity.kt              # App meta data model
│   ├── NoticeEntity.kt           # Announcement metadata model
│   ├── SubmissionEntity.kt       # Application upload request queue schema
│   ├── AppDao.kt                 # Database caching utilities
│   ├── FirebaseService.kt        # Realtime Database, Firestore, and FCM broadcast engine
│   └── FirebaseAuthService.kt    # Secure login & signup Firebase interface
│
├── utils/                        # Hardware, Files, and Package Utilities
│   ├── ApkInstaller.kt           # Silent / Intent APK installer & Device Admin receiver
│   ├── CustomDownloadManager.kt  # Progress-tracked HTTP file downloader (OkHttp)
│   ├── MyFirebaseMessagingService.kt # FCM remote push message interceptor & local storer
│   └── StorageManager.kt         # Secure local disk cleanup and directory setup
│
├── view/                         # User Interfaces
│   └── AuthScreen.kt             # Register / Login / Authenticate interface
│
├── viewmodel/                    # Business Logic Layer
│   └── StoreViewModel.kt         # Master store engine managing state and cloud pipelines
│
├── widget/                       # Home Screen Widget Integration
│   └── DarkStoreWidget.kt        # Home screen shortcut & active news feeds
│
└── MainActivity.kt               # Central UI Coordinator (Compose screens, dialog configurations)
```

---

## 📐 Security & Data Rules

### Firebase Realtime Database Rule Configuration (`database.rules.json`)
```json
{
  "rules": {
    "apps": {
      ".read": "true",
      ".write": "auth != null && auth.token.email === 'davidstha900@gmail.com'"
    },
    "submissions": {
      ".read": "auth != null",
      "$submissionId": {
        ".read": "auth != null && (auth.token.email === 'davidstha900@gmail.com' || data.child('submittedBy').val() === auth.token.email)",
        ".write": "auth != null && (auth.token.email === 'davidstha900@gmail.com' || (!data.exists() && newData.child('submittedBy').val() === auth.token.email && newData.child('status').val() === 'Pending') || (data.exists() && data.child('submittedBy').val() === auth.token.email && newData.child('status').val() === data.child('status').val()))"
      }
    },
    "notices": {
      ".read": "true",
      "$noticeId": {
        ".read": "true",
        ".write": "auth != null && auth.token.email === 'davidstha900@gmail.com'"
      }
    }
  }
}
```

### Cloud Firestore Security Rules (`firestore.rules`)
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /apps/{appId} {
      allow read: if true;
      allow write: if request.auth != null && request.auth.token.email == "davidstha900@gmail.com";
    }
    match /submissions/{submissionId} {
      allow read: if request.auth != null && (
        request.auth.token.email == "davidstha900@gmail.com" || 
        resource.data.submittedBy == request.auth.token.email
      );
      allow write: if request.auth != null && (
        request.auth.token.email == "davidstha900@gmail.com" ||
        (!exists(/databases/$(database)/documents/submissions/$(submissionId)) && request.resource.data.submittedBy == request.auth.token.email && request.resource.data.status == "Pending") ||
        (exists(/databases/$(database)/documents/submissions/$(submissionId)) && resource.data.submittedBy == request.auth.token.email && request.resource.data.status == resource.data.status)
      );
    }
    match /notices/{noticeId} {
      allow read: if true;
      allow write: if request.auth != null && request.auth.token.email == "davidstha900@gmail.com";
    }
  }
}
```

---

## 🔧 Setup & Customization Parameters
1. **Firebase Integration**: Paste your target `google-services.json` into `/app/` directory and configure credentials inside your Firebase project console.
2. **Administration**: The master developer account configured across rule levels is currently binded to **`davidstha900@gmail.com`**.
3. **FCM Key Integration**: To broadcast push notifications dynamically, enable the **Legacy Cloud Messaging API** in Google Firebase Console settings. Paste your legacy FCM key inside the Administrator Announcement Form fields to instantly dispatch system notifications!
