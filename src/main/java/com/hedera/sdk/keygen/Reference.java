package com.hedera.sdk.keygen;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Encapsulation of a Swirlds "reference", which is a 384-bit hash of a public key, file, swirld name, or
 * other entity. It supports converting between byte array and string, for several types of string.
 * Eventually, it may also allow converting between those and a QR code image. It is also legal to
 * instantiate with a 128-bit or 256-bit hash, but for some uses, 128 bits will not be secure against
 * birthday attacks, and 256 bits will not be secure against quantum computers. The US CNSA Suite requires
 * 384-bit hashes, and 256-bit keys.
 */
public class Reference {
	// data is the actual reference, either 16 or 32 bytes
	private byte[] data;
	private static final String digits = "0123456789"
			+ "abcdefghijklmnopqrstuvwxyz" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
	/** use this for all logging, as controlled by the optional data/log4j2.xml file */

	/**
	 * Pass to the constructor 16, 32, or 48 bytes (128, 256, or 384 bits), which is the hash of the thing
	 * being referenced (a key, a swirld name, a file, etc). A copy of data is made, so it is OK to change
	 * the array after instantiating the Reference.
	 * 
	 * The stored information will be one byte longer than the data, found by first appending a CRC8
	 * checksum to the end, then XORing all the previous bytes with that checksum. Later, the checksum can
	 * be checked by doing the XOR, then the checksum calculation, then comparing the result to the last
	 * byte. When expressing a Reference in base 62 or as a list of words, the longer version is used.
	 * 
	 * @param data
	 *            the 16 or 32 or 48 bytes constituting the reference
	 */
	public Reference(byte[] data) {
		if (data == null) {
			throw new InvalidParameterException("data should not be null");
		}
		if (data == null || (data.length != 32 && data.length != 16
				&& data.length != 48)) {
			throw new InvalidParameterException(
					"data.length() should be 16 or 32 or 48, not "
							+ data.length);
		}
		this.data = new byte[data.length + 1];
		// store in scrambled form so changing one bit of reference changes the entire string representation
		byte crc = crc8(data);
		this.data[data.length] = crc; // the crc goes at the end
		for (int i = 0; i < data.length; i++) {
			this.data[i] = (byte) (data[i] ^ crc); // the checksum also is XORed with all previous bytes
		}
	}

	/**
	 * The natural log of 2, used in the log2() function
	 */
	private final static double LOG_2 = Math.log(2);

	/**
	 * return the log base 2 of x
	 * 
	 * @param x
	 *            the number to take the log of
	 * @return the log base 2 of x
	 */
	private double log2(double x) {
		return Math.log(x) / LOG_2;
	}

