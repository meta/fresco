/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.scrollperf.preferences;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.samples.scrollperf.R;
import com.facebook.samples.scrollperf.util.SizeUtil;
import javax.annotation.Nullable;

/** A simple Preference containing a SeekBar in order to select a size */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class SizePreferences extends Preference implements SeekBar.OnSeekBarChangeListener {

  // We always use half of the width as default
  private static final int DEFAULT_SIZE_VALUE = SizeUtil.DISPLAY_WIDTH / 2;

  // NULLSAFE_FIXME[Field Not Initialized]
  private SeekBar mSeekBar;

  @Nullable private TextView mSeekBarValueTextView;
  // NULLSAFE_FIXME[Field Not Initialized]
  private TextView mTitleView;

  private int mProgressValue;

  private int mMaxValue;

  @SuppressWarnings("this-escape")
  public SizePreferences(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);
    setLayoutResource(R.layout.size_preference);
  }

  public SizePreferences(Context context, AttributeSet attrs, int defStyleAttr) {
    this(context, attrs, defStyleAttr, 0);
  }

  public SizePreferences(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public void setSeekBarMaxValue(int maxValue) {
    mMaxValue = maxValue;
  }

  @Override
  public void onBindViewHolder(PreferenceViewHolder holder) {
    super.onBindViewHolder(holder);
    // We get the reference to the mSeekBar
    // NULLSAFE_FIXME[Not Vetted Third-Party]
    mSeekBar = (SeekBar) holder.findViewById(R.id.size_seek_bar);
    mSeekBar.setMax(mMaxValue);
    mSeekBar.setOnSeekBarChangeListener(this);
    mSeekBarValueTextView = (TextView) holder.findViewById(R.id.seek_bar_value);
    // NULLSAFE_FIXME[Not Vetted Third-Party]
    mTitleView = (TextView) holder.findViewById(R.id.title);
    mTitleView.setText(getTitle());
    // This is called after the initial value is set
    mSeekBar.setProgress(mProgressValue);
    updateCurrentValue(mProgressValue);
  }

  @Override
  public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
    updateCurrentValue(progress);
  }

  @Override
  public void onStartTrackingTouch(SeekBar seekBar) {}

  @Override
  public void onStopTrackingTouch(SeekBar seekBar) {}

  @Override
  protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
    super.onSetInitialValue(restorePersistedValue, defaultValue);
    final int valueToDisplay;
    if (restorePersistedValue) {
      valueToDisplay = getPersistedInt(DEFAULT_SIZE_VALUE);
    } else {
      valueToDisplay = (Integer) defaultValue;
    }
    updateCurrentValue(valueToDisplay);
  }

  @Override
  protected Object onGetDefaultValue(TypedArray a, int index) {
    return a.getInt(index, 0);
  }

  public void updateCurrentValue(int progress) {
    if (shouldPersist()) {
      persistInt(progress);
    }
    if (progress != mProgressValue) {
      mProgressValue = progress;
      notifyChanged();
    }
    if (mSeekBarValueTextView != null) {
      final String valueStr = getContext().getString(R.string.size_label_format, progress);
      mSeekBarValueTextView.setText(valueStr);
    }
  }
}
