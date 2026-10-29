/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.theme.base;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.android.dialer.R;

public final class ThemePreferences {

  private final SharedPreferences prefs;
  private final String themeModeKey;
  private final String wallpaperColorKey;

  public ThemePreferences(Context context) {
    Context storageContext = context.createDeviceProtectedStorageContext();
    prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
    themeModeKey = context.getString(R.string.dialer_theme_mode_key);
    wallpaperColorKey = context.getString(R.string.dialer_wallpaper_color_key);
  }

  public ThemeMode getThemeMode() {
    return ThemeMode.fromPrefValue(prefs.getString(themeModeKey, null));
  }

  public void setThemeMode(ThemeMode mode) {
    prefs.edit().putString(themeModeKey, mode.prefValue()).apply();
  }

  public boolean isWallpaperColorEnabled() {
    return prefs.getBoolean(wallpaperColorKey, false);
  }

  public void setWallpaperColorEnabled(boolean enabled) {
    prefs.edit().putBoolean(wallpaperColorKey, enabled).apply();
  }

  public AccentSource getAccentSource() {
    return AccentSource.fromWallpaperEnabled(isWallpaperColorEnabled());
  }
}