	/**
	 * Pass to the constructor a string representing the data. If the string starts and ends in <> and
	 * contains only letters and digits in between, then it is considered a base62 encoding. Otherwise, it
	 * is considered a list of words.
	 * 
	 * @param dataString
	 *            the 16, 32, or 48 bytes constituting the reference
	 */
	public Reference(String dataString) {
		if (dataString.matches("^<[a-zA-Z0-9]*>$")) { // base 62 encoding
			int lenString = dataString.length() - 2; // number of characters other than <>
			int len128Bits = (int) Math.ceil((128 + 8) / log2(digits.length()));
			int len256Bits = (int) Math.ceil((256 + 8) / log2(digits.length()));
			int len384Bits = (int) Math.ceil((384 + 8) / log2(digits.length()));
			if (lenString != len128Bits && lenString != len256Bits
					&& lenString != len384Bits) {
				throw new InvalidParameterException(dataString
						+ " is not a proper encoding of 128, 256, or 384 bits because it has "
						+ lenString + " digits instead of " + len128Bits + ", "
						+ len256Bits + ", or " + len384Bits);
			}
			int arrayLen = (lenString == len128Bits) ? 16 + 1
					: (lenString == len256Bits) ? 32 + 1 : 48 + 1;
			data = new byte[arrayLen];

			int[] inDigits = new int[lenString];
			for (int i = 0; i < lenString; i++) {
				inDigits[i] = digits.indexOf(dataString.charAt(i + 1));
			}
			int[] outDigits = convertRadix(inDigits, 62, 256, arrayLen);
			data = new byte[arrayLen];
			for (int i = 0; i < arrayLen; i++) {
				data[i] = (byte) outDigits[i];
			}
		} else { // list of words
			List<String> words = Reference.lowercasedWords();
			int len128Bits = (int) Math.ceil((128 + 8) / log2(words.size()));
			int len256Bits = (int) Math.ceil((256 + 8) / log2(words.size()));
			int len384Bits = (int) Math.ceil((384 + 8) / log2(words.size()));

			List<String> allWords = Collections
					.synchronizedList(new ArrayList<String>());
			Matcher m = Pattern.compile("[a-zA-Z]+")
					.matcher(dataString.toLowerCase());
			while (m.find()) {
				allWords.add(m.group());
			}
			int[] indices = new int[allWords.size()];
			int j = 0;
			for (String word : allWords) {
				indices[j++] = words.indexOf(word);
			}

			if (allWords.size() != len128Bits
					&& allWords.size() != len256Bits) {
				throw new InvalidParameterException("there should be "
						+ len128Bits + ", " + len256Bits + ", or " + len384Bits
						+ " words, not " + allWords.size());
			}

			int dataLen = allWords.size() == len128Bits ? 16 + 1
					: allWords.size() == len256Bits ? 32 + 1 : 48 + 1;
			int[] toDigits = convertRadix(indices, words.size(), 256, dataLen);
			data = new byte[dataLen];
			for (int i = 0; i < dataLen; i++) {
				data[i] = (byte) toDigits[i];
			}
		}
		byte[] dataUnscrambled = new byte[32];
		byte crc = data[data.length - 1];
		for (int i = 0; i < data.length - 1; i++) {
			dataUnscrambled[i] = (byte) (((byte)data[i]) ^ crc);
		}
		crc = crc8(dataUnscrambled);
		if (data[data.length - 1] != crc) {
			throw new InvalidParameterException(
					"Invalid string: fails the cyclic redundency check");
		}
	}

	/**
	 * Given a number in base fromRadix, with each digit being an integer in array number (number[0] is most
	 * significant), return a new number in toRadix, where the array has toLength elements, left padded with
	 * zeros if the number is too small, and chopping off the most significant digits if it is too large.
	 * 
	 * @param number
	 *            the input number to convert
	 * @param fromRadix
	 *            the base of input number
	 * @param toRadix
	 *            the base of the returned number
	 * @param toLength
	 *            the number of digits in the returned number
	 * @return the number in the new base
	 */
	
	private int[] convertRadix(int[] number, int fromRadix, int toRadix,
			int toLength) {
		int[] result = new int[toLength];
		BigInteger num = BigInteger.valueOf(0);
		BigInteger fromR = BigInteger.valueOf(fromRadix);
		BigInteger toR = BigInteger.valueOf(toRadix);
		for (int i = 0; i < number.length; i++) { // convert number ==> BigInteger
			num = num.multiply(fromR);
			num = num.add(BigInteger.valueOf(number[i]));
		}
		for (int i = toLength - 1; i >= 0; i--) { // convert BigInteger ==> result
			BigInteger[] pair = num.divideAndRemainder(toR);
			num = pair[0];
			result[i] = pair[1].intValue();
		}
		return result;
	}

	/**
	 * calculate the checksum of all but the last byte in data. according to
	 * http://www.ece.cmu.edu/~koopman/roses/dsn04/koopman04_crc_poly_embedded.pdf Koopman says 0xA6 (1
	 * 0100110) is a good polynomial choice, which is x^8 + x^6 + x^3 + x^2 + x^1 . The following code uses
	 * 0xB2 (1 0110010), which is 0xA6 with the bits reversed (after the first bit), which is needed for
	 * this code.
	 * 
	 * @param data
	 *            the data to find checksum for (where the last byte does not affect the checksum)
	 * @return the checksum
	 */
	public static byte crc8(byte[] data) {
	    int count = data.length;
		int crc = 0xFF;
		for (int i = 0; i < data.length - 1; i++) {
			crc ^= Reference.byteToUnsignedInt(data[i]);
			for (int j = 0; j < 8; j++) {
				crc = (crc >>> 1) ^ (((crc & 1) == 0) ? 0 : 0xB2);
			}
		}
		return (byte) (crc ^ 0xFF);
	}

	/**
	 * Return the base-62 encoding, inside of <brackets>.
	 */
	
	@Override
	public String toString() {
		return to62();
	}

