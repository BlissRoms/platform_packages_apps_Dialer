/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.app.settings;

public enum CardPosition {
  SINGLE,
  TOP,
  MIDDLE,
  BOTTOM;

  public static CardPosition of(boolean previousIsCard, boolean nextIsCard) {
    if (previousIsCard) {
      return nextIsCard ? MIDDLE : BOTTOM;
    }
    return nextIsCard ? TOP : SINGLE;
  }
}
