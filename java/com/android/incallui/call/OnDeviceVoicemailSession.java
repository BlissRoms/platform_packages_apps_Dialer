/*
 * Copyright (C) 2026 The BlissRoms Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.incallui.call;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import androidx.preference.PreferenceManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.dialer.common.LogUtil;

import android.provider.VoicemailContract;
import android.content.ContentValues;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Plays a greeting + beep into the call uplink, then records the caller's message to storage. */
public class OnDeviceVoicemailSession {

  private static final String GREETING =
      "Thanks for calling. The person you reached is currently unavailable. "
          + "At the tone, feel free to leave a message.";

  private static final int BEEP_FREQUENCY_HZ = 1000;
  private static final int BEEP_DURATION_MS = 400;
  private static final int BEEP_SAMPLE_RATE = 8000;

  private static final long MAX_MESSAGE_DURATION_MS = 60_000L;

  private final Context context;
  private final DialerCall call;
  private long recordingStartTime;

  public DialerCall getCall() {
    return call;
  }
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final AudioManager audioManager;

  @Nullable private TextToSpeech tts;
  @Nullable private AudioTrack uplinkTrack;
  @Nullable private MediaRecorder recorder;
  @Nullable private File outputFile;
  @Nullable private Uri outputUri;
  @Nullable private ParcelFileDescriptor outputFd;
  private boolean stopped;
  private boolean micWasMuted;

  public OnDeviceVoicemailSession(@NonNull Context context, @NonNull DialerCall call) {
    this.context = context.getApplicationContext();
    this.call = call;
    this.audioManager = this.context.getSystemService(AudioManager.class);
  }

  /** Begins the greeting → beep → record sequence. Safe to call once. */
  public void start() {
    LogUtil.i("OnDeviceVoicemailSession.start", "starting on-device voicemail session");
    if (audioManager == null) {
      LogUtil.e("OnDeviceVoicemailSession.start", "no AudioManager");
      return;
    }
    // Keep the caller from hearing the room if we can inject audio directly into the uplink.
    micWasMuted = audioManager.isMicrophoneMute();
    if (findTelephonyOutput() != null) {
      audioManager.setMicrophoneMute(true);
    }

    tts = new TextToSpeech(context, status -> onTtsInit(status));
  }

  private void onTtsInit(int status) {
    if (stopped) {
      return;
    }
    if (status != TextToSpeech.SUCCESS || tts == null) {
      LogUtil.e("OnDeviceVoicemailSession.onTtsInit", "TTS init failed, skipping greeting");
      startRecording();
      return;
    }
    tts.setLanguage(Locale.getDefault());
    tts.setOnUtteranceProgressListener(
        new UtteranceProgressListener() {
          @Override
          public void onStart(String utteranceId) {}

          @Override
          public void onDone(String utteranceId) {
            handler.post(() -> onGreetingSynthesized());
          }

          @Override
          public void onError(String utteranceId) {
            LogUtil.e("OnDeviceVoicemailSession.onError", "TTS synthesis failed");
            handler.post(() -> startRecording());
          }
        });

    File greetingFile = new File(context.getCacheDir(), "vm_greeting.wav");
    Bundle params = new Bundle();
    int result = tts.synthesizeToFile(GREETING, params, greetingFile, "vm_greeting");
    if (result != TextToSpeech.SUCCESS) {
      LogUtil.e("OnDeviceVoicemailSession.onTtsInit", "synthesizeToFile failed");
      startRecording();
    }
  }

  private void onGreetingSynthesized() {
    if (stopped) {
      return;
    }
    new Thread(() -> {
      File greetingFile = new File(context.getCacheDir(), "vm_greeting.wav");
      try {
        playWavToUplink(greetingFile);
        if (!stopped) {
          playBeepToUplink();
        }
      } catch (Exception e) {
        LogUtil.e("OnDeviceVoicemailSession.onGreeting", "failed to play greeting", e);
      } finally {
        greetingFile.delete();
      }
      if (!stopped) {
        handler.post(this::startRecording);
      }
    }).start();
  }