	/**
	 * Return this reference as an array of 16 or 32 or 48 bytes.
	 * 
	 * @return the reference as an array of 16 or 32 or 48 bytes.
	 */
	
	public byte[] toBytes() {
		byte[] result = Arrays.copyOfRange(data, 0, data.length - 1);
		byte crc = data[data.length - 1];
		for (int i = 0; i < data.length - 1; i++) { // return unscrambled data
			result[i] ^= crc;
		}
		return result;
	}

	/**
	 * Return the first few characters of the string representing the reference, encoded in base 62
	 * 
	 * @return
	 */
	
	public String to62Prefix() {
		return to62().substring(0, 6) + "...>";
	}

	/**
	 * Return a string representing the reference, encoded in base 62
	 * 
	 * @return
	 */
	
	public String to62() {
		int len = (int) Math.ceil(data.length * 8 / log2(digits.length()));
		int[] fromDigits = new int[data.length];
		int[] toDigits;
		char[] answer = new char[len];

		for (int i = 0; i < data.length; i++) {
			fromDigits[i] = Reference.byteToUnsignedInt(data[i]);
		}
		toDigits = convertRadix(fromDigits, 256, digits.length(), len);
		for (int i = 0; i < len; i++) {
			answer[i] = digits.charAt(toDigits[i]);
		}
		return "<" + (new String(answer)) + ">";
	}

	/**
	 * Return a string representing the reference, made up of multiple lines of 4 words each, broken into
	 * groups of 4 lines. The last group may have fewer lines, and its last line may have fewer words.
	 * 
	 * @return the result as one string
	 */
	
	public String toWords() {
		return toWords("+----------\n| ", " ", "\n| ", "\n| ", "\n|\n| ",
				"\n|\n| ", "\n+----------");

		// uncomment the following instead of the above, to indent every other group instead of skipping
		// a line every 4th line:
		//
		// return toWords("+----------\n| ", " ", "\n| ", "\n| ", "\n| ",
		// "\n| ", "\n+----------");
	}

	/**
	 * Return a string representing the reference, made up of multiple lines of 4 words each, broken into
	 * groups of 4 lines. The last group may have fewer lines, and its last line may have fewer words.
	 * 
	 * @indent a string inserted at the start of each line
	 * @return the result as one string
	 */
	
	public String toWords(String indent) {
		return toWords( //
				indent + "+----------\n" + indent + "| ", //
				" ", //
				"\n" + indent + "| ", //
				"\n" + indent + "| ", //
				"\n" + indent + "|\n" + indent + "| ", //
				"\n" + indent + "|\n" + indent + "| ", //
				"\n" + indent + "+----------");

		// uncomment the following instead of the above, to indent every other group instead of skipping
		// a line every 4th line:
		//
		// return toWords("+----------\n| ", " ", "\n| ", "\n| ", "\n| ",
		// "\n| ", "\n+----------");
	}

	/**
	 * Return a string representing the reference, made up of multiple lines of 4 words each, broken into
	 * groups of 3 lines. The last group may have fewer lines, and its last line may have fewer words. There
	 * are no actual line breaks unless they are included in the parameters passed in.
	 * 
	 * @param prefix
	 *            start of the entire string
	 * @param wordSeparator
	 *            between adjacent words in a line of 4 words
	 * @param lineSeparator1
	 *            between lines in a group of 3 lines, for every other group, starting with first
	 * @param lineSeparator2
	 *            between lines in a group of 3 lines, for every other group, starting with second
	 * @param groupSeparator1
	 *            between groups of 4 lines, for every other group, starting with first
	 * @param groupSeparator2
	 *            between groups of 4 lines, for every other group, starting with second
	 * @param suffix
	 *            end of the entire string
	 * @return the result as one string
	 */
	
