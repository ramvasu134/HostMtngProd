# HostMtng Enhancement Guide - User Instructions

## 🎓 For Teachers/Hosts

### Viewing Terms & Conditions
1. Go to login page: `http://localhost:8080/login`
2. Click **"Terms & Conditions"** link at the bottom
3. Review all sections
4. Use **Print** button to save as PDF

### Using Live Transcripts in Meeting

#### During a Meeting:
1. Click the **"Live Transcripts"** tab on the right panel
2. As students speak, transcripts appear in real-time
3. Each transcript shows:
   - Student's name
   - What they said
   - Time markers (start - end)
4. Click **"Archive"** button to archive a transcript
5. Transcripts smoothly scroll and auto-update

#### Accessing Archived Transcripts:
1. After meeting ends
2. Go to **Transcripts** tab
3. View all archived transcripts from the session
4. Click transcript to see full details
5. Download or share transcripts

#### Recording Management:
1. Enable recording when starting meeting
2. Student audio is automatically captured
3. Combined teacher + student audio saved
4. Transcripts auto-generated from audio
5. Download recordings from dashboard

---

## 👨‍💼 For Administrators

### Managing Teachers

#### Create New Teacher:
1. Go to Admin Dashboard: `http://localhost:8080/admin/dashboard`
2. Click **"Manage Teachers"** or go to `/admin/teachers`
3. Click **"Add New Teacher"**
4. Fill in:
   - Teacher Name (e.g., "John Doe")
   - Username (login ID)
   - Password (auto-generated or custom)
   - Display Name
   - Email (optional)
   - Phone (optional)
5. Click **"Create Teacher"**
6. Credentials displayed for sharing

#### Share Teacher Credentials:
1. In Teachers list, click **"Share"** button
2. Display shows:
   - Username
   - Password
   - Email
   - Phone
3. Use buttons to share via:
   - WhatsApp
   - Telegram
   - Email
   - Copy to clipboard

#### Edit Teacher Details:
1. Click **"Edit"** button
2. Update:
   - Display Name
   - Email
   - Phone
   - Active Status
3. Save changes

#### Delete Teacher:
1. Click **"Delete"** button
2. Confirm deletion
3. All associated data removed:
   - Students of teacher
   - Meetings hosted
   - Recordings
   - Transcripts

#### Reset Teacher Password:
1. Click **"Reset Password"**
2. Generate new password
3. Share with teacher

#### Toggle Teacher Status:
1. Click **"Block"** to deactivate
2. Click **"Activate"** to re-enable
3. Inactive teachers cannot login

### Dashboard Statistics:
- Total Teachers
- Active Teachers
- Total Students
- Total Meetings
- Live Meetings
- Total Recordings
- Pending Recording Cleanup

### Recording Cleanup:
1. Go to **"Cleanup"** page
2. View recordings pending deletion
3. Click **"Trigger Cleanup"** for manual cleanup
4. Automatic cleanup runs on schedule

---

## 👨‍🎓 For Students

### Joining a Meeting:
1. Login with credentials
2. Go to **"Join Meeting"**
3. Enter meeting code provided by teacher
4. Click **"Join"**

### Speaking and Recording:
1. **Unmute**: Click mic icon to speak
2. **Auto-recording**: Your speech recorded automatically
3. **Transcript**: Your speech transcribed in real-time
4. **Time markers**: Recorded with start/end time

### Viewing Transcripts:
1. In meeting, click **"Recordings"** tab
2. See your recordings
3. Expand to view transcript
4. Download recording if available

### After Meeting:
1. Check student dashboard
2. View all your recordings
3. Review transcripts
4. Download for study

---

## 🔧 Technical Integration

### WebSocket Transcript Flow:

**Client sends:**
```javascript
{
  "text": "What is photosynthesis?",
  "speakerName": "Student Name",
  "startTime": 120,
  "endTime": 125,
  "recordingId": 42
}
```

**Server broadcasts:**
```javascript
/topic/transcript/{meetingCode}
{
  "success": true,
  "transcriptId": 1,
  "userId": 5,
  "userName": "John Doe",
  "speakerName": "Student Name",
  "text": "What is photosynthesis?",
  "startTime": 120,
  "endTime": 125,
  "timestamp": "2026-04-22T17:30:00Z",
  "event": "transcript_created"
}
```

