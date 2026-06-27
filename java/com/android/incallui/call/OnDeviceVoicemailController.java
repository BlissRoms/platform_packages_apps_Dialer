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
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.dialer.R;
import com.android.dialer.common.LogUtil;
import com.android.incallui.InCallActivity;
import com.android.incallui.InCallPresenter;
import com.android.incallui.call.state.DialerCallState;

/** Auto-answers an unanswered incoming call after a configurable timeout for on-device voicemail. */
public class OnDeviceVoicemailController implements CallList.Listener {

  private static final int DEFAULT_TIMEOUT_SECONDS = 15;

  private final Context context;
  private final Handler handler = new Handler(Looper.getMainLooper());

  @Nullable private DialerCall scheduledCall;
  @Nullable private Runnable scheduledRunnable;
  @Nullable private OnDeviceVoicemailSession session;

  public OnDeviceVoicemailController(@NonNull Context context) {
    this.context = context.getApplicationContext();
  }

  /** True if the audio HAL exposes a telephony audio device (needed for uplink greeting injection). */
  public static boolean isTelephonyAudioSupported(@NonNull Context context) {
    AudioManager audioManager = context.getSystemService(AudioManager.class);
    if (audioManager == null) {
      return false;
    }
    for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
      if (device.getType() == AudioDeviceInfo.TYPE_TELEPHONY) {
        return true;
      }
    }
    return false;
  }

  private SharedPreferences getPrefs() {
    // Read the multi-process, device-protected prefs (InCallUI runs in a separate process).
    String prefName = context.getPackageName() + "_preferences";
    return context
        .createDeviceProtectedStorageContext()
        .getSharedPreferences(prefName, Context.MODE_MULTI_PROCESS);
  }

  private boolean isEnabled() {
    return getPrefs().getBoolean(context.getString(R.string.on_device_voicemail_enabled_key), false);
  }

  private int getTimeoutSeconds() {
    String value =
        getPrefs().getString(context.getString(R.string.on_device_voicemail_timeout_key), null);
    if (value == null) {
      return DEFAULT_TIMEOUT_SECONDS;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return DEFAULT_TIMEOUT_SECONDS;
    }
  }

  @Override
  public void onIncomingCall(DialerCall call) {
    if (!isEnabled()) {
      return;
    }
    int timeoutSeconds = getTimeoutSeconds();
    if (timeoutSeconds <= 0) {
      return;
    }
    cancelScheduled();
    scheduledCall = call;
    scheduledRunnable = () -> answerIfStillRinging(call);
    LogUtil.i(
        "OnDeviceVoicemailController.onIncomingCall",
        "scheduling auto-answer in %d seconds",
        timeoutSeconds);
    handler.postDelayed(scheduledRunnable, timeoutSeconds * 1000L);
  }

  private void answerIfStillRinging(DialerCall call) {
    scheduledCall = null;
    scheduledRunnable = null;
    if (call.getState() != DialerCallState.INCOMING
        && call.getState() != DialerCallState.CALL_WAITING) {
      return;
    }
    boolean supported = isTelephonyAudioSupported(context);
    LogUtil.i(
        "OnDeviceVoicemailController.answerIfStillRinging",
        "ring timeout reached, answering call; telephonyAudioSupported=%b",
        supported);
    call.answer();
    // Give the call a moment to move to ACTIVE before injecting greeting audio.
    OnDeviceVoicemailSession newSession = new OnDeviceVoicemailSession(context, call);
    session = newSession;
    handler.postDelayed(
        () -> {
          if (session == newSession) {
            newSession.start();
            // The machine is handling the call; keep the full-screen UI out of the way to avoid
            // accidental touches (e.g. while the phone is in a pocket).
            InCallActivity activity = InCallPresenter.getInstance().getActivity();
            if (activity != null) {
              activity.moveTaskToBack(true);
            }
          }
        },
        1500L);
  }

  private void cancelScheduled() {
    if (scheduledRunnable != null) {
      handler.removeCallbacks(scheduledRunnable);
    }
    scheduledCall = null;
    scheduledRunnable = null;
  }

  private void cancelIfScheduledFor(@Nullable DialerCall call) {
    if (scheduledCall != null && call != null && scheduledCall == call) {
      cancelScheduled();
    }
  }

  @Override
  public void onDisconnect(DialerCall call) {
    cancelIfScheduledFor(call);
    if (session != null) {
      session.stop();
      session = null;
    }
  }

  @Override
  public void onCallListChange(CallList callList) {
    // If the scheduled call is no longer ringing (e.g. user answered or it moved state), cancel.
    if (scheduledCall != null
        && scheduledCall.getState() != DialerCallState.INCOMING
        && scheduledCall.getState() != DialerCallState.CALL_WAITING) {
      cancelScheduled();
    }
  }

  @Override
  public void onUpgradeToVideo(DialerCall call) {}

  @Override
  public void onSessionModificationStateChange(DialerCall call) {}

  @Override
  public void onWiFiToLteHandover(DialerCall call) {}

  @Override
  public void onHandoverToWifiFailed(DialerCall call) {}

  @Override
  public void onInternationalCallOnWifi(@NonNull DialerCall call) {}
}
