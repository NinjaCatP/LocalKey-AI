package com.jason.aikeyboard;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import com.jason.aikeyboard.core.UserModel;

import java.io.File;
import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** One personal model per process, shared by the keyboard service and the settings screen. App-private storage only. */
public final class Learned {
    private static UserModel model; private static UserModel.Codec codec;
    public static final String FILE = "learned_words.tsv", PHRASES = "recent_phrases.bin";

    public static synchronized UserModel get(Context c) {
        if (model == null) { model = new UserModel(); model.load(file(c), phraseFile(c), codec()); }
        return model;
    }
    static File file(Context c) { return new File(c.getFilesDir(), FILE); }
    static File phraseFile(Context c) { return new File(c.getFilesDir(), PHRASES); }
    public static synchronized void save(Context c) {
        if (model == null) return;
        try { model.save(file(c), phraseFile(c), codec()); } catch (Exception e) { Log.w("AIKeyboard", "save learned data failed", e); }
    }
    public static synchronized void clearAll(Context c) {
        get(c).clear();
        file(c).delete(); phraseFile(c).delete(); new File(c.getFilesDir(), FILE + ".tmp").delete(); new File(c.getFilesDir(), PHRASES + ".tmp").delete();
    }
    /** Tests only: simulate a process restart. */
    static synchronized void resetForTests() { model = null; }
    /** Tests only: Robolectric has no AndroidKeyStore. */
    static synchronized void setCodecForTests(UserModel.Codec c) { codec = c; }

    static synchronized UserModel.Codec codec() {
        if (codec == null) {
            try { codec = new KeystoreCodec(); }
            catch (Throwable t) { Log.w("AIKeyboard", "Keystore unavailable, recent phrases kept in memory only", t); codec = NO_PERSIST; }
        }
        return codec;
    }
    /** If the keystore can't be used, never write phrases to disk in plain text. */
    static final UserModel.Codec NO_PERSIST = new UserModel.Codec() {
        public byte[] encode(byte[] p) { return new byte[0]; }
        public byte[] decode(byte[] s) { return new byte[0]; }
    };

    /** AES-256-GCM with a non-exportable key in Android Keystore. File = 12-byte IV + ciphertext. */
    static final class KeystoreCodec implements UserModel.Codec {
        private static final String ALIAS = "aik_phrases_v3";
        private final SecretKey key;
        KeystoreCodec() throws Exception {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
                g.generateKey();
            }
            key = (SecretKey) ks.getKey(ALIAS, null);
            if (key == null) throw new IllegalStateException("no key");
        }
        public byte[] encode(byte[] plain) throws Exception {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = c.getIV(), ct = c.doFinal(plain);
            return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
        }
        public byte[] decode(byte[] s) throws Exception {
            if (s.length < 13) return new byte[0];
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, s, 0, 12));
            return c.doFinal(s, 12, s.length - 12);
        }
    }
}
