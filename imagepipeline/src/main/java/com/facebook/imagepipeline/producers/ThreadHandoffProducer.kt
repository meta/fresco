/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.imagepipeline.producers

import com.facebook.imagepipeline.instrumentation.FrescoInstrumenter
import com.facebook.imagepipeline.systrace.FrescoSystrace.traceSection
import java.lang.Exception

/** Uses ExecutorService to move further computation to different thread */
class ThreadHandoffProducer<T>(
    val inputProducer: Producer<T>,
    val threadHandoffProducerQueue: ThreadHandoffProducerQueue,
) : Producer<T> {

  override fun produceResults(consumer: Consumer<T>, context: ProducerContext) {
    traceSection("ThreadHandoffProducer#produceResults") {
      val producerListener = context.producerListener
      if (shouldRunImmediately(context)) {
        producerListener.onProducerStart(context, PRODUCER_NAME)
        producerListener.onProducerFinishWithSuccess(context, PRODUCER_NAME, null)
        inputProducer.produceResults(consumer, context)
        return
      }

      val statefulRunnable: StatefulProducerRunnable<T> =
          object : StatefulProducerRunnable<T>(consumer, producerListener, context, PRODUCER_NAME) {
            override fun onSuccess(result: T?) {
              producerListener.onProducerFinishWithSuccess(context, PRODUCER_NAME, null)
              inputProducer.produceResults(consumer, context)
            }

            override fun disposeResult(result: T?) = Unit

            @Throws(Exception::class)
            override fun getResult(): T? {
              return null
            }
          }
      context.addCallbacks(
          object : BaseProducerContextCallbacks() {
            override fun onCancellationRequested() {
              statefulRunnable.cancel()
              threadHandoffProducerQueue.remove(statefulRunnable)
            }
          },
      )
      threadHandoffProducerQueue.addToQueueOrExecute(
          FrescoInstrumenter.decorateRunnable(statefulRunnable, getInstrumentationTag(context)),
      )
    }
  }

  companion object {
    const val PRODUCER_NAME = "BackgroundThreadHandoffProducer"

    private fun shouldRunImmediately(context: ProducerContext): Boolean {
      return BaseProducerContext.getImagePipelineConfigOrNull(context)?.let {
        !it.experiments.isCriticalThread()
      } ?: false
    }

    private fun getInstrumentationTag(context: ProducerContext): String? {
      return if (FrescoInstrumenter.isTracing) "ThreadHandoffProducer_produceResults_" + context.id
      else null
    }
  }
}
