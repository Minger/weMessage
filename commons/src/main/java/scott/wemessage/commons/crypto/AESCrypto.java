/*
 * Copyright (c) 2014-2015 Tozny LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * Created by Isaac Potoczny-Jones on 11/12/14.
 */

package scott.wemessage.commons.crypto;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import scott.wemessage.commons.Constants;

/**
 * Simple library for the "right" defaults for AES key generation, encryption,
 * and decryption using 128-bit AES, CBC, PKCS5 padding, and a random 16-byte IV
 * with SHA1PRNG. Integrity with HmacSHA256.
 *
 * Modified by Roman Scott to support native Java and the weMessage App
 */

public class AESCrypto {

    private static Base64Wrapper base64Wrapper;
    private static final String CIPHER_TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final String CIPHER = "AES";
    private static final int AES_KEY_LENGTH_BITS = 128;
    private static final int IV_LENGTH_BYTES = 16;
    private static final int PBE_ITERATION_COUNT = 10000;
    private static final int PBE_SALT_LENGTH_BITS = AES_KEY_LENGTH_BITS; // same size as key output
    private static final String PBE_ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int HMAC_KEY_LENGTH_BITS = 256;

    static final AtomicBoolean prngFixed = new AtomicBoolean(false);
    static final AtomicBoolean checkMemoryAvailability = new AtomicBoolean(false);

    /**
     * Sets the Base64 Wrapper class so the proper Base64 methods are called for each perspective variation of Java
     * If this is not set, every method will throw a Null Pointer Exception
     *
     * @param wrapper The Base64 Wrapper
     */
    public static void setBase64Wrapper(Base64Wrapper wrapper){
        if (base64Wrapper == null) {
            base64Wrapper = wrapper;
        }
    }

    /**
     * Sets whether or not memory checks should be performed during encryption or decryption processes
     * to prevent Out of Memory Errors
     *
     * @param checkAvailability Whether or not the check should be enabled
     */

    public static void setMemoryAvailabilityCheck(boolean checkAvailability){
        checkMemoryAvailability.set(checkAvailability);
    }

    /**
     * Converts the given AES/HMAC keys into a base64 encoded string suitable for
     * storage. Sister function of keys.
     *
     * @param keys The combined aes and hmac keys
     * @return a base 64 encoded AES string & hmac key as base64(aesKey) : base64(hmacKey)
     */
    public static String keysToString(SecretKeys keys) {
        return keys.toString();
    }

    /**
     * An aes key derived from a base64 encoded key. This does not generate the
     * key. It's not random or a PBE key.
     *
     * @param keysStr a base64 encoded AES key / hmac key as base64(aesKey) : base64(hmacKey).
     * @return an AES & HMAC key set suitable for other functions.
     */
    public static SecretKeys stringToKeys(String keysStr) throws InvalidKeyException {
        String[] keysArr = keysStr.split(":");

        if (keysArr.length != 2) {
            throw new IllegalArgumentException("Cannot parse aesKey:hmacKey");

        } else {
            byte[] confidentialityKey = base64Wrapper.decodeString(keysArr[0]);
            if (confidentialityKey.length != AES_KEY_LENGTH_BITS /8) {
                throw new InvalidKeyException("Base64 decoded key is not " + AES_KEY_LENGTH_BITS + " bytes");
            }
            byte[] integrityKey = base64Wrapper.decodeString(keysArr[1]);
            if (integrityKey.length != HMAC_KEY_LENGTH_BITS /8) {
                throw new InvalidKeyException("Base64 decoded key is not " + HMAC_KEY_LENGTH_BITS + " bytes");
            }

            return new SecretKeys(
                    new SecretKeySpec(confidentialityKey, 0, confidentialityKey.length, CIPHER),
                    new SecretKeySpec(integrityKey, HMAC_ALGORITHM));
        }
    }

    /**
     * A function that generates random AES & HMAC keys and prints out exceptions but
     * doesn't throw them since none should be encountered. If they are
     * encountered, the return value is null.
     *
     * @return The AES & HMAC keys.
     * @throws GeneralSecurityException if AES is not implemented on this system,
     *                                  or a suitable RNG is not available
     */
    public static SecretKeys generateKeys() throws GeneralSecurityException {
        fixPrng();
        KeyGenerator keyGen = KeyGenerator.getInstance(CIPHER);
        // No need to provide a SecureRandom or set a seed since that will
        // happen automatically.
        keyGen.init(AES_KEY_LENGTH_BITS);
        SecretKey confidentialityKey = keyGen.generateKey();

        //Now make the HMAC key
        byte[] integrityKeyBytes = randomBytes(HMAC_KEY_LENGTH_BITS / 8);//to get bytes
        SecretKey integrityKey = new SecretKeySpec(integrityKeyBytes, HMAC_ALGORITHM);

        return new SecretKeys(confidentialityKey, integrityKey);
    }

