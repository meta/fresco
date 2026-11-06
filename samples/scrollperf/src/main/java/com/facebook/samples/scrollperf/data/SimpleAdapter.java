/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.samples.scrollperf.data;

import com.facebook.infer.annotation.Nullsafe;

/**
 * This is a simple version of an Adapter which just provides the number of element and the element
 * itself based on an index
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public interface SimpleAdapter<E> {

  int getSize();

  E get(int position);

  boolean isLazy();

  /** Utility class for SimpleAdapter */
  class Util {

    @SuppressWarnings("rawtypes")
    public static SimpleAdapter EMPTY_ADAPTER =
        new SimpleAdapter() {
          @Override
          public int getSize() {
            return 0;
          }

          @Override
          public Object get(int position) {
            // NULLSAFE_FIXME[Return Not Nullable]
            return null;
          }

          @Override
          public boolean isLazy() {
            return false;
          }
        };

    /**
     * This creates an infinite version of the given SimpleAdapter setting
     *
     * @param srcAdapter The source SimpleAdapter
     * @param <E> The parameter type for this SimpleAdapter
     * @return The infinite version of this SimpleAdapter
     */
    public static <E> SimpleAdapter<E> makeItInfinite(final SimpleAdapter<E> srcAdapter) {
      if (srcAdapter.getSize() == Integer.MAX_VALUE) {
        return srcAdapter;
      }
      return new SimpleAdapter<E>() {
        @Override
        public int getSize() {
          return (srcAdapter.getSize() == 0) ? 0 : Integer.MAX_VALUE;
        }

        @Override
        public E get(int position) {
          return srcAdapter.get(position % srcAdapter.getSize());
        }

        @Override
        public boolean isLazy() {
          return srcAdapter.isLazy();
        }
      };
    }

    /**
     * This creates an infinite version of the given SimpleAdapter setting
     *
     * @param adaptee The source SimpleAdapter to decorate
     * @param <E> The parameter type for this SimpleAdapter
     * @return The infinite version of this SimpleAdapter
     */
    public static <E> SimpleAdapter<E> decorate(
        final SimpleAdapter<E> adaptee, final Decorator<E> decorator) {

      return new SimpleAdapter<E>() {
        @Override
        public int getSize() {
          return Integer.MAX_VALUE;
        }

        @Override
        public E get(int position) {
          return decorator.decorate(adaptee, position);
        }

        @Override
        public boolean isLazy() {
          // This is never lazy
          return false;
        }
      };
    }
  }
}
