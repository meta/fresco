/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.common.callercontext

import com.google.common.collect.ImmutableMap
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ContextChainTest {

  @Test
  fun testGetStringExtra() {
    val contextChain = ContextChain(
        "grandchild_tag",
        "grandchild_name",
        ImmutableMap.of("keyA", "valueA"),
        ContextChain(
            "child_tag",
            "child_name",
            ImmutableMap.of("keyB", "valueB"),
            ContextChain("root_tag", "root_name", ImmutableMap.of("keyC", "valueC"), null),
        ),
    )
    assertThat(contextChain.getStringExtra("keyA")).isEqualTo("valueA")
    assertThat(contextChain.getStringExtra("keyB")).isEqualTo("valueB")
    assertThat(contextChain.getStringExtra("keyC")).isEqualTo("valueC")
    assertThat(contextChain.getStringExtra("unknownKey")).isNull()
  }

  @Test
  fun testPutStringExtra() {
    val contextChain = ContextChain(
        "grandchild_tag",
        "grandchild_name",
        null,
        ContextChain(
            "child_tag",
            "child_name",
            null,
            ContextChain("root_tag", "root_name", null, null),
        ),
    )
    assertThat(contextChain.getStringExtra("keyA")).isNull()
    contextChain.putObjectExtra("keyA", "valueA")
    assertThat(contextChain.getStringExtra("keyA")).isEqualTo("valueA")
    contextChain.putObjectExtra("keyA", "valueAA")
    assertThat(contextChain.getStringExtra("keyA")).isEqualTo("valueAA")
    assertThat(contextChain.getStringExtra("unknownKey")).isNull()
  }

  @Test
  fun testSerialize() {
    val contextChain = ContextChain(
        "grandchild_tag",
        "grandchild_name",
        null,
        ContextChain(
            "child_tag",
            "child_name",
            null,
            ContextChain("root_tag", "root_name", null, null),
        ),
    )
    assertThat(contextChain.toString())
        .isEqualTo("root_tag:root_name/child_tag:child_name/grandchild_tag:grandchild_name")
  }

  @Test
  fun testSerializeToStringList() {
    val contextChain = ContextChain(
        "grandchild_tag",
        "grandchild_name",
        null,
        ContextChain(
            "child_tag",
            "child_name",
            null,
            ContextChain("root_tag", "root_name", null, null),
        ),
    )
    val res = contextChain.toStringArray()
    assertThat(res.size).isEqualTo(3)
    assertThat(res[0]).isEqualTo("root_tag:root_name")
    assertThat(res[1]).isEqualTo("child_tag:child_name")
    assertThat(res[2]).isEqualTo("grandchild_tag:grandchild_name")
  }

  @Test
  fun testSerializeToCachedStringList() {
    val contextChain = ContextChain(
        "child_tag",
        "child_name",
        ContextChain("root_tag", "root_name", null),
    )

    val first = contextChain.toStringArrayCached()
    val second = contextChain.toStringArrayCached()

    assertThat(first).containsExactly("root_tag:root_name", "child_tag:child_name")
    assertThat(second).isSameAs(first)
  }

  @Test
  fun testSerializedContextChainToArray() {
    val contextChain =
        ContextChain("root_tag:root_name/child_tag:child_name/grandchild_tag:grandchild_name", null)
    val res = contextChain.toStringArray()
    assertThat(res.size).isEqualTo(3)
    assertThat(res[0]).isEqualTo("root_tag:root_name")
    assertThat(res[1]).isEqualTo("child_tag:child_name")
    assertThat(res[2]).isEqualTo("grandchild_tag:grandchild_name")
  }

  @Test
  fun testSerializedContextChainToString() {
    val contextChain =
        ContextChain("root_tag:root_name/child_tag:child_name/grandchild_tag:grandchild_name", null)
    assertThat(contextChain.toString())
        .isEqualTo("root_tag:root_name/child_tag:child_name/grandchild_tag:grandchild_name")
  }

  @Test
  fun testCombiningChains() {
    val nativeChain =
        ContextChain("native_tag:native_tag_name/native_child_tag:native_child_tag_name", null)
    val remoteChain = ContextChain(
        "root_tag:root_name/child_tag:child_name/grandchild_tag:grandchild_name",
        nativeChain,
    )
    val res = remoteChain.toStringArray()
    assertThat(res.size).isEqualTo(5)
    assertThat(res[0]).isEqualTo("native_tag:native_tag_name")
    assertThat(res[1]).isEqualTo("native_child_tag:native_child_tag_name")
    assertThat(res[2]).isEqualTo("root_tag:root_name")
    assertThat(res[3]).isEqualTo("child_tag:child_name")
    assertThat(res[4]).isEqualTo("grandchild_tag:grandchild_name")
  }
}
