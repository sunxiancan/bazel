// Copyright 2014 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ParameterFile;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.packages.Type;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.rules.cpp.CppConfiguration.DynamicMode;
import com.google.devtools.build.lib.rules.cpp.CppLinkAction.Context;
import com.google.devtools.build.lib.rules.cpp.Link.LinkStaticness;
import com.google.devtools.build.lib.rules.cpp.Link.LinkTargetType;
import com.google.devtools.build.lib.rules.cpp.LinkerInputs.LibraryToLink;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.util.Pair;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.AnalysisEnvironment;
import com.google.devtools.build.lib.view.ConfiguredTarget;
import com.google.devtools.build.lib.view.FileProvider;
import com.google.devtools.build.lib.view.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.view.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.view.RuleContext;
import com.google.devtools.build.lib.view.Runfiles;
import com.google.devtools.build.lib.view.RunfilesProvider;
import com.google.devtools.build.lib.view.RunfilesSupport;
import com.google.devtools.build.lib.view.TransitiveInfoCollection;
import com.google.devtools.build.lib.view.Util;
import com.google.devtools.build.lib.view.actions.FileWriteAction;
import com.google.devtools.build.lib.view.actions.SpawnAction;
import com.google.devtools.build.lib.view.config.BuildConfiguration;
import com.google.devtools.build.lib.view.test.BaselineCoverageAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * A ConfiguredTarget for <code>cc_binary</code> rules.
 */
public abstract class CcBinary implements RuleConfiguredTargetFactory {

  private final CppSemantics semantics;

  protected CcBinary(CppSemantics semantics) {
    this.semantics = semantics;
  }

  // TODO(bazel-team): should this use Link.SHARED_LIBRARY_FILETYPES?
  private static final FileTypeSet SHARED_LIBRARY_FILETYPES = FileTypeSet.of(
      CppFileTypes.SHARED_LIBRARY,
      CppFileTypes.VERSIONED_SHARED_LIBRARY);

  /**
   * The maximum number of inputs for any single .dwp generating action. For cases where
   * this value is exceeded, the action is split up into "batches" that fall under the limit.
   * See {@link #createDebugPackagerActions} for details.
   */
  @VisibleForTesting
  public static final int MAX_INPUTS_PER_DWP_ACTION = 100;

  /**
   * Intermediate dwps are written to this subdirectory under the main dwp's output path.
   */
  @VisibleForTesting
  public static final String INTERMEDIATE_DWP_DIR = "_dwps";

