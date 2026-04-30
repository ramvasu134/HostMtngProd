const EventEmitter = require("events");
const twilio = require("twilio");

/**
 * Delay helper for retry logic.
 * @param {number} milliseconds - Wait time before next retry.
 * @returns {Promise<void>}
 */
function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

class NotificationService extends EventEmitter {
  /**
   * Create a NotificationService.
   *
   * Environment mapping:
   * - api_key: Set this to your Twilio Auth Token.
   * - phone_number: Set this to your Twilio WhatsApp sender number
   *   (example: "whatsapp:+14155238886").
   *
   * @param {Object} config - Service configuration.
   * @param {string} config.accountSid - Twilio account SID.
   * @param {string} config.apiKey - Twilio auth token (api_key).
   * @param {string} config.phoneNumber - Twilio WhatsApp sender number (phone_number).
   * @param {number} [config.maxRetries=3] - Max retry attempts.
   * @param {number} [config.retryDelayMs=2000] - Delay between retries.
   */
  constructor({ accountSid, apiKey, phoneNumber, maxRetries = 3, retryDelayMs = 2000 }) {
    super();
    this.maxRetries = maxRetries;
    this.retryDelayMs = retryDelayMs;
    this.twilioSenderNumber = phoneNumber;
    this.isConfigured = Boolean(accountSid && apiKey && phoneNumber);
    this.twilioClient = this.isConfigured ? twilio(accountSid, apiKey) : null;

    if (!this.isConfigured) {
      console.warn(
        "[NotificationService] Twilio is not fully configured. Service will run, but WhatsApp sends will be skipped."
      );
    }

    this.on("notify", async ({ recipientPhoneNumber, recordingUrl }) => {
      try {
        await this.sendWithRetry(recipientPhoneNumber, recordingUrl);
      } catch (error) {
        console.error("[NotificationService] Final failure after retries:", error.message);
      }
    });
  }

  /**
   * Trigger WhatsApp send in background (non-blocking).
   *
   * This method emits an internal event and returns immediately,
   * so API handlers can reply instantly.
   *
   * @param {string} phoneNumber - Recipient WhatsApp number (example: "whatsapp:+919999999999").
   * @param {string} recordingUrl - Public recording URL.
   * @returns {void}
   */
  sendWhatsApp(phoneNumber, recordingUrl) {
    this.emit("notify", {
      recipientPhoneNumber: phoneNumber,
      recordingUrl,
    });
  }

  /**
   * Send WhatsApp message with retry handling.
   * @private
   * @param {string} recipientPhoneNumber - Recipient WhatsApp number.
   * @param {string} recordingUrl - Recording URL.
   * @returns {Promise<void>}
   */
  async sendWithRetry(recipientPhoneNumber, recordingUrl) {
    if (!this.isConfigured) {
      console.warn(
        `[NotificationService] Skipping send for ${recipientPhoneNumber} because Twilio credentials are missing.`
      );
      return;
    }

    for (let attempt = 1; attempt <= this.maxRetries; attempt += 1) {
      try {
        const messageText =
          "Your recording is ready.\n" +
          `You can watch or download it here: ${recordingUrl}`;

        await this.twilioClient.messages.create({
          from: this.twilioSenderNumber,
          to: recipientPhoneNumber,
          body: messageText,
        });

        console.log(
          `[NotificationService] WhatsApp sent to ${recipientPhoneNumber} on attempt ${attempt}.`
        );
        return;
      } catch (error) {
        console.error(
          `[NotificationService] Attempt ${attempt}/${this.maxRetries} failed for ${recipientPhoneNumber}: ${error.message}`
        );

        if (attempt === this.maxRetries) {
          throw error;
        }

        await sleep(this.retryDelayMs);
      }
    }
  }
}

module.exports = NotificationService;
