package underad.blackbox.core.util;

import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.binary.Base64;
import org.joda.time.Duration;

public class Crypto {
	// Ideally use SHA512... though appears to require usage of Bouncy Castle libs.
	private static final String PBKDF2_HASH_ALGORITHM = "PBKDF2WithHmacSHA1";
	private static final int PBKDF2_ITERATIONS = 65000;
	private static final int PBKDF2_HASH_BYTE_SIZE = 32;
	
	private static final Charset CHARSET = Charset.forName("UTF-8");
	private static final Cipher CIPHER;
	private static final IvParameterSpec INIT_VECTOR_PARAM_SPEC;
	// 600,000ms = 600s = 10 mins
	private static final Duration KEY_DURATION = new Duration(600000);
	private static final byte[] NULL_SALT = Base64.encodeBase64(new byte[] {0});
	
	static {
		try {
			// PKCS5Padding required to allow for ciphering of arbitrary-length inputs
			CIPHER = Cipher.getInstance("AES/CBC/PKCS5Padding");
			/*
			 * We want a nil initialisation vector, as otherwise even when the key is the same, regeneration of the page
			 * will result in different cipher texts. This in turn would result in cache misses for downloaded images/JS
			 * etc when fresh HTML is retrieved.
			 */
			INIT_VECTOR_PARAM_SPEC = new IvParameterSpec(new byte[CIPHER.getBlockSize()]);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
			/*
			 * NoSuchAlgorithmException/NoSuchPaddingException won't happen because
			 * cipher is hard-coded.
			 */
			throw new Error("These should never have been checked exceptions...");
		}
	}
	
	public static String encrypt(String password, long publisherUnixTimeMillis, String plainText) {
		byte[] plainTextBytes = plainText.getBytes(CHARSET);
	    byte[] cipherTextBytes = crypt(password, publisherUnixTimeMillis, plainTextBytes, Cipher.ENCRYPT_MODE);
	    return new String(Base64.encodeBase64(cipherTextBytes), CHARSET);
	}
	
	public static String decrypt(String password, long publisherUnixTimeMillis, String cipherText) {
		byte[] cipherTextBytes = Base64.decodeBase64(cipherText.getBytes(CHARSET));
		byte[] originalBytes = crypt(password, publisherUnixTimeMillis, cipherTextBytes, Cipher.DECRYPT_MODE);
		return new String(originalBytes, CHARSET);
	}
	
	private static byte[] crypt(String password, long publisherUnixTimeMillis, byte[] input, int cipherMode) {
		long period = publisherUnixTimeMillis / KEY_DURATION.getMillis();
		String periodedPassword = period + password;
		SecretKey key = null;
		try {
			SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_HASH_ALGORITHM);
			KeySpec spec = new PBEKeySpec(
					periodedPassword.toCharArray(), NULL_SALT, PBKDF2_ITERATIONS, PBKDF2_HASH_BYTE_SIZE * 8);
			// Need key to have algorithm set to AES, hence one SecretKey (from generateSecret()) being used to make another
			key = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
			CIPHER.init(cipherMode, key, INIT_VECTOR_PARAM_SPEC);
			byte[] cipherTextBytes = CIPHER.doFinal(input);
			return cipherTextBytes;
		} catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | IllegalBlockSizeException e) {
			/*
			 * NoSuchAlgorithmException/InvalidAlgorithmParameterException won't happen as values are hardcoded.
			 * IllegalBlockSizeException can't happen with PKCS5Padding.
			 */
			throw new Error("These should never have been checked exceptions... (2)");
		} catch (InvalidKeySpecException | InvalidKeyException e) {
			throw new IllegalArgumentException(String.format("Key is invalid: %s", key), e);
		} catch (BadPaddingException e) {
			throw new IllegalArgumentException(String.format("Input ciphertext is invalid: %s", input));
		}
	}
}