    /**
     * A function that generates password-based AES & HMAC keys. It prints out exceptions but
     * doesn't throw them since none should be encountered. If they are
     * encountered, the return value is null.
     *
     * @param password The password to derive the keys from.
     * @return The AES & HMAC keys.
     * @throws GeneralSecurityException if AES is not implemented on this system,
     *                                  or a suitable RNG is not available
     */
    public static SecretKeys generateKeyFromPassword(String password, byte[] salt) throws GeneralSecurityException {
        fixPrng();
        //Get enough random bytes for both the AES key and the HMAC key:
        KeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt,
                PBE_ITERATION_COUNT, AES_KEY_LENGTH_BITS + HMAC_KEY_LENGTH_BITS);
        SecretKeyFactory keyFactory = SecretKeyFactory
                .getInstance(PBE_ALGORITHM);
        byte[] keyBytes = keyFactory.generateSecret(keySpec).getEncoded();

        // Split the random bytes into two parts:
        byte[] confidentialityKeyBytes = copyOfRange(keyBytes, 0, AES_KEY_LENGTH_BITS /8);
        byte[] integrityKeyBytes = copyOfRange(keyBytes, AES_KEY_LENGTH_BITS /8, AES_KEY_LENGTH_BITS /8 + HMAC_KEY_LENGTH_BITS /8);

        //Generate the AES key
        SecretKey confidentialityKey = new SecretKeySpec(confidentialityKeyBytes, CIPHER);

        //Generate the HMAC key
        SecretKey integrityKey = new SecretKeySpec(integrityKeyBytes, HMAC_ALGORITHM);