  static Runfiles collectRunfiles(RuleContext context,
      CcCommon common, BuildConfiguration configuration,
      CcLinkingOutputs linkingOutputs,
      NestedSet<Artifact> filesToBuild,
      Iterable<Artifact> fakeLinkerInputs,
      boolean fake) {
    Runfiles.Builder builder = new Runfiles.Builder();
    CppConfiguration cppConfiguration = configuration.getFragment(CppConfiguration.class);
    Function<TransitiveInfoCollection, Runfiles> runfilesMapping =
        CppRunfilesProvider.runfilesFunction(
            getLinkStaticness(context, common, cppConfiguration) != LinkStaticness.DYNAMIC);
    boolean linkshared = isLinkShared(context);
    builder.addTransitiveArtifacts(filesToBuild);
    // Add the shared libraries to the runfiles. This adds any shared libraries that are in the
    // srcs of this target.
    builder.addArtifacts(linkingOutputs.getLibrariesForRunfiles(true));
    builder.addRunfiles(context, RunfilesProvider.DEFAULT_RUNFILES);
    builder.add(context, runfilesMapping);
    // Add the C++ runtime libraries if linking them dynamically.
    if (getLinkStaticness(context, common, cppConfiguration) == LinkStaticness.DYNAMIC) {
      builder.addTransitiveArtifacts(CppHelper.getDynamicRuntimeInputsForLink(
          context, configuration));
    }
    // For cc_binary and cc_test rules, there is an implicit dependency on
    // the malloc library package, which is specified by the "malloc" attribute.
    // As the BUILD encyclopedia says, the "malloc" attribute should be ignored
    // if linkshared=1.
    if (!linkshared) {
      TransitiveInfoCollection malloc = CppHelper.mallocForTarget(context);
      builder.addTarget(malloc, RunfilesProvider.DEFAULT_RUNFILES);
      builder.addTarget(malloc, runfilesMapping);
    }

    if (fake) {
      // Add the object files, libraries, and linker scripts that are used to
      // link this executable.
      builder.addSymlinksToArtifacts(Iterables.filter(fakeLinkerInputs, Artifact.MIDDLEMAN_FILTER));
      // The crosstool inputs for the link action are not sufficient; we also need the crosstool
      // inputs for compilation. Node that these cannot be middlemen because Runfiles does not
      // know how to expand them.
      builder.addTransitiveArtifacts(CppHelper.getCompiler(context).getCrosstool());
      TransitiveInfoCollection libcLink = context.getPrerequisite(":libc_link", Mode.HOST);
      if (libcLink != null) {
        builder.addTransitiveArtifacts(libcLink.getProvider(FileProvider.class).getFilesToBuild());
      }
      // Add the sources files that are used to compile the object files.
      // We add the headers in the transitive closure and our own sources in the srcs
      // attribute. We do not provide the auxiliary inputs, because they are only used when we
      // do FDO compilation, and cc_fake_binary does not support FDO.
      builder.addSymlinksToArtifacts(
          Iterables.transform(common.getCAndCppSources(), Pair.<Artifact, Label>firstFunction()));
      builder.addSymlinksToArtifacts(common.getCppCompilationContext().getDeclaredIncludeSrcs());
    }
    return builder.build();
  }

  @Override
  public ConfiguredTarget create(RuleContext context) {
    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(context);
    CcBinary.init(semantics, context, builder, /*fake =*/ false, /*useExecOrigin =*/ false);
    return builder.build();
  }

