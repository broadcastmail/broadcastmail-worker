package com.broadcastmail.worker.common;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class SecurityUtil {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int GCM_IV_LENGTH = 12; // optimal for internal counter
    private static final int GCM_TAG_LENGTH = 128; //  maximum tag length
    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final String API_KEY_PREFIX = "bm_live_";
    private static final int API_KEY_RANDOM_BYTES = 32; // 256 bits of entropy


    private SecurityUtil(){}
    public static String generateApiKey(){
        byte[] bytes = new byte[API_KEY_RANDOM_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return API_KEY_PREFIX +
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String sha256(String input){
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }
        catch (Exception e) {
            throw new RuntimeException("SHA-256 hashing failed",e);
        }
    }
    public static String encrypt(String plainText, String masterKey){
        try{
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    buildKey(masterKey),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv)
            );
            byte[] cipherText = cipher.doFinal(
                 plainText.getBytes(StandardCharsets.UTF_8)
            );

            byte[] combined  = new byte[iv.length + cipherText.length];
            System.arraycopy(iv,0,combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(combined);
        }
        catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }public static String decrypt(String cipherText, String masterKey){
        try{
            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    buildKey(masterKey),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv)
            );
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public static String generatePassword() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static SecretKeySpec buildKey(String masterKey) {
        // the master key must be exactly 32 chars (256 bits) for AES-256
        byte[] keyBytes = masterKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "Encryption key must be exactly 32 characters, got: " + keyBytes.length
            );
        }
        return new SecretKeySpec(keyBytes, "AES");
    }


}
