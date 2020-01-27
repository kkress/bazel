// Copyright 2010 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.testutil.Suite;
import com.google.devtools.build.lib.testutil.TestSpec;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link LabelExpander}. */
@TestSpec(size = Suite.SMALL_TESTS)
@RunWith(JUnit4.class)
public class LabelExpanderTest extends BuildViewTestCase {
  /**
   * A dummy target that resolves labels and receives errors.
   */
  private ConfiguredTarget dummyTarget;

  /**
   * Artifacts generated by {@code dummyTarget} identified by their
   * root-relative paths; to be used for mock label-to-artifact mappings.
   */
  private Map<String, Artifact> artifactsByName;

  /**
   * All characters that the heuristic considers to be part of a target.
   * This is a subset of the allowed label characters. The ones left out may
   * have a special meaning during expression expansion:
   *
   * <ul>
   *   <li>comma (",") - may separate labels
   *   <li>equals sign ("=") - may separate labels
   *   <li>colon (":") - can only appear in labels, not in target names
   * </ul>
   */
  private static final String allowedChars = "_/.-+" + PathFragment.SEPARATOR_CHAR
      + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

  // Helper methods -----------------------------------------------------------

  private void setupDummy() throws Exception {
    dummyTarget = scratchConfiguredTarget(
        "foo", "foo",
        "filegroup(name = 'foo',",
        "          srcs = ['x1','x2','bar/x3', '" + allowedChars + "', 'xx11', 'x11', 'xx1'])");
    collectArtifacts();
  }

  /**
   * Collects all generated mock artifacts for {@code dummyTarget} and assigns
   * the result to {@code artifactsByName}.
   */
  private void collectArtifacts() {
    ImmutableMap.Builder<String, Artifact> builder = ImmutableMap.builder();
    for (Artifact artifact : getFilesToBuild(dummyTarget).toList()) {
      builder.put(artifact.getRootRelativePath().toString(), artifact);
    }
    artifactsByName = builder.build();
  }

  /**
   * Gets a generated artifact object for a target in package "foo" from {@code
   * artifactsByName}.
   */
  private Artifact artifactFor(String targetName) {
    return artifactsByName.get("foo/" + targetName);
  }

  /**
   * Creates fake label in package "foo".
   */
  private static Label labelFor(String targetName) throws LabelSyntaxException {
    return Label.create("@//foo", targetName);
  }

  /**
   * Asserts that an expansion with a given mapping produces the expected
   * results.
   */
  private void assertExpansion(String expectedResult, String expressionToExpand,
      Map<Label, Iterable<Artifact>> mapping) throws Exception {
    assertThat(LabelExpander.expand(expressionToExpand, mapping, dummyTarget.getLabel()))
        .isEqualTo(expectedResult);
  }

  /**
   * Asserts that an expansion with an empty mapping produces the expected
   * results.
   */
  private void assertExpansion(String expected, String original) throws Exception {
    assertExpansion(expected, original, ImmutableMap.<Label, Iterable<Artifact>>of());
  }

  // Actual tests -------------------------------------------------------------

  /**
   * Tests that if no mapping is specified, then strings expand to themselves.
   */
  @Test
  public void testStringExpandsToItselfWhenNoMappingSpecified() throws Exception {
    setupDummy();
    assertExpansion("", null);
    assertExpansion("cmd", "cmd");
    assertExpansion("//x:y,:z,w", "//x:y,:z,w");
    assertExpansion(allowedChars, allowedChars);
  }

  /**
   * Tests that in case of a one-to-one label-to-artifact mapping the expansion
   * produces the expected results.
   */
  @Test
  public void testExpansion() throws Exception {
    setupDummy();
    assertExpansion("foo/x1", "x1", ImmutableMap.<Label, Iterable<Artifact>>of(
        labelFor("x1"), ImmutableList.of(artifactFor("x1"))));

    assertExpansion("foo/x1", ":x1", ImmutableMap.<Label, Iterable<Artifact>>of(
        labelFor("x1"), ImmutableList.of(artifactFor("x1"))));

    assertExpansion("foo/x1", "//foo:x1", ImmutableMap.<Label, Iterable<Artifact>>of(
        labelFor("x1"), ImmutableList.of(artifactFor("x1"))));
  }