  public static void init(CppSemantics semantics, RuleContext context,
      RuleConfiguredTargetBuilder targetBuilder, boolean fake, boolean useExecOrigin) {
    CcCommon common = new CcCommon(context, semantics, /*initExtraPrerequisites =*/ false);
    CppConfiguration cppConfiguration = context.getFragment(CppConfiguration.class);

    NavigableMap<PathFragment, Label> pathsToTargets;
    if (cppConfiguration.isLipoContextCollector()) {
      pathsToTargets = initializeLipoContext(context, common);
    } else {
      pathsToTargets = Maps.newTreeMap();
    }
    common.createModuleMapAction();

    // if cc_binary includes "linkshared=1", then gcc will be invoked with
    // linkopt "-shared", which causes the result of linking to be a shared
    // library. In this case, the name of the executable target should end
    // in ".so".
    PathFragment executableName = Util.getWorkspaceRelativePath(context.getTarget());
    CppLinkAction.Builder linkActionBuilder =
        common.newLinkActionBuilder(executableName, useExecOrigin);
    CcCompilationOutputs ccCompilationOutputs = determineLinkerArguments(
        context, common, cppConfiguration, fake, linkActionBuilder);
    linkActionBuilder.addNonLibraryInputs(ccCompilationOutputs.getHeaderTokenFiles());

    LinkTargetType type =
        isLinkShared(context) ? LinkTargetType.DYNAMIC_LIBRARY : LinkTargetType.EXECUTABLE;

    final NestedSet<Artifact> runtimeInputs;
    final Artifact runtimeMiddleman;

    if (getLinkStaticness(context, common, cppConfiguration) == LinkStaticness.DYNAMIC) {
      runtimeMiddleman = CppHelper.getDynamicRuntimeInputMiddlemanForLink(
          context, context.getConfiguration());
      runtimeInputs = CppHelper.getDynamicRuntimeInputsForLink(context, context.getConfiguration());
    } else {
      runtimeMiddleman = CppHelper.getStaticRuntimeInputMiddlemanForLink(
          context, context.getConfiguration());
      runtimeInputs = CppHelper.getStaticRuntimeInputsForLink(context, context.getConfiguration());
      // Only force a static link of libgcc if static runtime linking is enabled (which
      // can't be true if runtimeInputs is empty).
      if (!runtimeInputs.isEmpty()) {
        linkActionBuilder.addLinkopt("-static-libgcc");
      }
    }

    context.checkSrcsSamePackage(true);

    linkActionBuilder.setLinkType(type);
    linkActionBuilder.setLinkStaticness(getLinkStaticness(context, common, cppConfiguration));
    linkActionBuilder.setRuntimeInputs(runtimeMiddleman, runtimeInputs);

    if (fake) {
      linkActionBuilder.setFake(true);
    }
    // store immutable context now, recreate builder later
    Context linkContext = new Context(linkActionBuilder);

    CppLinkAction linkAction = linkActionBuilder.build();
    context.getAnalysisEnvironment().registerAction(linkAction);
    LibraryToLink outputLibrary = linkAction.getOutputLibrary();
    Iterable<Artifact> fakeLinkerInputs =
        fake ? linkAction.getInputs() : ImmutableList.<Artifact>of();
    Artifact executable = outputLibrary.getArtifact();
    CcLinkingOutputs.Builder linkingOutputsBuilder = new CcLinkingOutputs.Builder();
    if (isLinkShared(context)) {
      if (CppFileTypes.SHARED_LIBRARY.matches(executableName)) {
        linkingOutputsBuilder.addDynamicLibrary(outputLibrary);
        linkingOutputsBuilder.addExecutionDynamicLibrary(outputLibrary);
      } else {
        context.attributeError("linkshared", "'linkshared' used in non-shared library");
      }
    }
    // Also add all shared libraries from srcs.
    for (Artifact library : common.getSharedLibrariesFromSrcs()) {
      LibraryToLink symlink = common.getDynamicLibrarySymlink(library, true);
      linkingOutputsBuilder.addDynamicLibrary(symlink);
      linkingOutputsBuilder.addExecutionDynamicLibrary(symlink);
    }
    CcLinkingOutputs linkingOutputs = linkingOutputsBuilder.build();
    NestedSet<Artifact> filesToBuild = NestedSetBuilder.create(Order.STABLE_ORDER, executable);

    // Create the stripped binary, but don't add it to filesToBuild; it's only built when requested.
    Artifact output = context.getImplicitOutputArtifact(CppRuleClasses.CC_BINARY_STRIPPED);
    createStripAction(context, cppConfiguration, executable, output);

    DwoArtifactsCollector dwoArtifacts =
        collectTransitiveDwoArtifacts(context, common, cppConfiguration, ccCompilationOutputs);
    Artifact dwpFile = context.getImplicitOutputArtifact(CppRuleClasses.CC_BINARY_DEBUG_PACKAGE);
    createDebugPackagerActions(context, cppConfiguration, dwpFile, dwoArtifacts);

    // TODO(bazel-team): Do we need to put original shared libraries (along with
    // mangled symlinks) into the RunfilesSupport object? It does not seem
    // logical since all symlinked libraries will be linked anyway and would
    // not require manual loading but if we do, then we would need to collect
    // their names and use a different constructor below.
    Runfiles runfiles = collectRunfiles(context, common, context.getConfiguration(),
        linkingOutputs, filesToBuild, fakeLinkerInputs, fake);
    RunfilesSupport runfilesSupport = RunfilesSupport.withExecutable(
        context, runfiles, executable, context.getConfiguration().buildRunfiles());

    common.addTransitiveInfoProviders(
        targetBuilder, filesToBuild, ccCompilationOutputs, linkingOutputs, dwoArtifacts);

    targetBuilder
        .add(RunfilesProvider.class, RunfilesProvider.simple(runfiles))
        .setRunfilesSupport(runfilesSupport, executable)
        .setBaselineCoverageArtifacts(createBaselineCoverageArtifacts(
            context, common, ccCompilationOutputs, fake))
        .addProvider(LipoContextProvider.class, new LipoContextProvider(
            common.getCppCompilationContext(), pathsToTargets))
        .addProvider(CppLinkAction.Context.class, linkContext);
  }

