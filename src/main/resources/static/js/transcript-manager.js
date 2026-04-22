/**
 * Live Transcripts Handler for Meeting Control Tab
 * Handles real-time transcript display and archiving
 */

const TranscriptManager = {
    transcripts: [],
    currentGroupId: 0,
    maxTranscriptsDisplay: 20,
    stompClient: null,

    /**
     * Initialize transcript manager
     */
    init: function(stompClient) {
        this.stompClient = stompClient;
        console.log('TranscriptManager initialized');
    },

    /**
     * Subscribe to live transcript updates
     */
    subscribe: function(meetingCode) {
        if (!this.stompClient || !this.stompClient.connected) {
            console.warn('STOMP client not connected, cannot subscribe to transcripts');
            return;
        }

        this.stompClient.subscribe('/topic/transcript/' + meetingCode, (message) => {
            try {
                const transcriptData = JSON.parse(message.body);
                if (transcriptData.success) {
                    this.addTranscript(transcriptData);
                }
            } catch (e) {
                console.error('Error parsing transcript message:', e);
            }
        });

        console.log('Subscribed to transcript updates for meeting:', meetingCode);
    },

    /**
     * Add a new transcript to the display
     */
    addTranscript: function(transcriptData) {
        const transcriptContainer = document.getElementById('liveTranscriptsList');
        const emptyState = document.getElementById('transcriptsEmpty');

        // Hide empty state if showing
        if (emptyState && emptyState.style.display !== 'none') {
            emptyState.style.display = 'none';
        }

        // Create transcript group element
        const groupId = 'transcript-group-' + this.currentGroupId;
        this.currentGroupId++;

        const groupElement = document.createElement('div');
        groupElement.id = groupId;
        groupElement.className = 'transcript-message-group';
        groupElement.innerHTML = `
            <div class="transcript-speaker">
                <i class="fas fa-microphone"></i> ${this.escapeHtml(transcriptData.speakerName || 'Unknown')}
            </div>
            <div class="transcript-text">
                ${this.escapeHtml(transcriptData.text)}
            </div>
            <div class="transcript-time">
                ${this.formatTime(transcriptData.startTime)} - ${this.formatTime(transcriptData.endTime)}
            </div>
            <div class="transcript-actions">
                <button class="transcript-ok-btn" onclick="TranscriptManager.archiveTranscript('${groupId}', ${transcriptData.transcriptId})">
                    <i class="fas fa-check"></i> Okay
                </button>
            </div>
        `;

        // Add to container
        if (transcriptContainer) {
            transcriptContainer.appendChild(groupElement);

            // Keep only recent transcripts displayed
            if (transcriptContainer.children.length > this.maxTranscriptsDisplay) {
                const oldestChild = transcriptContainer.children[0];
                oldestChild.remove();
            }

            // Auto scroll to bottom
            const parent = transcriptContainer.parentElement;
            if (parent) {
                parent.scrollTop = parent.scrollHeight;
            }
        }

        // Add to transcripts array
        this.transcripts.push({
            id: transcriptData.transcriptId,
            groupId: groupId,
            speaker: transcriptData.speakerName,
            text: transcriptData.text,
            startTime: transcriptData.startTime,
            endTime: transcriptData.endTime,
            timestamp: new Date(transcriptData.timestamp)
        });
    },

    /**
     * Archive a transcript to transcripts tab
     */
    archiveTranscript: function(groupId, transcriptId) {
        const groupElement = document.getElementById(groupId);
        const archivedContainer = document.getElementById('archivedTranscriptsList');
        const archivedEmpty = document.getElementById('archivedTranscriptsEmpty');

        // Add archived state
        if (groupElement) {
            groupElement.classList.add('archived');

            // Disable button
            const btn = groupElement.querySelector('.transcript-ok-btn');
            if (btn) {
                btn.disabled = true;
                btn.innerHTML = '<i class="fas fa-check-circle"></i> Archived';
                btn.style.background = 'rgba(34, 197, 94, 0.4)';
            }

            if (archivedContainer) {
                if (archivedEmpty) archivedEmpty.style.display = 'none';
                archivedContainer.insertBefore(groupElement, archivedContainer.firstChild);
            }
        }

        // Show empty state if no live transcripts
        const liveContainer = document.getElementById('liveTranscriptsList');
        if (liveContainer && liveContainer.children.length === 0) {
            const emptyState = document.getElementById('transcriptsEmpty');
            if (emptyState) emptyState.style.display = 'flex';
        }

        // Remove from array
        this.transcripts = this.transcripts.filter(t => t.id !== transcriptId);

        // Show success notification
        this.showNotification('Transcript archived', 'success');
    },

    /**
     * Format time in seconds to MM:SS
     */
    formatTime: function(seconds) {
        if (seconds === undefined || seconds === null) return '0:00';
        const mins = Math.floor(seconds / 60);
        const secs = seconds % 60;
        return `${mins}:${secs.toString().padStart(2, '0')}`;
    },

    /**
     * Escape HTML special characters
     */
    escapeHtml: function(text) {
        if (!text) return '';
        const map = {
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#039;'
        };
        return text.replace(/[&<>"']/g, m => map[m]);
    },

    /**
     * Show notification
     */
    showNotification: function(message, type = 'info') {
        const notification = document.createElement('div');
        notification.className = `room-toast room-toast-${type}`;
        notification.style.cssText = `
            position: fixed;
            top: 80px;
            right: 20px;
            z-index: 9999;
            background: var(--bg-secondary);
            border: 1px solid var(--border-color);
            border-radius: 14px;
            padding: 14px 18px;
            color: white;
            animation: toastSlideIn 0.3s ease;
        `;

        const icon = type === 'success' ? 'check-circle' : 'info-circle';
        notification.innerHTML = `
            <div style="display: flex; gap: 10px; align-items: center;">
                <i class="fas fa-${icon}"></i>
                <span>${message}</span>
            </div>
        `;

        document.body.appendChild(notification);

        setTimeout(() => {
            notification.style.transition = 'opacity 0.3s ease';
            notification.style.opacity = '0';
            setTimeout(() => notification.remove(), 300);
        }, 3000);
    },

    /**
     * Get all transcripts for current session
     */
    getAllTranscripts: function() {
        return this.transcripts;
    },

    /**
     * Export transcripts as JSON
     */
    exportTranscripts: function() {
        const data = JSON.stringify(this.transcripts, null, 2);
        const blob = new Blob([data], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `transcripts-${new Date().toISOString().slice(0, 10)}.json`;
        a.click();
        URL.revokeObjectURL(url);
    },

    /**
     * Clear all transcripts
     */
    clearAll: function() {
        if (confirm('Are you sure you want to clear all transcripts?')) {
            this.transcripts = [];
            const container = document.getElementById('liveTranscriptsList');
            if (container) {
                container.innerHTML = '';
                const emptyState = document.getElementById('transcriptsEmpty');
                if (emptyState) emptyState.style.display = 'flex';
            }
            this.showNotification('All transcripts cleared', 'info');
        }
    }
};

// Add CSS animation
const style = document.createElement('style');
style.textContent = `
@keyframes slideOutTranscript {
    from {
        opacity: 1;
        transform: translateX(0);
    }
    to {
        opacity: 0;
        transform: translateX(10px);
    }
}
`;
document.head.appendChild(style);

// Auto-initialize when script loads
document.addEventListener('DOMContentLoaded', () => {
    console.log('Transcript Manager loaded');
});

