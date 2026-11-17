/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers;

import static com.facebook.imageformat.DefaultImageFormats.HEIF;
import static com.facebook.imageformat.DefaultImageFormats.JPEG;
import static com.facebook.imagepipeline.transcoder.JpegTranscoderUtils.DEFAULT_JPEG_QUALITY;

import android.media.ExifInterface;
import androidx.annotation.VisibleForTesting;
import com.facebook.common.internal.ImmutableMap;
import com.facebook.common.internal.Preconditions;
import com.facebook.common.memory.PooledByteBuffer;
import com.facebook.common.memory.PooledByteBufferFactory;
import com.facebook.common.memory.PooledByteBufferOutputStream;
import com.facebook.common.references.CloseableReference;
import com.facebook.common.util.TriState;
import com.facebook.imageformat.ImageFormat;
import com.facebook.imagepipeline.common.ResizeOptions;
import com.facebook.imagepipeline.common.RotationOptions;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.transcoder.DownsampleUtil;
import com.facebook.imagepipeline.transcoder.ImageTranscodeResult;
import com.facebook.imagepipeline.transcoder.ImageTranscoder;
import com.facebook.imagepipeline.transcoder.ImageTranscoderFactory;
import com.facebook.imagepipeline.transcoder.JpegTranscoderUtils;
import com.facebook.imagepipeline.transcoder.TranscodeStatus;
import com.facebook.infer.annotation.Nullsafe;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

/**
 * Resizes and rotates images according to the EXIF orientation data or a specified rotation angle.
 *
 * <p>If the image is not supported by the {@link ImageTranscoder}, no transformation is applied.
 *
 * <p>This can be used even if downsampling is enabled as long as resizing is disabled.
 */
@Nullsafe(Nullsafe.Mode.LOCAL)
public class ResizeAndRotateProducer implements Producer<EncodedImage> {
  private static final String PRODUCER_NAME = "ResizeAndRotateProducer";
  private static final String INPUT_IMAGE_FORMAT = "Image format";
  private static final String ORIGINAL_SIZE_KEY = "Original size";
  private static final String REQUESTED_SIZE_KEY = "Requested size";
  private static final String TRANSCODING_RESULT = "Transcoding result";
  private static final String TRANSCODER_ID = "Transcoder id";

  @VisibleForTesting static final int MIN_TRANSFORM_INTERVAL_MS = 100;

  private final Executor mExecutor;
  private final PooledByteBufferFactory mPooledByteBufferFactory;
  private final Producer<EncodedImage> mInputProducer;
  private final boolean mIsResizingEnabled;
  private final ImageTranscoderFactory mImageTranscoderFactory;

  public ResizeAndRotateProducer(
      final Executor executor,
      final PooledByteBufferFactory pooledByteBufferFactory,
      final Producer<EncodedImage> inputProducer,
      final boolean isResizingEnabled,
      final ImageTranscoderFactory imageTranscoderFactory) {
    mExecutor = Preconditions.checkNotNull(executor);
    mPooledByteBufferFactory = Preconditions.checkNotNull(pooledByteBufferFactory);
    mInputProducer = Preconditions.checkNotNull(inputProducer);
    mImageTranscoderFactory = Preconditions.checkNotNull(imageTranscoderFactory);
    mIsResizingEnabled = isResizingEnabled;
  }

  @Override
  public void produceResults(final Consumer<EncodedImage> consumer, final ProducerContext context) {
    mInputProducer.produceResults(
        new TransformingConsumer(consumer, context, mIsResizingEnabled, mImageTranscoderFactory),
        context);
  }

  private class TransformingConsumer extends DelegatingConsumer<EncodedImage, EncodedImage> {

    private final boolean mIsResizingEnabled;
    private final ImageTranscoderFactory mImageTranscoderFactory;
    private final ProducerContext mProducerContext;
    private boolean mIsCancelled;

    private final JobScheduler mJobScheduler;

    TransformingConsumer(
        final Consumer<EncodedImage> consumer,
        final ProducerContext producerContext,
        final boolean isResizingEnabled,
        final ImageTranscoderFactory imageTranscoderFactory) {
      super(consumer);
      mIsCancelled = false;
      mProducerContext = producerContext;

      final ImageRequest imageRequest = mProducerContext.getImageRequest();
      final ResizeOptions resizeOptions = imageRequest.getResizeOptions();
      final Boolean resizingAllowedOverride = imageRequest.getResizingAllowedOverride();
      if (resizeOptions != null && resizeOptions.transcoder == ResizeOptions.Transcoder.NONE) {
        mIsResizingEnabled = false;
      } else {
        mIsResizingEnabled =
            resizingAllowedOverride != null
                ? resizingAllowedOverride // use request settings if available
                : isResizingEnabled; // fallback to default option supplied
      }

      mImageTranscoderFactory = imageTranscoderFactory;

      JobScheduler.JobRunnable job =
          new JobScheduler.JobRunnable() {
            @Override
            public void run(@Nullable EncodedImage encodedImage, @Status int status) {
              if (encodedImage != null) {
                doTransform(
                    encodedImage,
                    status,
                    Preconditions.checkNotNull(
                        mImageTranscoderFactory.createImageTranscoder(
                            encodedImage.getImageFormat(), isResizingEnabled(encodedImage))));
              } else {
                getConsumer().onNewResult(null, status);
              }
            }
          };
      mJobScheduler = new JobScheduler(mExecutor, job, MIN_TRANSFORM_INTERVAL_MS);

      mProducerContext.addCallbacks(
          new BaseProducerContextCallbacks() {
            @Override
            public void onIsIntermediateResultExpectedChanged() {
              if (mProducerContext.isIntermediateResultExpected()) {
                mJobScheduler.scheduleJob();
              }
            }

            @Override
            public void onCancellationRequested() {
              mJobScheduler.clearJob();
              mIsCancelled = true;
              // this only works if it is safe to discard the output of previous producer
              consumer.onCancellation();
            }
          });
    }

