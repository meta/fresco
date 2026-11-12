/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

// Meta's ImmutableMap, not Guava's (temporary-lint alias).
import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import com.facebook.common.internal.ImmutableMap as FbImmutableMap
import com.facebook.common.internal.Preconditions
import com.facebook.common.references.CloseableReference
import com.facebook.imagepipeline.bitmaps.PlatformBitmapFactory
import com.facebook.imagepipeline.image.CloseableImage
import com.facebook.imagepipeline.image.CloseableStaticBitmap
import com.facebook.imagepipeline.request.Postprocessor
import com.facebook.imagepipeline.request.RepeatedPostprocessor
import com.facebook.imagepipeline.request.RepeatedPostprocessorRunner
import java.util.concurrent.Executor
import javax.annotation.concurrent.GuardedBy

/**
 * Runs a caller-supplied post-processor object.
 *
 * Post-processors are only supported for static bitmaps. If the request is for an animated image,
 * the post-processor step will be skipped without warning.
 */
class PostprocessorProducer(
    inputProducer: Producer<CloseableReference<CloseableImage>>,
    private val mBitmapFactory: PlatformBitmapFactory,
    executor: Executor?,
) : Producer<CloseableReference<CloseableImage>> {
  private val inputProducer: Producer<CloseableReference<CloseableImage>>
  private val mExecutor: Executor

  init {
    this.inputProducer =
        Preconditions.checkNotNull<Producer<CloseableReference<CloseableImage>>>(inputProducer)
    mExecutor = Preconditions.checkNotNull<Executor>(executor)
  }

  override fun produceResults(
      consumer: Consumer<CloseableReference<CloseableImage>>,
      context: ProducerContext,
  ) {
    val listener = context.producerListener
    val postprocessor = context.imageRequest.getPostprocessor()
    Preconditions.checkNotNull<Postprocessor?>(postprocessor)
    val basePostprocessorConsumer =
        PostprocessorConsumer(consumer, listener, postprocessor!!, context)
    val postprocessorConsumer: Consumer<CloseableReference<CloseableImage>>
    if (postprocessor is RepeatedPostprocessor) {
      postprocessorConsumer =
          RepeatedPostprocessorConsumer(basePostprocessorConsumer, postprocessor, context)
    } else {
      postprocessorConsumer = SingleUsePostprocessorConsumer(basePostprocessorConsumer)
    }
    inputProducer.produceResults(postprocessorConsumer, context)
  }

  /** Performs postprocessing and takes care of scheduling. */
  inner class PostprocessorConsumer(
      consumer: Consumer<CloseableReference<CloseableImage>>,
      private val mListener: ProducerListener2,
      private val mPostprocessor: Postprocessor,
      private val mProducerContext: ProducerContext,
  ) :
      DelegatingConsumer<
          CloseableReference<CloseableImage>,
          CloseableReference<CloseableImage>,
      >(consumer) {
    @get:Synchronized
    @GuardedBy("PostprocessorConsumer.this")
    var isClosed: Boolean = false
      private set

    @GuardedBy("PostprocessorConsumer.this")
    private var mSourceImageRef: CloseableReference<CloseableImage>? = null

    @GuardedBy("PostprocessorConsumer.this") private var status = 0

    @GuardedBy("PostprocessorConsumer.this") private var isDirty = false

    @GuardedBy("PostprocessorConsumer.this") private var isPostProcessingRunning = false

    init {
      mProducerContext.addCallbacks(
          object : BaseProducerContextCallbacks() {
            override fun onCancellationRequested() {
              maybeNotifyOnCancellation()
            }
          },
      )
    }

    override fun onNewResultImpl(
        newResult: CloseableReference<CloseableImage>?,
        @Consumer.Status status: Int,
    ) {
      if (!CloseableReference.isValid(newResult)) {
        // try to propagate if the last result is invalid
        if (isLast(status)) {
          maybeNotifyOnNewResult(null, status)
        }
        // ignore if invalid
        return
      }
      updateSourceImageRef(newResult, status)
    }

    override fun onFailureImpl(t: Throwable) {
      maybeNotifyOnFailure(t)
    }

    override fun onCancellationImpl() {
      maybeNotifyOnCancellation()
    }

    fun updateSourceImageRef(sourceImageRef: CloseableReference<CloseableImage>?, status: Int) {
      val oldSourceImageRef: CloseableReference<CloseableImage>?
      val shouldSubmit: Boolean
      synchronized(this@PostprocessorConsumer) {
        if (this.isClosed) {
          return
        }
        oldSourceImageRef = mSourceImageRef
        mSourceImageRef = CloseableReference.cloneOrNull<CloseableImage>(sourceImageRef)
        this.status = status
        isDirty = true
        shouldSubmit = setRunningIfDirtyAndNotRunning()
      }
      CloseableReference.closeSafely(oldSourceImageRef)
      if (shouldSubmit) {
        submitPostprocessing()
      }
    }

    fun submitPostprocessing() {
      mExecutor.execute(
          object : Runnable {
            override fun run() {
              val closeableImageRef: CloseableReference<CloseableImage>?
              val status: Int
              synchronized(this@PostprocessorConsumer) {
                // instead of cloning and closing the reference, we do a more efficient move.
                closeableImageRef = mSourceImageRef
                status = this@PostprocessorConsumer.status
                mSourceImageRef = null
                isDirty = false
              }

              if (CloseableReference.isValid(closeableImageRef)) {
                try {
                  doPostprocessing(closeableImageRef, status)
                } finally {
                  CloseableReference.closeSafely(closeableImageRef)
                }
              }
              clearRunningAndStartIfDirty()
            }
          },
      )
    }

    fun clearRunningAndStartIfDirty() {
      val shouldExecuteAgain: Boolean
      synchronized(this@PostprocessorConsumer) {
        isPostProcessingRunning = false
        shouldExecuteAgain = setRunningIfDirtyAndNotRunning()
      }
      if (shouldExecuteAgain) {
        submitPostprocessing()
      }
    }

    @Synchronized
    fun setRunningIfDirtyAndNotRunning(): Boolean {
      if (
          !this.isClosed &&
              isDirty &&
              !isPostProcessingRunning &&
              CloseableReference.isValid(mSourceImageRef)
      ) {
        isPostProcessingRunning = true
        return true
      }
      return false
    }

    fun doPostprocessing(sourceImageRef: CloseableReference<CloseableImage>?, status: Int) {
      Preconditions.checkArgument(CloseableReference.isValid(sourceImageRef))
      if (!shouldPostprocess(sourceImageRef!!.get())) {
        maybeNotifyOnNewResult(sourceImageRef, status)
        return
      }
      mListener.onProducerStart(mProducerContext, NAME)
      var destImageRef: CloseableReference<CloseableImage>? = null
      try {
        try {
          destImageRef = postprocessInternal(sourceImageRef.get())
        } catch (e: Exception) {
          mListener.onProducerFinishWithFailure(
              mProducerContext,
              NAME,
              e,
              getExtraMap(mListener, mProducerContext, mPostprocessor),
          )
          maybeNotifyOnFailure(e)
          return
        }
        mListener.onProducerFinishWithSuccess(
            mProducerContext,
            NAME,
            getExtraMap(mListener, mProducerContext, mPostprocessor),
        )
        maybeNotifyOnNewResult(destImageRef, status)
      } finally {
        CloseableReference.closeSafely(destImageRef)
      }
    }

    fun getExtraMap(
        listener: ProducerListener2,
        producerContext: ProducerContext?,
        postprocessor: Postprocessor?,
    ): MutableMap<String?, String?>? {
      if (!listener.requiresExtraMap(producerContext, NAME)) {
        return null
      }
      return FbImmutableMap.of<String?, String?>(POSTPROCESSOR, postprocessor!!.getName())
    }

    fun shouldPostprocess(sourceImage: CloseableImage?): Boolean {
      return (sourceImage is CloseableStaticBitmap)
    }

    fun postprocessInternal(sourceImage: CloseableImage): CloseableReference<CloseableImage>? {
      val staticBitmap = sourceImage as CloseableStaticBitmap
      val sourceBitmap =
          checkNotNull(staticBitmap.getUnderlyingBitmap()) {
            "Underlying bitmap is null (image may be closed)"
          }
      val bitmapRef = mPostprocessor.process(sourceBitmap, mBitmapFactory)
      val rotationAngle = staticBitmap.getRotationAngle()
      val exifOrientation = staticBitmap.getExifOrientation()
      try {
        val closeableStaticBitmap =
            CloseableStaticBitmap.of(
                bitmapRef,
                sourceImage.getQualityInfo(),
                rotationAngle,
                exifOrientation,
            )
        closeableStaticBitmap.putExtras(staticBitmap.getExtras())
        return CloseableReference.of<CloseableImage>(closeableStaticBitmap)
      } finally {
        CloseableReference.closeSafely(bitmapRef)
      }
    }

    fun maybeNotifyOnNewResult(newRef: CloseableReference<CloseableImage>?, status: Int) {
      val isLast = isLast(status)
      if ((!isLast && !this.isClosed) || (isLast && close())) {
        consumer.onNewResult(newRef, status)
      }
    }

    fun maybeNotifyOnFailure(throwable: Throwable?) {
      if (close()) {
        consumer.onFailure(throwable)
      }
    }

    fun maybeNotifyOnCancellation() {
      if (close()) {
        consumer.onCancellation()
      }
    }

    fun close(): Boolean {
      val oldSourceImageRef: CloseableReference<CloseableImage>?
      synchronized(this@PostprocessorConsumer) {
        if (this.isClosed) {
          return false
        }
        oldSourceImageRef = mSourceImageRef
        mSourceImageRef = null
        this.isClosed = true
      }
      CloseableReference.closeSafely(oldSourceImageRef)
      return true
    }
  }

  /** PostprocessorConsumer wrapper that ignores intermediate results. */
  inner class SingleUsePostprocessorConsumer
  constructor(postprocessorConsumer: PostprocessorConsumer) :
      DelegatingConsumer<
          CloseableReference<CloseableImage>,
          CloseableReference<CloseableImage>,
      >(postprocessorConsumer) {
    override fun onNewResultImpl(
        newResult: CloseableReference<CloseableImage>?,
        @Consumer.Status status: Int,
    ) {
      // ignore intermediate results
      if (isNotLast(status)) {
        return
      }
      consumer.onNewResult(newResult, status)
    }
  }

  /**
   * PostprocessorConsumer wrapper that allows repeated postprocessing.
   *
   * Reference to the last result received is cloned and kept until the request is cancelled. In
   * order to allow multiple postprocessing, results are always propagated as non-final. When
   * [ ][.update] is called, a new postprocessing of the last received result is requested.
   *
   * Intermediate results are ignored.
   */
  inner class RepeatedPostprocessorConsumer
  constructor(
      postprocessorConsumer: PostprocessorConsumer,
      repeatedPostprocessor: RepeatedPostprocessor,
      context: ProducerContext,
  ) :
      DelegatingConsumer<
          CloseableReference<CloseableImage>,
          CloseableReference<CloseableImage>,
      >(postprocessorConsumer),
      RepeatedPostprocessorRunner {
    @GuardedBy("RepeatedPostprocessorConsumer.this") private var mIsClosed = false

    @GuardedBy("RepeatedPostprocessorConsumer.this")
    private var mSourceImageRef: CloseableReference<CloseableImage?>? = null

    init {
      repeatedPostprocessor.setCallback(this@RepeatedPostprocessorConsumer)
      context.addCallbacks(
          object : BaseProducerContextCallbacks() {
            override fun onCancellationRequested() {
              if (close()) {
                consumer.onCancellation()
              }
            }
          },
      )
    }

    override fun onNewResultImpl(
        newResult: CloseableReference<CloseableImage>?,
        @Consumer.Status status: Int,
    ) {
      // ignore intermediate results
      if (isNotLast(status)) {
        return
      }
      setSourceImageRef(newResult)
      updateInternal()
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun onFailureImpl(throwable: Throwable) {
      if (close()) {
        consumer.onFailure(throwable)
      }
    }

    override fun onCancellationImpl() {
      if (close()) {
        consumer.onCancellation()
      }
    }

    @Synchronized
    override fun update() {
      updateInternal()
    }

    @SuppressLint("WrongConstant")
    private fun updateInternal() {
      val sourceImageRef: CloseableReference<CloseableImage>?
      synchronized(this@RepeatedPostprocessorConsumer) {
        if (mIsClosed) {
          return
        }
        sourceImageRef = CloseableReference.cloneOrNull<CloseableImage>(mSourceImageRef)
      }
      try {
        consumer.onNewResult(sourceImageRef, Consumer.Companion.NO_FLAGS)
      } finally {
        CloseableReference.closeSafely(sourceImageRef)
      }
    }

    private fun setSourceImageRef(sourceImageRef: CloseableReference<CloseableImage>?) {
      val oldSourceImageRef: CloseableReference<CloseableImage?>?
      synchronized(this@RepeatedPostprocessorConsumer) {
        if (mIsClosed) {
          return
        }
        oldSourceImageRef = mSourceImageRef
        mSourceImageRef = CloseableReference.cloneOrNull<CloseableImage?>(sourceImageRef)
      }
      CloseableReference.closeSafely(oldSourceImageRef)
    }

    private fun close(): Boolean {
      val oldSourceImageRef: CloseableReference<CloseableImage?>?
      synchronized(this@RepeatedPostprocessorConsumer) {
        if (mIsClosed) {
          return false
        }
        oldSourceImageRef = mSourceImageRef
        mSourceImageRef = null
        mIsClosed = true
      }
      CloseableReference.closeSafely(oldSourceImageRef)
      return true
    }
  }

  companion object {
    const val NAME: String = "PostprocessorProducer"
    @VisibleForTesting const val POSTPROCESSOR: String = "Postprocessor"
  }
}
