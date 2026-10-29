/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.speeddial;

import android.content.Context;

import androidx.fragment.app.FragmentManager;

import com.android.dialer.callintent.CallInitiationType;
import com.android.dialer.callintent.CallIntentBuilder;
import com.android.dialer.precall.PreCall;
import com.android.dialer.speeddial.database.SpeedDialEntry.Channel;
import com.android.dialer.speeddial.loader.SpeedDialUiItem;

public final class ContactCalls {

  private ContactCalls() {}

  public static void call(
      Context context,
      SpeedDialUiItem speedDialUiItem,
      FragmentManager fragmentManager,
      CallInitiationType.Type initiationType) {
    if (speedDialUiItem.defaultChannel() != null) {
      placeCall(context, speedDialUiItem.defaultChannel(), initiationType);
    } else if (speedDialUiItem.channels().size() == 1) {
      placeCall(context, speedDialUiItem.channels().get(0), initiationType);
    } else if (!speedDialUiItem.channels().isEmpty()) {
      DisambigDialog.show(speedDialUiItem, fragmentManager);
    }
  }

  public static void placeCall(
      Context context, Channel channel, CallInitiationType.Type initiationType) {
    PreCall.start(
        context,
        new CallIntentBuilder(channel.number(), initiationType)
            .setAllowAssistedDial(true)
            .setIsVideoCall(channel.isVideoTechnology()));
  }
}
