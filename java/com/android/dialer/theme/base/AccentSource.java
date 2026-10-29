/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.theme.base;

public enum AccentSource {
  BRAND,
  WALLPAPER;

  public static AccentSource fromWallpaperEnabled(boolean enabled) {
    return enabled ? WALLPAPER : BRAND;
  }

  public static boolean isStale(AccentSource applied, AccentSource current) {
    return applied != null && applied != current;
  }
}