  /** Plays a 16-bit PCM WAV file into the call uplink. */
  private void playWavToUplink(File wav) throws IOException {
    WavHeader header = WavHeader.read(wav);
    if (header == null) {
      LogUtil.e("OnDeviceVoicemailSession.playWav", "could not parse greeting WAV");
      return;
    }
    AudioTrack track =
        buildUplinkTrack(
            header.sampleRate,
            header.channels == 1
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO);
    if (track == null) {
      return;
    }
    track.play();
    byte[] buffer = new byte[4096];
    try (FileInputStream in = new FileInputStream(wav)) {
      long skipped = in.skip(header.dataOffset);
      if (skipped != header.dataOffset) {
        LogUtil.w("OnDeviceVoicemailSession.playWav", "short skip of WAV header");
      }
      int read;
      while (!stopped && (read = in.read(buffer)) > 0) {
        track.write(buffer, 0, read);
      }
    }
    drainAndRelease(track);
  }

  private void playBeepToUplink() {
    AudioTrack track = buildUplinkTrack(BEEP_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO);
    if (track == null) {
      return;
    }
    int sampleCount = BEEP_SAMPLE_RATE * BEEP_DURATION_MS / 1000;
    byte[] pcm = new byte[sampleCount * 2];
    for (int i = 0; i < sampleCount; i++) {
      double angle = 2.0 * Math.PI * i * BEEP_FREQUENCY_HZ / BEEP_SAMPLE_RATE;
      short sample = (short) (Math.sin(angle) * 0.6 * Short.MAX_VALUE);
      pcm[i * 2] = (byte) (sample & 0xff);
      pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
    }
    track.play();
    track.write(pcm, 0, pcm.length);
    drainAndRelease(track);
  }

  @Nullable
  private AudioTrack buildUplinkTrack(int sampleRate, int channelMask) {
    AudioDeviceInfo telephonyOut = findTelephonyOutput();
    int minBuffer =
        AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
    if (minBuffer <= 0) {
      LogUtil.e("OnDeviceVoicemailSession.buildUplinkTrack", "invalid buffer size");
      return null;
    }
    AudioTrack track =
        new AudioTrack.Builder()
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
            .setAudioFormat(
                new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build())
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build();
    if (telephonyOut != null) {
      boolean ok = track.setPreferredDevice(telephonyOut);
      LogUtil.i("OnDeviceVoicemailSession.buildUplinkTrack", "preferred telephony device set=%b", ok);
    }
    uplinkTrack = track;
    return track;
  }

  private void drainAndRelease(AudioTrack track) {
    try {
      track.stop();
    } catch (IllegalStateException e) {
      // ignore
    }
    track.release();
    if (uplinkTrack == track) {
      uplinkTrack = null;
    }
  }

