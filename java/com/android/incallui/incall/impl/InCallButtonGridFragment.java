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
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

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

  private static final int BUTTON_COUNT = 6;
  private static final int BUTTONS_PER_ROW = 3;
  /** Number of real controls shown in the collapsed sheet; the rest live in the expandable row. */
  private static final int FIRST_ROW_COUNT = 3;

  private final CheckableLabeledButton[] buttons = new CheckableLabeledButton[BUTTON_COUNT];
  private OnButtonGridCreatedListener buttonGridListener;

  private View sheet;
  private View handle;
  private View moreButton;
  private View secondaryRow;
  private boolean expanded;
  private boolean hasSecondaryRow;

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

    buttons[0] = ((CheckableLabeledButton) view.findViewById(R.id.incall_first_button));
    buttons[1] = ((CheckableLabeledButton) view.findViewById(R.id.incall_second_button));
    buttons[2] = ((CheckableLabeledButton) view.findViewById(R.id.incall_third_button));
    buttons[3] = ((CheckableLabeledButton) view.findViewById(R.id.incall_fourth_button));
    buttons[4] = ((CheckableLabeledButton) view.findViewById(R.id.incall_fifth_button));
    buttons[5] = ((CheckableLabeledButton) view.findViewById(R.id.incall_sixth_button));

    sheet = view.findViewById(R.id.incall_button_sheet);
    handle = view.findViewById(R.id.incall_button_sheet_handle);
    moreButton = view.findViewById(R.id.incall_more_button);
    secondaryRow = view.findViewById(R.id.incall_button_row_two);
    handle.setOnClickListener(v -> setSheetExpanded(!expanded));
    moreButton.setOnClickListener(v -> setSheetExpanded(!expanded));

    GestureDetector gestureDetector = new GestureDetector(getContext(), new SwipeListener());
    sheet.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

    return view;
  }

  private void setSheetExpanded(boolean expand) {
    if (!hasSecondaryRow) {
      expand = false;
    }
    expanded = expand;
    if (sheet != null) {
      TransitionManager.beginDelayedTransition((ViewGroup) sheet);
    }
    if (secondaryRow != null) {
      secondaryRow.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }
  }

  private void updateSheetAffordance() {
    if (handle == null) {
      return;
    }
    if (!hasSecondaryRow) {
      expanded = false;
    }
    handle.setVisibility(hasSecondaryRow ? View.VISIBLE : View.GONE);
    if (moreButton != null) {
      moreButton.setVisibility(hasSecondaryRow ? View.VISIBLE : View.GONE);
    }
    if (secondaryRow != null) {
      secondaryRow.setVisibility(expanded && hasSecondaryRow ? View.VISIBLE : View.GONE);
    }
  }

  /** Detects vertical flings on the control sheet to expand or collapse it. */
  private class SwipeListener extends GestureDetector.SimpleOnGestureListener {
    private static final int FLING_THRESHOLD = 200;

    @Override
    public boolean onDown(MotionEvent e) {
      return true;
    }

    @Override
    public boolean onFling(
        MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
      if (velocityY < -FLING_THRESHOLD) {
        setSheetExpanded(true);
        return true;
      }
      if (velocityY > FLING_THRESHOLD) {
        setSheetExpanded(false);
        return true;
      }
      return false;
    }
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
    for (CheckableLabeledButton button : buttons) {
      button.setImportantForAccessibility(
          isShowing
              ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
              : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
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

    if (buttonChooser == null) {
      buttonChooser =
          ButtonChooserFactory.newButtonChooser(voiceNetworkType, false /* isWiFi */, phoneType);
    }

    int numVisibleButtons = getResources().getInteger(R.integer.incall_num_rows) * BUTTONS_PER_ROW;
    List<Integer> buttonsToPlace =
        buttonChooser.getButtonPlacement(numVisibleButtons, allowedButtons, disabledButtons);

    for (int i = 0; i < BUTTON_COUNT; ++i) {
      if (i >= buttonsToPlace.size()) {
        buttons[i].setVisibility(View.INVISIBLE);
        continue;
      }
      @InCallButtonIds int button = buttonsToPlace.get(i);
      buttonGridListener.getButtonController(button).setButton(buttons[i]);
    }

    hasSecondaryRow = buttonsToPlace.size() > FIRST_ROW_COUNT;
    updateSheetAffordance();

    return numVisibleButtons;
  }

  /** Interface to let the listener know the status of the button grid. */
  public interface OnButtonGridCreatedListener {
    void onButtonGridCreated(InCallButtonGridFragment inCallButtonGridFragment);
    void onButtonGridDestroyed();

    ButtonController getButtonController(@InCallButtonIds int id);
  }
}
