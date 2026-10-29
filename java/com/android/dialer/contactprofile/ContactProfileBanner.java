/*
 * SPDX-FileCopyrightText: The BlissRoms Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.dialer.contactprofile;

import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.Uri;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.dialer.R;
import com.android.dialer.contactphoto.ContactPhotoManager;
import com.android.dialer.contactphoto.ContactPhotoManager.DefaultImageProvider;
import com.android.dialer.contactphoto.ContactPhotoManager.DefaultImageRequest;

final class ContactProfileBanner {

  private static final DefaultImageProvider NO_PHOTO =
      new DefaultImageProvider() {
        @Override
        public void applyDefaultImage(
            ImageView view, int extent, boolean darkTheme, DefaultImageRequest request) {
          view.setImageDrawable(null);
        }
      };

  private ContactProfileBanner() {}

  static void bind(ImageView banner, @Nullable Uri photoUri) {
    if (photoUri == null) {
      banner.setImageDrawable(null);
      banner.setRenderEffect(null);
      return;
    }
    float radius = banner.getResources().getDimension(R.dimen.contact_profile_banner_blur_radius);
    banner.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    ContactPhotoManager.getInstance(banner.getContext())
        .loadPhoto(
            banner,
            photoUri,
            banner.getResources().getDimensionPixelSize(R.dimen.contact_profile_banner_height),
            false,
            false,
            null,
            NO_PHOTO);
  }
}
