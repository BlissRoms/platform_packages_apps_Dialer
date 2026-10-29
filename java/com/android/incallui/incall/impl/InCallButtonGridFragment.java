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
 * limitations under the License.
 */

package com.android.incallui.incall.impl;

import android.os.Bundle;
import android.transition.TransitionManager;
import android.util.ArraySet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroup.MarginLayoutParams;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.dialer.R;
import com.android.dialer.common.Assert;
import com.android.dialer.common.FragmentUtils;
import com.android.incallui.incall.protocol.InCallButtonIds;

import java.util.List;
import java.util.Set;

/** Fragment for the in call buttons (mute, speaker, ect.). */
public class InCallButtonGridFragment extends Fragment {

  private static final int[][] PRIMARY_SLOTS = {
    {InCallButtonIds.BUTTON_MERGE, InCallButtonIds.BUTTON_ADD_CALL},
    {InCallButtonIds.BUTTON_UPGRADE_TO_VIDEO, InCallButtonIds.BUTTON_SWAP},
    {InCallButtonIds.BUTTON_DIALPAD},
    {InCallButtonIds.BUTTON_MUTE},
    {InCallButtonIds.BUTTON_AUDIO},
    {InCallButtonIds.BUTTON_RECORD_CALL, InCallButtonIds.BUTTON_HOLD},
  };
  private static final int WIDE_SLOT_COUNT = 2;
  private static final int EXTRAS_PER_ROW = 4;
  private static final int[] PRIMARY_BUTTON_IDS = {
    R.id.incall_first_button,
    R.id.incall_second_button,
    R.id.incall_third_button,
    R.id.incall_fourth_button,
    R.id.incall_fifth_button,
    R.id.incall_sixth_button,
  };
  private static final int[] EXTRA_BUTTON_IDS = {
    R.id.incall_extra_button_1,
    R.id.incall_extra_button_2,
    R.id.incall_extra_button_3,
    R.id.incall_extra_button_4,
    R.id.incall_extra_button_5,
    R.id.incall_extra_button_6,
    R.id.incall_extra_button_7,
    R.id.incall_extra_button_8,
  };

  private final CheckableLabeledButton[] primaryButtons =
      new CheckableLabeledButton[PRIMARY_BUTTON_IDS.length];
  private final CheckableLabeledButton[] extraButtons =
      new CheckableLabeledButton[EXTRA_BUTTON_IDS.length];
  private OnButtonGridCreatedListener buttonGridListener;

  private ViewGroup sheet;
  private View extraContainer;
  private View extraRowTwo;
  private View primaryRowOne;
  private View primaryRowTwo;
  private boolean extrasExpanded;
  private boolean hasExtras;

  public static Fragment newInstance() {
    return new InCallButtonGridFragment();
  }

  @Override
  public void onCreate(@Nullable Bundle bundle) {
    super.onCreate(bundle);
    buttonGridListener = FragmentUtils.getParent(this, OnButtonGridCreatedListener.class);
    Assert.isNotNull(buttonGridListener);
  }

  @Nullable
  @Override
  public View onCreateView(
      LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle bundle) {
    View view = inflater.inflate(R.layout.incall_button_grid, parent, false);
    for (int i = 0; i < PRIMARY_BUTTON_IDS.length; i++) {
      primaryButtons[i] = view.findViewById(PRIMARY_BUTTON_IDS[i]);
    }
    for (int i = 0; i < EXTRA_BUTTON_IDS.length; i++) {
      extraButtons[i] = view.findViewById(EXTRA_BUTTON_IDS[i]);
    }
    sheet = view.findViewById(R.id.incall_button_sheet);
    extraContainer = view.findViewById(R.id.incall_extra_buttons);
    extraRowTwo = view.findViewById(R.id.incall_extra_row_two);
    primaryRowOne = view.findViewById(R.id.incall_button_row_one);
    primaryRowTwo = view.findViewById(R.id.incall_button_row_two);
    extrasExpanded = false;
    return view;
  }

  public boolean toggleExtras() {
    setExtrasExpanded(!extrasExpanded);
    return extrasExpanded;
  }

  private void setExtrasExpanded(boolean expand) {
    extrasExpanded = expand && hasExtras;
    if (sheet == null) {
      return;
    }
    TransitionManager.beginDelayedTransition(sheet);
    extraContainer.setVisibility(extrasExpanded ? View.VISIBLE : View.GONE);
  }

  @Override
  public void onViewCreated(View view, @Nullable Bundle bundle) {
    super.onViewCreated(view, bundle);
    buttonGridListener.onButtonGridCreated(this);
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    buttonGridListener.onButtonGridDestroyed();
  }

