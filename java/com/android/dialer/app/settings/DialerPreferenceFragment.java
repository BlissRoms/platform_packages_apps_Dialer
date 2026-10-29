/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.app.settings;

import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.android.dialer.R;

public abstract class DialerPreferenceFragment extends PreferenceFragmentCompat {

  @NonNull
  @Override
  protected RecyclerView.Adapter onCreateAdapter(@NonNull PreferenceScreen preferenceScreen) {
    return new CardGroupAdapter(preferenceScreen);
  }

  private static final class CardGroupAdapter extends PreferenceGroupAdapter {

    CardGroupAdapter(PreferenceGroup preferenceGroup) {
      super(preferenceGroup);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
      super.onBindViewHolder(holder, position);
      if (!isCard(position)) {
        return;
      }
      CardPosition cardPosition = CardPosition.of(isCard(position - 1), isCard(position + 1));
      holder.itemView.setBackgroundResource(backgroundFor(cardPosition));
      ViewGroup.LayoutParams params = holder.itemView.getLayoutParams();
      if (params instanceof ViewGroup.MarginLayoutParams) {
        boolean hasRowBelow = cardPosition == CardPosition.TOP || cardPosition == CardPosition.MIDDLE;
        ((ViewGroup.MarginLayoutParams) params).bottomMargin =
            hasRowBelow
                ? holder.itemView.getResources().getDimensionPixelSize(R.dimen.settings_row_gap)
                : 0;
        holder.itemView.setLayoutParams(params);
      }
    }

    private boolean isCard(int position) {
      if (position < 0 || position >= getItemCount()) {
        return false;
      }
      Preference preference = getItem(position);
      return preference != null && !(preference instanceof PreferenceCategory);
    }

    @DrawableRes
    private static int backgroundFor(CardPosition cardPosition) {
      switch (cardPosition) {
        case TOP:
          return R.drawable.settings_card_top;
        case MIDDLE:
          return R.drawable.settings_card_middle;
        case BOTTOM:
          return R.drawable.settings_card_bottom;
        case SINGLE:
        default:
          return R.drawable.settings_card_single;
      }
    }
  }
}
