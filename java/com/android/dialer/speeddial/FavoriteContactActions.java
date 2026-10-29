/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.speeddial;

import android.content.Intent;
import android.net.Uri;
import android.provider.ContactsContract.Contacts;

import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.android.dialer.callintent.CallInitiationType;
import com.android.dialer.callintent.CallIntentBuilder;
import com.android.dialer.common.concurrent.SupportUiListener;
import com.android.dialer.precall.PreCall;
import com.android.dialer.speeddial.ContextMenu.ContextMenuItemListener;
import com.android.dialer.speeddial.database.SpeedDialEntry.Channel;
import com.android.dialer.speeddial.loader.SpeedDialUiItem;
import com.android.dialer.speeddial.loader.UiItemLoaderComponent;
import com.android.dialer.util.IntentUtil;
import com.google.common.collect.ImmutableList;

import java.util.function.Consumer;

public final class FavoriteContactActions implements ContextMenuItemListener {

  private final FragmentActivity activity;
  private final SupportUiListener<ImmutableList<SpeedDialUiItem>> loaderListener;
  private final Consumer<ImmutableList<SpeedDialUiItem>> onFavoritesChanged;

  public FavoriteContactActions(
      FragmentActivity activity,
      SupportUiListener<ImmutableList<SpeedDialUiItem>> loaderListener,
      Consumer<ImmutableList<SpeedDialUiItem>> onFavoritesChanged) {
    this.activity = activity;
    this.loaderListener = loaderListener;
    this.onFavoritesChanged = onFavoritesChanged;
  }

  public void call(SpeedDialUiItem speedDialUiItem, FragmentManager fragmentManager) {
    if (speedDialUiItem.defaultChannel() != null) {
      placeCall(speedDialUiItem.defaultChannel());
    } else if (speedDialUiItem.channels().size() == 1) {
      placeCall(speedDialUiItem.channels().get(0));
    } else {
      DisambigDialog.show(speedDialUiItem, fragmentManager);
    }
  }

  @Override
  public void placeCall(Channel channel) {
    PreCall.start(
        activity,
        new CallIntentBuilder(channel.number(), CallInitiationType.Type.SPEED_DIAL)
            .setAllowAssistedDial(true)
            .setIsVideoCall(channel.isVideoTechnology()));
  }

  @Override
  public void openSmsConversation(String number) {
    activity.startActivity(IntentUtil.getSendSmsIntent(number));
  }

  @Override
  public void removeFavoriteContact(SpeedDialUiItem speedDialUiItem) {
    loaderListener.listen(
        activity,
        UiItemLoaderComponent.get(activity).speedDialUiItemMutator()
            .removeSpeedDialUiItem(speedDialUiItem),
        onFavoritesChanged::accept,
        throwable -> {
          throw new RuntimeException(throwable);
        });
  }

  @Override
  public void openContactInfo(SpeedDialUiItem speedDialUiItem) {
    activity.startActivity(
        new Intent(
            Intent.ACTION_VIEW,
            Uri.withAppendedPath(
                Contacts.CONTENT_URI, String.valueOf(speedDialUiItem.contactId()))));
  }
}