  /*
   * This function collects data for the LipoContextProvider interface. It's only invoked if the
   * cc_binary is the same as the "--lipo_context". Overridden by cc_tests, because they don't
   * need to provide this information, although they extend CcBinaryConfiguredTarget.
   */
  static NavigableMap<PathFragment, Label> initializeLipoContext(
      RuleContext context, CcCommon common) {
    // GCDA path to label mapping. Only one instance is created, and that's owned
    // by the lipo_context cc_binary.
    NavigableMap<PathFragment, Label> pathsToTargets = Maps.newTreeMap();
    PathFragment binPath = context.getConfiguration().getBinFragment();
    for (Label label : common.getTransitiveLipoLabels()) {
      pathsToTargets.put(binPath.getRelative(CppHelper.getObjDirectory(label)), label);
    }
    return pathsToTargets;
  }

  /**
   * Creates an action to strip an executable.
   */
  static void createStripAction(RuleContext context,
      CppConfiguration cppConfiguration, Artifact input, Artifact output) {
    new SpawnAction.Builder(context)
        .addInput(input)
        .addTransitiveInputs(CppHelper.getCompiler(context).getStrip())
        .addOutput(output)
        .useDefaultShellEnvironment()
        .setExecutable(cppConfiguration.getStripExecutable())
        .addArguments("-S", "-p", "-o", output.getExecPathString())
        .addArguments("-R", ".gnu.switches.text.quote_paths")
        .addArguments("-R", ".gnu.switches.text.bracket_paths")
        .addArguments("-R", ".gnu.switches.text.system_paths")
        .addArguments("-R", ".gnu.switches.text.cpp_defines")
        .addArguments("-R", ".gnu.switches.text.cpp_includes")
        .addArguments("-R", ".gnu.switches.text.cl_args")
        .addArguments("-R", ".gnu.switches.text.lipo_info")
        .addArguments("-R", ".gnu.switches.text.annotation")
        .addArguments(cppConfiguration.getStripOpts())
        .addArgument(input.getExecPathString())
        .setProgressMessage("Stripping " + output.prettyPrint() + " for " + context.getLabel())
        .setMnemonic("CcStrip")
        .build();
  }

  /**
   * Given 'temps', traverse this target and its dependencies and collect up all
   * the object files, libraries, linker options, linkstamps attributes and linker scripts.
   */
  static CcCompilationOutputs determineLinkerArguments(RuleContext context, CcCommon common,
      CppConfiguration cppConfiguration, boolean fake, CppLinkAction.Builder builder) {
    // Determine the object files to link in.
    final boolean usePic = CppHelper.usePic(context, !isLinkShared(context)) && !fake;
    CcCompilationOutputs compilationOutputs = common.createCompileActions(fake);
    Iterable<Artifact> compiledObjectFiles = compilationOutputs.getObjectFiles(usePic);

    if (fake) {
      builder.addFakeNonLibraryInputs(compiledObjectFiles);
    } else {
      builder.addNonLibraryInputs(compiledObjectFiles);
    }

    builder.addNonLibraryInputs(common.getObjectFilesFromSrcs(usePic));
    builder.addNonLibraryInputs(common.getLinkerScripts());

    // Determine the libraries to link in.
    // First libraries from srcs. Shared library artifacts here are substituted with mangled symlink
    // artifacts generated by getDynamicLibraryLink(). This is done to minimize number of -rpath
    // entries during linking process.
    for (Artifact library : common.getLibrariesFromSrcs()) {
      if (SHARED_LIBRARY_FILETYPES.matches(library.getFilename())) {
        builder.addLibrary(common.getDynamicLibrarySymlink(library, true));
      } else {
        builder.addLibrary(LinkerInputs.opaqueLibraryToLink(library));
      }
    }

    // Then libraries from the closure of deps.
    List<String> linkopts = new ArrayList<>();
    Map<Artifact, ImmutableList<Artifact>> linkstamps = new LinkedHashMap<>();

    NestedSet<LibraryToLink> librariesInDepsClosure =
        findLibrariesToLinkInDepsClosure(context, common, cppConfiguration, linkopts, linkstamps);
    builder.addLinkopts(linkopts);
    CppHelper.addLinkstamps(context, builder, linkstamps);

    builder.addLibraries(librariesInDepsClosure);
    return compilationOutputs;
  }

