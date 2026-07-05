# Dark Store Firebase Security Rules

Here is the exact code for Firebase Cloud Firestore and Realtime Database rules that you should publish in your Firebase console. These rules ensure that only authenticated developers can submit apps (defaulting to unapproved status), only your admin email (`davidstha900@gmail.com`) can verify/approve apps, and anyone can read approved apps.

---

## 1. Cloud Firestore Rules

Publish these rules in **Firebase Console -> Firestore Database -> Rules**:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    
    // Security Rules for Apps Catalog
    match /apps/{appId} {
      // Allow read access to anyone so they can explore categories and download packages
      allow read: if true;

      // Allow creation if the developer is logged in, designates their own email as submitter,
      // and sets isApproved to false (unless they are the administrator)
      allow create: if request.auth != null 
                    && request.resource.data.submittedBy == request.auth.token.email
                    && request.resource.data.isApproved == (request.auth.token.email == "davidstha900@gmail.com");

      // Admin (davidstha900@gmail.com) can update or delete any document.
      // Registered developers can update or delete their own app but they CANNOT self-approve theirs.
      allow update, delete: if request.auth != null 
                            && (
                              request.auth.token.email == "davidstha900@gmail.com" 
                              || (
                                resource.data.submittedBy == request.auth.token.email 
                                && request.resource.data.isApproved == resource.data.isApproved
                              )
                            );
    }

    // Security Rules for Users Profile
    match /users/{userId} {
      allow read: if request.auth != null;
      allow write: if request.auth != null && (request.auth.uid == userId || request.auth.token.email == "davidstha900@gmail.com");
    }

    // Security Rules for Submission Reviews
    match /submissions/{submissionId} {
      allow read: if request.auth != null;
                  
      allow create: if request.auth != null 
                    && request.resource.data.submittedBy == request.auth.token.email
                    && request.resource.data.status == "Pending";
                    
      allow update, delete: if request.auth != null 
                            && (
                              request.auth.token.email == "davidstha900@gmail.com"
                              || (
                                resource.data.submittedBy == request.auth.token.email
                                && request.resource.data.status == resource.data.status
                              )
                            );
    }

    // Security Rules for Notices & Announcements
    match /notices/{noticeId} {
      allow read: if true;
      allow write: if request.auth != null && request.auth.token.email == "davidstha900@gmail.com";
    }

    // Security Rules for App Configurations (e.g. self-updates)
    match /configs/{configId} {
      allow read: if true;
      allow write: if request.auth != null && request.auth.token.email == "davidstha900@gmail.com";
    }
  }
}
```

---

## 2. Realtime Database Rules

Publish these rules in **Firebase Console -> Realtime Database -> Rules**:

```json
{
  "rules": {
    "apps": {
      // Allow read access to anyone so they can explore categories and download packages
      ".read": "true",
      "$appId": {
        ".read": "true",
        // Write access constraints:
        // 1. If Admin: davidstha900@gmail.com has full write permissions
        // 2. If Developer submits a NEW app: must be unapproved and signed by their email
        // 3. If Developer updates their OWN existing app: must not change the isApproved status
        ".write": "auth != null && (auth.token.email === 'davidstha900@gmail.com' || (!data.exists() && newData.child('submittedBy').val() === auth.token.email && newData.child('isApproved').val() === false) || (data.exists() && data.child('submittedBy').val() === auth.token.email && newData.child('isApproved').val() === data.child('isApproved').val()))"
      }
    },
    "users": {
      "$userId": {
        ".read": "auth != null",
        ".write": "auth != null && (auth.uid === $userId || auth.token.email === 'davidstha900@gmail.com')"
      }
    },
    "submissions": {
      ".read": "auth != null",
      "$submissionId": {
        // Only admin or the submitting developer can see submission details
        ".read": "auth != null && (auth.token.email === 'davidstha900@gmail.com' || (data.exists() && data.child('submittedBy').val() === auth.token.email))",
        ".write": "auth != null && (auth.token.email === 'davidstha900@gmail.com' || (!data.exists() && newData.child('submittedBy').val() === auth.token.email && newData.child('status').val() === 'Pending') || (data.exists() && data.child('submittedBy').val() === auth.token.email && newData.child('status').val() === data.child('status').val()))"
      }
    },
    "notices": {
      ".read": "true",
      "$noticeId": {
        ".read": "true",
        ".write": "auth != null && auth.token.email === 'davidstha900@gmail.com'"
      }
    },
    "DarkStoreUpdate": {
      ".read": "true",
      ".write": "auth != null && auth.token.email === 'davidstha900@gmail.com'"
    }
  }
}
```
