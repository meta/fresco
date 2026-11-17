/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.fresco.vito.options

import com.facebook.imagepipeline.common.Priority
import com.facebook.imagepipeline.request.ImageRequest.CacheChoice
import com.facebook.imagepipeline.request.ImageRequestBuilder.BuilderException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class EncodedImageOptionsTest {

  private class TestBuilder : EncodedImageOptions.Builder<TestBuilder> {
    constructor() : super()

    constructor(defaultOptions: EncodedImageOptions) : super(defaultOptions)
  }

  @Test
  fun defaults_haveNullFields() {
    val options = TestBuilder().build()

    assertThat(options.priority).isNull()
    assertThat(options.cacheChoice).isNull()
    assertThat(options.diskCacheId).isNull()
  }

  @Test
  fun builder_storesAllFields() {
    val options =
        TestBuilder()
            .priority(Priority.HIGH)
            .cacheChoice(CacheChoice.DYNAMIC)
            .diskCacheId("disk-id")
            .build()

    assertThat(options.priority).isEqualTo(Priority.HIGH)
    assertThat(options.cacheChoice).isEqualTo(CacheChoice.DYNAMIC)
    assertThat(options.diskCacheId).isEqualTo("disk-id")
  }

  @Test
  fun builder_settersReturnSameBuilder() {
    val builder = TestBuilder()

    assertThat(builder.priority(Priority.HIGH)).isSameAs(builder)
    assertThat(builder.cacheChoice(CacheChoice.DEFAULT)).isSameAs(builder)
    assertThat(builder.diskCacheId("disk-id")).isSameAs(builder)
  }

  @Test
  fun builder_nullPriority_clearsValue() {
    val options = TestBuilder().priority(Priority.HIGH).priority(null).build()

    assertThat(options.priority).isNull()
  }

  @Test
  fun dynamicCacheChoice_withoutDiskCacheId_throws() {
    assertThatThrownBy { TestBuilder().cacheChoice(CacheChoice.DYNAMIC).build() }
        .isInstanceOf(BuilderException::class.java)
        .hasMessage("Invalid request builder: Disk cache id must be set for dynamic cache choice")
  }

  @Test
  fun dynamicCacheChoice_withEmptyDiskCacheId_buildsSuccessfully() {
    val options = TestBuilder().cacheChoice(CacheChoice.DYNAMIC).diskCacheId("").build()

    assertThat(options.cacheChoice).isEqualTo(CacheChoice.DYNAMIC)
    assertThat(options.diskCacheId).isEmpty()
  }

  @Test
  fun nonDynamicCacheChoice_withDiskCacheId_throws() {
    val builder = TestBuilder().cacheChoice(CacheChoice.DEFAULT).diskCacheId("disk-id")

    assertThatThrownBy { builder.build() }
        .isInstanceOf(BuilderException::class.java)
        .hasMessageContaining("you set the CacheChoice to DYNAMIC")
  }

  @Test
  fun builder_copiedFromExisting_preservesFields() {
    val original =
        TestBuilder()
            .priority(Priority.HIGH)
            .cacheChoice(CacheChoice.DYNAMIC)
            .diskCacheId("disk-id")
            .build()

    val copy = TestBuilder(original).build()

    assertThat(copy).isEqualTo(original)
    assertThat(copy.hashCode()).isEqualTo(original.hashCode())
    assertThat(copy.priority).isEqualTo(Priority.HIGH)
    assertThat(copy.cacheChoice).isEqualTo(CacheChoice.DYNAMIC)
    assertThat(copy.diskCacheId).isEqualTo("disk-id")
  }

  @Test
  fun builder_copiedFromExisting_canOverrideOneField() {
    val original =
        TestBuilder()
            .priority(Priority.HIGH)
            .cacheChoice(CacheChoice.DYNAMIC)
            .diskCacheId("disk-id")
            .build()

    val modified = TestBuilder(original).priority(Priority.LOW).build()

    assertThat(modified.priority).isEqualTo(Priority.LOW)
    assertThat(modified.cacheChoice).isEqualTo(CacheChoice.DYNAMIC)
    assertThat(modified.diskCacheId).isEqualTo("disk-id")
    assertThat(modified).isNotEqualTo(original)
  }

  @Test
  fun equals_sameInstance_returnsTrue() {
    val options = TestBuilder().build()

    assertThat(options.equals(options)).isTrue()
  }

  @Test
  fun equals_null_returnsFalse() {
    val options = TestBuilder().build()

    assertThat(options.equals(null)).isFalse()
  }

  @Test
  fun equals_differentClass_returnsFalse() {
    val encoded = TestBuilder().build()
    val imageOptions = ImageOptions.create().build()

    assertThat(encoded.equals(imageOptions)).isFalse()
    assertThat(imageOptions.equals(encoded)).isFalse()
  }

  @Test
  fun equals_sameFieldValues_returnsTrue() {
    val first =
        TestBuilder()
            .priority(Priority.HIGH)
            .cacheChoice(CacheChoice.DYNAMIC)
            .diskCacheId("disk-id")
            .build()
    val second =
        TestBuilder()
            .priority(Priority.HIGH)
            .cacheChoice(CacheChoice.DYNAMIC)
            .diskCacheId("disk-id")
            .build()

    assertThat(first).isEqualTo(second)
  }

  @Test
  fun equals_differentPriority_returnsFalse() {
    val high = TestBuilder().priority(Priority.HIGH).build()
    val low = TestBuilder().priority(Priority.LOW).build()

    assertThat(high).isNotEqualTo(low)
  }

  @Test
  fun equals_differentCacheChoice_returnsFalse() {
    val small = TestBuilder().cacheChoice(CacheChoice.SMALL).build()
    val def = TestBuilder().cacheChoice(CacheChoice.DEFAULT).build()

    assertThat(small).isNotEqualTo(def)
  }

  @Test
  fun equals_differentDiskCacheId_returnsFalse() {
    val first = TestBuilder().cacheChoice(CacheChoice.DYNAMIC).diskCacheId("disk-id-1").build()
    val second = TestBuilder().cacheChoice(CacheChoice.DYNAMIC).diskCacheId("disk-id-2").build()

    assertThat(first).isNotEqualTo(second)
  }

  @Test
  fun equalOptions_haveEqualHashCodes() {
    val first =
        TestBuilder()
            .priority(Priority.HIGH)
            .cacheChoice(CacheChoice.DYNAMIC)
            .diskCacheId("disk-id")
            .build()
    val second =
        TestBuilder()
            .priority(Priority.HIGH)
            .cacheChoice(CacheChoice.DYNAMIC)
            .diskCacheId("disk-id")
            .build()

    assertThat(first.hashCode()).isEqualTo(second.hashCode())
  }

  @Test
  fun hashCode_differentPriority_haveDifferentHashCodes() {
    val high = TestBuilder().priority(Priority.HIGH).build()
    val low = TestBuilder().priority(Priority.LOW).build()

    assertThat(high.hashCode()).isNotEqualTo(low.hashCode())
  }

  @Test
  fun toString_includesAllSetFields() {
    val options =
        TestBuilder()
            .priority(Priority.HIGH)
            .cacheChoice(CacheChoice.DYNAMIC)
            .diskCacheId("disk-id")
            .build()

    assertThat(options.toString()).contains("HIGH").contains("DYNAMIC").contains("disk-id")
  }
}
