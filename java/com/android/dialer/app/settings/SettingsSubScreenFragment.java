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
package com.android.dialer.app.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.android.dialer.R;

/**
 * Hosts a legacy preference sub-screen under a custom header that matches the redesigned settings
 * root. Used instead of the system ActionBar so there is no header flash when navigating back.
 */
public class SettingsSubScreenFragment extends Fragment {

  private static final String ARG_FRAGMENT_CLASS = "fragment_class";
  private static final String ARG_TITLE = "title";
  private static final String ARG_TARGET_ARGS = "target_args";

  public static SettingsSubScreenFragment newInstance(
      @NonNull String fragmentClass, CharSequence title, @Nullable Bundle targetArgs) {
    Bundle args = new Bundle();
    args.putString(ARG_FRAGMENT_CLASS, fragmentClass);
    args.putCharSequence(ARG_TITLE, title);
    if (targetArgs != null) {
      args.putBundle(ARG_TARGET_ARGS, targetArgs);
    }
    SettingsSubScreenFragment fragment = new SettingsSubScreenFragment();
    fragment.setArguments(args);
    return fragment;
  }

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup parent,
      @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.settings_subscreen, parent, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    Bundle args = requireArguments();
    ((TextView) view.findViewById(R.id.sub_title)).setText(args.getCharSequence(ARG_TITLE));
    view.findViewById(R.id.sub_back)
        .setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());

    if (getChildFragmentManager().findFragmentById(R.id.sub_content) == null) {
      Fragment target =
          getChildFragmentManager()
              .getFragmentFactory()
              .instantiate(requireContext().getClassLoader(), args.getString(ARG_FRAGMENT_CLASS));
      Bundle targetArgs = args.getBundle(ARG_TARGET_ARGS);
      if (targetArgs != null) {
        target.setArguments(targetArgs);
      }
      getChildFragmentManager().beginTransaction().replace(R.id.sub_content, target).commit();
    }
  }
}