  /**
   * Explore the transitive closure of our deps to collect linking information.
   */
  private static NestedSet<LibraryToLink> findLibrariesToLinkInDepsClosure(
      RuleContext context,
      CcCommon common,
      CppConfiguration cppConfiguration,
      List<String> linkopts,
      Map<Artifact,
      ImmutableList<Artifact>> linkstamps) {
    // This is true for both FULLY STATIC and MOSTLY STATIC linking.
    boolean linkingStatically =
        getLinkStaticness(context, common, cppConfiguration) != LinkStaticness.DYNAMIC;

    CcLinkParams linkParams = collectCcLinkParams(
        context, common, linkingStatically, isLinkShared(context));
    linkopts.addAll(linkParams.flattenedLinkopts());
    linkstamps.putAll(CppHelper.resolveLinkstamps(context, linkParams));
    return linkParams.getLibraries();
  }

  /**
   * Gets the linkopts to use for this binary. These options are NOT used when
   * linking other binaries that depend on this binary.
   *
   * @return a new List instance that contains the linkopts for this binary
   *         target.
   */
  private static ImmutableList<String> getBinaryLinkopts(RuleContext context,
      CcCommon common) {
    List<String> linkopts = new ArrayList<>();
    if (isLinkShared(context)) {
      linkopts.add("-shared");
    }
    linkopts.addAll(common.getLinkopts());
    return ImmutableList.copyOf(linkopts);
  }

  private static boolean linkstaticAttribute(RuleContext context) {
    return context.attributes().get("linkstatic", Type.BOOLEAN);
  }

  /**
   * Returns "true" if the {@code linkshared} attribute exists and is set.
   */
  static final boolean isLinkShared(RuleContext context) {
    return context.getRule().getRuleClassObject().hasAttr("linkshared", Type.BOOLEAN)
        && context.attributes().get("linkshared", Type.BOOLEAN);
  }

  private static final boolean dashStaticInLinkopts(CcCommon common,
      CppConfiguration cppConfiguration) {
    return common.getLinkopts().contains("-static")
        || cppConfiguration.getLinkOptions().contains("-static");
  }

  static final LinkStaticness getLinkStaticness(RuleContext context,
      CcCommon common, CppConfiguration cppConfiguration) {
    if (cppConfiguration.getDynamicMode() == DynamicMode.FULLY) {
      return LinkStaticness.DYNAMIC;
    } else if (dashStaticInLinkopts(common, cppConfiguration)) {
      return LinkStaticness.FULLY_STATIC;
    } else if (cppConfiguration.getDynamicMode() == DynamicMode.OFF
        || linkstaticAttribute(context)) {
      return LinkStaticness.MOSTLY_STATIC;
    } else {
      return LinkStaticness.DYNAMIC;
    }
  }

  /**
   * Collects .dwo artifacts either transitively or directly, depending on the link type.
   *
   * <p>For a cc_binary, we only include the .dwo files corresponding to the .o files that are
   * passed into the link. For static linking, this includes all transitive dependencies. But
   * for dynamic linking, dependencies are separately linked into their own shared libraries,
   * so we don't need them here.
   */
  static DwoArtifactsCollector collectTransitiveDwoArtifacts(RuleContext context,
      CcCommon common, CppConfiguration cppConfiguration, CcCompilationOutputs compilationOutputs) {
    if (getLinkStaticness(context, common, cppConfiguration) == LinkStaticness.DYNAMIC) {
      return DwoArtifactsCollector.directCollector(compilationOutputs);
    } else {
      return CcCommon.collectTransitiveDwoArtifacts(context, compilationOutputs);
    }
  }

