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
package com.android.dialer.app.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.android.dialer.R;

/** Dedicated call recording settings (audio format), split out from the sound settings screen. */
public class CallRecordingSettingsFragment extends PreferenceFragmentCompat
    implements Preference.OnPreferenceChangeListener {

  private static final String KEY_RECORDING_WARNING_PRESENTED = "recording_warning_presented";

  private SwitchPreferenceCompat callRecordAutostart;

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    addPreferencesFromResource(R.xml.call_recording_settings);

    Context context = getActivity();
    callRecordAutostart = findPreference(context.getString(R.string.call_recording_autostart_key));
    if (callRecordAutostart != null) {
      callRecordAutostart.setOnPreferenceChangeListener(this);
    }
  }

  @Override
  public boolean onPreferenceChange(Preference preference, Object objValue) {
    if (preference == callRecordAutostart) {
      boolean newValue = (Boolean) objValue;
      if (newValue) {
        final SharedPreferences prefs =
                getPreferenceManager().getDefaultSharedPreferences(getContext());
        boolean warningPresented = prefs.getBoolean(KEY_RECORDING_WARNING_PRESENTED, false);
        if (!warningPresented) {
          new AlertDialog.Builder(getActivity())
                  .setTitle(R.string.recording_warning_title)
                  .setMessage(R.string.recording_warning_text)
                  .setPositiveButton(R.string.onscreenCallRecordText, (dialog, which) -> {
                    prefs.edit()
                            .putBoolean(KEY_RECORDING_WARNING_PRESENTED, true)
                            .apply();
                    callRecordAutostart.setChecked(true);
                  })
                  .setNegativeButton(android.R.string.cancel, null)
                  .show();

          // At this time, it is unknown whether the user granted the permission
          return false;
        }
      }
    }
    return true;
  }
}
