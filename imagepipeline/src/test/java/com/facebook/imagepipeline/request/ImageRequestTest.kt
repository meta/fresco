/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.request

import android.net.Uri
import com.facebook.imagepipeline.common.ImageDecodeOptions
import com.facebook.imagepipeline.common.Priority
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.common.RotationOptions
import com.facebook.imagepipeline.listener.RequestLoggingListener
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageRequestTest {

  @Test
  fun testCreatingRequestFromExistingRequest() {
    val original =
        ImageRequestBuilder.newBuilderWithSource(Uri.parse("http://frescolib.org/image.jpg"))
            .setCacheChoice(ImageRequest.CacheChoice.SMALL)
            .setImageDecodeOptions(ImageDecodeOptions.newBuilder().build())
            .setLocalThumbnailPreviewsEnabled(true)
            .setLowestPermittedRequestLevel(ImageRequest.RequestLevel.DISK_CACHE)
            .setPostprocessor(
                object : BasePostprocessor() {
                  override fun getName(): String {
                    return super.getName()
                  }
                },
            )
            .setProgressiveRenderingEnabled(true)
            .setRequestListener(RequestLoggingListener())
            .setResizeOptions(ResizeOptions(20, 20))
            .setRotationOptions(RotationOptions.forceRotation(RotationOptions.ROTATE_90))
            .setRequestPriority(Priority.HIGH)
            .build()

    val copy = ImageRequestBuilder.fromRequest(original).build()

    assertThat(copy).isEqualTo(original)
  }

  @Test
  fun testCreatingRequestWithDynamicCacheChoice_success() {
    val request =
        ImageRequestBuilder.newBuilderWithSource(Uri.parse("http://frescolib.org/image.jpg"))
            .setCacheChoice(ImageRequest.CacheChoice.DYNAMIC)
            .setDiskCacheId("dynamic_cache_id")
            .setImageDecodeOptions(ImageDecodeOptions.newBuilder().build())
            .setLocalThumbnailPreviewsEnabled(true)
            .setLowestPermittedRequestLevel(ImageRequest.RequestLevel.DISK_CACHE)
            .setPostprocessor(
                object : BasePostprocessor() {
                  override fun getName(): String {
                    return super.getName()
                  }
                },
            )
            .setProgressiveRenderingEnabled(true)
            .setRequestListener(RequestLoggingListener())
            .setResizeOptions(ResizeOptions(20, 20))
            .setRotationOptions(RotationOptions.forceRotation(RotationOptions.ROTATE_90))
            .setRequestPriority(Priority.HIGH)
            .build()

    assertThat(request).isNotNull()
    assertThat(request.cacheChoice).isEqualTo(ImageRequest.CacheChoice.DYNAMIC)
    assertThat(request.diskCacheId).isEqualTo("dynamic_cache_id")
  }

  @Test
  fun testImageRequestForLocalFile_normal() {
    val file = File("/foo/photos/penguin.jpg")
    val imageRequest = ImageRequest.fromFile(file)

    assertThat(imageRequest).isNotNull()
    assertThat(imageRequest?.sourceFile?.absolutePath).isEqualTo(file.absolutePath)
  }

  @Test
  fun testImageRequestForLocalFile_withSpaces() {
    val file = File("/foo/photos folder/penguin crowd.jpg")
    val imageRequest = ImageRequest.fromFile(file)

    assertThat(imageRequest).isNotNull()
    assertThat(imageRequest?.sourceFile?.absolutePath).isEqualTo(file.absolutePath)
  }

  @Test
  fun testImageRequestForLocalFile_withSpecialCharacters() {
    val file = File("/foo/photos#folder/with spaces/penguin?_&*-...jpg")
    val imageRequest = ImageRequest.fromFile(file)

    assertThat(imageRequest).isNotNull()
    assertThat(imageRequest?.sourceFile?.absolutePath).isEqualTo(file.absolutePath)
  }

  @Test
  fun testNewImageRequestFromExistingBuilder() {
    val originalImageRequest =
        ImageRequestBuilder.newBuilderWithSource(Uri.parse("http://frescolib.org/image.jpg"))
            .setCacheChoice(ImageRequest.CacheChoice.DYNAMIC)
            .setDiskCacheId("dynamic_cache_id")
            .setImageDecodeOptions(ImageDecodeOptions.newBuilder().build())
            .setLocalThumbnailPreviewsEnabled(true)
            .setLowestPermittedRequestLevel(ImageRequest.RequestLevel.DISK_CACHE)
            .setPostprocessor(
                object : BasePostprocessor() {
                  override fun getName(): String {
                    return super.getName()
                  }
                },
            )
            .setProgressiveRenderingEnabled(true)
            .setRequestListener(RequestLoggingListener())
            .setResizeOptions(ResizeOptions(20, 20))
            .setRotationOptions(RotationOptions.forceRotation(RotationOptions.ROTATE_90))
            .setRequestPriority(Priority.HIGH)
            .build()
    val newBuilder = ImageRequestBuilder.fromRequest(originalImageRequest)
    newBuilder.setLocalThumbnailPreviewsEnabled(false)
    assertThat(originalImageRequest.newImageRequestFromImageRequestBuilder(newBuilder))
        .isEqualTo(newBuilder.build())
  }

  @Test
  fun testExtras_areCarriedFromBuilderToRequest() {
    val request = newBuilder().setExtra(SIZE_EXTRA, 3).setExtra(LABEL_EXTRA, "feed").build()

    assertThat(request.getExtra(SIZE_EXTRA)).isEqualTo(3)
    assertThat(request.getExtra(LABEL_EXTRA)).isEqualTo("feed")
    assertThat(request.extras).hasSize(2)
  }

  @Test
  fun testExtras_nullValueRemovesExtra() {
    val request = newBuilder().setExtra(SIZE_EXTRA, 3).setExtra(SIZE_EXTRA, null).build()

    assertThat(request.getExtra(SIZE_EXTRA)).isNull()
    assertThat(request.extras).isNull()
  }

  @Test
  fun testExtras_areCopiedByFromRequest() {
    val original = newBuilder().setExtra(SIZE_EXTRA, 3).setExtra(LABEL_EXTRA, "feed").build()

    val copy = ImageRequestBuilder.fromRequest(original).build()

    assertThat(copy.getExtra(SIZE_EXTRA)).isEqualTo(3)
    assertThat(copy.getExtra(LABEL_EXTRA)).isEqualTo("feed")
    assertThat(copy).isEqualTo(original)
  }

  @Test
  fun testExtras_requestIsNotAffectedByLaterBuilderChanges() {
    val builder = newBuilder().setExtra(SIZE_EXTRA, 3)
    val request = builder.build()

    builder.setExtra(SIZE_EXTRA, 4)

    assertThat(request.getExtra(SIZE_EXTRA)).isEqualTo(3)
  }

  @Test(expected = UnsupportedOperationException::class)
  fun testExtras_requestExtrasAreReadOnly() {
    val request = newBuilder().setExtra(SIZE_EXTRA, 3).build()

    request.extras?.clear()
  }

  @Test
  fun testExtras_equalityExtraDistinguishesRequests() {
    val small = newBuilder().setExtra(SIZE_EXTRA, 1).build()
    val large = newBuilder().setExtra(SIZE_EXTRA, 2).build()
    val none = newBuilder().build()

    assertThat(small).isNotEqualTo(large)
    assertThat(small).isNotEqualTo(none)
    assertThat(none).isNotEqualTo(small)
  }

  @Test
  fun testExtras_nonEqualityExtraKeepsRequestsEqual() {
    val feed = newBuilder().setExtra(LABEL_EXTRA, "feed").build()
    val explore = newBuilder().setExtra(LABEL_EXTRA, "explore").build()
    val none = newBuilder().build()

    assertThat(feed).isEqualTo(explore)
    assertThat(feed).isEqualTo(none)
    assertThat(feed.hashCode()).isEqualTo(explore.hashCode())
    assertThat(feed.hashCode()).isEqualTo(none.hashCode())
  }

  @Test
  fun testExtras_equalRequestsHaveEqualHashCodes() {
    val first = newBuilder().setExtra(SIZE_EXTRA, 3).build()
    val second = newBuilder().setExtra(SIZE_EXTRA, 3).build()

    assertThat(first).isEqualTo(second)
    assertThat(first.hashCode()).isEqualTo(second.hashCode())
  }

  @Test
  fun testSourceSupplier_nullResultLeavesSourceUnset() {
    val builder = ImageRequestBuilder.newBuilderWithSourceSupplier { null }

    assertThat(builder.sourceUri).isNull()
    assertThatThrownBy { builder.build() }
        .isInstanceOf(ImageRequestBuilder.BuilderException::class.java)
        .hasMessageContaining("Source must be set")
  }

  @Test
  fun testSourceSupplier_receivesItsBuilderAndMayStoreExtrasOnIt() {
    var received: ImageRequestBuilder? = null
    val builder = ImageRequestBuilder.newBuilderWithSourceSupplier { owner ->
      received = owner
      owner.setExtra(SIZE_EXTRA, 3)
      Uri.parse("https://frescolib.org/image.jpg")
    }

    val request = builder.build()

    assertThat(received).isSameAs(builder)
    assertThat(request.getExtra(SIZE_EXTRA)).isEqualTo(3)
  }

  @Test
  fun testSourceSupplier_resolvesOnFirstReadAndIsReusedByRequests() {
    val source = Uri.parse("https://frescolib.org/image.jpg")
    var calls = 0
    val builder = ImageRequestBuilder.newBuilderWithSourceSupplier {
      calls++
      source
    }
    assertThat(calls).isZero()

    assertThat(builder.sourceUri).isSameAs(source)
    val request = builder.build()
    val copy = ImageRequestBuilder.fromRequest(request).build()

    assertThat(builder.build().sourceUri).isSameAs(source)
    assertThat(request.sourceUri).isSameAs(source)
    assertThat(copy.sourceUri).isSameAs(source)
    assertThat(calls).isEqualTo(1)
  }

  @Test
  fun testSourceSupplier_buildUsesResolvedSourceForTypeAndDiskCache() {
    for (source in
        listOf(Uri.parse("https://frescolib.org/image.jpg"), Uri.parse("asset:///image.jpg"))) {
      var calls = 0
      val builder = ImageRequestBuilder.newBuilderWithSourceSupplier {
        calls++
        source
      }
      val request = builder.build()
      val eager = ImageRequestBuilder.newBuilderWithSource(source).build()

      assertThat(request).isEqualTo(eager)
      assertThat(request.sourceUriType).isEqualTo(eager.sourceUriType)
      assertThat(request.isDiskCacheEnabled).isEqualTo(eager.isDiskCacheEnabled)
      assertThat(builder.isDiskCacheEnabled).isEqualTo(eager.isDiskCacheEnabled)
      assertThat(calls).isEqualTo(1)
    }
  }

  @Test
  fun testSourceSupplier_diskCacheReadResolvesSourceOnce() {
    var calls = 0
    val builder = ImageRequestBuilder.newBuilderWithSourceSupplier {
      calls++
      Uri.parse("https://frescolib.org/image.jpg")
    }

    assertThat(builder.isDiskCacheEnabled).isTrue()
    assertThat(builder.build().isDiskCacheEnabled).isTrue()
    assertThat(calls).isEqualTo(1)
  }

  @Test
  fun testSourceSupplier_setSourceDiscardsUnresolvedSupplier() {
    val source = Uri.parse("https://frescolib.org/replacement.jpg")
    val builder = ImageRequestBuilder.newBuilderWithSourceSupplier {
      throw AssertionError("Discarded supplier was invoked")
    }

    assertThat(builder.setSource(source).build().sourceUri).isSameAs(source)
  }

  @Test
  fun testSourceSupplier_replacementDiscardsPreviouslyResolvedSource() {
    val source = Uri.parse("asset:///replacement.jpg")
    val builder = newBuilder()
    builder.sourceUri
    builder.setSourceSupplier { source }

    assertThat(builder.build().sourceUri).isSameAs(source)
    assertThat(builder.isDiskCacheEnabled).isFalse()
  }

  @Test
  fun testSourceSupplier_replacementDiscardsUnresolvedSupplier() {
    val source = Uri.parse("asset:///replacement.jpg")
    val builder = ImageRequestBuilder.newBuilderWithSourceSupplier {
      throw AssertionError("Discarded supplier was invoked")
    }
    builder.setSourceSupplier { source }

    assertThat(builder.build().sourceUri).isSameAs(source)
  }

  @Test
  fun testSourceSupplier_replacementDoesNotChangePreviouslyBuiltRequest() {
    val originalSource = Uri.parse("https://frescolib.org/image.jpg")
    val replacementSource = Uri.parse("asset:///replacement.jpg")
    var originalCalls = 0
    var replacementCalls = 0
    val builder = ImageRequestBuilder.newBuilderWithSourceSupplier {
      originalCalls++
      originalSource
    }
    val original = builder.build()
    builder.setSourceSupplier {
      replacementCalls++
      replacementSource
    }
    val replacement = builder.build()
    val eagerReplacement = ImageRequestBuilder.newBuilderWithSource(replacementSource).build()

    assertThat(original.sourceUri).isSameAs(originalSource)
    assertThat(original.isDiskCacheEnabled).isTrue()
    assertThat(replacement.sourceUri).isSameAs(replacementSource)
    assertThat(replacement.sourceUriType).isEqualTo(eagerReplacement.sourceUriType)
    assertThat(replacement.isDiskCacheEnabled).isFalse()
    assertThat(builder.build().sourceUri).isSameAs(replacementSource)
    assertThat(originalCalls).isEqualTo(1)
    assertThat(replacementCalls).isEqualTo(1)
  }

  @Test
  fun testSourceSupplier_keepsNativeResourceValidation() {
    val builder = ImageRequestBuilder.newBuilderWithSourceSupplier {
      Uri.parse("res:///named_resource")
    }

    assertThatThrownBy { builder.build() }
        .isInstanceOf(ImageRequestBuilder.BuilderException::class.java)
        .hasMessageContaining("Resource URI path must be a resource id")
  }

  @Test
  fun testSourceSupplier_customValidationRunsBeforeSourceRead() {
    var validated = false
    val builder = ImageRequestBuilder.newBuilderWithSourceSupplier {
      assertThat(validated).isTrue()
      Uri.parse("res:///named_resource")
    }
    val request = builder.build { validated = true }

    assertThat(request.sourceUri.path).isEqualTo("/named_resource")
  }

  @Test
  fun testSourceSupplier_nullResultFailsCustomValidationBuild() {
    var validated = false
    val builder = ImageRequestBuilder.newBuilderWithSourceSupplier { null }

    assertThatThrownBy { builder.build { validated = true } }
        .isInstanceOf(ImageRequestBuilder.BuilderException::class.java)
        .hasMessageContaining("Source must be set")
    assertThat(validated).isTrue()
  }

  private fun newBuilder(): ImageRequestBuilder =
      ImageRequestBuilder.newBuilderWithSource(Uri.parse("http://frescolib.org/image.jpg"))

  private companion object {
    val SIZE_EXTRA = ImageRequestExtra<Int>("size")
    val LABEL_EXTRA = ImageRequestExtra<String>("label", affectsEquality = false)
  }
}