  @VisibleForTesting
  public static Iterable<Artifact> getDwpInputs(
      RuleContext context, NestedSet<Artifact> picDwoArtifacts, NestedSet<Artifact> dwoArtifacts) {
    return CppHelper.usePic(context, !isLinkShared(context))
        ? picDwoArtifacts
        : dwoArtifacts;
  }

  /**
   * Creates the actions needed to generate this target's "debug info package"
   * (i.e. its .dwp file).
   */
  private static void createDebugPackagerActions(RuleContext context,
      CppConfiguration cppConfiguration, Artifact dwpOutput,
      DwoArtifactsCollector dwoArtifactsCollector) {
    Iterable<Artifact> allInputs = getDwpInputs(context,
        dwoArtifactsCollector.getPicDwoArtifacts(),
        dwoArtifactsCollector.getDwoArtifacts());

    // No inputs? Just generate a trivially empty .dwp.
    //
    // Note this condition automatically triggers for any build where fission is disabled.
    // Because rules referencing .dwp targets may be invoked with or without fission, we need
    // to support .dwp generation even when fission is disabled. Since no actual functionality
    // is expected then, an empty file is appropriate.
    if (Iterables.isEmpty(allInputs)) {
      context.getAnalysisEnvironment().registerAction(
          new FileWriteAction(context.getActionOwner(), dwpOutput, "", false));
      return;
    }

    // Get the tool inputs necessary to run the dwp command.
    NestedSet<Artifact> dwpTools = CppHelper.getCompiler(context).getDwp();
    Preconditions.checkState(!dwpTools.isEmpty());

    // We apply a hierarchical action structure to limit the maximum number of inputs to any
    // single action.
    //
    // While the dwp tools consumes .dwo files, it can also consume intermediate .dwp files,
    // allowing us to split a large input set into smaller batches of arbitrary size and order.
    // Aside from the parallelism performance benefits this offers, this also reduces input
    // size requirements: if a.dwo, b.dwo, c.dwo, and e.dwo are each 1 KB files, we can apply
    // two intermediate actions DWP(a.dwo, b.dwo) --> i1.dwp and DWP(c.dwo, e.dwo) --> i2.dwp.
    // When we then apply the final action DWP(i1.dwp, i2.dwp) --> finalOutput.dwp, the inputs
    // to this action will usually total far less than 4 KB.
    //
    // This list tracks every action we'll need to generate the output .dwp with batching.
    List<SpawnAction.Builder> packagers = new ArrayList<>();

    // Step 1: generate our batches. We currently break into arbitrary batches of fixed maximum
    // input counts, but we can always apply more intelligent heuristics if the need arises.
    SpawnAction.Builder currentPackager = newDwpAction(context, cppConfiguration, dwpTools);
    int inputsForCurrentPackager = 0;

    for (Artifact dwoInput : allInputs) {
      if (inputsForCurrentPackager == MAX_INPUTS_PER_DWP_ACTION) {
        packagers.add(currentPackager);
        currentPackager = newDwpAction(context, cppConfiguration, dwpTools);
        inputsForCurrentPackager = 0;
      }
      currentPackager.addInputArgument(dwoInput);
      inputsForCurrentPackager++;
    }
    packagers.add(currentPackager);

    // Step 2: given the batches, create the actions.
    if (packagers.size() == 1) {
      // If we only have one batch, make a single "original inputs --> final output" action.
      Iterables.getOnlyElement(packagers)
          .addArgument("-o")
          .addOutputArgument(dwpOutput)
          .setMnemonic("CcGenerateDwp")
          .build();
    } else {
      // If we have multiple batches, make them all intermediate actions, then pipe their outputs
      // into an additional action that outputs the final artifact.
      //
      // Note this only creates a hierarchy one level deep (i.e. we don't check if the number of
      // intermediate outputs exceeds the maximum batch size). This is okay for current needs,
      // which shouldn't stress those limits.
      List<Artifact> intermediateOutputs = new ArrayList<>();

      int count = 1;
      for (SpawnAction.Builder packager : packagers) {
        Artifact intermediateOutput =
            getIntermediateDwpFile(context.getAnalysisEnvironment(), dwpOutput, count++);
        packager
            .addArgument("-o")
            .addOutputArgument(intermediateOutput)
            .setMnemonic("CcGenerateIntermediateDwp")
            .build(); // This creates the action and registers it with the analysis environment.
        intermediateOutputs.add(intermediateOutput);
      }

      // Now create the final action.
      newDwpAction(context, cppConfiguration, dwpTools)
          .addInputArguments(intermediateOutputs)
          .addArgument("-o")
          .addOutputArgument(dwpOutput)
          .setMnemonic("CcGenerateDwp")
          .build(); // This creates the action and registers it with the analysis environment.
    }
  }

