const EventEmitter = require("events");
const twilio = require("twilio");

/**
 * Wait helper used by retry logic.
 * @param {number} milliseconds - Time to wait before retrying.
 * @returns {Promise<void>}
 */
function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

class NotificationService extends EventEmitter {
  /**
   * Creates a NotificationService instance.
   *
   * Configuration notes:
   * - apiKey is your Twilio Auth Token and is required to authenticate requests.
   * - accountSid is your Twilio Account SID.
   * - fromPhoneNumber is your Twilio WhatsApp-enabled sender number,
   *   usually in the format "whatsapp:+14155238886".
   * - maxAttempts controls retry count for temporary failures.
   * - retryDelayMs controls wait time between retry attempts.
   *
   * @param {Object} options - Service configuration object.
   * @param {string} options.accountSid - Twilio Account SID.
   * @param {string} options.apiKey - Twilio Auth Token used for API authentication.
   * @param {string} options.fromPhoneNumber - Twilio WhatsApp sender number.
   * @param {number} [options.maxAttempts=3] - Maximum retry attempts.
   * @param {number} [options.retryDelayMs=2000] - Delay between retries in milliseconds.
   */
  constructor({
    accountSid,
    apiKey,
    fromPhoneNumber,
    maxAttempts = 3,
    retryDelayMs = 2000,
  }) {
    super();
    this.maxAttempts = maxAttempts;
    this.retryDelayMs = retryDelayMs;
    this.fromPhoneNumber = fromPhoneNumber;
    this.twilioClient = twilio(accountSid, apiKey);

    this.on("send-recording-notification", async ({ phoneNumber, recordingUrl }) => {
      try {
        await this.sendWithRetry(phoneNumber, recordingUrl);
      } catch (error) {
        console.error("[NotificationService] Background send failed:", error.message);
      }
    });
  }

  /**
   * Queues a recording notification to be sent in the background.
   *
   * This method is intentionally non-blocking: it emits an internal event and
   * returns immediately, allowing API routes to respond to users without waiting
   * for external WhatsApp API calls.
   *
   * phoneNumber should be in WhatsApp format, for example: "whatsapp:+919999999999".
   *
   * @param {string} phoneNumber - Recipient WhatsApp number.
   * @param {string} recordingUrl - Public recording URL that will be shared.
   */
  sendRecordingNotification(phoneNumber, recordingUrl) {
    this.emit("send-recording-notification", { phoneNumber, recordingUrl });
  }

  /**
   * Attempts to send a WhatsApp message with retry support.
   * @private
   * @param {string} phoneNumber - Recipient WhatsApp number.
   * @param {string} recordingUrl - Public recording URL.
   * @returns {Promise<void>}
   */
  async sendWithRetry(phoneNumber, recordingUrl) {
    for (let attempt = 1; attempt <= this.maxAttempts; attempt += 1) {
      try {
        const messageBody =
          `Hello! Your meeting recording is ready.\n` +
          `Watch or download it here: ${recordingUrl}`;

        await this.twilioClient.messages.create({
          from: this.fromPhoneNumber,
          to: phoneNumber,
          body: messageBody,
        });

        console.log(
          `[NotificationService] WhatsApp notification sent successfully to ${phoneNumber} on attempt ${attempt}.`
        );
        return;
      } catch (error) {
        const isLastAttempt = attempt === this.maxAttempts;
        console.error(
          `[NotificationService] Attempt ${attempt}/${this.maxAttempts} failed for ${phoneNumber}:`,
          error.message
        );

        if (isLastAttempt) {
          throw error;
        }
        await delay(this.retryDelayMs);
      }
    }
  }
}

module.exports = NotificationService;
