/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.theme.base;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.android.dialer.R;

public final class ThemePreferences {

  private final SharedPreferences prefs;
  private final String themeModeKey;
  private final String wallpaperColorKey;
  private final String hideInCallLabelsKey;

  public ThemePreferences(Context context) {
    Context storageContext = context.createDeviceProtectedStorageContext();
    prefs = PreferenceManager.getDefaultSharedPreferences(storageContext);
    themeModeKey = context.getString(R.string.dialer_theme_mode_key);
    wallpaperColorKey = context.getString(R.string.dialer_wallpaper_color_key);
    hideInCallLabelsKey = context.getString(R.string.dialer_hide_incall_labels_key);
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

  public boolean isInCallLabelsHidden() {
    return prefs.getBoolean(hideInCallLabelsKey, false);
  }

  public void setInCallLabelsHidden(boolean hidden) {
    prefs.edit().putBoolean(hideInCallLabelsKey, hidden).apply();
  }

  public boolean isThemeModeKey(@Nullable String key) {
    return themeModeKey.equals(key);
  }

  public void registerOnChangeListener(
      SharedPreferences.OnSharedPreferenceChangeListener listener) {
    prefs.registerOnSharedPreferenceChangeListener(listener);
  }

  public void unregisterOnChangeListener(
      SharedPreferences.OnSharedPreferenceChangeListener listener) {
    prefs.unregisterOnSharedPreferenceChangeListener(listener);
  }

  public AccentSource getAccentSource() {
    return AccentSource.fromWallpaperEnabled(isWallpaperColorEnabled());
  }
}
