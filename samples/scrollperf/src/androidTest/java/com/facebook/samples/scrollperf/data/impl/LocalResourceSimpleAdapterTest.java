/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.scrollperf.data.impl;

import static org.assertj.core.api.Assertions.assertThat;

import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import androidx.test.InstrumentationRegistry;
import com.facebook.samples.scrollperf.R;
import com.facebook.samples.scrollperf.data.SimpleAdapter;
import org.junit.Test;

/** This is the Unit Test class for the LocalResourceSimpleAdapterTest */
public class LocalResourceSimpleAdapterTest {

  @Test
  public void testEagerAdapter() {
    final Context context = InstrumentationRegistry.getTargetContext();
    final Resources res = context.getResources();
    final String[] uris = res.getStringArray(R.array.local_uri_test);
    assertThat(uris).isNotNull();
    SimpleAdapter<Uri> simpleAdapter =
        LocalResourceSimpleAdapter.getEagerAdapter(context, R.array.local_uri_test);
    checkSimpleAdapterData(simpleAdapter, uris.length);
    assertThat(simpleAdapter.isLazy()).isFalse();
  }

  @Test
  public void testLazyAdapter() {
    final Context context = InstrumentationRegistry.getTargetContext();
    final Resources res = context.getResources();
    final String[] uris = res.getStringArray(R.array.local_uri_test);
    assertThat(uris).isNotNull();
    SimpleAdapter<Uri> simpleAdapter =
        LocalResourceSimpleAdapter.getEagerAdapter(context, R.array.local_uri_test);
    checkSimpleAdapterData(simpleAdapter, uris.length);
    assertThat(simpleAdapter.isLazy()).isTrue();
  }

  private void checkSimpleAdapterData(SimpleAdapter<Uri> simpleAdapter, int requestedSize) {
    assertThat(simpleAdapter).isNotNull();
    assertThat(requestedSize).isEqualTo(10);
    assertThat(simpleAdapter.getSize()).isEqualTo(requestedSize);
    Uri firstUri = simpleAdapter.get(0);
    assertThat(firstUri).isNotNull();
    assertThat(firstUri).isEqualTo(Uri.parse("http://myserver.com/file1"));
    Uri lastUri = simpleAdapter.get(simpleAdapter.getSize() - 1);
    assertThat(lastUri).isNotNull();
    assertThat(lastUri).isEqualTo(Uri.parse("http://myserver.com/file10"));
  }
}