        return new SecretKeys(confidentialityKey, integrityKey);
    }

    /**
     * A function that generates password-based AES & HMAC keys. See generateKeyFromPassword.
     * @param password The password to derive the AES/HMAC keys from
     * @param salt A string version of the salt; base64 encoded.
     * @return The AES & HMAC keys.
     * @throws GeneralSecurityException
     */
    public static SecretKeys generateKeyFromPassword(String password, String salt) throws GeneralSecurityException {
        return generateKeyFromPassword(password, base64Wrapper.decodeString(salt));
    }

    /**
     * Generates a random salt.
     * @return The random salt suitable for generateKeyFromPassword.
     */
    public static byte[] generateSalt() throws GeneralSecurityException {
        return randomBytes(PBE_SALT_LENGTH_BITS);
    }

    /**
     * Converts the given salt into a base64 encoded string suitable for
     * storage.
     *
     * @param salt
     * @return a base 64 encoded salt string suitable to pass into generateKeyFromPassword.
     */
    public static String saltString(byte[] salt) {
        return base64Wrapper.encodeToString(salt);
    }

    /**
     * Creates a random Initialization Vector (IV) of IV_LENGTH_BYTES.
     *
     * @return The byte array of this IV
     * @throws GeneralSecurityException if a suitable RNG is not available
     */
    public static byte[] generateIv() throws GeneralSecurityException {
        return randomBytes(IV_LENGTH_BYTES);
    }

    private static byte[] randomBytes(int length) throws GeneralSecurityException {
        fixPrng();
        SecureRandom random = new SecureRandom();
        byte[] b = new byte[length];
        random.nextBytes(b);
        return b;
    }

    /*
     * -----------------------------------------------------------------
     * Encryption
     * -----------------------------------------------------------------
     */

    /**
     * Generates a random IV and encrypts this plain text with the given key. Then attaches
     * a hashed MAC, which is contained in the CipherTextIvMac class.
     *
     * @param plaintext The text that will be encrypted, which
     *                  will be serialized with UTF-8
     * @param secretKeys The AES & HMAC keys with which to encrypt
     * @return a tuple of the IV, ciphertext, mac
     * @throws GeneralSecurityException if AES is not implemented on this system
     * @throws UnsupportedEncodingException if UTF-8 is not supported in this system
     */
    public static CipherTextIvMac encrypt(String plaintext, SecretKeys secretKeys)
            throws UnsupportedEncodingException, GeneralSecurityException {
        return encrypt(plaintext, secretKeys, "UTF-8");
    }

    /**
     * Generates a random IV and encrypts this plain text with the given key. Then attaches
     * a hashed MAC, which is contained in the CipherTextIvMac class, and is then converted to a String
     *
     * @param plaintext The text that will be encrypted, which
     *                  will be serialized with UTF-8
     * @param secretKeys The AES & HMAC keys with which to encrypt
     * @return a tuple of the IV, ciphertext, and mac as a string
     * @throws GeneralSecurityException if AES is not implemented on this system
     * @throws UnsupportedEncodingException if UTF-8 is not supported in this system
     */
    public static String encryptString(String plaintext, SecretKeys secretKeys)
            throws UnsupportedEncodingException, GeneralSecurityException {
        return encrypt(plaintext, secretKeys).toString();
    }

    /**
     * Generates a random IV and encrypts this plain text with the given key. Then attaches
     * a hashed MAC, which is contained in the CipherTextIvMac class, and is then converted to a String
     *
     * @param plaintext The text that will be encrypted, which
     *                  will be serialized with UTF-8
     * @param secretKeys The AES & HMAC keys with which to encrypt, as a string
     * @return a tuple of the IV, ciphertext, and mac as a string
     * @throws GeneralSecurityException if AES is not implemented on this system
     * @throws UnsupportedEncodingException if UTF-8 is not supported in this system
     */
    public static String encryptString(String plaintext, String secretKeys)
            throws UnsupportedEncodingException, GeneralSecurityException {
        return encrypt(plaintext, stringToKeys(secretKeys)).toString();
    }

    /**
     * Generates a random IV and encrypts this plain text with the given key. Then attaches
     * a hashed MAC, which is contained in the CipherTextIvMac class.
     *
     * @param plaintext The bytes that will be encrypted
     * @param secretKeys The AES & HMAC keys with which to encrypt
     * @return a tuple of the IV, ciphertext, mac
     * @throws GeneralSecurityException if AES is not implemented on this system
     * @throws UnsupportedEncodingException if the specified encoding is invalid
     */
    public static CipherTextIvMac encrypt(String plaintext, SecretKeys secretKeys, String encoding)
            throws UnsupportedEncodingException, GeneralSecurityException {
        return encrypt(plaintext.getBytes(encoding), secretKeys);
    }

    /**
     * Generates a random IV and encrypts this plain text with the given key. Then attaches
     * a hashed MAC, which is contained in the CipherTextIvMac class.
     *
     * @param plaintext The text that will be encrypted
     * @param secretKeys The combined AES & HMAC keys with which to encrypt
     * @return a tuple of the IV, ciphertext, mac
     * @throws GeneralSecurityException if AES is not implemented on this system
     */
    public static CipherTextIvMac encrypt(byte[] plaintext, SecretKeys secretKeys)
            throws GeneralSecurityException {
        byte[] iv = generateIv();
        Cipher aesCipherForEncryption = Cipher.getInstance(CIPHER_TRANSFORMATION);
        aesCipherForEncryption.init(Cipher.ENCRYPT_MODE, secretKeys.getConfidentialityKey(), new IvParameterSpec(iv));

        /*
         * Now we get back the IV that will actually be used. Some Android
         * versions do funny stuff w/ the IV, so this is to work around bugs:
         */
        iv = aesCipherForEncryption.getIV();
        byte[] byteCipherText = aesCipherForEncryption.doFinal(plaintext);
        byte[] ivCipherConcat = CipherTextIvMac.ivCipherConcat(iv, byteCipherText);

        byte[] integrityMac = generateMac(ivCipherConcat, secretKeys.getIntegrityKey());
        return new CipherTextIvMac(byteCipherText, iv, integrityMac);
    }

    /**
     * Generates a random IV and encrypts the byte array with the given key. Then attaches
     * a hashed MAC, which is contained in the CipherByteArrayIvMac class.
     *
     * @param byteArray The byte array that will be encrypted
     * @param secretKeys The combined AES & HMAC keys with which to encrypt
     * @return a tuple of the IV, byte array, and mac
     * @throws GeneralSecurityException if AES is not implemented on this system
     */
    public static CipherByteArrayIvMac encryptBytes(byte[] byteArray, SecretKeys secretKeys) throws GeneralSecurityException {
        byte[] iv = generateIv();
        Cipher aesCipherForEncryption = Cipher.getInstance(CIPHER_TRANSFORMATION);
        aesCipherForEncryption.init(Cipher.ENCRYPT_MODE, secretKeys.getConfidentialityKey(), new IvParameterSpec(iv));

        /*
         * Now we get back the IV that will actually be used. Some Android
         * versions do funny stuff w/ the IV, so this is to work around bugs:
         */

        iv = aesCipherForEncryption.getIV();
        byte[] cipheredBytes = aesCipherForEncryption.doFinal(byteArray);
        byte[] ivCipherConcat = CipherByteArrayIvMac.ivCipherConcat(iv, cipheredBytes);

        byte[] integrityMac = generateMac(ivCipherConcat, secretKeys.getIntegrityKey());
        return new CipherByteArrayIvMac(cipheredBytes, iv, integrityMac);
    }

    /**
     * Generates a random IV and encrypts the byte array with the given key. Then attaches
     * a hashed MAC, which is contained in the CipherByteArrayIvMac class.
     *
     * @param byteArray The byte array that will be encrypted
     * @param secretKeys The combined AES & HMAC keys with which to encrypt, as a string
     * @return a tuple of the IV, byte array, and mac
     * @throws GeneralSecurityException if AES is not implemented on this system
     */
    public static CipherByteArrayIvMac encryptBytes(byte[] byteArray, String secretKeys) throws GeneralSecurityException {
        return encryptBytes(byteArray, stringToKeys(secretKeys));
    }

    /**
     * Generates a random IV and encrypts bytes from a file with the given key. Then it is bundled in a
     * CipherByteArrayIv class.
     *
     * @param inputFile The file that will be encrypted
     * @param secretKeys The combined AES & HMAC keys with which to encrypt
     * @return a tuple of the IV and byte array
     * @throws GeneralSecurityException if AES is not implemented on this system
     * @throws IOException if the file is not found or an error occurs while reading bytes from it
     */

    public static CipherByteArrayIv encryptFile(File inputFile, SecretKeys secretKeys) throws GeneralSecurityException, IOException {
        byte[] iv = generateIv();
        Cipher aesCipherForEncryption = Cipher.getInstance(CIPHER_TRANSFORMATION);
        aesCipherForEncryption.init(Cipher.ENCRYPT_MODE, secretKeys.getConfidentialityKey(), new IvParameterSpec(iv));

        iv = aesCipherForEncryption.getIV();

        BufferedInputStream inputStream = new BufferedInputStream(new FileInputStream(inputFile));
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CipherOutputStream cipherOutputStream = new CipherOutputStream(baos, aesCipherForEncryption);

        int read;
        byte[] buffer = new byte[1024];
        boolean outOfMemTrigger = false;
        long fileLength = inputFile.length();

        while ((read = inputStream.read(buffer)) != -1) {
            if (checkMemoryAvailability.get()) {
                Runtime runtime = Runtime.getRuntime();
                long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
                long maxHeapSize = runtime.maxMemory() / 1048576L;
                long availableHeapSize = maxHeapSize - usedMemory;

                if ((fileLength / 1048576L) > (availableHeapSize - 10)) {
                    outOfMemTrigger = true;
                    break;
                }
            }

            cipherOutputStream.write(buffer, 0, read);
        }

        baos.close();
        inputStream.close();
        cipherOutputStream.close();

        if (outOfMemTrigger){
            System.gc();
            return new OOMCipherByteArray();
        }

        return new CipherByteArrayIv(baos.toByteArray(), iv);
    }

    /**
     * Generates a random IV and encrypts bytes from a file with the given key. Then it is bundled in a
     * CipherByteArrayIv class.
     *
     * @param inputFile The file that will be encrypted
     * @param secretKeys The combined AES & HMAC keys with which to encrypt, as a string
     * @return a tuple of the IV and byte array
     * @throws GeneralSecurityException if AES is not implemented on this system
     * @throws IOException if the file is not found or an error occurs while reading bytes from it
     */

    public static CipherByteArrayIv encryptFile(File inputFile, String secretKeys) throws GeneralSecurityException, IOException {
        return encryptFile(inputFile, stringToKeys(secretKeys));
    }


    /**
     * Ensures that the PRNG is fixed. Should be used before generating any keys.
     * Will only run once, and every subsequent call should return immediately.
     */
    private static void fixPrng() {
        if (!prngFixed.get()) {
            synchronized (PRNGFixes.class) {
                if (!prngFixed.get()) {
                    PRNGFixes.apply();
                    prngFixed.set(true);
                }
            }
        }
    }


    /*
     * -----------------------------------------------------------------
     * Decryption
     * -----------------------------------------------------------------
     */

    /**
     * AES CBC decrypt.
     *
     * @param civ The cipher text, IV, and mac
     * @param secretKeys The AES & HMAC keys
     * @param encoding The string encoding to use to decode the bytes after decryption
     * @return A string derived from the decrypted bytes (not base64 encoded)
     * @throws GeneralSecurityException if AES is not implemented on this system
     * @throws UnsupportedEncodingException if the encoding is unsupported
     */
    public static String decryptString(CipherTextIvMac civ, SecretKeys secretKeys, String encoding)
            throws UnsupportedEncodingException, GeneralSecurityException {
        return new String(decrypt(civ, secretKeys), encoding);
    }

    /**
     * AES CBC decrypt.
     *
     * @param civ The cipher text, IV, and mac
     * @param secretKeys The AES & HMAC keys
     * @return A string derived from the decrypted bytes, which are interpreted
     *         as a UTF-8 String
     * @throws GeneralSecurityException if AES is not implemented on this system
     * @throws UnsupportedEncodingException if UTF-8 is not supported
     */
    public static String decryptString(CipherTextIvMac civ, SecretKeys secretKeys)
            throws UnsupportedEncodingException, GeneralSecurityException {
        return decryptString(civ, secretKeys, "UTF-8");
    }

    /**
     * AES CBC decrypt.
     *
     * @param encryptedText The cipher text, IV, and mac as a string
     * @param secretKeys The AES & HMAC keys
     * @return A string derived from the decrypted bytes, which are interpreted
     *         as a UTF-8 String
     * @throws GeneralSecurityException if AES is not implemented on this system
     * @throws UnsupportedEncodingException if UTF-8 is not supported
     */
    public static String decryptString(String encryptedText, SecretKeys secretKeys)
            throws UnsupportedEncodingException, GeneralSecurityException {
        return decryptString(new CipherTextIvMac(encryptedText), secretKeys);
    }

    /**
     * AES CBC decrypt.
     *
     * @param encryptedText The cipher text, IV, and mac as a string
     * @param secretKeys The AES & HMAC keys, as a string
     * @return A string derived from the decrypted bytes, which are interpreted
     *         as a UTF-8 String
     * @throws GeneralSecurityException if AES is not implemented on this system
     * @throws UnsupportedEncodingException if UTF-8 is not supported
     */
    public static String decryptString(String encryptedText, String secretKeys)
            throws UnsupportedEncodingException, GeneralSecurityException {
        return decryptString(encryptedText, stringToKeys(secretKeys));
    }

    /**
     * AES CBC decrypt.
     *
     * @param civ the cipher text, iv, and mac
     * @param secretKeys the AES & HMAC keys
     * @return The raw decrypted bytes
     * @throws GeneralSecurityException if MACs don't match or AES is not implemented
     */
    public static byte[] decrypt(CipherTextIvMac civ, SecretKeys secretKeys)
            throws GeneralSecurityException {

        byte[] ivCipherConcat = CipherTextIvMac.ivCipherConcat(civ.getIv(), civ.getCipherText());
        byte[] computedMac = generateMac(ivCipherConcat, secretKeys.getIntegrityKey());
        if (constantTimeEq(computedMac, civ.getMac())) {
            Cipher aesCipherForDecryption = Cipher.getInstance(CIPHER_TRANSFORMATION);
            aesCipherForDecryption.init(Cipher.DECRYPT_MODE, secretKeys.getConfidentialityKey(),
                    new IvParameterSpec(civ.getIv()));
            return aesCipherForDecryption.doFinal(civ.getCipherText());
        } else {
            throw new GeneralSecurityException("MAC stored in civ does not match computed MAC.");
        }
    }

    /**
     * AES CBC decryption of a byte array
     *
     * @param byteArrayIvMac the ciphered byte array, iv, and mac
     * @param secretKeys the AES & HMAC keys
     * @return The raw decrypted bytes
     * @throws GeneralSecurityException if MACs don't match or AES is not implemented
     */
    public static byte[] decryptBytes(CipherByteArrayIvMac byteArrayIvMac, SecretKeys secretKeys) throws GeneralSecurityException {

        byte[] ivCipherConcat = CipherByteArrayIvMac.ivCipherConcat(byteArrayIvMac.getIv(), byteArrayIvMac.getCipherBytes());
        byte[] computedMac = generateMac(ivCipherConcat, secretKeys.getIntegrityKey());

        if (constantTimeEq(computedMac, byteArrayIvMac.getMac())) {
            Cipher aesCipherForDecryption = Cipher.getInstance(CIPHER_TRANSFORMATION);
            aesCipherForDecryption.init(Cipher.DECRYPT_MODE, secretKeys.getConfidentialityKey(), new IvParameterSpec(byteArrayIvMac.getIv()));
            return aesCipherForDecryption.doFinal(byteArrayIvMac.getCipherBytes());
        } else {
            throw new GeneralSecurityException("MAC stored in ciphered byte array does not match computed MAC.");
        }
    }

    /**
     * AES CBC decryption of a byte array
     *
     * @param byteArrayIvMac the ciphered byte array, iv, and mac
     * @param secretKeys the AES & HMAC keys as a string
     * @return The raw decrypted bytes
     * @throws GeneralSecurityException if MACs don't match or AES is not implemented
     */
    public static byte[] decryptBytes(CipherByteArrayIvMac byteArrayIvMac, String secretKeys) throws GeneralSecurityException {
        return decryptBytes(byteArrayIvMac, stringToKeys(secretKeys));
    }

    /**
     * AES CBC decryption of a file byte array
     *
     * @param byteArrayIv the ciphered byte array and iv
     * @param secretKeys the AES & HMAC keys
     * @return The raw decrypted bytes
     * @throws GeneralSecurityException if MACs don't match or AES is not implemented
     * @throws IOException if an error occurs while reading the bytes
     */

    public static byte[] decryptFileBytes(CipherByteArrayIv byteArrayIv, SecretKeys secretKeys) throws GeneralSecurityException, IOException {
        Cipher aesCipherForDecryption = Cipher.getInstance(CIPHER_TRANSFORMATION);
        aesCipherForDecryption.init(Cipher.DECRYPT_MODE, secretKeys.getConfidentialityKey(), new IvParameterSpec(byteArrayIv.getIv()));

        ByteArrayInputStream bais = new ByteArrayInputStream(byteArrayIv.getCipherBytes());
        CipherInputStream cipherInputStream = new CipherInputStream(bais, aesCipherForDecryption);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        long byteLength = byteArrayIv.getCipherBytes().length;
        byte[] b = new byte[1024];
        int bytesRead;

        boolean outOfMemTrigger = false;

        while ((bytesRead = cipherInputStream.read(b)) >= 0) {
            if (checkMemoryAvailability.get()) {
                Runtime runtime = Runtime.getRuntime();
                long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L;
                long maxHeapSize = runtime.maxMemory() / 1048576L;
                long availableHeapSize = maxHeapSize - usedMemory;

                if ((byteLength / 1048576L) > (availableHeapSize - 10)) {
                    outOfMemTrigger = true;
                    break;
                }
            }

            baos.write(b, 0, bytesRead);
        }

        baos.close();
        bais.close();
        cipherInputStream.close();

        if (outOfMemTrigger){
            System.gc();

            byte[] outOfMemoryByteErrorCode = new byte[1];
            Arrays.fill(outOfMemoryByteErrorCode, (byte) Constants.CRYPTO_ERROR_MEMORY);

            return outOfMemoryByteErrorCode;
        }else {
            return baos.toByteArray();
        }
    }

    /**
     * AES CBC decryption of a file byte array
     *
     * @param byteArrayIv the ciphered byte array and iv
     * @param secretKeys the AES & HMAC keys, as a string
     * @return The raw decrypted bytes
     * @throws GeneralSecurityException if MACs don't match or AES is not implemented
     * @throws IOException if an error occurs while reading the bytes
     */

    public static byte[] decryptFileBytes(CipherByteArrayIv byteArrayIv, String secretKeys) throws GeneralSecurityException, IOException {
        return decryptFileBytes(byteArrayIv, stringToKeys(secretKeys));
    }



    /*
     * -----------------------------------------------------------------
     * Helper Code
     * -----------------------------------------------------------------
     */

    /**
     * Generate the mac based on HMAC_ALGORITHM
     * @param integrityKey The key used for hmac
     * @param byteCipherText the cipher text
     * @return A byte array of the HMAC for the given key & ciphertext
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     */
    public static byte[] generateMac(byte[] byteCipherText, SecretKey integrityKey) throws NoSuchAlgorithmException, InvalidKeyException {
        //Now compute the mac for later integrity checking
        Mac sha256_HMAC = Mac.getInstance(HMAC_ALGORITHM);
        sha256_HMAC.init(integrityKey);
        return sha256_HMAC.doFinal(byteCipherText);
    }


    /**
     * Holder class that has both the secret AES key for encryption (confidentiality)
     * and the secret HMAC key for integrity.
     */

    public static class SecretKeys {
        private SecretKey confidentialityKey;
        private SecretKey integrityKey;

        /**
         * Construct the secret keys container.
         * @param confidentialityKeyIn The AES key
         * @param integrityKeyIn the HMAC key
         */
        public SecretKeys(SecretKey confidentialityKeyIn, SecretKey integrityKeyIn) {
            setConfidentialityKey(confidentialityKeyIn);
            setIntegrityKey(integrityKeyIn);
        }

        public SecretKey getConfidentialityKey() {
            return confidentialityKey;
        }

        public void setConfidentialityKey(SecretKey confidentialityKey) {
            this.confidentialityKey = confidentialityKey;
        }

        public SecretKey getIntegrityKey() {
            return integrityKey;
        }

        public void setIntegrityKey(SecretKey integrityKey) {
            this.integrityKey = integrityKey;
        }

        /**
         * Encodes the two keys as a string
         * @return base64(confidentialityKey):base64(integrityKey)
         */
        @Override
        public String toString() {
            return base64Wrapper.encodeToString(getConfidentialityKey().getEncoded())
                    + ":" + base64Wrapper.encodeToString(getIntegrityKey().getEncoded());
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + confidentialityKey.hashCode();
            result = prime * result + integrityKey.hashCode();
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            SecretKeys other = (SecretKeys) obj;
            if (!integrityKey.equals(other.integrityKey))
                return false;
            if (!confidentialityKey.equals(other.confidentialityKey))
                return false;
            return true;
        }
    }


    /**
     * Simple constant-time equality of two byte arrays. Used for security to avoid timing attacks.
     * @param a
     * @param b
     * @return true iff the arrays are exactly equal.
     */
    public static boolean constantTimeEq(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * Holder class that allows us to bundle ciphertext and IV together.
     */
    public static class CipherTextIvMac {
        private final byte[] cipherText;
        private final byte[] iv;
        private final byte[] mac;

        public byte[] getCipherText() {
            return cipherText;
        }

        public byte[] getIv() {
            return iv;
        }

        public byte[] getMac() {
            return mac;
        }

        /**
         * Construct a new bundle of ciphertext and IV.
         * @param c The ciphertext
         * @param i The IV
         * @param h The mac
         */
        public CipherTextIvMac(byte[] c, byte[] i, byte[] h) {
            cipherText = new byte[c.length];
            System.arraycopy(c, 0, cipherText, 0, c.length);
            iv = new byte[i.length];
            System.arraycopy(i, 0, iv, 0, i.length);
            mac = new byte[h.length];
            System.arraycopy(h, 0, mac, 0, h.length);
        }

        /**
         * Constructs a new bundle of ciphertext and IV from a string of the
         * format <code>base64(iv):base64(ciphertext)</code>.
         *
         * @param base64IvAndCiphertext A string of the format
         *            <code>iv:ciphertext</code> The IV and ciphertext must each
         *            be base64-encoded.
         */
        public CipherTextIvMac(String base64IvAndCiphertext) {
            String[] civArray = base64IvAndCiphertext.split(":");
            if (civArray.length != 3) {
                throw new IllegalArgumentException("Cannot parse iv:ciphertext:mac");
            } else {
                iv = base64Wrapper.decodeString(civArray[0]);
                mac = base64Wrapper.decodeString(civArray[1]);
                cipherText = base64Wrapper.decodeString(civArray[2]);
            }
        }

        /**
         * Concatinate the IV to the cipherText using array copy.
         * This is used e.g. before computing mac.
         * @param iv The IV to prepend
         * @param cipherText the cipherText to append
         * @return iv:cipherText, a new byte array.
         */
        public static byte[] ivCipherConcat(byte[] iv, byte[] cipherText) {
            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return combined;
        }

        /**
         * Encodes this ciphertext, IV, mac as a string.
         *
         * @return base64(iv) : base64(mac) : base64(ciphertext).
         * The iv and mac go first because they're fixed length.
         */
        @Override
        public String toString() {
            String ivString = base64Wrapper.encodeToString(iv);
            String cipherTextString = base64Wrapper.encodeToString(cipherText);
            String macString = base64Wrapper.encodeToString(mac);
            return String.format(ivString + ":" + macString + ":" + cipherTextString);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode(cipherText);
            result = prime * result + Arrays.hashCode(iv);
            result = prime * result + Arrays.hashCode(mac);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            CipherTextIvMac other = (CipherTextIvMac) obj;
            if (!Arrays.equals(cipherText, other.cipherText))
                return false;
            if (!Arrays.equals(iv, other.iv))
                return false;
            if (!Arrays.equals(mac, other.mac))
                return false;
            return true;
        }
    }

    /**
     * Holder class that allows us to bundle ciphered byte array and IV together.
     */
    public static class CipherByteArrayIvMac {
        private final byte[] cipherBytes;
        private final byte[] iv;
        private final byte[] mac;

        public byte[] getCipherBytes() {
            return cipherBytes;
        }

        public byte[] getIv() {
            return iv;
        }

        public byte[] getMac() {
            return mac;
        }

        /**
         * Construct a new bundle of ciphered data and IV.
         * @param theBytes The ciphered byte array
         * @param theIv The IV
         * @param theMac The mac
         */
        public CipherByteArrayIvMac(byte[] theBytes, byte[] theIv, byte[] theMac) {
            cipherBytes = new byte[theBytes.length];
            System.arraycopy(theBytes, 0, cipherBytes, 0, theBytes.length);
            iv = new byte[theIv.length];
            System.arraycopy(theIv, 0, iv, 0, theIv.length);
            mac = new byte[theMac.length];
            System.arraycopy(theMac, 0, mac, 0, theMac.length);
        }

        /**
         * Constructs a new bundle of cipherbytes and IV from a byte array and string of the
         * format <code>base64(iv):base64(mac)</code>.
         *
         * @param theBytes The byte array passed in
         *
         * @param base64IvAndMac A string of the format
         *            <code>iv:mac</code> The IV and mac must each
         *            be base64-encoded.
         */
        public CipherByteArrayIvMac(byte[] theBytes, String base64IvAndMac) {
            String[] civArray = base64IvAndMac.split(":");
            if (civArray.length != 2) {
                throw new IllegalArgumentException("Cannot parse iv:mac");
            } else {
                iv = base64Wrapper.decodeString(civArray[0]);
                mac = base64Wrapper.decodeString(civArray[1]);
                this.cipherBytes = theBytes;
            }
        }

        /**
         * Concatinate the IV to the ciphered byte array using array copy.
         * This is used e.g. before computing mac.
         * @param iv The IV to prepend
         * @param cipherBytes the cipherBytes to append
         * @return iv:cipherBytes, a new byte array.
         */
        public static byte[] ivCipherConcat(byte[] iv, byte[] cipherBytes) {
            byte[] combined = new byte[iv.length + cipherBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherBytes, 0, combined, iv.length, cipherBytes.length);
            return combined;
        }

        public String joinedIvAndMac(){
            String ivString = base64Wrapper.encodeToString(iv);
            String macString = base64Wrapper.encodeToString(mac);
            return String.format(ivString + ":" + macString);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + Arrays.hashCode(cipherBytes);
            result = prime * result + Arrays.hashCode(iv);
            result = prime * result + Arrays.hashCode(mac);
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            CipherByteArrayIvMac other = (CipherByteArrayIvMac) obj;
            if (!Arrays.equals(cipherBytes, other.cipherBytes))
                return false;
            if (!Arrays.equals(iv, other.iv))
                return false;
            if (!Arrays.equals(mac, other.mac))
                return false;
            return true;
        }
    }



    /**
     * Holder class that allows us to bundle ciphered byte array and IV together (without the MAC integrity).
     */

    public static class CipherByteArrayIv {
        private final byte[] cipherBytes;
        private final byte[] iv;

        public CipherByteArrayIv(byte[] cipherBytes, byte[] iv){
            this.cipherBytes = cipherBytes;
            this.iv = iv;
        }

        public byte[] getCipherBytes() {
            return cipherBytes;
        }

        public byte[] getIv() {
            return iv;
        }
    }

    /**
     * A holder class that indicates whether or not an encryption operation will throw an Out of Memory Error
     */

    public static class OOMCipherByteArray extends CipherByteArrayIv {

        public OOMCipherByteArray() {
            super(null, null);
        }
    }

    /**
     * Copy the elements from the start to the end
     *
     * @param from  the source
     * @param start the start index to copy
     * @param end   the end index to finish
     * @return the new buffer
     */
    private static byte[] copyOfRange(byte[] from, int start, int end) {
        int length = end - start;
        byte[] result = new byte[length];
        System.arraycopy(from, start, result, 0, length);
        return result;
    }

    /**
     * Returns the PRNG helper class that has been set for applying the fixes. If none has been set already, returns null
     *
     * @return The PRNG Helper class
     */
    public static IPRNGHelper getPrngHelper(){
        return PRNGFixes.getHelper();
    }

    /**
     * Sets the PRNG helper for the AES crypto library
     *
     * @param helper the PRNG Helper class
     */
    public static void setPrngHelper(IPRNGHelper helper){
        PRNGFixes.setHelper(helper);
    }

    /**
     *
     * A helper class to fix the PRNG problems hidden in Android while still maintaining compatibility with native Java
     *
     */
    private static final class PRNGFixes {

        private static IPRNGHelper helper;

        /** Hidden constructor to prevent instantiation **/
        private PRNGFixes(){ }

        /**
         * Applies all fixes.
         *
         * @throws SecurityException if a fix is needed but could not be
         *             applied.
         */
        private static void apply() {
            if (helper != null) {
                helper.applyFixes();
            }
        }

        private static IPRNGHelper getHelper(){
            return helper;
        }

        private static void setHelper(IPRNGHelper theHelper){
            if (helper == null) {
                helper = theHelper;
            }
        }
    }

    /**
     *
     * The PRNG Helper interface that applies the necessary fixes
     *
     */
    public interface IPRNGHelper {

        void applyFixes();
    }
}