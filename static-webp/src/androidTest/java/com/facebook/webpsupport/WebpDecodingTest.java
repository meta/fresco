/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.webpsupport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.os.MemoryFile;
import androidx.test.InstrumentationRegistry;
import com.facebook.common.internal.ByteStreams;
import com.facebook.common.internal.Throwables;
import com.facebook.imagepipeline.core.ImagePipelineConfig;
import com.facebook.imagepipeline.core.ImagePipelineFactory;
import com.facebook.infer.annotation.Initializer;
import com.facebook.infer.annotation.Nullsafe;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;

/** This is the test in order to directly decoding all types of webp images */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class WebpDecodingTest {

  @Nullable private static Method sGetFileDescriptorMethod;

  private Instrumentation mInstrumentation;

  private WebpBitmapFactoryImpl mWebpBitmapFactory;

  @Before
  @Initializer
  public void setUp() {
    mInstrumentation = InstrumentationRegistry.getInstrumentation();
    mWebpBitmapFactory = new WebpBitmapFactoryImpl();
    ImagePipelineConfig.Builder configBuilder =
        // NULLSAFE_FIXME[Not Vetted Third-Party]
        ImagePipelineConfig.newBuilder(mInstrumentation.getContext());
    configBuilder.experiment().setWebpBitmapFactory(mWebpBitmapFactory);
    ImagePipelineFactory.initialize(configBuilder.build());
  }

  private MemoryFile getMemoryFile(String path) {
    try {
      byte[] data = ByteStreams.toByteArray(getTestImageInputStream(path));
      MemoryFile memoryFile = new MemoryFile(null, data.length);
      memoryFile.allowPurging(false);
      memoryFile.writeBytes(data, 0, 0, data.length);
      return memoryFile;
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  private synchronized Method getFileDescriptorMethod() {
    if (sGetFileDescriptorMethod == null) {
      try {
        sGetFileDescriptorMethod = MemoryFile.class.getDeclaredMethod("getFileDescriptor");
      } catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }
    return sGetFileDescriptorMethod;
  }

  private FileDescriptor getMemoryFileDescriptor(MemoryFile memoryFile) {
    try {
      Object rawFD = getFileDescriptorMethod().invoke(memoryFile);
      // NULLSAFE_FIXME[Return Not Nullable]
      return (FileDescriptor) rawFD;
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  private InputStream getTestImageInputStream(String path) {
    try {
      // NULLSAFE_FIXME[Not Vetted Third-Party]
      return mInstrumentation.getContext().getResources().getAssets().open(path);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  @Test
  public void test_webp_extended_decoding_inputstream_bitmap() throws Throwable {
    final Bitmap bitmap =
        mWebpBitmapFactory.decodeStream(getTestImageInputStream("webp_e.webp"), null, null);
    // NULLSAFE_FIXME[Parameter Not Nullable]
    assertBitmap(bitmap, 480, 320);
  }

  @Test
  public void test_webp_extended_decoding_filedescriptor_bitmap() throws Throwable {
    final MemoryFile memoryFile = getMemoryFile("webp_e.webp");
    final Bitmap bitmap =
        mWebpBitmapFactory.decodeFileDescriptor(getMemoryFileDescriptor(memoryFile), null, null);
    memoryFile.close();
    // NULLSAFE_FIXME[Parameter Not Nullable]
    assertBitmap(bitmap, 480, 320);
  }

  @Test
  public void test_webp_extended_with_alpha_decoding_inputstream_bitmap() throws Throwable {
    final Bitmap bitmap =
        mWebpBitmapFactory.decodeStream(getTestImageInputStream("webp_ea.webp"), null, null);

    // NULLSAFE_FIXME[Parameter Not Nullable]
    assertBitmap(bitmap, 400, 301);
  }

  @Test
  public void test_webp_extended_with_alpha_decoding_filedescriptor_bitmap() throws Throwable {
    final MemoryFile memoryFile = getMemoryFile("webp_ea.webp");
    final Bitmap bitmap =
        mWebpBitmapFactory.decodeFileDescriptor(getMemoryFileDescriptor(memoryFile), null, null);
    memoryFile.close();
    // NULLSAFE_FIXME[Parameter Not Nullable]
    assertBitmap(bitmap, 400, 301);
  }

  @Test
  public void test_webp_lossless_decoding_inputstream_bitmap() throws Throwable {
    final Bitmap bitmap =
        mWebpBitmapFactory.decodeStream(getTestImageInputStream("webp_ll.webp"), null, null);

    // NULLSAFE_FIXME[Parameter Not Nullable]
    assertBitmap(bitmap, 400, 301);
  }

  @Test
  public void test_webp_lossless_decoding_filedescriptor_bitmap() throws Throwable {
    final MemoryFile memoryFile = getMemoryFile("webp_ll.webp");
    final Bitmap bitmap =
        mWebpBitmapFactory.decodeFileDescriptor(getMemoryFileDescriptor(memoryFile), null, null);
    memoryFile.close();
    // NULLSAFE_FIXME[Parameter Not Nullable]
    assertBitmap(bitmap, 400, 301);
  }

  @Test
  public void test_webp_plain_inputstream_bitmap() throws Throwable {
    final Bitmap bitmap =
        mWebpBitmapFactory.decodeStream(getTestImageInputStream("webp_plain.webp"), null, null);

    // NULLSAFE_FIXME[Parameter Not Nullable]
    assertBitmap(bitmap, 320, 214);
  }

  @Test
  public void test_webp_plain_decoding_filedescriptor_bitmap() throws Throwable {
    final MemoryFile memoryFile = getMemoryFile("webp_plain.webp");
    final Bitmap bitmap =
        mWebpBitmapFactory.decodeFileDescriptor(getMemoryFileDescriptor(memoryFile), null, null);
    memoryFile.close();
    // NULLSAFE_FIXME[Parameter Not Nullable]
    assertBitmap(bitmap, 320, 214);
  }

  private void assertBitmap(Bitmap bitmap, int width, int height) {
    assertNotNull("Bitmap should not be null", bitmap);
    assertEquals("Width should be decoded properly", width, bitmap.getWidth());
    assertEquals("Height should be decoded properly", height, bitmap.getHeight());
  }
}
