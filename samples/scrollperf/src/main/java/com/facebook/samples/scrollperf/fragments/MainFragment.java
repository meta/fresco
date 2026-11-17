/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.scrollperf.fragments;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.facebook.infer.annotation.Nullsafe;
import com.facebook.samples.scrollperf.R;
import com.facebook.samples.scrollperf.conf.Config;
import com.facebook.samples.scrollperf.conf.Const;
import com.facebook.samples.scrollperf.data.SimpleAdapter;
import com.facebook.samples.scrollperf.data.impl.ContentProviderSimpleAdapter;
import com.facebook.samples.scrollperf.data.impl.DistinctUriDecorator;
import com.facebook.samples.scrollperf.data.impl.LocalResourceSimpleAdapter;
import com.facebook.samples.scrollperf.fragments.recycler.VitoViewAdapter;
import com.facebook.samples.scrollperf.fragments.recycler.VitoViewListAdapter;
import com.facebook.samples.scrollperf.instrumentation.PerfListener;
import com.facebook.samples.scrollperf.util.UI;

@Nullsafe(Nullsafe.Mode.LOCAL)
public class MainFragment extends Fragment {

  public static final String TAG = MainFragment.class.getSimpleName();

  private static final int REQUEST_READ_EXTERNAL_ID = 1;

  // NULLSAFE_FIXME[Field Not Initialized]
  private RecyclerView mRecyclerView;

  // NULLSAFE_FIXME[Field Not Initialized]
  private ListView mListView;

  // NULLSAFE_FIXME[Field Not Initialized]
  private VitoViewAdapter mVitoViewAdapter;

  // NULLSAFE_FIXME[Field Not Initialized]
  private ListAdapter mListAdapter;

  @Nullable private SimpleAdapter<Uri> mSimpleAdapter;

  private Config mConfig;

  boolean mDistinctUriCompatible = true;

