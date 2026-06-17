/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.common

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.google.ai.edge.gallery.data.SAMPLE_RATE
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

private const val TAG = "AGAudioConverter"

/**
 * Loads an audio clip from [uri] in ANY format the device can decode (wav, mp3, m4a/aac, ogg/opus,
 * flac, 3gp, ...) and converts it to the 16-bit, mono, [SAMPLE_RATE] Hz PCM representation expected
 * by the on-device speech models used by Audio Scribe.
 *
 * This is the single entry point the UI should call when the user picks an audio file. WAV files
 * are handled by the existing lightweight parser ([convertWavToMonoWithMaxSeconds]); every other
 * format is decoded with the platform [MediaExtractor] / [MediaCodec] pipeline. No third-party
 * dependencies are required.
 *
 * @return an [AudioClip] with raw little-endian 16-bit PCM data at [SAMPLE_RATE] Hz, or null if the
 *   file could not be read or contained no decodable audio track.
 */
fun loadAudioClipFromUri(context: Context, uri: Uri, maxSeconds: Int = 30): AudioClip? {
  return try {
    if (isWavUri(context = context, uri = uri)) {
      // WAV is uncompressed PCM; reuse the existing fast-path parser.
      convertWavToMonoWithMaxSeconds(context = context, stereoUri = uri, maxSeconds = maxSeconds)
    } else {
      decodeCompressedAudioToMono(context = context, uri = uri, maxSeconds = maxSeconds)
    }
  } catch (e: Exception) {
    Log.e(TAG, "Failed to load audio clip from uri: $uri", e)
    null
  }
}

/** Detects a WAV/RIFF file by sniffing the header, falling back to the reported MIME type. */
private fun isWavUri(context: Context, uri: Uri): Boolean {
  // Sniff the first 12 bytes for the "RIFF....WAVE" signature; this is definitive.
  try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
      val header = ByteArray(12)
      val read = stream.read(header)
      if (read >= 12) {
        val isRiff =
          header[0].toInt() == 'R'.code &&
            header[1].toInt() == 'I'.code &&
            header[2].toInt() == 'F'.code &&
            header[3].toInt() == 'F'.code
        val isWave =
          header[8].toInt() == 'W'.code &&
            header[9].toInt() == 'A'.code &&
            header[10].toInt() == 'V'.code &&
            header[11].toInt() == 'E'.code
        return isRiff && isWave
      }
    }
  } catch (e: Exception) {
    Log.w(TAG, "Could not sniff header, falling back to MIME type.", e)
  }

  // Fallback: trust the MIME type the content provider reports.
  val mime = context.contentResolver.getType(uri)?.lowercase()
  return mime == "audio/wav" || mime == "audio/x-wav" || mime == "audio/wave"
}

/**
 * Decodes a compressed audio file to mono [SAMPLE_RATE] Hz 16-bit PCM using the platform codecs.
 *
 * To keep memory bounded for long files, decoding stops once enough samples to cover
 * ([maxSeconds] + 1) seconds have been produced.
 */