	public String toWords(String prefix, String wordSeparator,
			String lineSeparator1, String lineSeparator2,
			String groupSeparator1, String groupSeparator2, String suffix) {
		List<String> words = this.toWordsList();
		// need len words
		String answer = "";

		for (int i = 0; i < words.size(); i++) {
			if (i == 0) {
				answer += prefix;
			} else if (i % 24 == 0) {
				answer += groupSeparator1;
			} else if (i % 12 == 0) {
				answer += groupSeparator2;
			} else if (i % 4 == 0 && i % 24 < 12) {
				answer += lineSeparator1;
			} else if (i % 4 == 0) {
				answer += lineSeparator2;
			} else {
				answer += wordSeparator;
			}
			answer += words.get(i);
		}
		return new String(answer + suffix);
	}

	
	public List<String> toWordsList(){
        List<String> words = WordList.words;
        // need len words
        int len = (int) Math.ceil(data.length * 8 / log2(words.size()));
        ArrayList<String> answer = new ArrayList<String>();

        int[] fromDigits = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            fromDigits[i] = Reference.byteToUnsignedInt(data[i]);
        }
        int[] toDigits = convertRadix(fromDigits, 256, words.size(), len);

        for (int i = 0; i < len; i++) {
            answer.add(words.get(toDigits[i]));
        }
        return answer;
    }

	/**
	 * return a string representation of the given byte array in hex, with a each byte becoming two
	 * characters (including a leading zero, if necessary). Only use the array from index firstIndex to
	 * index lastIndex. A lastIndex of -1 means the last element, -2 is the second to last, and so on. If
	 * minIndex>maxIndex (after any wrapping of lastIndex), then return the null string.
	 * 
	 * @param bytes
	 *            an array of bytes, some of which are to be converted
	 * @param firstIndex
	 *            index of first element to convert
	 * @param lastIndex
	 *            index of last element to convert (or -1 for last -2 for 2nd to last, etc)
	 * @return a hex string of exactly two characters per converted byte
	 */
	
	static String toHex(byte[] bytes, int firstIndex, int lastIndex) {
		String ans = "";
		int last = lastIndex >= 0 ? lastIndex : bytes.length + lastIndex;
		if (!(0 <= firstIndex && firstIndex <= last && last < bytes.length)) {
			return "";
		}
		for (int i = firstIndex; i <= last; i++) {
			ans += String.format("%02x", bytes[i] & 255);
		}
		return ans;
	}

	public static int byteToUnsignedInt(byte b) {
		return 0x00 << 24 | b & 0xff;
	}

	public static List<String> lowercasedWords () {
		List<String> words = WordList.words;
		ArrayList<String> lowercasedWords = new ArrayList<>();
		for (int i = 0; i < words.size() ; i++) {
			lowercasedWords.add(words.get(i).toLowerCase());
		}
		return lowercasedWords;
	}

	// /**
	// * This is only used for testing this class. It should probably be deleted at some point.
	// * @param args ignored
	// */
	// private static void main(String[] args) {
	// Random rnd = new Random(123);
	// Reference ref;
	// byte[] data;
	//
	// for (int i=16; i<=32; i+=16 ) {
	// String strString, str62, strWords;
	//
	// data = new byte[i];
	// rnd.nextBytes(data);
	// ref = new Reference(data);
	// strString=ref.toString();
	// System.out.println("toString() = " + strString);
	//
	// str62=ref.to62();
	// System.out.println("to62() = " + str62);
	//
	// //str62 = str62.substring(0, 5) + "X" + str62.substring(6);
	// Reference newRef = new Reference(str62);
	// byte[] newRefBytes = newRef.toBytes();
	// String roundTrip62 = new Reference(newRefBytes).to62();
	// System.out.println("RT to62() = " + roundTrip62);
	//
	// strWords=ref.toWords(" "," ","\n","\n","");
	// System.out.println("toWords() = \n" + strWords);
	//
	//
	// //strWords = strWords.substring(0, 5) + "X" + strWords.substring(6);
	// Reference newRef2 = new Reference(strWords);
	// String strWords2 = newRef2.toWords(" "," ","\n","");
	// System.out.println("RT toWords() = \n" + strWords2);
	//
	// System.out.println();;
	// System.out.println("number of words: "+words.size());
	//
	// str62=ref.to62();
	// System.out.println("to62() = " + str62);
	// data[5] ^= 16;
	// Reference ref3 = new Reference(data);
	// System.out.println("to62() = " + ref3.to62());
	// }
	//
	// Class<?> main = null;
	// try {
	// Class.forName("com.swirlds.demos.ServerDemoMain");
	// } catch(Exception e) {
	// Platform.log(e,"");
	// }
	// System.out.println("Found class "+main);
	// }

	// {
	// System.out.println("WordList.words.size(): " + WordList.words.size());
	// }
}
