# Remaining Tasks Summary

## ✅ COMPLETED
1. **Fixed WhatsApp Error** - Added Twilio environment variable mappings to `application-prod.properties` so WhatsApp notifications are properly configured in production
2. **Removed Transcript Module (Partial)**:
   - ✅ Removed TranscriptService autowire from HostApiController & StudentApiController
   - ✅ Removed all transcript endpoints from API controllers
   - ✅ Removed transcript generation from RecordingService
   - ✅ Removed transcript references from UserService & AdminService
   - ✅ Removed TRANSCRIPT_READY from NotificationService method
   - ✅ Removed transcript tab from meeting/room.html
   - ✅ Removed transcript display from host/student-recordings.html
   - ✅ Removed transcript-manager.js script reference
   - ⏳ TODO: Delete Transcript.java model & TranscriptRepository.java (requires file deletion)

## 🔄 IN PROGRESS / TODO

### UI/UX Improvements

1. **Titles in Structured Table Rows**
   - First screenshot: Make titles spread uniquely in structured table format
   - Need to review layout and apply CSS grid/flexbox properly

2. **Micro-Level Controls for Participants**
   - Second screenshot: When user joins, add micro-level controls
   - Add icons for: speaker sound, chat, exit/kick button
   - Position as micro buttons next to each participant
   - Location: Each user in participant list should have action buttons

3. **Speaker Sorting in Participant List**
   - Track who has spoken in the meeting (via mic-toggle events)
   - Move speakers to the **last/bottom** of the participant list
   - Purpose: Single view for both waiting (at top) and speaking (at bottom)
   - Keep host always at the top
   - Order: [HOST] → [Non-speakers (new joins)] → [Speakers (who have spoken)]

## Implementation Details

### For Speaker Sorting:
```javascript
// Track speakers in host-room.js
const speakersInMeeting = new Set(); // userId set of who has spoken

// When mic-toggle event happens, add to speakers set:
if (data.event === 'mic-toggle' && data.micEnabled) {
    speakersInMeeting.add(String(data.userId));
    resortParticipantList();  // Move to bottom
}

// Function to re-sort the participant list
function resortParticipantList() {
    const list = document.getElementById('participantsList');
    // Sort: HOST stays first, then non-speakers, then speakers at bottom
}
```

### For Micro-Level Controls:
```html
<!-- Update participant item to include action buttons -->
<div class="participant-item student">
    <div class="participant-avatar"><i class="fas fa-user-graduate"></i></div>
    <div class="participant-info">
        <span class="participant-name">Student Name</span>
        <span class="participant-status-text">...</span>
    </div>
    <div class="participant-controls">
        <button class="control-micro" title="Mute/Unmute"><i class="fas fa-microphone"></i></button>
        <button class="control-chat" title="Send Message"><i class="fas fa-comment"></i></button>
        <button class="control-exit" title="Remove"><i class="fas fa-times-circle"></i></button>
    </div>
</div>
```

## Files Modified
- ✅ `application-prod.properties` - Added Twilio config
- ✅ `HostApiController.java` - Removed transcript endpoints
- ✅ `StudentApiController.java` - Removed transcript endpoints
- ✅ `RecordingService.java` - Removed transcript generation
- ✅ `UserService.java` - Removed transcript deletion logic
- ✅ `AdminService.java` - Updated comment
- ✅ `NotificationService.java` - Removed transcript notification
- ✅ `NotificationType.java` - Removed TRANSCRIPT_READY
- ✅ `meeting/room.html` - Removed transcript tab
- ✅ `host/student-recordings.html` - Removed transcript display
- 🔄 `host-room.js` - TODO: Add speaker tracking & sorting
- ⏳ `Transcript.java` - TODO: Delete model file
- ⏳ `TranscriptRepository.java` - TODO: Delete repository file

## Current Status
- WhatsApp fix: ✅ COMPLETE
- Transcript removal: ~90% COMPLETE (core logic removed, files remain)
- UI improvements: ⏳ PENDING
- Speaker sorting: ⏳ PENDING
- Micro controls: ⏳ PENDING

**Date**: June 12, 2026
**Priority**: High - UI improvements for UX are critical