  public void onInCallScreenDialpadVisibilityChange(boolean isShowing) {
    int importance =
        isShowing
            ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
    for (CheckableLabeledButton button : primaryButtons) {
      button.setImportantForAccessibility(importance);
    }
    for (CheckableLabeledButton button : extraButtons) {
      button.setImportantForAccessibility(importance);
    }
  }

  public int updateButtonStates(
      List<ButtonController> buttonControllers,
      @Nullable ButtonChooser buttonChooser,
      int voiceNetworkType,
      int phoneType) {
    Set<Integer> allowedButtons = new ArraySet<>();
    Set<Integer> disabledButtons = new ArraySet<>();
    for (ButtonController controller : buttonControllers) {
      if (controller.isAllowed()) {
        allowedButtons.add(controller.getInCallButtonId());
        if (!controller.isEnabled()) {
          disabledButtons.add(controller.getInCallButtonId());
        }
      }
    }

    for (ButtonController controller : buttonControllers) {
      controller.setButton(null);
    }

    Set<Integer> placedButtons = new ArraySet<>();
    int firstPrimarySlot = firstPrimarySlot();
    for (int slot = 0; slot < PRIMARY_SLOTS.length; slot++) {
      Integer button =
          slot < firstPrimarySlot
              ? null
              : firstAvailable(PRIMARY_SLOTS[slot], allowedButtons, placedButtons);
      if (button == null) {
        primaryButtons[slot].setVisibility(View.GONE);
        continue;
      }
      placedButtons.add(button);
      buttonGridListener.getButtonController(button).setButton(primaryButtons[slot]);
    }
    primaryRowOne.setVisibility(rowVisibility(0, WIDE_SLOT_COUNT));
    primaryRowTwo.setVisibility(rowVisibility(WIDE_SLOT_COUNT, PRIMARY_SLOTS.length));
    MarginLayoutParams rowTwoParams = (MarginLayoutParams) primaryRowTwo.getLayoutParams();
    rowTwoParams.topMargin =
        primaryRowOne.getVisibility() == View.VISIBLE
            ? getResources().getDimensionPixelSize(R.dimen.incall_button_row_gap)
            : 0;
    primaryRowTwo.setLayoutParams(rowTwoParams);

    Set<Integer> remainingButtons = new ArraySet<>(allowedButtons);
    remainingButtons.removeAll(placedButtons);
    if (buttonChooser == null) {
      buttonChooser =
          ButtonChooserFactory.newButtonChooser(voiceNetworkType, false /* isWiFi */, phoneType);
    }
    List<Integer> extras =
        buttonChooser.getButtonPlacement(
            EXTRA_BUTTON_IDS.length, remainingButtons, disabledButtons);
    for (int i = 0; i < extraButtons.length; i++) {
      if (i < extras.size()) {
        buttonGridListener.getButtonController(extras.get(i)).setButton(extraButtons[i]);
      } else {
        extraButtons[i].setVisibility(View.INVISIBLE);
      }
    }
    extraRowTwo.setVisibility(extras.size() > EXTRAS_PER_ROW ? View.VISIBLE : View.GONE);

    hasExtras = !extras.isEmpty();
    if (!hasExtras) {
      setExtrasExpanded(false);
    }
    buttonGridListener.onMoreAvailabilityChanged(hasExtras);
    return placedButtons.size() + extras.size();
  }

  private int firstPrimarySlot() {
    int rows = getResources().getInteger(R.integer.incall_num_rows);
    if (rows >= 2) {
      return 0;
    }
    return rows == 1 ? WIDE_SLOT_COUNT : PRIMARY_SLOTS.length;
  }

  @Nullable
  private static Integer firstAvailable(
      int[] candidates, Set<Integer> allowedButtons, Set<Integer> placedButtons) {
    for (int candidate : candidates) {
      if (allowedButtons.contains(candidate) && !placedButtons.contains(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private int rowVisibility(int fromSlot, int toSlot) {
    for (int slot = fromSlot; slot < toSlot; slot++) {
      if (primaryButtons[slot].getVisibility() != View.GONE) {
        return View.VISIBLE;
      }
    }
    return View.GONE;
  }

  /** Interface to let the listener know the status of the button grid. */
  public interface OnButtonGridCreatedListener {
    void onButtonGridCreated(InCallButtonGridFragment inCallButtonGridFragment);
    void onButtonGridDestroyed();

    ButtonController getButtonController(@InCallButtonIds int id);

    void onMoreAvailabilityChanged(boolean available);
  }
}
