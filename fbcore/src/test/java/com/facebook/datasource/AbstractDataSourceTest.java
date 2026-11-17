/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.datasource;

import static com.facebook.datasource.DataSourceTestUtils.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AbstractDataSourceTest {

  public interface Value {
    public void close();
  }

  private static class FakeAbstractDataSource extends AbstractDataSource<Value> {
    @Override
    public boolean setResult(@Nullable Value value, boolean isLast) {
      return super.setResult(value, isLast);
    }

    @Override
    public boolean setFailure(Throwable throwable) {
      return super.setFailure(throwable);
    }

    @Override
    public boolean setProgress(float progress) {
      return super.setProgress(progress);
    }

    @Override
    public void closeResult(Value result) {
      result.close();
    }
  }

  private Executor mExecutor1;
  private Executor mExecutor2;
  private DataSubscriber<Value> mDataSubscriber1;
  private DataSubscriber<Value> mDataSubscriber2;
  private FakeAbstractDataSource mDataSource;

  @Before
  public void setUp() {
    mExecutor1 = mock(Executor.class);
    mExecutor2 = mock(Executor.class);
    mDataSubscriber1 = mock(DataSubscriber.class);
    mDataSubscriber2 = mock(DataSubscriber.class);
    mDataSource = new FakeAbstractDataSource();
  }

  private void verifyExecutor(Executor executor) {
    ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
    verify(executor).execute(captor.capture());
    Runnable runnable = captor.getValue();
    assertThat(runnable).isNotNull();
    runnable.run();
  }

  private void verifySubscribers(int expected) {
    switch (expected) {
      case NO_INTERACTIONS:
        verifyNoMoreInteractions(mExecutor1, mDataSubscriber1);
        verifyNoMoreInteractions(mExecutor2, mDataSubscriber2);
        break;
      case ON_NEW_RESULT:
        verifyExecutor(mExecutor1);
        verify(mDataSubscriber1).onNewResult(mDataSource);
        verifyExecutor(mExecutor2);
        verify(mDataSubscriber2).onNewResult(mDataSource);
        break;
      case ON_FAILURE:
        verifyExecutor(mExecutor1);
        verify(mDataSubscriber1).onFailure(mDataSource);
        verifyExecutor(mExecutor2);
        verify(mDataSubscriber2).onFailure(mDataSource);
        break;
      case ON_CANCELLATION:
        verifyExecutor(mExecutor1);
        verify(mDataSubscriber1).onCancellation(mDataSource);
        verifyExecutor(mExecutor2);
        verify(mDataSubscriber2).onCancellation(mDataSource);
        break;
    }
    reset(mExecutor1, mExecutor2, mDataSubscriber1, mDataSubscriber2);
  }

  private void subscribe() {
    mDataSource.subscribe(mDataSubscriber1, mExecutor1);
    mDataSource.subscribe(mDataSubscriber2, mExecutor2);
  }

  @Test
  public void testInitialState() {
    verifyState(mDataSource, NOT_CLOSED, NOT_FINISHED, WITHOUT_RESULT, null, NOT_FAILED, null);
  }

  @Test
  public void testLifeCycle_LastResult_Close() {
    subscribe();
    // last result
    Value value = mock(Value.class);
    mDataSource.setResult(value, LAST);
    verifySubscribers(ON_NEW_RESULT);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITH_RESULT, value, NOT_FAILED, null);
    // close
    mDataSource.close();
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, CLOSED, FINISHED, WITHOUT_RESULT, null, NOT_FAILED, null);
  }

  @Test
  public void testLifeCycle_Failure_Close() {
    subscribe();
    // failure
    Throwable throwable = mock(Throwable.class);
    mDataSource.setFailure(throwable);
    verifySubscribers(ON_FAILURE);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITHOUT_RESULT, null, FAILED, throwable);
    // close
    mDataSource.close();
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, CLOSED, FINISHED, WITHOUT_RESULT, null, FAILED, throwable);
  }

  @Test
  public void testLifeCycle_IntermediateResult_LastResult_Close() {
    subscribe();
    // intermediate result
    Value value1 = mock(Value.class);
    mDataSource.setResult(value1, INTERMEDIATE);
    verifySubscribers(ON_NEW_RESULT);
    verifyState(mDataSource, NOT_CLOSED, NOT_FINISHED, WITH_RESULT, value1, NOT_FAILED, null);
    // last result
    Value value = mock(Value.class);
    mDataSource.setResult(value, LAST);
    verifySubscribers(ON_NEW_RESULT);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITH_RESULT, value, NOT_FAILED, null);
    // close
    mDataSource.close();
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, CLOSED, FINISHED, WITHOUT_RESULT, null, NOT_FAILED, null);
  }

  @Test
  public void testLifeCycle_IntermediateResult_Failure_Close() {
    subscribe();
    // intermediate result
    Value value1 = mock(Value.class);
    mDataSource.setResult(value1, INTERMEDIATE);
    verifySubscribers(ON_NEW_RESULT);
    verifyState(mDataSource, NOT_CLOSED, NOT_FINISHED, WITH_RESULT, value1, NOT_FAILED, null);
    // failure
    Throwable throwable = mock(Throwable.class);
    mDataSource.setFailure(throwable);
    verifySubscribers(ON_FAILURE);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITH_RESULT, value1, FAILED, throwable);
    // close
    mDataSource.close();
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, CLOSED, FINISHED, WITHOUT_RESULT, null, FAILED, throwable);
  }

  @Test
  public void testLifeCycle_AfterSuccess() {
    subscribe();
    // success
    Value value = mock(Value.class);
    mDataSource.setResult(value, LAST);
    verifySubscribers(ON_NEW_RESULT);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITH_RESULT, value, NOT_FAILED, null);
    // try intermediate
    mDataSource.setResult(mock(Value.class), INTERMEDIATE);
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITH_RESULT, value, NOT_FAILED, null);
    // try last
    mDataSource.setResult(mock(Value.class), LAST);
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITH_RESULT, value, NOT_FAILED, null);
    // try failure
    mDataSource.setFailure(mock(Throwable.class));
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITH_RESULT, value, NOT_FAILED, null);
  }

  @Test
  public void testLifeCycle_AfterFailure() {
    subscribe();
    // failure
    Throwable throwable = mock(Throwable.class);
    mDataSource.setFailure(throwable);
    verifySubscribers(ON_FAILURE);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITHOUT_RESULT, null, FAILED, throwable);
    // try intermediate
    mDataSource.setResult(mock(Value.class), INTERMEDIATE);
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITHOUT_RESULT, null, FAILED, throwable);
    // try last
    mDataSource.setResult(mock(Value.class), LAST);
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITHOUT_RESULT, null, FAILED, throwable);
    // try failure
    mDataSource.setFailure(mock(Throwable.class));
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, NOT_CLOSED, FINISHED, WITHOUT_RESULT, null, FAILED, throwable);
  }

  @Test
  public void testLifeCycle_AfterClose() {
    subscribe();
    // close
    mDataSource.close();
    verifySubscribers(ON_CANCELLATION);
    verifyState(mDataSource, CLOSED, NOT_FINISHED, WITHOUT_RESULT, null, NOT_FAILED, null);
    // try intermediate
    mDataSource.setResult(mock(Value.class), INTERMEDIATE);
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, CLOSED, NOT_FINISHED, WITHOUT_RESULT, null, NOT_FAILED, null);
    // try last
    mDataSource.setResult(mock(Value.class), LAST);
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, CLOSED, NOT_FINISHED, WITHOUT_RESULT, null, NOT_FAILED, null);
    // try failure
    mDataSource.setFailure(mock(Throwable.class));
    verifySubscribers(NO_INTERACTIONS);
    verifyState(mDataSource, CLOSED, NOT_FINISHED, WITHOUT_RESULT, null, NOT_FAILED, null);
  }

  @Test
  public void testSubscribe_InProgress_WithoutResult() {
    subscribe();
    verifySubscribers(NO_INTERACTIONS);
  }

  @Test
  public void testSubscribe_InProgress_WithResult() {
    mDataSource.setResult(mock(Value.class), INTERMEDIATE);
    subscribe();
    verifySubscribers(ON_NEW_RESULT);
  }

  @Test
  public void testSubscribe_Finished_WithoutResult() {
    mDataSource.setResult(null, LAST);
    subscribe();
    verifySubscribers(ON_NEW_RESULT);
  }

  @Test
  public void testSubscribe_Finished_WithResult() {
    mDataSource.setResult(mock(Value.class), LAST);
    subscribe();
    verifySubscribers(ON_NEW_RESULT);
  }

  @Test
  public void testSubscribe_Failed_WithoutResult() {
    mDataSource.setFailure(mock(Throwable.class));
    subscribe();
    verifySubscribers(ON_FAILURE);
  }

  @Test
  public void testSubscribe_Failed_WithResult() {
    mDataSource.setResult(mock(Value.class), INTERMEDIATE);
    mDataSource.setFailure(mock(Throwable.class));
    subscribe();
    verifySubscribers(ON_FAILURE);
  }

  @Test
  public void testSubscribe_Closed_AfterSuccess() {
    mDataSource.setResult(mock(Value.class), LAST);
    mDataSource.close();
    subscribe();
    verifySubscribers(NO_INTERACTIONS);
  }

  @Test
  public void testSubscribe_Closed_AfterFailure() {
    mDataSource.setFailure(mock(Throwable.class));
    mDataSource.close();
    subscribe();
    verifySubscribers(NO_INTERACTIONS);
  }

  @Test
  public void testInlineExecutor_ResultAndCompletedSubscriptionAvoidExecute() {
    InlineTestExecutor executor = new InlineTestExecutor(true);
    mDataSource.subscribe(mDataSubscriber1, executor);
    mDataSource.setResult(mock(Value.class), LAST);
    mDataSource.subscribe(mDataSubscriber2, executor);

    verify(mDataSubscriber1).onNewResult(mDataSource);
    verify(mDataSubscriber2).onNewResult(mDataSource);
    assertThat(executor.commands).isEmpty();
  }

  @Test
  public void testInlineExecutor_FailureAvoidsExecute() {
    InlineTestExecutor executor = new InlineTestExecutor(true);
    mDataSource.subscribe(mDataSubscriber1, executor);
    mDataSource.setFailure(new IllegalStateException("failure"));

    verify(mDataSubscriber1).onFailure(mDataSource);
    assertThat(executor.commands).isEmpty();
  }

  @Test
  public void testInlineExecutor_CancellationAvoidsExecute() {
    InlineTestExecutor executor = new InlineTestExecutor(true);
    mDataSource.subscribe(mDataSubscriber1, executor);
    mDataSource.close();

    verify(mDataSubscriber1).onCancellation(mDataSource);
    assertThat(executor.commands).isEmpty();
  }

  @Test
  public void testInlineExecutor_ProgressAvoidsExecute() {
    InlineTestExecutor executor = new InlineTestExecutor(true);
    mDataSource.subscribe(mDataSubscriber1, executor);
    mDataSource.setProgress(0.5f);

    verify(mDataSubscriber1).onProgressUpdate(mDataSource);
    assertThat(executor.commands).isEmpty();
  }

  @Test
  public void testInlineExecutor_DisallowedInlineDeliveryKeepsDispatch() {
    InlineTestExecutor executor = new InlineTestExecutor(false);
    mDataSource.subscribe(mDataSubscriber1, executor);
    mDataSource.setResult(mock(Value.class), INTERMEDIATE);

    verifyNoMoreInteractions(mDataSubscriber1);
    assertThat(executor.commands).hasSize(1);
    executor.commands.get(0).run();
    verify(mDataSubscriber1).onNewResult(mDataSource);
  }

  @Test
  public void testInlineExecutor_InstrumenterKeepsDecoratedDispatch() {
    List<String> tags = new ArrayList<>();
    List<String> events = new ArrayList<>();
    AbstractDataSource.provideInstrumenter(
        (runnable, tag) -> {
          tags.add(tag);
          return () -> {
            events.add("decorated");
            runnable.run();
          };
        });
    try {
      InlineTestExecutor executor = new InlineTestExecutor(true);
      mDataSource.subscribe(mDataSubscriber1, executor);
      mDataSource.setResult(mock(Value.class), LAST);

      assertThat(executor.commands).hasSize(1);
      assertThat(tags).containsExactly("AbstractDataSource_notifyDataSubscriber");
      assertThat(events).containsExactly("decorated");
      verify(mDataSubscriber1).onNewResult(mDataSource);
    } finally {
      AbstractDataSource.provideInstrumenter(null);
    }
  }

  @Test
  public void testProgressPreferenceSkipsDispatchButStoresProgress() {
    ProgressDataSubscriber<Value> subscriber = mock(ProgressDataSubscriber.class);
    when(subscriber.shouldNotifyProgress()).thenReturn(false, true);
    InlineTestExecutor executor = new InlineTestExecutor(false);
    mDataSource.subscribe(subscriber, executor);

    mDataSource.setProgress(0.5f);
    assertThat(mDataSource.getProgress()).isEqualTo(0.5f);
    assertThat(executor.commands).isEmpty();
    verify(subscriber, never()).onProgressUpdate(any());

    mDataSource.setProgress(0.75f);
    assertThat(executor.commands).hasSize(1);
    executor.commands.get(0).run();
    verify(subscriber).onProgressUpdate(mDataSource);
  }

  @Test
  public void testProgressPreferenceDoesNotSuppressResultOrFailure() {
    ProgressDataSubscriber<Value> subscriber = mock(ProgressDataSubscriber.class);
    when(subscriber.shouldNotifyProgress()).thenReturn(false);
    InlineTestExecutor executor = new InlineTestExecutor(true);
    mDataSource.subscribe(subscriber, executor);

    mDataSource.setResult(mock(Value.class), INTERMEDIATE);
    mDataSource.setFailure(new IllegalStateException("failure"));

    verify(subscriber).onNewResult(mDataSource);
    verify(subscriber).onFailure(mDataSource);
    assertThat(executor.commands).isEmpty();
  }

  @Test
  public void testInlineCallbackCanCloseReentrantlyOutsideMonitor() {
    InlineTestExecutor executor = new InlineTestExecutor(true);
    mDataSource.subscribe(mDataSubscriber1, executor);
    doAnswer(
            invocation -> {
              assertThat(Thread.holdsLock(mDataSource)).isFalse();
              mDataSource.close();
              return null;
            })
        .when(mDataSubscriber1)
        .onNewResult(mDataSource);

    mDataSource.setResult(mock(Value.class), INTERMEDIATE);

    org.mockito.InOrder order = inOrder(mDataSubscriber1);
    order.verify(mDataSubscriber1).onNewResult(mDataSource);
    order.verify(mDataSubscriber1).onCancellation(mDataSource);
    assertThat(executor.commands).isEmpty();
    assertThat(mDataSource.isClosed()).isTrue();
  }

  @Test
  public void testInlineCallbackExceptionPropagatesWithoutResubmission() {
    InlineTestExecutor executor = new InlineTestExecutor(true);
    IllegalStateException failure = new IllegalStateException("callback failure");
    mDataSource.subscribe(mDataSubscriber1, executor);
    doThrow(failure).when(mDataSubscriber1).onNewResult(mDataSource);

    try {
      mDataSource.setResult(mock(Value.class), LAST);
      org.junit.Assert.fail("Expected callback failure");
    } catch (IllegalStateException caught) {
      assertThat(caught).isSameAs(failure);
    }
    assertThat(executor.commands).isEmpty();
  }

  private static class InlineTestExecutor implements InlineDataSourceExecutor {
    private final boolean inline;
    final List<Runnable> commands = new ArrayList<>();

    InlineTestExecutor(boolean inline) {
      this.inline = inline;
    }

    @Override
    public boolean canRunInline() {
      return inline;
    }

    @Override
    public void execute(Runnable command) {
      commands.add(command);
      if (inline) {
        command.run();
      }
    }
  }

  @Test
  public void testCloseResult() {
    Value value1 = mock(Value.class);
    mDataSource.setResult(value1, false);

    Value value2 = mock(Value.class);
    mDataSource.setResult(value2, false);
    verify(value1).close();
    verify(value2, never()).close();

    Value value3 = mock(Value.class);
    mDataSource.setResult(value3, false);
    verify(value2).close();
    verify(value3, never()).close();

    mDataSource.close();
    verify(value3).close();
  }
}