    @Override
    protected void onNewResultImpl(@Nullable EncodedImage newResult, @Status int status) {
      if (mIsCancelled) {
        return;
      }
      boolean isLast = isLast(status);
      if (newResult == null) {
        if (isLast) {
          getConsumer().onNewResult(null, Consumer.IS_LAST);
        }
        return;
      }
      ImageFormat imageFormat = newResult.getImageFormat();
      TriState shouldTransform =
          shouldTransform(
              mProducerContext.getImageRequest(),
              newResult,
              Preconditions.checkNotNull(
                  mImageTranscoderFactory.createImageTranscoder(
                      imageFormat, isResizingEnabled(newResult))));
      // ignore the intermediate result if we don't know what to do with it
      if (!isLast && shouldTransform == TriState.UNSET) {
        return;
      }
      // just forward the result if we know that it shouldn't be transformed
      if (shouldTransform != TriState.YES) {
        forwardNewResult(newResult, status, imageFormat);
        return;
      }
      // we know that the result should be transformed, hence schedule it
      if (!mJobScheduler.updateJob(newResult, status)) {
        return;
      }
      if (isLast || mProducerContext.isIntermediateResultExpected()) {
        mJobScheduler.scheduleJob();
      }
    }

    private boolean isResizingEnabled(EncodedImage encodedImage) {
      if (!mIsResizingEnabled) {
        return false;
      }
      final ImageRequest imageRequest = mProducerContext.getImageRequest();
      final ResizeOptions resizeOptions = imageRequest.getResizeOptions();
      if (resizeOptions == null
          || resizeOptions.transcoder != ResizeOptions.Transcoder.NONE_FOR_DECODE_SCALES
          || encodedImage.getImageFormat() != JPEG) {
        return true;
      }
      final RotationOptions rotationOptions = imageRequest.getRotationOptions();
      final int numerator =
          JpegTranscoderUtils.getSoftwareNumerator(
              rotationOptions, resizeOptions, encodedImage, true);
      final int sampleSize =
          DownsampleUtil.determineSampleSize(
              rotationOptions, resizeOptions, encodedImage, (int) resizeOptions.maxBitmapDimension);
      return numerator * sampleSize != JpegTranscoderUtils.SCALE_DENOMINATOR;
    }

    private void forwardNewResult(
        EncodedImage newResult, @Status int status, ImageFormat imageFormat) {
      if (imageFormat == JPEG || imageFormat == HEIF) {
        newResult = getNewResultsForJpegOrHeif(newResult);
      } else {
        newResult = getNewResultForImagesWithoutExifData(newResult);
      }
      getConsumer().onNewResult(newResult, status);
    }

    private @Nullable EncodedImage getNewResultForImagesWithoutExifData(EncodedImage encodedImage) {
      RotationOptions options = mProducerContext.getImageRequest().getRotationOptions();
      if (!options.useImageMetadata() && options.rotationEnabled()) {
        encodedImage = getCloneWithRotationApplied(encodedImage, options.getForcedAngle());
      }
      return encodedImage;
    }

    private @Nullable EncodedImage getNewResultsForJpegOrHeif(EncodedImage encodedImage) {
      if (!mProducerContext.getImageRequest().getRotationOptions().canDeferUntilRendered()
          && encodedImage.getRotationAngle() != 0
          && encodedImage.getRotationAngle() != EncodedImage.UNKNOWN_ROTATION_ANGLE) {
        encodedImage = getCloneWithRotationApplied(encodedImage, RotationOptions.NO_ROTATION);
      }
      return encodedImage;
    }

    private @Nullable EncodedImage getCloneWithRotationApplied(
        EncodedImage encodedImage, int angle) {
      EncodedImage newResult = EncodedImage.cloneOrNull(encodedImage); // for thread-safety sake
      if (newResult != null) {
        newResult.setRotationAngle(angle);
      }
      return newResult;
    }

