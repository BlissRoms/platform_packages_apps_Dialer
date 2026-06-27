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

package com.android.incallui.contactgrid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

/** An ImageView that masks its content into a four-petal clover (quatrefoil) avatar silhouette. */
public class CloverImageView extends AppCompatImageView {

  /** Petal radius as a fraction of the smaller side. */
  private static final float PETAL_RADIUS_RATIO = 0.30f;
  /** Distance of each petal centre from the view centre, as a fraction of the smaller side. */
  private static final float PETAL_OFFSET_RATIO = 0.20f;

  private final Path clipPath = new Path();

  public CloverImageView(Context context) {
    super(context);
  }

  public CloverImageView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  public CloverImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    super.onSizeChanged(width, height, oldWidth, oldHeight);
    buildClipPath(width, height);
  }

  private void buildClipPath(int width, int height) {
    clipPath.reset();
    if (width == 0 || height == 0) {
      return;
    }
    float size = Math.min(width, height);
    float centerX = width / 2f;
    float centerY = height / 2f;
    float radius = size * PETAL_RADIUS_RATIO;
    float offset = size * PETAL_OFFSET_RATIO;

    float[][] centers = {
      {centerX - offset, centerY - offset},
      {centerX + offset, centerY - offset},
      {centerX - offset, centerY + offset},
      {centerX + offset, centerY + offset},
    };
    Path petal = new Path();
    for (float[] center : centers) {
      petal.reset();
      petal.addCircle(center[0], center[1], radius, Path.Direction.CW);
      clipPath.op(petal, Path.Op.UNION);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    if (clipPath.isEmpty()) {
      super.onDraw(canvas);
      return;
    }
    int save = canvas.save();
    canvas.clipPath(clipPath);
    super.onDraw(canvas);
    canvas.restoreToCount(save);
  }
}
