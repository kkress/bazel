// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An attribute value consisting of a concatenation of native types and selects, e.g:
 *
 * <pre>
 *   rule(
 *       name = 'myrule',
 *       deps =
 *           [':defaultdep']
 *           + select({
 *               'a': [':adep'],
 *               'b': [':bdep'],})
 *           + select({
 *               'c': [':cdep'],
 *               'd': [':ddep'],})
 *   )
 * </pre>
 */
@SkylarkModule(
    name = "select",
    doc = "A selector between configuration-dependent entities.",
    documented = false)
@AutoCodec
public final class SelectorList implements StarlarkValue, HasBinary {

  // TODO(adonovan): move to lib.packages.
  // TODO(adonovan): combine Selector{List,Value}. There's no need for two data types.

  private final Class<?> type;
  private final List<Object> elements;

  @AutoCodec.VisibleForSerialization
  SelectorList(Class<?> type, List<Object> elements) {
    this.type = type;
    this.elements = elements;
  }

  /**
   * Returns an ordered list of the elements in this expression. Each element may be a
   * native type or a select.
   */
  public List<Object> getElements() {
    return elements;
  }

  /**
   * Returns the native type contained by this expression.
   */
  public Class<?> getType() {
    return type;
  }

  /**
   * Creates a "wrapper" list that consists of a single select.
   */
  public static SelectorList of(SelectorValue selector) {
    return new SelectorList(selector.getType(), ImmutableList.of(selector));
  }

  /**
   * Creates a list that concatenates two values, where each value may be a native type, a select
   * over that type, or a selector list over that type.
   *
   * @throws EvalException if the values don't have the same underlying type
   */
  public static SelectorList concat(Object x, Object y) throws EvalException {
    return of(Arrays.asList(x, y));
  }

  @Override
  public SelectorList binaryOp(TokenKind op, Object that, boolean thisLeft) throws EvalException {
    if (op == TokenKind.PLUS) {
      return thisLeft ? concat(this, that) : concat(that, this);
    }
    return null;
  }

  /**
   * Creates a list from the given sequence of values, which must be non-empty. Each value may be a
   * native type, a select over that type, or a selector list over that type.
   *
   * @throws EvalException if all values don't have the same underlying type
   */
  public static SelectorList of(Iterable<?> values) throws EvalException {
    Preconditions.checkArgument(!Iterables.isEmpty(values));
    ImmutableList.Builder<Object> elements = ImmutableList.builder();
    Object firstValue = null;

    for (Object value : values) {
      if (value instanceof SelectorList) {
        elements.addAll(((SelectorList) value).getElements());
      } else {
        elements.add(value);
      }
      if (firstValue == null) {
        firstValue = value;
      }
      if (!canConcatenate(getNativeType(firstValue), getNativeType(value))) {
        throw Starlark.errorf(
            "'+' operator applied to incompatible types (%s, %s)",
            getTypeName(firstValue), getTypeName(value));
      }
    }

    return new SelectorList(getNativeType(firstValue), elements.build());
  }

  private static final Class<?> NATIVE_LIST_TYPE = List.class;

  private static String getTypeName(Object x) {
    if (x instanceof SelectorList) {
      return "select of " + EvalUtils.getDataTypeNameFromClass(((SelectorList) x).getType());
    } else if (x instanceof SelectorValue) {
      return "select of " + EvalUtils.getDataTypeNameFromClass(((SelectorValue) x).getType());
    } else {
      return Starlark.type(x);
    }
  }

  private static Class<?> getNativeType(Object value) {
    if (value instanceof SelectorList) {
      return ((SelectorList) value).getType();
    } else if (value instanceof SelectorValue) {
      return ((SelectorValue) value).getType();
    } else {
      return value.getClass();
    }
  }

  private static boolean isListType(Class<?> type) {
    return NATIVE_LIST_TYPE.isAssignableFrom(type);
  }

  private static boolean canConcatenate(Class<?> type1, Class<?> type2) {
    if (type1 == type2) {
      return true;
    } else if (isListType(type1) && isListType(type2)) {
      return true;
    } else {
      return false;
    }
  }

  @Override
  public String toString() {
    return Starlark.repr(this);
  }

  @Override
  public void repr(Printer printer) {
    printer.printList(elements, "", " + ", "", null);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, elements);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SelectorList)) {
      return false;
    }
    SelectorList that = (SelectorList) other;
    return Objects.equals(this.type, that.type) && Objects.equals(this.elements, that.elements);
  }
}