private fun decodeCompressedAudioToMono(
  context: Context,
  uri: Uri,
  maxSeconds: Int,
): AudioClip? {
  val extractor = MediaExtractor()
  var codec: MediaCodec? = null
  try {
    extractor.setDataSource(context, uri, null)

    // Find the first audio track.
    var trackIndex = -1
    var inputFormat: MediaFormat? = null
    for (i in 0 until extractor.trackCount) {
      val format = extractor.getTrackFormat(i)
      val trackMime = format.getString(MediaFormat.KEY_MIME) ?: continue
      if (trackMime.startsWith("audio/")) {
        trackIndex = i
        inputFormat = format
        break
      }
    }
    if (trackIndex < 0 || inputFormat == null) {
      Log.e(TAG, "No audio track found in $uri")
      return null
    }
    extractor.selectTrack(trackIndex)

    val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
    var sourceSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    var pcmEncoding =
      if (inputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
        inputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
      } else {
        AudioFormat.ENCODING_PCM_16BIT
      }

    // Upper bound on bytes to decode so we don't load huge files fully into memory.
    val bytesPerSample = 2 // assume 16-bit until told otherwise
    var maxSourceBytes =
      (maxSeconds + 1).toLong() * sourceSampleRate * channels * bytesPerSample

    codec = MediaCodec.createDecoderByType(mime)
    codec.configure(inputFormat, null, null, 0)
    codec.start()

    val bufferInfo = MediaCodec.BufferInfo()
    val pcmOut = ByteArrayOutputStream()
    var sawInputEos = false
    var sawOutputEos = false
    val timeoutUs = 10_000L

    while (!sawOutputEos) {
      if (!sawInputEos) {
        val inIndex = codec.dequeueInputBuffer(timeoutUs)
        if (inIndex >= 0) {
          val inputBuffer = codec.getInputBuffer(inIndex)
          val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
          if (sampleSize < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            sawInputEos = true
          } else {
            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
          }
        }
      }

      val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
      when {
        outIndex >= 0 -> {
          if (bufferInfo.size > 0) {
            val outBuffer = codec.getOutputBuffer(outIndex)
            if (outBuffer != null) {
              outBuffer.position(bufferInfo.offset)
              outBuffer.limit(bufferInfo.offset + bufferInfo.size)
              val chunk = ByteArray(bufferInfo.size)
              outBuffer.get(chunk)
              pcmOut.write(chunk)
            }
          }
          codec.releaseOutputBuffer(outIndex, false)

          if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            sawOutputEos = true
          }
          // Stop early once we have more than enough audio for the trimmed clip.
          if (!sawInputEos && pcmOut.size() >= maxSourceBytes) {
            sawInputEos = true
            val flushIndex = codec.dequeueInputBuffer(timeoutUs)
            if (flushIndex >= 0) {
              codec.queueInputBuffer(
                flushIndex,
                0,
                0,
                0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
              )
            }
          }
        }

        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
          val outFormat = codec.outputFormat
          sourceSampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
          channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
          }
          maxSourceBytes =
            (maxSeconds + 1).toLong() *
              sourceSampleRate *
              channels *
              if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        }
      }
    }

    val pcmBytes = pcmOut.toByteArray()
    if (pcmBytes.isEmpty()) {
      Log.e(TAG, "Decoder produced no PCM data for $uri")
      return null
    }

    // Interpret the decoded bytes as interleaved 16-bit PCM samples (converting from float if the
    // decoder emitted float output).
    val interleaved: ShortArray =
      if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
        val floatBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        ShortArray(floatBuffer.remaining()) {
          val sample = floatBuffer.get().coerceIn(-1f, 1f)
          (sample * 32767f).toInt().toShort()
        }
      } else {
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        ShortArray(shortBuffer.remaining()).also { shortBuffer.get(it) }
      }

    // Downmix to mono, resample to SAMPLE_RATE, then trim.
    val mono = downmixToMono(interleaved, channels)
    val resampled = resampleMono(mono, sourceSampleRate, SAMPLE_RATE)

    val maxSamples = maxSeconds * SAMPLE_RATE
    val trimmed =
      if (resampled.size > maxSamples) resampled.copyOfRange(0, maxSamples) else resampled

    val outByteBuffer = ByteBuffer.allocate(trimmed.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    outByteBuffer.asShortBuffer().put(trimmed)
    Log.d(
      TAG,
      "Decoded '$mime' ${sourceSampleRate}Hz/${channels}ch -> mono/${SAMPLE_RATE}Hz, " +
        "${trimmed.size} samples",
    )
    return AudioClip(audioData = outByteBuffer.array(), sampleRate = SAMPLE_RATE)
  } catch (e: Exception) {
    Log.e(TAG, "Failed to decode compressed audio: $uri", e)
    return null
  } finally {
    try {
      codec?.stop()
    } catch (_: Exception) {}
    try {
      codec?.release()
    } catch (_: Exception) {}
    extractor.release()
  }
}

/** Averages interleaved multi-channel PCM down to a single mono channel. */
private fun downmixToMono(interleaved: ShortArray, channels: Int): ShortArray {
  if (channels <= 1) return interleaved
  val frameCount = interleaved.size / channels
  val mono = ShortArray(frameCount)
  for (i in 0 until frameCount) {
    var sum = 0
    for (c in 0 until channels) {
      sum += interleaved[i * channels + c]
    }
    mono[i] = (sum / channels).toShort()
  }
  return mono
}

/**
 * Resamples a mono PCM stream from [fromRate] to [toRate] using linear interpolation. Handles both
 * up- and down-sampling.
 */
private fun resampleMono(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
  if (fromRate == toRate || input.isEmpty()) return input
  val ratio = toRate.toDouble() / fromRate.toDouble()
  val outLength = (input.size * ratio).toInt()
  val output = ShortArray(outLength)
  for (i in output.indices) {
    val position = i / ratio
    val index1 = floor(position).toInt()
    val index2 = index1 + 1
    val fraction = position - index1
    val s1 = if (index1 < input.size) input[index1].toDouble() else 0.0
    val s2 = if (index2 < input.size) input[index2].toDouble() else 0.0
    output[i] = (s1 * (1 - fraction) + s2 * fraction).toInt().toShort()
  }
  return output
}
