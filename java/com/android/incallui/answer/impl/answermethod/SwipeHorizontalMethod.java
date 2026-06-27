/*
 * Copyright (C) 2026 The BlissRoms Project
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

package com.android.incallui.answer.impl.answermethod;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.FloatRange;
import androidx.annotation.Nullable;

import com.android.dialer.R;
import com.android.dialer.common.LogUtil;

/**
 * Answer method that shows a horizontally draggable puck inside a pill track. Swiping the puck to
 * the right answers the call, swiping it to the left rejects it.
 */
public class SwipeHorizontalMethod extends AnswerMethod {

  private static final String STATE_HINT_TEXT = "hintText";
  private static final String STATE_INCOMING_WILL_DISCONNECT = "incomingWillDisconnect";

  /** Fraction of the available travel the puck must cross to trigger an action. */
  private static final float ACTION_THRESHOLD = 0.5f;

  private static final int SETTLE_DURATION_MS = 150;
  private static final int SNAP_BACK_DURATION_MS = 200;

  private View puck;
  private TextView declineLabel;
  private TextView answerLabel;
  private TextView hintTextView;

  private CharSequence hintText;
  private boolean incomingWillDisconnect;
  private boolean actionPerformed;

  private float downRawX;
  private float puckStartTranslationX;
  private float maxTranslation;
  private boolean tracking;
  private int minFlingVelocity;
  @Nullable private VelocityTracker velocityTracker;

  @Override
  public void onCreate(@Nullable Bundle bundle) {
    super.onCreate(bundle);
    if (bundle != null) {
      incomingWillDisconnect = bundle.getBoolean(STATE_INCOMING_WILL_DISCONNECT);
      hintText = bundle.getCharSequence(STATE_HINT_TEXT);
    }
    minFlingVelocity = ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity();
  }

  @Override
  public void onSaveInstanceState(Bundle bundle) {
    super.onSaveInstanceState(bundle);
    bundle.putBoolean(STATE_INCOMING_WILL_DISCONNECT, incomingWillDisconnect);
    bundle.putCharSequence(STATE_HINT_TEXT, hintText);
  }

  @Nullable
  @Override
  @SuppressLint("ClickableViewAccessibility")
  public View onCreateView(
      LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle bundle) {
    View view = inflater.inflate(R.layout.swipe_horizontal_method, container, false);
    puck = view.findViewById(R.id.swipe_horizontal_puck);
    declineLabel = view.findViewById(R.id.swipe_horizontal_decline_label);
    answerLabel = view.findViewById(R.id.swipe_horizontal_answer_label);
    hintTextView = view.findViewById(R.id.swipe_horizontal_hint_text);
    updateHintText();

    puck.setOnTouchListener((v, event) -> handleTouch(event));
    return view;
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (velocityTracker != null) {
      velocityTracker.recycle();
      velocityTracker = null;
    }
  }

  private boolean handleTouch(MotionEvent event) {
    if (actionPerformed) {
      return false;
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        downRawX = event.getRawX();
        puckStartTranslationX = puck.getTranslationX();
        maxTranslation = computeMaxTranslation();
        tracking = true;
        puck.animate().cancel();
        velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!tracking) {
          return false;
        }
        if (velocityTracker != null) {
          velocityTracker.addMovement(event);
        }
        float translationX =
            clamp(puckStartTranslationX + event.getRawX() - downRawX, -maxTranslation, maxTranslation);
        puck.setTranslationX(translationX);
        updateProgress(maxTranslation == 0 ? 0 : translationX / maxTranslation);
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (!tracking) {
          return false;
        }
        tracking = false;
        float velocityX = 0;
        if (velocityTracker != null) {
          velocityTracker.addMovement(event);
          velocityTracker.computeCurrentVelocity(1000);
          velocityX = velocityTracker.getXVelocity();
          velocityTracker.recycle();
          velocityTracker = null;
        }
        finishGesture(velocityX);
        return true;
      default:
        return false;
    }
  }

  private void finishGesture(float velocityX) {
    float translationX = puck.getTranslationX();
    boolean answer =
        translationX >= maxTranslation * ACTION_THRESHOLD || velocityX > minFlingVelocity;
    boolean reject =
        translationX <= -maxTranslation * ACTION_THRESHOLD || velocityX < -minFlingVelocity;
    if (answer && translationX >= 0) {
      settle(true);
    } else if (reject && translationX <= 0) {
      settle(false);
    } else {
      snapBack();
    }
  }

  private void settle(boolean accept) {
    actionPerformed = true;
    float target = accept ? maxTranslation : -maxTranslation;
    ObjectAnimator animator = ObjectAnimator.ofFloat(puck, View.TRANSLATION_X, target);
    animator.setDuration(SETTLE_DURATION_MS);
    animator.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            updateProgress(accept ? 1f : -1f);
            if (accept) {
              LogUtil.i("SwipeHorizontalMethod.settle", "Call answered");
              getParent().answerFromMethod();
            } else {
              LogUtil.i("SwipeHorizontalMethod.settle", "Call rejected");
              getParent().rejectFromMethod();
            }
          }
        });
    animator.start();
  }

  private void snapBack() {
    ObjectAnimator animator = ObjectAnimator.ofFloat(puck, View.TRANSLATION_X, 0f);
    animator.setDuration(SNAP_BACK_DURATION_MS);
    animator.addUpdateListener(
        animation -> updateProgress(maxTranslation == 0 ? 0 : (float) animation.getAnimatedValue() / maxTranslation));
    animator.start();
  }

  private float computeMaxTranslation() {
    View track = (View) puck.getParent();
    float edgePadding = getResources().getDimension(R.dimen.swipe_horizontal_edge_padding);
    return Math.max(0f, track.getWidth() / 2f - puck.getWidth() / 2f - edgePadding);
  }

  private void updateProgress(@FloatRange(from = -1f, to = 1f) float progress) {
    float clamped = clamp(progress, -1f, 1f);
    getParent().onAnswerProgressUpdate(clamped);
    // Fade out the label on the opposite side of travel.
    answerLabel.setAlpha(1f - Math.max(0f, -clamped));
    declineLabel.setAlpha(1f - Math.max(0f, clamped));
  }

  private static float clamp(float value, float min, float max) {
    return Math.max(min, Math.min(max, value));
  }

  @Override
  public void setHintText(@Nullable CharSequence hintText) {
    this.hintText = hintText;
    updateHintText();
  }

  @Override
  public void setShowIncomingWillDisconnect(boolean incomingWillDisconnect) {
    this.incomingWillDisconnect = incomingWillDisconnect;
    updateHintText();
  }

  private void updateHintText() {
    if (hintTextView == null) {
      return;
    }
    if (!TextUtils.isEmpty(hintText) && !actionPerformed) {
      hintTextView.setText(hintText);
      hintTextView.animate().alpha(1f).start();
    } else if (incomingWillDisconnect && !actionPerformed) {
      hintTextView.setText(R.string.call_incoming_will_disconnect);
      hintTextView.animate().alpha(1f).start();
    } else {
      hintTextView.animate().alpha(0f).start();
    }
  }
}
