/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.drawee.testing;

import static org.mockito.Mockito.*;

import android.graphics.drawable.Drawable;
import com.facebook.common.internal.Supplier;
import com.facebook.drawee.controller.AbstractDraweeController;
import com.facebook.drawee.controller.ControllerListener;
import com.facebook.drawee.controller.ForwardingControllerListener;
import com.facebook.drawee.drawable.DrawableTestUtils;
import com.facebook.drawee.generic.GenericDraweeHierarchy;
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder;
import com.facebook.drawee.interfaces.DraweeController;
import com.facebook.drawee.interfaces.DraweeHierarchy;
import com.facebook.infer.annotation.Nullsafe;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/** Drawee mocks */
@Nullsafe(Nullsafe.Mode.LOCAL)
@SuppressWarnings({"rawtypes", "unchecked"})
public class DraweeMocks {

  /**
   * Creates a mock DraweeController with some methods stubbed.
   *
   * @return mock DraweeController
   */
  public static DraweeController mockController() {
    DraweeController controller = mock(AbstractDraweeController.class);
    stubGetAndSetHierarchy(controller);
    stubGetAndSetContentDescription(controller);
    return controller;
  }

  /**
   * Stubs setHierarchy and getHierarchy methods.
   *
   * @param controller controller to stub methods of
   */
  public static void stubGetAndSetHierarchy(DraweeController controller) {
    final DraweeHierarchy[] dhHolder = new DraweeHierarchy[1];
    doAnswer(
            new Answer<Object>() {
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                return dhHolder[0];
              }
            })
        .when(controller)
        .getHierarchy();
    doAnswer(
            new Answer() {
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                return dhHolder[0] = (DraweeHierarchy) invocation.getArguments()[0];
              }
            })
        .when(controller)
        .setHierarchy((DraweeHierarchy) any());
  }

  /**
   * Stubs setContentDescription and getContentDescription methods.
   *
   * @param controller controller to stub methods of
   */
  public static void stubGetAndSetContentDescription(DraweeController controller) {
    final String[] contentDescriptionHolder = new String[1];
    doAnswer(
            new Answer<Object>() {
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                return contentDescriptionHolder[0];
              }
            })
        .when(controller)
        .getContentDescription();
    doAnswer(
            new Answer() {
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                return contentDescriptionHolder[0] = (String) invocation.getArguments()[0];
              }
            })
        .when(controller)
        .setContentDescription(any(String.class));
  }

  /**
   * Stubs addControllerListener
   *
   * @param controller
   * @return forwarding listener
   */
  @Nullable
  public static ControllerListener stubControllerListener(final DraweeController controller) {
    final ForwardingControllerListener forwardingListener = new ForwardingControllerListener();
    if (!(controller instanceof AbstractDraweeController)) {
      return null;
    }
    AbstractDraweeController abstractController = (AbstractDraweeController) controller;
    doAnswer(
            new Answer() {
              @Nullable
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                forwardingListener.addListener((ControllerListener) invocation.getArguments()[0]);
                return null;
              }
            })
        .when(abstractController)
        .addControllerListener(any(ControllerListener.class));
    return forwardingListener;
  }

  /**
   * Creates a mock GenericDraweeHierarchy with some methods stubbed.
   *
   * @param topLevelDrawable drawable to return on {@code getTopLevelDrawable()}
   * @return mock GenericDraweeHierarchy
   */
  public static GenericDraweeHierarchy mockDraweeHierarchyOf(Drawable topLevelDrawable) {
    GenericDraweeHierarchy gdh = mock(GenericDraweeHierarchy.class);
    when(gdh.getTopLevelDrawable()).thenReturn(topLevelDrawable);
    return gdh;
  }

  /**
   * Creates a mock GenericDraweeHierarchy with some methods stubbed.
   *
   * @return mock GenericDraweeHierarchy
   */
  public static GenericDraweeHierarchy mockDraweeHierarchy() {
    return mockDraweeHierarchyOf(DrawableTestUtils.mockDrawable());
  }

  /**
   * Creates a mock GenericDraweeHierarchyBuilder that builds a new DH instance each time.
   *
   * @return mock GenericDraweeHierarchyBuilder
   */
  public static GenericDraweeHierarchyBuilder mockGenericDraweeHierarchyBuilder() {
    GenericDraweeHierarchyBuilder builder =
        // NULLSAFE_FIXME[Not Vetted Third-Party]
        mock(GenericDraweeHierarchyBuilder.class, CALLS_REAL_METHODS);
    doAnswer(
            new Answer<Object>() {
              @Override
              public DraweeHierarchy answer(InvocationOnMock invocation) throws Throwable {
                return mockDraweeHierarchy();
              }
            })
        .when(builder)
        .build();
    return builder;
  }

  /**
   * Creates a mock GenericDraweeHierarchyBuilder with stubbed build.
   *
   * @param drawableHierarchies drawable hierarchies to return on {@code build()}
   * @return mock GenericDraweeHierarchyBuilder
   */
  public static GenericDraweeHierarchyBuilder mockBuilderOf(
      GenericDraweeHierarchy... drawableHierarchies) {
    GenericDraweeHierarchyBuilder builder =
        // NULLSAFE_FIXME[Not Vetted Third-Party]
        mock(GenericDraweeHierarchyBuilder.class, CALLS_REAL_METHODS);
    final Supplier<GenericDraweeHierarchy> gdhProvider = supplierOf(drawableHierarchies);
    doAnswer(
            new Answer() {
              @Override
              public Object answer(InvocationOnMock invocation) throws Throwable {
                return gdhProvider.get();
              }
            })
        .when(builder)
        .build();
    return builder;
  }

  /**
   * Creates a supplier of T.
   *
   * @param values values to return on {@code get()}
   * @return supplier of T
   */
  @SafeVarargs
  public static <T> Supplier<T> supplierOf(final T... values) {
    final AtomicInteger index = new AtomicInteger(0);
    return new Supplier<T>() {
      @Override
      public T get() {
        if (index.get() < values.length) {
          return values[index.getAndIncrement()];
        } else {
          return values[values.length - 1];
        }
      }
    };
  }
}