### API Endpoints:

**Get All Teachers:**
```
GET /api/admin/teachers
Response: { teachers: [...], total: N }
```

**Create Teacher:**
```
POST /api/admin/teachers
Body: {
  "teacherName": "John",
  "username": "john_doe",
  "password": "secure123",
  "displayName": "John Doe",
  "email": "john@example.com"
}
```

**Update Teacher:**
```
PUT /api/admin/teachers/{id}
Body: {
  "displayName": "John Smith",
  "email": "john.smith@example.com",
  "phone": "+1234567890",
  "active": true
}
```

**Delete Teacher:**
```
DELETE /api/admin/teachers/{id}
```

**Get Credentials:**
```
GET /api/admin/teachers/{id}/credentials
Response: {
  "username": "john_doe",
  "password": "secure123",
  "email": "john@example.com"
}
```

**Reset Password:**
```
POST /api/admin/teachers/{id}/reset-password
Body: { "newPassword": "newpass123" }
```

**Toggle Status:**
```
POST /api/admin/teachers/{id}/toggle-status
```

---

## 🐛 Troubleshooting

### Transcripts Not Appearing
- Check browser console for WebSocket errors
- Verify STOMP connection established
- Ensure meeting is active
- Check user authentication

### Port Already In Use
```bash
# Find process using port 8080
netstat -ano | findstr :8080

# Kill process (Windows)
taskkill /PID <PID> /F

# Or use different port
java -jar app.jar --server.port=8081
```

### Database Connection Issues
```bash
# Check H2 console
http://localhost:8080/h2-console

# Login with
Username: postgres
Password: postgres
JDBC URL: jdbc:h2:file:./data/meeting_db
```

### Admin Access Denied
- Verify user has ADMIN role
- Check database: SELECT * FROM users WHERE username = 'admin'
- Ensure Spring Security configuration correct

---

## 📊 Database Queries

### View Transcripts:
```sql
SELECT * FROM transcripts 
ORDER BY created_at DESC
LIMIT 20;
```

### View Recordings:
```sql
SELECT * FROM recordings
WHERE status != 'DELETED'
ORDER BY created_at DESC;
```

### View Teachers:
```sql
SELECT * FROM users
WHERE role = 'HOST'
ORDER BY created_at DESC;
```

### View Active Sessions:
```sql
SELECT * FROM meeting_participants
WHERE left_at IS NULL;
```

---

## 🔐 Security Notes

### Recording Consent
- Teachers must enable recording for each meeting
- Recording disabled by default
- Transcripts auto-generated from audio
- Combined audio captures teacher + student

### Data Privacy
- Recordings encrypted at rest
- Audio data secured in transit
- Access controlled by role-based permissions
- Transcripts searchable only by authorized users

### Admin Capabilities
- Can create/delete users
- Can view all recordings
- Can manage teacher credentials
- Can reset passwords
- Cannot edit student grades (if integrated)

---

## 📱 Mobile/Responsive Design

### Supported Devices
- ✅ Desktop (1920x1080+)
- ✅ Tablet (768px+)
- ✅ Mobile (320px+)

### Responsive Features
- Collapsed sidebar on mobile
- Vertical transcript panel layout
- Touch-friendly buttons
- Full-screen video option

---

## 🎯 Quick Start Checklist

- [ ] Start application: `java -jar app.jar --spring.profiles.active=dev`
- [ ] Login with test credentials
- [ ] Navigate to Terms & Conditions
- [ ] Admin: Create a test teacher
- [ ] Host: Start a meeting
- [ ] Student: Join meeting
- [ ] Teacher: Check Live Transcripts
- [ ] Verify recordings saved
- [ ] Archive transcript
- [ ] Check Transcripts tab

---

## 📞 Support

For issues or questions:
1. Check ENHANCEMENT_COMPLETE.md for technical details
2. Review application logs
3. Test with different browsers
4. Verify database connectivity
5. Contact system administrator

---

**Version**: 1.0
**Last Updated**: April 22, 2026
**Status**: Ready for Production Testing