  private PerfListener mPerfListener;

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setHasOptionsMenu(true);
    mPerfListener = new PerfListener();
  }

  @Override
  public View onCreateView(
      LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    // NULLSAFE_FIXME[Parameter Not Nullable]
    mConfig = Config.load(getContext());
    // Initialize the SimpleAdapter
    mSimpleAdapter = initializeSimpleAdapter(mConfig);
    // We use a different layout based on the type of output
    final View layout;
    switch (mConfig.recyclerLayoutType) {
      case Const.RECYCLER_VIEW_LAYOUT_VALUE:
        layout = inflater.inflate(R.layout.content_recyclerview, container, false);
        initializeRecyclerView(layout);
        break;
      case Const.LISTVIEW_LAYOUT_VALUE:
        layout = inflater.inflate(R.layout.content_listview, container, false);
        initializeListView(layout);
        break;
      case Const.GRID_RECYCLER_VIEW_LAYOUT_VALUE:
        layout = inflater.inflate(R.layout.content_recyclerview, container, false);
        initializeGridRecyclerView(layout);
        break;
      default:
        throw new IllegalStateException("Recycler Layout not supported");
    }
    updateAdapter();
    return layout;
  }

  @Override
  public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
    inflater.inflate(R.menu.menu, menu);
    super.onCreateOptionsMenu(menu, inflater);
  }

  private void initializeRecyclerView(final View layout) {
    // Get RecyclerView
    mRecyclerView = UI.findViewById(layout, R.id.recycler_view);
    // Choose the LayoutManager
    LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
    layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
    layoutManager.scrollToPosition(0);
    mRecyclerView.setLayoutManager(layoutManager);
  }

  private void initializeGridRecyclerView(final View layout) {
    // Get RecyclerView
    mRecyclerView = UI.findViewById(layout, R.id.recycler_view);
    // Choose the LayoutManager
    // NULLSAFE_FIXME[Parameter Not Nullable]
    GridLayoutManager layoutManager = new GridLayoutManager(getContext(), mConfig.gridSpanCount);
    layoutManager.setOrientation(LinearLayoutManager.VERTICAL);
    layoutManager.scrollToPosition(0);
    mRecyclerView.setLayoutManager(layoutManager);
  }

  private void initializeListView(final View layout) {
    // get the ListView
    mListView = UI.findViewById(layout, R.id.list_view);
  }

  @Nullable
  private SimpleAdapter<Uri> initializeSimpleAdapter(final Config config) {
    SimpleAdapter<Uri> simpleAdapter = null;
    switch (config.dataSourceType) {
      case Const.LOCAL_RESOURCE_URIS:
        simpleAdapter =
            // NULLSAFE_FIXME[Parameter Not Nullable]
            LocalResourceSimpleAdapter.getEagerAdapter(getContext(), R.array.example_uris);
        break;
      case Const.LOCAL_RESOURCE_WEBP_URIS:
        simpleAdapter =
            // NULLSAFE_FIXME[Parameter Not Nullable]
            LocalResourceSimpleAdapter.getEagerAdapter(getContext(), R.array.example_webp_uris);
        break;
      case Const.LOCAL_RESOURCE_PNG_URIS:
        simpleAdapter =
            // NULLSAFE_FIXME[Parameter Not Nullable]
            LocalResourceSimpleAdapter.getEagerAdapter(getContext(), R.array.example_png_uris);
        break;
      case Const.LOCAL_INTERNAL_PHOTO_URIS:
        // NULLSAFE_FIXME[Parameter Not Nullable]
        simpleAdapter = ContentProviderSimpleAdapter.getInternalPhotoSimpleAdapter(getActivity());
        mDistinctUriCompatible = false;
        break;
      case Const.LOCAL_EXTERNAL_PHOTO_URIS:
        simpleAdapter = getExternalPhotoSimpleAdapter();
        mDistinctUriCompatible = false;
        break;
    }
    return simpleAdapter;
  }

  private void updateAdapter() {
    if (mSimpleAdapter == null) {
      return;
    }
    if (mConfig.infiniteDataSource) {
      mSimpleAdapter = SimpleAdapter.Util.makeItInfinite(mSimpleAdapter);
      if (mDistinctUriCompatible && mConfig.distinctUriDataSource) {
        mSimpleAdapter =
            SimpleAdapter.Util.decorate(mSimpleAdapter, DistinctUriDecorator.SINGLETON);
      }
    }
    switch (mConfig.recyclerLayoutType) {
      case Const.RECYCLER_VIEW_LAYOUT_VALUE:
      case Const.GRID_RECYCLER_VIEW_LAYOUT_VALUE:
        // Create the Adapter
        mVitoViewAdapter =
            // NULLSAFE_FIXME[Parameter Not Nullable]
            new VitoViewAdapter(getContext(), mSimpleAdapter, mConfig, mPerfListener);
        mRecyclerView.setAdapter(mVitoViewAdapter);
        break;
      case Const.LISTVIEW_LAYOUT_VALUE:
        // Create the Adapter
        mListAdapter =
            // NULLSAFE_FIXME[Parameter Not Nullable]
            new VitoViewListAdapter(getContext(), mSimpleAdapter, mConfig, mPerfListener);
        // Set the adapter
        mListView.setAdapter(mListAdapter);
        break;
      default:
        throw new IllegalStateException("Recycler Layout not supported");
    }
  }

  @SuppressWarnings("unchecked")
  private SimpleAdapter<Uri> getExternalPhotoSimpleAdapter() {
    // NULLSAFE_FIXME[Parameter Not Nullable]
    if (ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE)
        == PackageManager.PERMISSION_GRANTED) {
      // NULLSAFE_FIXME[Parameter Not Nullable]
      return ContentProviderSimpleAdapter.getExternalPhotoSimpleAdapter(getActivity());
    } else {
      requestPermissions(
          new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_ID);
    }
    return SimpleAdapter.Util.EMPTY_ADAPTER;
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode == REQUEST_READ_EXTERNAL_ID
        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      // NULLSAFE_FIXME[Parameter Not Nullable]
      mSimpleAdapter = ContentProviderSimpleAdapter.getExternalPhotoSimpleAdapter(getActivity());
      updateAdapter();
    }
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
  }
}
