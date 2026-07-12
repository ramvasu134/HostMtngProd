import ffmpegPath from '@ffmpeg-installer/ffmpeg';
import ffmpeg from 'fluent-ffmpeg';
import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { randomUUID } from 'crypto';

ffmpeg.setFfmpegPath(ffmpegPath.path);

/**
 * The recording pipeline captures audio client-side with the browser's
 * MediaRecorder API, which produces WebM (Opus) — great for the web player,
 * but WhatsApp's mobile client cannot reliably play a raw WebM container as
 * an audio-message attachment (even though the Opus codec inside is the
 * same one WhatsApp itself uses for voice notes). This shows up as
 * "Sorry, can't load this audio now" on the recipient's phone.
 *
 * Re-encoding to Ogg/Opus — WhatsApp's own native voice-note format — fixes
 * playback. A straight container remux (`-c:a copy`) would be cheaper, but
 * re-encoding is used instead so this works regardless of the *source*
 * container/codec (webm, m4a, mp3, wav, ...), not just webm/opus.
 *
 * @param {Buffer} inputBuffer raw audio bytes as downloaded from the recording URL
 * @returns {Promise<Buffer>} Ogg/Opus-encoded audio bytes
 */
export async function convertToOggOpus(inputBuffer) {
    const tmpDir = os.tmpdir();
    const id = randomUUID();
    const inputPath = path.join(tmpDir, `wa-in-${id}`);
    const outputPath = path.join(tmpDir, `wa-out-${id}.ogg`);

    await fs.writeFile(inputPath, inputBuffer);

    try {
        await new Promise((resolve, reject) => {
            ffmpeg(inputPath)
                .noVideo()
                .audioCodec('libopus')
                .audioChannels(1)
                .audioBitrate('32k')
                .format('ogg')
                .on('error', reject)
                .on('end', resolve)
                .save(outputPath);
        });
        return await fs.readFile(outputPath);
    } finally {
        await fs.unlink(inputPath).catch(() => {});
        await fs.unlink(outputPath).catch(() => {});
    }
}