  /**
   * Tests that label extraction works as expected - disallowed label characters
   * are resolved to themselves.
   */
  @Test
  public void testLabelExtraction() throws Exception {
    setupDummy();
    assertExpansion("(foo/" + allowedChars + ")", "(//foo:" + allowedChars + ")",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor(allowedChars), ImmutableList.of(artifactFor(allowedChars))));

    assertExpansion("foo/x1,foo/x2=foo/bar/x3", "x1,x2=bar/x3",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1")),
            labelFor("x2"), ImmutableList.of(artifactFor("x2")),
            labelFor("bar/x3"), ImmutableList.of(artifactFor("bar/x3"))));
  }

  /**
   * Tests that an exception is thrown when the mapping is not one-to-one.
   */
  @Test
  public void testThrowsWhenMappingIsNotOneToOne() throws Exception {
    setupDummy();
    assertThrows(
        LabelExpander.NotUniqueExpansionException.class,
        () ->
            LabelExpander.expand(
                "x1",
                ImmutableMap.<Label, Iterable<Artifact>>of(
                    labelFor("x1"), ImmutableList.<Artifact>of()),
                dummyTarget.getLabel()));

    assertThrows(
        LabelExpander.NotUniqueExpansionException.class,
        () ->
            LabelExpander.expand(
                "x1",
                ImmutableMap.<Label, Iterable<Artifact>>of(
                    labelFor("x1"), ImmutableList.of(artifactFor("x1"), artifactFor("x2"))),
                dummyTarget.getLabel()));
  }

  /**
   * Tests expanding labels that result in a SyntaxException.
   */
  @Test
  public void testIllFormedLabels() throws Exception {
    setupDummy();
    assertExpansion("x1:x2:x3", "x1:x2:x3",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1")),
            labelFor("x2"), ImmutableList.of(artifactFor("x2")),
            labelFor("bar/x3"), ImmutableList.of(artifactFor("bar/x3"))));

    assertExpansion("foo://x1 x1/../x2", "foo://x1 x1/../x2",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1")),
            labelFor("x2"), ImmutableList.of(artifactFor("x2")),
            labelFor("bar/x3"), ImmutableList.of(artifactFor("bar/x3"))));

    assertExpansion("//foo:/x1", "//foo:/x1",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1"))));

    assertExpansion("//foo:../x1", "//foo:../x1",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1"))));

    assertExpansion("//foo:x1/../x2", "//foo:x1/../x2",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1")),
            labelFor("x2"), ImmutableList.of(artifactFor("x2"))));

    assertExpansion("//foo:x1/./x2", "//foo:x1/./x2",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1")),
            labelFor("x2"), ImmutableList.of(artifactFor("x2"))));

    assertExpansion("//foo:x1//x2", "//foo:x1//x2",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1")),
            labelFor("x2"), ImmutableList.of(artifactFor("x2"))));

    assertExpansion("//foo:x1/..", "//foo:x1/..",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1"))));

    assertExpansion("//foo:x1/", "//foo:x1/",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1"))));

    assertExpansion(":", ":");
  }

  /**
   * Tests that label parsing is greedy (always extracting the longest
   * possible label). This means that if a label is a substring of another
   * label, it should not be expanded but be treated as part of the longer one.
   */
  @Test
  public void testLabelIsSubstringOfValidLabel() throws Exception {
    setupDummy();
    assertExpansion("x3=foo/bar/x3", "x3=bar/x3",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("bar/x3"), ImmutableList.of(artifactFor("bar/x3"))));

    assertExpansion("foo/x1,foo/x11,foo/xx1,foo/xx11", "x1,x11,xx1,xx11",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1")),
            labelFor("x11"), ImmutableList.of(artifactFor("x11")),
            labelFor("xx1"), ImmutableList.of(artifactFor("xx1")),
            labelFor("xx11"), ImmutableList.of(artifactFor("xx11"))));

    assertExpansion("//x1", "//x1",
        ImmutableMap.<Label, Iterable<Artifact>>of(
            labelFor("x1"), ImmutableList.of(artifactFor("x1"))));
  }
}