  @Nullable
  private AudioDeviceInfo findTelephonyOutput() {
    if (audioManager == null) {
      return null;
    }
    for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
      if (device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
        return device;
      }
    }
    return null;
  }

  private void startRecording() {
    if (stopped) {
      return;
    }
    try {
      recorder = new MediaRecorder(context);
      recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_CALL);
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      
      String number = call.getNumber();
      String safeNumber = TextUtils.isEmpty(number) ? "" : number.replaceAll("[^0-9+]", "");
      if (TextUtils.isEmpty(safeNumber)) {
        safeNumber = "unknown";
      }
      String stamp = new SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(new Date());
      String fileName = "vm_" + safeNumber + "_" + stamp + ".m4a";

      File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS), "Voicemails");
      if (!dir.exists()) {
        dir.mkdirs();
      }
      outputFile = new File(dir, fileName);
      recorder.setOutputFile(outputFile.getAbsolutePath());

      new Thread(() -> {
        try {
          recorder.prepare();
          recorder.start();
          long startTime = System.currentTimeMillis();
          handler.post(() -> {
            if (stopped) {
              return;
            }
            recordingStartTime = startTime;
            call.setIsOnDeviceVoicemailRecording(true);
            LogUtil.i("OnDeviceVoicemailSession.startRecording", "recording to %s", fileName);
            handler.postDelayed(this::stop, MAX_MESSAGE_DURATION_MS);
          });
        } catch (Exception e) {
          LogUtil.e("OnDeviceVoicemailSession.startRecording", "failed to record", e);
          handler.post(this::stop);
        }
      }).start();
    } catch (Exception e) {
      LogUtil.e("OnDeviceVoicemailSession.startRecording", "failed to setup recorder", e);
      stop();
    }
  }



  /** Stops recording, releases resources and restores the mic state. */
  public void stop() {
    if (stopped) {
      return;
    }
    stopped = true;
    handler.removeCallbacksAndMessages(null);

    if (recorder != null) {
      final MediaRecorder rec = recorder;
      recorder = null;
      new Thread(() -> {
        try {
          rec.stop();
        } catch (RuntimeException e) {
          LogUtil.w("OnDeviceVoicemailSession.stop", "recorder stop failed (no data?)");
        }
        rec.release();
        
        if (outputFile != null) {
          LogUtil.i(
              "OnDeviceVoicemailSession.stop", "voicemail saved to %s", outputFile.getAbsolutePath());
          addVoicemailToProvider(outputFile);
        }
      }).start();
    }
    if (uplinkTrack != null) {
      try {
        uplinkTrack.stop();
      } catch (IllegalStateException e) {
        // ignore
      }
      uplinkTrack.release();
      uplinkTrack = null;
    }
    if (tts != null) {
      tts.stop();
      tts.shutdown();
      tts = null;
    }
    if (audioManager != null) {
      audioManager.setMicrophoneMute(micWasMuted);
    }
    if (outputFd != null) {
      try {
        outputFd.close();
      } catch (IOException e) {
        // ignore
      }
      outputFd = null;
    }
  }

  private void addVoicemailToProvider(File file) {
    if (file == null || !file.exists()) {
      return;
    }
    long durationMs = System.currentTimeMillis() - recordingStartTime;
    if (recordingStartTime == 0 || durationMs < 0) {
      durationMs = 0;
    }
    long durationSeconds = durationMs / 1000;
    
    ContentValues values = new ContentValues();
    values.put(VoicemailContract.Voicemails.DATE, System.currentTimeMillis());
    values.put(VoicemailContract.Voicemails.NUMBER, call.getNumber());
    values.put(VoicemailContract.Voicemails.DURATION, durationSeconds);
    values.put(VoicemailContract.Voicemails.SOURCE_PACKAGE, context.getPackageName());
    values.put(VoicemailContract.Voicemails.IS_READ, 0);

    Uri uri = VoicemailContract.Voicemails.buildSourceUri(context.getPackageName());
    try {
      Uri newVoicemailUri = context.getContentResolver().insert(uri, values);
      if (newVoicemailUri != null) {
        try (OutputStream out = context.getContentResolver().openOutputStream(newVoicemailUri);
             InputStream in = new FileInputStream(file)) {
          byte[] buffer = new byte[1024];
          int len;
          while ((len = in.read(buffer)) != -1) {
            out.write(buffer, 0, len);
          }
        }
        values.clear();
        values.put(VoicemailContract.Voicemails.HAS_CONTENT, 1);
        context.getContentResolver().update(newVoicemailUri, values, null, null);
        LogUtil.i("OnDeviceVoicemailSession.addVoicemailToProvider", "Voicemail added to dialer app");
      }
    } catch (Exception e) {
      LogUtil.e("OnDeviceVoicemailSession.addVoicemailToProvider", "Failed to add voicemail to provider", e);
    }
  }

  /** Minimal 16-bit PCM WAV header reader. */
  private static final class WavHeader {
    final int sampleRate;
    final int channels;
    final long dataOffset;

    private WavHeader(int sampleRate, int channels, long dataOffset) {
      this.sampleRate = sampleRate;
      this.channels = channels;
      this.dataOffset = dataOffset;
    }

    @Nullable
    static WavHeader read(File file) {
      try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
        byte[] header = new byte[44];
        if (raf.read(header) != 44) {
          return null;
        }
        if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
          return null;
        }
        int channels = (header[22] & 0xff) | ((header[23] & 0xff) << 8);
        int sampleRate =
            (header[24] & 0xff)
                | ((header[25] & 0xff) << 8)
                | ((header[26] & 0xff) << 16)
                | ((header[27] & 0xff) << 24);
        if (sampleRate <= 0 || channels <= 0) {
          return null;
        }
        return new WavHeader(sampleRate, channels, 44);
      } catch (IOException e) {
        return null;
      }
    }
  }
}
