package org.koitharu.kotatsu.parsers.site.en.kagane

import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Image processor for Kagane.
 * Handles AES-GCM decryption and image unscrambling.
 */
internal object KaganeImageProcessor {

	/**
	 * Process an encrypted/scrambled image response.
	 * @param response The original response with encrypted image bytes
	 * @param seriesId The series ID for key derivation
	 * @param chapterId The chapter ID for key derivation
	 * @param pageIndex The 1-based page index for scramble seed
	 * @return A new response with the decrypted/unscrambled image
	 */
	fun processImageResponse(
		response: Response,
		seriesId: String,
		chapterId: String,
		pageIndex: Int,
	): Response {
		val imageBytes = response.body.bytes()

		// Decrypt the image
		val decrypted = decryptImage(imageBytes, seriesId, chapterId)
			?: throw IllegalStateException("Failed to decrypt image")

		// Unscramble if needed
		val final = processData(decrypted, pageIndex, seriesId, chapterId)
			?: throw IllegalStateException("Failed to unscramble image")

		return response.newBuilder()
			.body(final.toResponseBody(response.body.contentType()))
			.build()
	}

	/**
	 * Decrypt AES-GCM encrypted image data.
	 */
	private fun decryptImage(payload: ByteArray, keyPart1: String, keyPart2: String): ByteArray? {
		return try {
			if (payload.size < 140) return null

			val iv = payload.sliceArray(128 until 140)
			val ciphertext = payload.sliceArray(140 until payload.size)

			val keyHash = sha256("$keyPart1:$keyPart2")

			val secretKey = SecretKeySpec(keyHash, "AES")
			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			val spec = GCMParameterSpec(128, iv)

			cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
			cipher.doFinal(ciphertext)
		} catch (_: Exception) {
			null
		}
	}

	/**
	 * Process data: validate image format and unscramble if needed.
	 */
	private fun processData(input: ByteArray, index: Int, seriesId: String, chapterId: String): ByteArray? {
		try {
			var processed: ByteArray = input

			if (!isValidImage(processed)) {
				val seed = generateSeed(seriesId, chapterId, "%04d.jpg".format(index))
				val scrambler = KaganeScrambler(seed, 10)
				val scrambleMapping = scrambler.getScrambleMapping()
				processed = unscramble(processed, scrambleMapping, true)
				if (!isValidImage(processed)) return null
			}

			return processed
		} catch (_: Exception) {
			return null
		}
	}

	/**
	 * Check if data is a valid image format.
	 */
	private fun isValidImage(data: ByteArray): Boolean {
		return when {
			// JPEG
			data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> true
			// GIF
			data.size >= 6 && (
				data.copyOfRange(0, 6).contentEquals("GIF87a".encodeToByteArray()) ||
					data.copyOfRange(0, 6).contentEquals("GIF89a".encodeToByteArray())
				) -> true
			// PNG
			data.size >= 8 && data.copyOfRange(0, 8).contentEquals(
				byteArrayOf(
					0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
					0x0D, 0x0A, 0x1A, 0x0A,
				),
			) -> true
			// WEBP
			data.size >= 12 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() &&
				data[2] == 'F'.code.toByte() && data[3] == 'F'.code.toByte() &&
				data[8] == 'W'.code.toByte() && data[9] == 'E'.code.toByte() &&
				data[10] == 'B'.code.toByte() && data[11] == 'P'.code.toByte() -> true
			// HEIF
			data.size >= 12 && data.copyOfRange(4, 8).contentEquals("ftyp".encodeToByteArray()) -> {
				val type = data.copyOfRange(8, 11)
				type.contentEquals("hei".encodeToByteArray()) ||
					type.contentEquals("hev".encodeToByteArray()) ||
					type.contentEquals("avi".encodeToByteArray())
			}
			// JXL (codestream)
			data.size >= 2 && data[0] == 0xFF.toByte() && data[1] == 0x0A.toByte() -> true
			// JXL (container)
			data.size >= 12 && data.copyOfRange(0, 8).contentEquals(
				byteArrayOf(0, 0, 0, 12, 'J'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), ' '.code.toByte()),
			) -> true
			else -> false
		}
	}

	/**
	 * Generate seed for scrambling from series/chapter/page info.
	 */
	private fun generateSeed(seriesId: String, chapterId: String, pageName: String): BigInteger {
		val hash = sha256("$seriesId:$chapterId:$pageName")
		var a = BigInteger.ZERO
		for (i in 0 until 8) {
			a = a.shiftLeft(8).or(BigInteger.valueOf((hash[i].toInt() and 0xFF).toLong()))
		}
		return a
	}

	/**
	 * Unscramble image data using the provided mapping.
	 */
	private fun unscramble(data: ByteArray, mapping: List<Pair<Int, Int>>, n: Boolean): ByteArray {
		val s = mapping.size
		val a = data.size
		val l = a / s
		val o = a % s

		val (r, i) = if (n) {
			if (o > 0) {
				Pair(data.copyOfRange(0, o), data.copyOfRange(o, a))
			} else {
				Pair(ByteArray(0), data)
			}
		} else {
			if (o > 0) {
				Pair(data.copyOfRange(a - o, a), data.copyOfRange(0, a - o))
			} else {
				Pair(ByteArray(0), data)
			}
		}

		val chunks = (0 until s).map { idx ->
			val start = idx * l
			val end = (idx + 1) * l
			i.copyOfRange(start, end)
		}.toMutableList()

		val u = Array(s) { ByteArray(0) }

		if (n) {
			for ((e, m) in mapping) {
				if (e < s && m < s) {
					u[e] = chunks[m]
				}
			}
		} else {
			for ((e, m) in mapping) {
				if (e < s && m < s) {
					u[m] = chunks[e]
				}
			}
		}

		val h = u.fold(ByteArray(0)) { acc, chunk -> acc + chunk }

		return if (n) {
			h + r
		} else {
			r + h
		}
	}
}

/**
 * Build PSSH box for Widevine DRM challenge.
 */
internal fun buildPsshBox(keyId: ByteArray): ByteArray {
	val systemId = java.util.Base64.getDecoder().decode("7e+LqXnWSs6jyCfc1R0h7Q==")
	val zeroes = ByteArray(4)

	val initData = byteArrayOf(18, keyId.size.toByte()) + keyId
	val initDataSize = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(initData.size).array()

	val innerBox = zeroes + systemId + initDataSize + initData
	val outerSize = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(innerBox.size + 8).array()
	val psshHeader = "pssh".toByteArray(Charsets.UTF_8)

	return outerSize + psshHeader + innerBox
}
