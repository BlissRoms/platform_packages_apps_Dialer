/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2023 The LineageOS Project
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
 * limitations under the License
 */

package com.android.dialer.app.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.android.dialer.R;
import com.android.dialer.theme.base.DialerThemeApplier;
import com.android.dialer.theme.base.ThemeMode;
import com.android.dialer.theme.base.ThemePreferences;

public class DisplayOptionsSettingsFragment extends PreferenceFragmentCompat {

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
    addPreferencesFromResource(R.xml.display_options_settings);
    ThemePreferences themePreferences = new ThemePreferences(requireContext());

    Preference contactsCategory =
        findPreference(getString(R.string.display_options_contacts_category_key));
    contactsCategory.setVisible(isContactOrderChangeable());

    ListPreference themeMode = findPreference(getString(R.string.dialer_theme_mode_key));
    themeMode.setValue(themePreferences.getThemeMode().prefValue());
    themeMode.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          ThemeMode mode = ThemeMode.fromPrefValue((String) newValue);
          themePreferences.setThemeMode(mode);
          DialerThemeApplier.applyNightMode(requireContext(), mode);
          return true;
        });

    SwitchPreferenceCompat wallpaperColor =
        findPreference(getString(R.string.dialer_wallpaper_color_key));
    wallpaperColor.setChecked(themePreferences.isWallpaperColorEnabled());
    wallpaperColor.setOnPreferenceChangeListener(
        (preference, newValue) -> {
          themePreferences.setWallpaperColorEnabled((Boolean) newValue);
          DialerThemeApplier.onAccentChanged(requireContext());
          requireActivity().recreate();
          return true;
        });
  }

  private boolean isContactOrderChangeable() {
    return getResources().getBoolean(R.bool.config_display_order_user_changeable)
        && getResources().getBoolean(R.bool.config_sort_order_user_changeable);
  }
}