  /**
   * Returns a new SpawnAction builder for generating dwp files, pre-initialized with
   * standard settings.
   */
  private static SpawnAction.Builder newDwpAction(RuleContext context,
      CppConfiguration cppConfiguration, NestedSet<Artifact> dwpTools) {
    return new SpawnAction.Builder(context)
        .addTransitiveInputs(dwpTools)
        .setExecutable(cppConfiguration.getDwpExecutable())
        .useParameterFile(ParameterFile.ParameterFileType.UNQUOTED);
  }

  /**
   * Creates an intermediate dwp file keyed off the name and path of the final output.
   */
  private static Artifact getIntermediateDwpFile(AnalysisEnvironment env, Artifact dwpOutput,
      int orderNumber) {
    PathFragment outputPath = dwpOutput.getRootRelativePath();
    PathFragment intermediatePath =
        FileSystemUtils.appendWithoutExtension(outputPath, "-" + String.valueOf(orderNumber));
    return env.getDerivedArtifact(
        outputPath.getParentDirectory().getRelative(
            INTERMEDIATE_DWP_DIR + "/" + intermediatePath.getPathString()),
        dwpOutput.getRoot());
  }

  /**
   * Collect link parameters from the transitive closure.
   */
  private static CcLinkParams collectCcLinkParams(RuleContext context, CcCommon common,
      boolean linkingStatically, boolean linkShared) {
    CcLinkParams.Builder builder = CcLinkParams.builder(linkingStatically, linkShared);

    if (isLinkShared(context)) {
      // CcLinkingOutputs is empty because this target is not configured yet
      builder.addCcLibrary(context, common, false, CcLinkingOutputs.EMPTY);
    } else {
      builder.addTransitiveTargets(
          context.getPrerequisites("deps", Mode.TARGET),
          CcLinkParamsProvider.TO_LINK_PARAMS, CcSpecificLinkParamsProvider.TO_LINK_PARAMS);
      builder.addTransitiveTarget(CppHelper.mallocForTarget(context));
      builder.addLinkOpts(getBinaryLinkopts(context, common));
    }
    return builder.build();
  }

  private static ImmutableList<Artifact> createBaselineCoverageArtifacts(
      RuleContext context, CcCommon common, CcCompilationOutputs compilationOutputs,
      boolean fake) {
    if (!TargetUtils.isTestRule(context.getRule()) && !fake) {
      Iterable<Artifact> objectFiles = compilationOutputs.getObjectFiles(
          CppHelper.usePic(context, !isLinkShared(context)));
      return BaselineCoverageAction.getBaselineCoverageArtifacts(context,
          common.getInstrumentedFiles(objectFiles));
    } else {
      return ImmutableList.of();
    }
  }
}