    private void doTransform(
        EncodedImage encodedImage, @Status int status, ImageTranscoder imageTranscoder) {
      mProducerContext.getProducerListener().onProducerStart(mProducerContext, PRODUCER_NAME);
      ImageRequest imageRequest = mProducerContext.getImageRequest();
      PooledByteBufferOutputStream outputStream = mPooledByteBufferFactory.newOutputStream();
      Map<String, String> extraMap = null;
      EncodedImage ret;
      try {
        ImageTranscodeResult result =
            imageTranscoder.transcode(
                encodedImage,
                outputStream,
                imageRequest.getRotationOptions(),
                imageRequest.getResizeOptions(),
                null,
                DEFAULT_JPEG_QUALITY,
                encodedImage.getColorSpace());

        if (result.getTranscodeStatus() == TranscodeStatus.TRANSCODING_ERROR) {
          throw new RuntimeException("Error while transcoding the image");
        }

        extraMap =
            this.getExtraMap(
                encodedImage,
                imageRequest.getResizeOptions(),
                result,
                imageTranscoder.getIdentifier());

        CloseableReference<PooledByteBuffer> ref =
            CloseableReference.of(outputStream.toByteBuffer());
        try {
          ret = new EncodedImage(ref);
          ret.setImageFormat(result.getOutputFormat());
          try {
            ret.parseMetaData();
            mProducerContext
                .getProducerListener()
                .onProducerFinishWithSuccess(mProducerContext, PRODUCER_NAME, extraMap);
            if (result.getTranscodeStatus() != TranscodeStatus.TRANSCODING_NO_RESIZING) {
              status |= Consumer.IS_RESIZING_DONE;
            }
            getConsumer().onNewResult(ret, status);
          } finally {
            EncodedImage.closeSafely(ret);
          }
        } finally {
          CloseableReference.closeSafely(ref);
        }
      } catch (Exception e) {
        mProducerContext
            .getProducerListener()
            .onProducerFinishWithFailure(mProducerContext, PRODUCER_NAME, e, extraMap);
        if (isLast(status)) {
          getConsumer().onFailure(e);
        }
        return;
      } finally {
        outputStream.close();
      }
    }

    private @Nullable Map<String, String> getExtraMap(
        EncodedImage encodedImage,
        @Nullable ResizeOptions resizeOptions,
        @Nullable ImageTranscodeResult transcodeResult,
        @Nullable String transcoderId) {
      if (!mProducerContext
          .getProducerListener()
          .requiresExtraMap(mProducerContext, PRODUCER_NAME)) {
        return null;
      }
      String originalSize = encodedImage.getWidth() + "x" + encodedImage.getHeight();

      String requestedSize;
      if (resizeOptions != null) {
        requestedSize = resizeOptions.width + "x" + resizeOptions.height;
      } else {
        requestedSize = "Unspecified";
      }

      final Map<String, String> map = new HashMap<>();
      map.put(INPUT_IMAGE_FORMAT, String.valueOf(encodedImage.getImageFormat()));
      map.put(ORIGINAL_SIZE_KEY, originalSize);
      map.put(REQUESTED_SIZE_KEY, requestedSize);
      map.put(JobScheduler.QUEUE_TIME_KEY, String.valueOf(mJobScheduler.getQueuedTime()));
      map.put(TRANSCODER_ID, transcoderId);
      map.put(TRANSCODING_RESULT, String.valueOf(transcodeResult));
      return ImmutableMap.copyOf(map);
    }
  }

  private static TriState shouldTransform(
      ImageRequest request, EncodedImage encodedImage, ImageTranscoder imageTranscoder) {
    if (encodedImage == null || encodedImage.getImageFormat() == ImageFormat.UNKNOWN) {
      return TriState.UNSET;
    }

    if (!imageTranscoder.canTranscode(encodedImage.getImageFormat())) {
      return TriState.NO;
    }

    return TriState.valueOf(
        shouldRotate(request.getRotationOptions(), encodedImage)
            || imageTranscoder.canResize(
                encodedImage, request.getRotationOptions(), request.getResizeOptions())
            || imageTranscoder.canTransformAVIF(encodedImage.getImageFormat()));
  }

  private static boolean shouldRotate(RotationOptions rotationOptions, EncodedImage encodedImage) {
    return !rotationOptions.canDeferUntilRendered()
        && (JpegTranscoderUtils.getRotationAngle(rotationOptions, encodedImage) != 0
            || shouldRotateUsingExifOrientation(rotationOptions, encodedImage));
  }

  private static boolean shouldRotateUsingExifOrientation(
      RotationOptions rotationOptions, EncodedImage encodedImage) {
    if (!rotationOptions.rotationEnabled() || rotationOptions.canDeferUntilRendered()) {
      encodedImage.setExifOrientation(ExifInterface.ORIENTATION_UNDEFINED);
      return false;
    }
    return JpegTranscoderUtils.INVERTED_EXIF_ORIENTATIONS.contains(
        encodedImage.getExifOrientation());
  }
}
