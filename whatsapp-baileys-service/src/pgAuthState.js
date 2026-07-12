import { initAuthCreds, BufferJSON, proto } from '@whiskeysockets/baileys';

/**
 * Postgres-backed replacement for Baileys' `useMultiFileAuthState`.
 *
 * Render's free/starter web services have EPHEMERAL disks — every redeploy
 * or restart wipes local files, which would otherwise force re-scanning the
 * QR code every single time. Persisting the auth state (credentials + signal
 * keys) in the same Postgres database the Java app already uses keeps this
 * add-on at $0 cost while surviving restarts/redeploys.
 *
 * Storage model: one row per (session_id, key_name) with a JSON blob value,
 * using Baileys' own `BufferJSON` replacer/reviver so Buffers round-trip
 * correctly through JSON/JSONB.
 */
/** Wipes all stored credentials/keys for a session — used by the manual "unlink" flow so a fresh QR can be issued without waiting for WhatsApp to notice a stale session. */
export async function clearAuthState(pool, sessionId = 'default') {
    await pool.query('DELETE FROM baileys_auth_state WHERE session_id = $1', [sessionId]);
}

export async function usePostgresAuthState(pool, sessionId = 'default') {
    await pool.query(`
        CREATE TABLE IF NOT EXISTS baileys_auth_state (
            session_id TEXT NOT NULL,
            key_name   TEXT NOT NULL,
            value      TEXT,
            updated_at TIMESTAMP NOT NULL DEFAULT now(),
            PRIMARY KEY (session_id, key_name)
        )
    `);

    const readData = async (keyName) => {
        const { rows } = await pool.query(
            'SELECT value FROM baileys_auth_state WHERE session_id = $1 AND key_name = $2',
            [sessionId, keyName]
        );
        if (!rows.length || rows[0].value == null) return null;
        try {
            return JSON.parse(rows[0].value, BufferJSON.reviver);
        } catch {
            return null;
        }
    };

    const writeData = async (keyName, value) => {
        const json = JSON.stringify(value, BufferJSON.replacer);
        await pool.query(
            `INSERT INTO baileys_auth_state (session_id, key_name, value, updated_at)
             VALUES ($1, $2, $3, now())
             ON CONFLICT (session_id, key_name) DO UPDATE SET value = $3, updated_at = now()`,
            [sessionId, keyName, json]
        );
    };

    const removeData = async (keyName) => {
        await pool.query(
            'DELETE FROM baileys_auth_state WHERE session_id = $1 AND key_name = $2',
            [sessionId, keyName]
        );
    };

    const creds = (await readData('creds')) || initAuthCreds();

    return {
        state: {
            creds,
            keys: {
                get: async (type, ids) => {
                    const data = {};
                    await Promise.all(
                        ids.map(async (id) => {
                            let value = await readData(`${type}-${id}`);
                            if (type === 'app-state-sync-key' && value) {
                                value = proto.Message.AppStateSyncKeyData.fromObject(value);
                            }
                            data[id] = value;
                        })
                    );
                    return data;
                },
                set: async (data) => {
                    const tasks = [];
                    for (const category of Object.keys(data)) {
                        for (const id of Object.keys(data[category])) {
                            const value = data[category][id];
                            const keyName = `${category}-${id}`;
                            tasks.push(value ? writeData(keyName, value) : removeData(keyName));
                        }
                    }
                    await Promise.all(tasks);
                },
            },
        },
        saveCreds: async () => {
            await writeData('creds', creds);
        },
    };
}
