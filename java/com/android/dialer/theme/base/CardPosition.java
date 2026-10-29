/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.theme.base;

import androidx.annotation.DrawableRes;

import com.android.dialer.R;

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

  @DrawableRes
  public static int clipBackground(CardPosition position) {
    switch (position) {
      case TOP:
        return R.drawable.dialer_card_clip_top;
      case MIDDLE:
        return R.drawable.dialer_card_clip_middle;
      case BOTTOM:
        return R.drawable.dialer_card_clip_bottom;
      case SINGLE:
      default:
        return R.drawable.dialer_card_clip_single;
    }
  }

  public boolean hasRowBelow() {
    return this == TOP || this == MIDDLE;
  }

  @DrawableRes
  public static int background(CardPosition position) {
    switch (position) {
      case TOP:
        return R.drawable.dialer_card_top;
      case MIDDLE:
        return R.drawable.dialer_card_middle;
      case BOTTOM:
        return R.drawable.dialer_card_bottom;
      case SINGLE:
      default:
        return R.drawable.dialer_card_single;
    }
  }
}
