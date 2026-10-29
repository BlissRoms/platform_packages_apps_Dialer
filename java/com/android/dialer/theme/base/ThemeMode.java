/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.theme.base;

public enum ThemeMode {
  AUTOMATIC("automatic"),
  LIGHT("light"),
  DARK("dark");

  private final String prefValue;

  ThemeMode(String prefValue) {
    this.prefValue = prefValue;
  }

  public String prefValue() {
    return prefValue;
  }

  public static ThemeMode fromPrefValue(String value) {
    for (ThemeMode mode : values()) {
      if (mode.prefValue.equals(value)) {
        return mode;
      }
    }
    return AUTOMATIC;
  }
}
