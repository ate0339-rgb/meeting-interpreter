package com.hm.meetingnote;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** 16-bit mono PCM in, AAC .m4a out. Files are finalized on close(), so callers rotate them regularly. */
final class AacWriter {
 private final MediaCodec codec;
 private final MediaMuxer muxer;
 private final int rate;
 private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
 private int track = -1;
 private boolean started;
 private long samples;

 AacWriter(File file, int rate) throws IOException {
  this.rate = rate;
  MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
  fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
  fmt.setInteger(MediaFormat.KEY_BIT_RATE, 32000);
  fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
  codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
  codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
  codec.start();
  muxer = new MediaMuxer(file.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
 }

 long durationMs() { return samples * 1000L / rate; }

 void write(byte[] pcm, int len) {
  int off = 0;
  while (off < len) {
   int idx = codec.dequeueInputBuffer(10_000);
   if (idx < 0) { drain(false); continue; }
   ByteBuffer in = codec.getInputBuffer(idx);
   in.clear();
   int n = Math.min(in.remaining(), len - off);
   in.put(pcm, off, n);
   codec.queueInputBuffer(idx, 0, n, samples * 1_000_000L / rate, 0);
   samples += n / 2;
   off += n;
   drain(false);
  }
 }

 private void drain(boolean endOfStream) {
  int idle = 0;
  while (true) {
   int o = codec.dequeueOutputBuffer(info, endOfStream ? 10_000 : 0);
   if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
    if (!endOfStream || ++idle > 50) return;
    continue;
   }
   if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
    track = muxer.addTrack(codec.getOutputFormat());
    muxer.start();
    started = true;
    continue;
   }
   if (o < 0) continue;
   ByteBuffer out = codec.getOutputBuffer(o);
   if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
   if (info.size > 0 && started) {
    out.position(info.offset);
    out.limit(info.offset + info.size);
    muxer.writeSampleData(track, out, info);
   }
   codec.releaseOutputBuffer(o, false);
   if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
  }
 }

 void close() {
  try {
   int idx = codec.dequeueInputBuffer(10_000);
   if (idx >= 0) codec.queueInputBuffer(idx, 0, 0, samples * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
   drain(true);
  } catch (Exception ignored) { }
  try { codec.stop(); } catch (Exception ignored) { }
  codec.release();
  try { if (started) muxer.stop(); } catch (Exception ignored) { }
  muxer.release();
 }
}
