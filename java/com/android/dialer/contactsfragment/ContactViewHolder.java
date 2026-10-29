/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.dialer.contactsfragment;

import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup.MarginLayoutParams;
import android.widget.QuickContactBadge;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import com.android.dialer.R;
import com.android.dialer.common.Assert;
import com.android.dialer.contactsfragment.ContactsFragment.OnContactSelectedListener;
import com.android.dialer.theme.base.CardPosition;
import com.android.dialer.widget.BidiTextView;

import java.util.function.LongConsumer;

/** View holder for a contact. */
final class ContactViewHolder extends RecyclerView.ViewHolder implements OnClickListener {

  private final TextView header;
  private final View card;
  private final BidiTextView name;
  private final QuickContactBadge photo;
  private final View callButton;
  private final OnContactSelectedListener onContactSelectedListener;

  private Uri contactUri;
  private long contactId;

  ContactViewHolder(
      View itemView,
      OnContactSelectedListener onContactSelectedListener,
      LongConsumer onContactCallClicked) {
    super(itemView);
    this.onContactSelectedListener = Assert.isNotNull(onContactSelectedListener);
    card = itemView.findViewById(R.id.click_target);
    card.setOnClickListener(this);
    header = itemView.findViewById(R.id.header);
    name = itemView.findViewById(R.id.contact_name);
    photo = itemView.findViewById(R.id.photo);
    photo.setClickable(false);
    callButton = itemView.findViewById(R.id.call_button);
    callButton.setOnClickListener(v -> onContactCallClicked.accept(contactId));
  }

  public void bind(
      String headerText,
      String displayName,
      Uri contactUri,
      long contactId,
      CardPosition position,
      boolean hasPhoneNumber,
      boolean selected) {
    Assert.checkArgument(!TextUtils.isEmpty(displayName));
    this.contactUri = contactUri;
    this.contactId = contactId;

    name.setText(displayName);
    header.setText(headerText);
    header.setVisibility(
        position == CardPosition.TOP || position == CardPosition.SINGLE ? View.VISIBLE : View.GONE);
    card.setBackgroundResource(CardPosition.background(position));
    card.setActivated(selected);
    MarginLayoutParams params = (MarginLayoutParams) card.getLayoutParams();
    params.bottomMargin =
        position.hasRowBelow()
            ? itemView.getResources().getDimensionPixelSize(R.dimen.contact_row_gap)
            : 0;
    card.setLayoutParams(params);
    callButton.setVisibility(hasPhoneNumber ? View.VISIBLE : View.GONE);
    callButton.setContentDescription(
        TextUtils.expandTemplate(
            itemView.getResources().getText(R.string.description_call_action), displayName));
  }

  public QuickContactBadge getPhoto() {
    return photo;
  }

  @Override
  public void onClick(View v) {
    onContactSelectedListener.onContactSelected(photo, contactUri, contactId);
  }
}
