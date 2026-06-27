/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.incallui.answer.impl;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.dialer.R;
import com.android.dialer.common.DpUtil;
import com.android.dialer.common.FragmentUtils;
import com.android.dialer.common.LogUtil;
import com.android.incallui.incalluilock.InCallUiLock;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

/** Shows options for rejecting call with SMS */
public class SmsBottomSheetFragment extends BottomSheetDialogFragment {

  private static final String ARG_OPTIONS = "options";

  private InCallUiLock inCallUiLock;

  public static SmsBottomSheetFragment newInstance(@Nullable ArrayList<CharSequence> options) {
    SmsBottomSheetFragment fragment = new SmsBottomSheetFragment();
    Bundle args = new Bundle();
    args.putCharSequenceArrayList(ARG_OPTIONS, options);
    fragment.setArguments(args);
    return fragment;
  }

  @Nullable
  @Override
  public View onCreateView(
          LayoutInflater layoutInflater, @Nullable ViewGroup viewGroup, @Nullable Bundle bundle) {
    Context context = getContext();
    LinearLayout layout = new LinearLayout(context);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setBackground(context.getDrawable(R.drawable.quick_response_sheet_bg));
    layout.setPadding(0, dp(context, 12), 0, dp(context, 24));
    layout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

    View handle = new View(context);
    LinearLayout.LayoutParams handleParams =
        new LinearLayout.LayoutParams(dp(context, 32), dp(context, 4));
    handleParams.gravity = Gravity.CENTER_HORIZONTAL;
    handleParams.bottomMargin = dp(context, 12);
    handle.setLayoutParams(handleParams);
    handle.setBackground(context.getDrawable(R.drawable.quick_response_handle));
    layout.addView(handle);

    TextView title = new TextView(context);
    title.setText(R.string.qr_sheet_title);
    title.setTextColor(context.getColor(R.color.qr_on_surface));
    title.setTextSize(22);
    title.setPadding(dp(context, 24), dp(context, 4), dp(context, 24), dp(context, 12));
    layout.addView(title);

    List<CharSequence> items = getArguments().getCharSequenceArrayList(ARG_OPTIONS);
    if (items != null) {
      for (CharSequence item : items) {
        layout.addView(newItem(context, item));
      }
    }
    layout.addView(newItem(context, null));
    return layout;
  }

  private static int dp(Context context, int value) {
    return (int) DpUtil.dpToPx(context, value);
  }

  @Override
  public void onAttach(Context context) {
    super.onAttach(context);
    FragmentUtils.checkParent(this, SmsSheetHolder.class);
  }

  @Override
  public Dialog onCreateDialog(final Bundle savedInstanceState) {
    LogUtil.i("SmsBottomSheetFragment.onCreateDialog", null);
    Dialog dialog = super.onCreateDialog(savedInstanceState);
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    // Let our rounded surface show by clearing the default sheet background.
    dialog.setOnShowListener(
        d -> {
          View sheet =
              ((BottomSheetDialog) d)
                  .findViewById(com.google.android.material.R.id.design_bottom_sheet);
          if (sheet != null) {
            sheet.setBackgroundColor(Color.TRANSPARENT);
          }
        });

    inCallUiLock =
        FragmentUtils.getParentUnsafe(SmsBottomSheetFragment.this, SmsSheetHolder.class)
            .acquireInCallUiLock("SmsBottomSheetFragment");
    return dialog;
  }

  private View newItem(Context context, @Nullable final CharSequence text) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(context, 56));
    int padH = dp(context, 24);
    int padV = dp(context, 12);
    row.setPadding(padH, padV, padH, padV);
    row.setBackground(context.getDrawable(R.drawable.quick_response_item_bg));
    LinearLayout.LayoutParams rowParams =
        new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    int marginH = dp(context, 12);
    rowParams.setMargins(marginH, 0, marginH, 0);
    row.setLayoutParams(rowParams);

    ImageView icon = new ImageView(context);
    icon.setImageResource(
        text == null
            ? R.drawable.quantum_ic_edit_vd_theme_24
            : R.drawable.quantum_ic_message_vd_theme_24);
    icon.setImageTintList(ColorStateList.valueOf(context.getColor(R.color.qr_icon)));
    LinearLayout.LayoutParams iconParams =
        new LinearLayout.LayoutParams(dp(context, 24), dp(context, 24));
    iconParams.setMarginEnd(dp(context, 20));
    icon.setLayoutParams(iconParams);
    row.addView(icon);

    TextView textView = new TextView(context);
    textView.setText(text == null ? getString(R.string.call_incoming_message_custom) : text);
    textView.setTextColor(context.getColor(R.color.qr_on_surface));
    textView.setTextSize(16);
    textView.setLayoutParams(
        new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
    row.addView(textView);

    row.setOnClickListener(
            v -> {
              FragmentUtils.getParentUnsafe(SmsBottomSheetFragment.this, SmsSheetHolder.class)
                  .smsSelected(text);
              dismiss();
            });
    return row;
  }

  @Override
  public int getTheme() {
    return R.style.Theme_Design_Light_BottomSheetDialog;
  }

  @Override
  public void onDismiss(DialogInterface dialogInterface) {
    super.onDismiss(dialogInterface);
    FragmentUtils.getParentUnsafe(this, SmsSheetHolder.class).smsDismissed();
    inCallUiLock.release();
  }

  /** Callback interface for {@link SmsBottomSheetFragment} */
  public interface SmsSheetHolder {

    InCallUiLock acquireInCallUiLock(String tag);

    void smsSelected(@Nullable CharSequence text);

    void smsDismissed();
  }
}
