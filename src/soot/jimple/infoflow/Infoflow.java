/*******************************************************************************
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser Public License v2.1
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * Contributors: Christian Fritz, Steven Arzt, Siegfried Rasthofer, Eric
 * Bodden, and others.
 ******************************************************************************/
package soot.jimple.infoflow;

import heros.solver.CountingThreadPoolExecutor;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.MethodOrMethodContext;
import soot.PackManager;
import soot.PatchingChain;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Unit;
import soot.jimple.Stmt;
import soot.jimple.infoflow.aliasing.FlowSensitiveAliasStrategy;
import soot.jimple.infoflow.aliasing.IAliasingStrategy;
import soot.jimple.infoflow.aliasing.PtsBasedAliasStrategy;
import soot.jimple.infoflow.cfg.BiDirICFGFactory;
import soot.jimple.infoflow.cfg.LibraryClassPatcher;
import soot.jimple.infoflow.config.IInfoflowConfig;
import soot.jimple.infoflow.data.AbstractionAtSink;
import soot.jimple.infoflow.data.AccessPath;
import soot.jimple.infoflow.data.pathBuilders.DefaultPathBuilderFactory;
import soot.jimple.infoflow.data.pathBuilders.IAbstractionPathBuilder;
import soot.jimple.infoflow.data.pathBuilders.IPathBuilderFactory;
import soot.jimple.infoflow.entryPointCreators.IEntryPointCreator;
import soot.jimple.infoflow.handlers.PreAnalysisHandler;
import soot.jimple.infoflow.handlers.ResultsAvailableHandler;
import soot.jimple.infoflow.handlers.TaintPropagationHandler;
import soot.jimple.infoflow.ipc.DefaultIPCManager;
import soot.jimple.infoflow.ipc.IIPCManager;
import soot.jimple.infoflow.problems.BackwardsInfoflowProblem;
import soot.jimple.infoflow.problems.InfoflowProblem;
import soot.jimple.infoflow.results.InfoflowResults;
import soot.jimple.infoflow.results.ResultSinkInfo;
import soot.jimple.infoflow.results.ResultSourceInfo;
import soot.jimple.infoflow.solver.BackwardsInfoflowCFG;
import soot.jimple.infoflow.solver.IInfoflowCFG;
import soot.jimple.infoflow.solver.InfoflowCFG;
import soot.jimple.infoflow.solver.fastSolver.InfoflowSolver;
import soot.jimple.infoflow.source.ISourceSinkManager;
import soot.jimple.infoflow.util.InterproceduralConstantValuePropagator;
import soot.jimple.infoflow.util.SootMethodRepresentationParser;
import soot.jimple.infoflow.util.SystemClassHandler;
import soot.jimple.toolkits.callgraph.ReachableMethods;
import soot.jimple.toolkits.scalar.ConditionalBranchFolder;
import soot.jimple.toolkits.scalar.ConstantPropagatorAndFolder;
import soot.jimple.toolkits.scalar.DeadAssignmentEliminator;
import soot.jimple.toolkits.scalar.UnreachableCodeEliminator;
import soot.options.Options;
import soot.util.queue.QueueReader;
/**
 * main infoflow class which triggers the analysis and offers method to customize it.
 *
 */
public class Infoflow extends AbstractInfoflow {
	
    private final Logger logger = LoggerFactory.getLogger(getClass());
    
	private static int accessPathLength = 5;
	private static boolean useRecursiveAccessPaths = true;
	private static boolean pathAgnosticResults = true;
	private static boolean oneResultPerAccessPath = false;
	
	private InfoflowResults results = null;
	private final IPathBuilderFactory pathBuilderFactory;

	private final String androidPath;
	private final boolean forceAndroidJar;
	private IInfoflowConfig sootConfig;
	
	private IIPCManager ipcManager = new DefaultIPCManager(new ArrayList<String>());
	
    private IInfoflowCFG iCfg;
    
    private Set<ResultsAvailableHandler> onResultsAvailable = new HashSet<ResultsAvailableHandler>();
    private Set<TaintPropagationHandler> taintPropagationHandlers = new HashSet<TaintPropagationHandler>();
    
    private long maxMemoryConsumption = -1;

	/**
	 * Creates a new instance of the InfoFlow class for analyzing plain Java code without any references to APKs or the Android SDK.
	 */
	public Infoflow() {
		this.androidPath = "";
		this.forceAndroidJar = false;
		this.pathBuilderFactory = new DefaultPathBuilderFactory();
	}

	/**
	 * Creates a new instance of the Infoflow class for analyzing Android APK files.
	 * @param androidPath If forceAndroidJar is false, this is the base directory
	 * of the platform files in the Android SDK. If forceAndroidJar is true, this
	 * is the full path of a single android.jar file.
	 * @param forceAndroidJar True if a single platform JAR file shall be forced,
	 * false if Soot shall pick the appropriate platform version 
	 */
	public Infoflow(String androidPath, boolean forceAndroidJar) {
		super();
		this.androidPath = androidPath;
		this.forceAndroidJar = forceAndroidJar;
		this.pathBuilderFactory = new DefaultPathBuilderFactory();
	}

	/**
	 * Creates a new instance of the Infoflow class for analyzing Android APK files.
	 * @param androidPath If forceAndroidJar is false, this is the base directory
	 * of the platform files in the Android SDK. If forceAndroidJar is true, this
	 * is the full path of a single android.jar file.
	 * @param forceAndroidJar True if a single platform JAR file shall be forced,
	 * false if Soot shall pick the appropriate platform version
	 * @param icfgFactory The interprocedural CFG to be used by the InfoFlowProblem
	 * @param pathBuilderFactory The factory class for constructing a path builder
	 * algorithm 
	 */
	public Infoflow(String androidPath, boolean forceAndroidJar, BiDirICFGFactory icfgFactory,
			IPathBuilderFactory pathBuilderFactory) {
		super(icfgFactory);
		this.androidPath = androidPath;
		this.forceAndroidJar = forceAndroidJar;
		this.pathBuilderFactory = pathBuilderFactory;
	}
	
	public void setSootConfig(IInfoflowConfig config){
		sootConfig = config;
	}
	
	/**
	 * Initializes Soot.
	 * @param appPath The application path containing the analysis client
	 * @param libPath The Soot classpath containing the libraries
	 * @param classes The set of classes that shall be checked for data flow
	 * analysis seeds. All sources in these classes are used as seeds.
	 * @param sourcesSinks The manager object for identifying sources and sinks
	 */
	private void initializeSoot(String appPath, String libPath, Collection<String> classes) {
		initializeSoot(appPath, libPath, classes,  "");
	}
	
	/**
	 * Initializes Soot.
	 * @param appPath The application path containing the analysis client
	 * @param libPath The Soot classpath containing the libraries
	 * @param classes The set of classes that shall be checked for data flow
	 * analysis seeds. All sources in these classes are used as seeds. If a
	 * non-empty extra seed is given, this one is used too.
	 */
	private void initializeSoot(String appPath, String libPath, Collection<String> classes,
			String extraSeed) {
		// reset Soot:
		logger.info("Resetting Soot...");
		soot.G.reset();
				
		Options.v().set_no_bodies_for_excluded(true);
		Options.v().set_allow_phantom_refs(true);
		if (logger.isDebugEnabled())
			Options.v().set_output_format(Options.output_format_jimple);
		else
			Options.v().set_output_format(Options.output_format_none);
		
		// We only need to distinguish between application and library classes
		// if we use the OnTheFly ICFG
		if (callgraphAlgorithm == CallgraphAlgorithm.OnDemand) {
			Options.v().set_soot_classpath(libPath);
			if (appPath != null) {
				List<String> processDirs = new LinkedList<String>();
				for (String ap : appPath.split(File.pathSeparator))
					processDirs.add(ap);
				Options.v().set_process_dir(processDirs);
			}
		}
		else
			Options.v().set_soot_classpath(appendClasspath(appPath, libPath));
		
		// Configure the callgraph algorithm
		switch (callgraphAlgorithm) {
			case AutomaticSelection:
				// If we analyze a distinct entry point which is not static,
				// SPARK fails due to the missing allocation site and we fall
				// back to CHA.
				if (extraSeed == null || extraSeed.isEmpty()) {
					Options.v().setPhaseOption("cg.spark", "on");
					Options.v().setPhaseOption("cg.spark", "string-constants:true");
				}
				else
					Options.v().setPhaseOption("cg.cha", "on");
				break;
			case CHA:
				Options.v().setPhaseOption("cg.cha", "on");
				break;
			case RTA:
				Options.v().setPhaseOption("cg.spark", "on");
				Options.v().setPhaseOption("cg.spark", "rta:true");
				Options.v().setPhaseOption("cg.spark", "string-constants:true");
				break;
			case VTA:
				Options.v().setPhaseOption("cg.spark", "on");
				Options.v().setPhaseOption("cg.spark", "vta:true");
				Options.v().setPhaseOption("cg.spark", "string-constants:true");
				break;
			case SPARK:
				Options.v().setPhaseOption("cg.spark", "on");
				Options.v().setPhaseOption("cg.spark", "string-constants:true");
				
				if (this.aliasingAlgorithm == AliasingAlgorithm.FlowSensitive) {
//					Options.v().setPhaseOption("cg.spark", "types-for-sites:true");
				}
				
				break;
			case OnDemand:
				// nothing to set here
				break;
			default:
				throw new RuntimeException("Invalid callgraph algorithm");
		}
		
		// Specify additional options required for the callgraph
		if (callgraphAlgorithm != CallgraphAlgorithm.OnDemand) {
			Options.v().set_whole_program(true);
			Options.v().setPhaseOption("cg", "trim-clinit:false");
		}

		// do not merge variables (causes problems with PointsToSets)
		Options.v().setPhaseOption("jb.ulp", "off");
		
		if (!this.androidPath.isEmpty()) {
			Options.v().set_src_prec(Options.src_prec_apk);
			if (this.forceAndroidJar)
				soot.options.Options.v().set_force_android_jar(this.androidPath);
			else
				soot.options.Options.v().set_android_jars(this.androidPath);
		} else
			Options.v().set_src_prec(Options.src_prec_java);
		
		//at the end of setting: load user settings:
		if (sootConfig != null)
			sootConfig.setSootOptions(Options.v());
		
		// load all entryPoint classes with their bodies
		for (String className : classes)
			Scene.v().addBasicClass(className, SootClass.BODIES);
		Scene.v().loadNecessaryClasses();
		logger.info("Basic class loading done.");
		
		boolean hasClasses = false;
		for (String className : classes) {
			SootClass c = Scene.v().forceResolve(className, SootClass.BODIES);
			if (c != null){
				c.setApplicationClass();
				if(!c.isPhantomClass() && !c.isPhantom())
					hasClasses = true;
			}
		}
		if (!hasClasses) {
			logger.error("Only phantom classes loaded, skipping analysis...");
			return;
		}
	}
	
	/**
	 * Appends two elements to build a classpath
	 * @param appPath The first entry of the classpath
	 * @param libPath The second entry of the classpath
	 * @return The concatenated classpath
	 */
	private String appendClasspath(String appPath, String libPath) {
		String s = (appPath != null && !appPath.isEmpty()) ? appPath : "";
		
		if (libPath != null && !libPath.isEmpty()) {
			if (!s.isEmpty())
				s += File.pathSeparator;
			s += libPath;
		}
		return s;
	}

	@Override
	public void computeInfoflow(String appPath, String libPath,
			IEntryPointCreator entryPointCreator,
			ISourceSinkManager sourcesSinks) {
		if (sourcesSinks == null) {
			logger.error("Sources are empty!");
			return;
		}
		
		initializeSoot(appPath, libPath, entryPointCreator.getRequiredClasses());

		// entryPoints are the entryPoints required by Soot to calculate Graph - if there is no main method,
		// we have to create a new main method and use it as entryPoint and store our real entryPoints
		Scene.v().setEntryPoints(Collections.singletonList(entryPointCreator.createDummyMain()));
		
		// Run the analysis
        runAnalysis(sourcesSinks, null);
	}
	
	@Override
	public void computeInfoflow(String appPath, String libPath, String entryPoint,
			ISourceSinkManager sourcesSinks) {
		if (sourcesSinks == null) {
			logger.error("Sources are empty!");
			return;
		}

		initializeSoot(appPath, libPath,
				SootMethodRepresentationParser.v().parseClassNames
					(Collections.singletonList(entryPoint), false).keySet(), entryPoint);

		if (!Scene.v().containsMethod(entryPoint)){
			logger.error("Entry point not found: " + entryPoint);
			return;
		}
		SootMethod ep = Scene.v().getMethod(entryPoint);
		if (ep.isConcrete())
			ep.retrieveActiveBody();
		else {
			logger.debug("Skipping non-concrete method " + ep);
			return;
		}
		Scene.v().setEntryPoints(Collections.singletonList(ep));
		Options.v().set_main_class(ep.getDeclaringClass().getName());
		
		// Compute the additional seeds if they are specified
		Set<String> seeds = Collections.emptySet();
		if (entryPoint != null && !entryPoint.isEmpty())
			seeds = Collections.singleton(entryPoint);
		ipcManager.updateJimpleForICC();
		
		// Run the analysis
        runAnalysis(sourcesSinks, seeds);
	}

	private void runAnalysis(final ISourceSinkManager sourcesSinks, final Set<String> additionalSeeds) {
		maxMemoryConsumption = -1;
		ipcManager.updateJimpleForICC();
		
		// Clear the base registrations from previous runs
		AccessPath.clearBaseRegister();
		
		// Run the preprocessors
        for (PreAnalysisHandler tr : preProcessors)
            tr.onBeforeCallgraphConstruction();
        
        // Patch the system libraries we need for callgraph construction
        LibraryClassPatcher patcher = new LibraryClassPatcher();
        patcher.patchLibraries();
		
		// We explicitly select the packs we want to run for performance reasons
		if (callgraphAlgorithm != CallgraphAlgorithm.OnDemand) {
	        PackManager.v().getPack("wjpp").apply();
	        PackManager.v().getPack("cg").apply();
		}
		
		// Run the preprocessors
        for (PreAnalysisHandler tr : preProcessors)
            tr.onAfterCallgraphConstruction();
                
        // Perform constant propagation and remove dead code
        if (codeEliminationMode != CodeEliminationMode.NoCodeElimination) {
			long currentMillis = System.nanoTime();
			eliminateDeadCode(sourcesSinks);
			logger.info("Dead code elimination took " + (System.nanoTime() - currentMillis) / 1E9
					+ " seconds");
        }
		
        if (callgraphAlgorithm != CallgraphAlgorithm.OnDemand)
        	logger.info("Callgraph has {} edges", Scene.v().getCallGraph().size());
        iCfg = icfgFactory.buildBiDirICFG(callgraphAlgorithm);
		        
        int numThreads = Runtime.getRuntime().availableProcessors();
		CountingThreadPoolExecutor executor = createExecutor(numThreads);
		
		BackwardsInfoflowProblem backProblem;
		InfoflowSolver backSolver;
		final IAliasingStrategy aliasingStrategy;
		switch (aliasingAlgorithm) {
			case FlowSensitive:
				backProblem = new BackwardsInfoflowProblem(new BackwardsInfoflowCFG(iCfg), sourcesSinks);
				// need to set this before creating the zero abstraction
				backProblem.setFlowSensitiveAliasing(flowSensitiveAliasing);
				
				backSolver = new InfoflowSolver(backProblem, executor);
				backSolver.setJumpPredecessors(!pathBuilderFactory.supportsPathReconstruction());
//				backSolver.setEnableMergePointChecking(true);
				
				aliasingStrategy = new FlowSensitiveAliasStrategy(iCfg, backSolver);
				break;
			case PtsBased:
				backProblem = null;
				backSolver = null;
				aliasingStrategy = new PtsBasedAliasStrategy(iCfg);
				break;
			default:
				throw new RuntimeException("Unsupported aliasing algorithm");
		}
		
		InfoflowProblem forwardProblem  = new InfoflowProblem(iCfg, sourcesSinks,
				aliasingStrategy);
		// need to set this before creating the zero abstraction
		forwardProblem.setFlowSensitiveAliasing(flowSensitiveAliasing);
		if (backProblem != null)
			forwardProblem.setZeroValue(backProblem.createZeroValue());
		
		// Set the options
		InfoflowSolver forwardSolver = new InfoflowSolver(forwardProblem, executor);
		aliasingStrategy.setForwardSolver(forwardSolver);
		forwardSolver.setJumpPredecessors(!pathBuilderFactory.supportsPathReconstruction());
//		forwardSolver.setEnableMergePointChecking(true);
		
		forwardProblem.setInspectSources(inspectSources);
		forwardProblem.setInspectSinks(inspectSinks);
		forwardProblem.setEnableImplicitFlows(enableImplicitFlows);
		forwardProblem.setEnableStaticFieldTracking(enableStaticFields);
		forwardProblem.setEnableExceptionTracking(enableExceptions);
		for (TaintPropagationHandler tp : taintPropagationHandlers)
			forwardProblem.addTaintPropagationHandler(tp);
		forwardProblem.setTaintWrapper(taintWrapper);
		forwardProblem.setStopAfterFirstFlow(stopAfterFirstFlow);
		forwardProblem.setEnableTypeChecking(enableTypeChecking);
		forwardProblem.setIgnoreFlowsInSystemPackages(ignoreFlowsInSystemPackages);
		
		if (backProblem != null) {
			backProblem.setForwardSolver((InfoflowSolver) forwardSolver);
			backProblem.setTaintWrapper(taintWrapper);
			backProblem.setEnableStaticFieldTracking(enableStaticFields);
			backProblem.setEnableExceptionTracking(enableExceptions);
			for (TaintPropagationHandler tp : taintPropagationHandlers)
				backProblem.addTaintPropagationHandler(tp);
			backProblem.setTaintWrapper(taintWrapper);
			backProblem.setActivationUnitsToCallSites(forwardProblem);
			backProblem.setEnableTypeChecking(enableTypeChecking);
			backProblem.setIgnoreFlowsInSystemPackages(ignoreFlowsInSystemPackages);
			backProblem.setInspectSources(inspectSources);
			backProblem.setInspectSinks(inspectSinks);
		}
		
		if (!enableStaticFields)
			logger.warn("Static field tracking is disabled, results may be incomplete");
		if (!flowSensitiveAliasing || !aliasingStrategy.isFlowSensitive())
			logger.warn("Using flow-insensitive alias tracking, results may be imprecise");

		// We have to look through the complete program to find sources
		// which are then taken as seeds.
		int sinkCount = 0;
        logger.info("Looking for sources and sinks...");
        
        for (SootMethod sm : getMethodsForSeeds(iCfg))
			sinkCount += scanMethodForSourcesSinks(sourcesSinks, forwardProblem, sm);
        
		// We optionally also allow additional seeds to be specified
		if (additionalSeeds != null)
			for (String meth : additionalSeeds) {
				SootMethod m = Scene.v().getMethod(meth);
				if (!m.hasActiveBody()) {
					logger.warn("Seed method {} has no active body", m);
					continue;
				}
				forwardProblem.addInitialSeeds(m.getActiveBody().getUnits().getFirst(),
						Collections.singleton(forwardProblem.zeroValue()));
			}
		
		if (!forwardProblem.hasInitialSeeds() || sinkCount == 0){
			logger.error("No sources or sinks found, aborting analysis");
			return;
		}

		logger.info("Source lookup done, found {} sources and {} sinks.", forwardProblem.getInitialSeeds().size(),
				sinkCount);
		
		// Initialize the taint wrapper if we have one
		if (taintWrapper != null)
			taintWrapper.initialize();
		
		forwardSolver.solve();
		maxMemoryConsumption = Math.max(maxMemoryConsumption, getUsedMemory());
		
		// Not really nice, but sometimes Heros returns before all
		// executor tasks are actually done. This way, we give it a
		// chance to terminate gracefully before moving on.
		int terminateTries = 0;
		while (terminateTries < 10) {
			if (executor.getActiveCount() != 0 || !executor.isTerminated()) {
				terminateTries++;
				try {
					Thread.sleep(500);
				}
				catch (InterruptedException e) {
					logger.error("Could not wait for executor termination", e);
				}
			}
			else
				break;
		}
		if (executor.getActiveCount() != 0 || !executor.isTerminated())
			logger.error("Executor did not terminate gracefully");

		// Print taint wrapper statistics
		if (taintWrapper != null) {
			logger.info("Taint wrapper hits: " + taintWrapper.getWrapperHits());
			logger.info("Taint wrapper misses: " + taintWrapper.getWrapperMisses());
		}
		
		Set<AbstractionAtSink> res = forwardProblem.getResults();
		
		// We need to prune access paths that are entailed by another one
		for (Iterator<AbstractionAtSink> absAtSinkIt = res.iterator(); absAtSinkIt.hasNext(); ) {
			AbstractionAtSink curAbs = absAtSinkIt.next();
			for (AbstractionAtSink checkAbs : res)
				if (checkAbs != curAbs
						&& checkAbs.getSinkStmt() == curAbs.getSinkStmt()
						&& checkAbs.getAbstraction().isImplicit() == curAbs.getAbstraction().isImplicit())
					if (checkAbs.getAbstraction().getAccessPath().entails(
							curAbs.getAbstraction().getAccessPath())) {
						absAtSinkIt.remove();
						break;
					}
		}
		
		logger.info("IFDS problem with {} forward and {} backward edges solved, "
				+ "processing {} results...", forwardSolver.propagationCount,
				backSolver == null ? 0 : backSolver.propagationCount,
				res == null ? 0 : res.size());
		
		// Force a cleanup. Everything we need is reachable through the
		// results set, the other abstractions can be killed now.
		maxMemoryConsumption = Math.max(maxMemoryConsumption, getUsedMemory());
		forwardSolver.cleanup();
		if (backSolver != null) {
			backSolver.cleanup();
			backSolver = null;
			backProblem = null;
		}
		forwardSolver = null;
		forwardProblem = null;
		Runtime.getRuntime().gc();
		
		computeTaintPaths(res);
		
		if (results.getResults().isEmpty())
			logger.warn("No results found.");
		else for (Entry<ResultSinkInfo, Set<ResultSourceInfo>> entry : results.getResults().entrySet()) {
			logger.info("The sink {} in method {} was called with values from the following sources:",
                    entry.getKey(), iCfg.getMethodOf(entry.getKey().getSink()).getSignature() );
			for (ResultSourceInfo source : entry.getValue()) {
				logger.info("- {} in method {}",source, iCfg.getMethodOf(source.getSource()).getSignature());
				if (source.getPath() != null && !source.getPath().isEmpty()) {
					logger.info("\ton Path: ");
					for (Unit p : source.getPath()) {
						logger.info("\t -> " + iCfg.getMethodOf(p));
						logger.info("\t\t -> " + p);
					}
				}
			}
		}
		
		for (ResultsAvailableHandler handler : onResultsAvailable)
			handler.onResultsAvailable(iCfg, results);
		
		if (logger.isDebugEnabled())
			PackManager.v().writeOutput();
		
		maxMemoryConsumption = Math.max(maxMemoryConsumption, getUsedMemory());
	}
	
	/**
	 * Gets the memory used by FlowDroid at the moment
	 * @return FlowDroid's current memory consumption in bytes
	 */
	private long getUsedMemory() {
		Runtime runtime = Runtime.getRuntime();
		return runtime.totalMemory() - runtime.freeMemory();
	}

	/**
	 * Performs an interprocedural dead-code elimination on all application
	 * classes
	 * @param sourcesSinks The SourceSinkManager to make sure that sources
	 * remain intact during constant propagation
	 */
	private void eliminateDeadCode(ISourceSinkManager sourcesSinks) {
		// Perform an intra-procedural constant propagation to prepare for the
		// inter-procedural one
		for (QueueReader<MethodOrMethodContext> rdr =
				Scene.v().getReachableMethods().listener(); rdr.hasNext(); ) {
			MethodOrMethodContext sm = rdr.next();
			if (sm.method() == null || !sm.method().hasActiveBody())
				continue;
			
			// Exclude the dummy main method
			if (Scene.v().getEntryPoints().contains(sm.method()))
				continue;
			
			List<Unit> callSites = getCallsInMethod(sm.method());
			
			ConstantPropagatorAndFolder.v().transform(sm.method().getActiveBody());
			DeadAssignmentEliminator.v().transform(sm.method().getActiveBody());
			
			// Remove the dead callgraph edges
			List<Unit> newCallSites = getCallsInMethod(sm.method());
			if (callSites != null)
				for (Unit u : callSites)
					if (newCallSites == null ||  !newCallSites.contains(u))
						Scene.v().getCallGraph().removeAllEdgesOutOf(u);
		}
		
		// Perform an inter-procedural constant propagation and code cleanup
		InterproceduralConstantValuePropagator ipcvp =
				new InterproceduralConstantValuePropagator(
						new InfoflowCFG(),
						Scene.v().getEntryPoints(),
						sourcesSinks,
						taintWrapper);
		ipcvp.setRemoveSideEffectFreeMethods(codeEliminationMode
				== CodeEliminationMode.RemoveSideEffectFreeCode && !enableImplicitFlows);
		ipcvp.transform();
		
		// Get rid of all dead code
		for (QueueReader<MethodOrMethodContext> rdr =
				Scene.v().getReachableMethods().listener(); rdr.hasNext(); ) {
			MethodOrMethodContext sm = rdr.next();
			
			if (sm.method() == null || !sm.method().hasActiveBody())
				continue;
			if (SystemClassHandler.isClassInSystemPackage(sm.method()
					.getDeclaringClass().getName()))
				continue;
			
			ConditionalBranchFolder.v().transform(sm.method().getActiveBody());
			
			// Delete all dead code. We need to be careful and patch the cfg so
			// that it does not retain edges for call statements we have deleted
			List<Unit> callSites = getCallsInMethod(sm.method());
			UnreachableCodeEliminator.v().transform(sm.method().getActiveBody());
			List<Unit> newCallSites = getCallsInMethod(sm.method());
			if (callSites != null)
				for (Unit u : callSites)
					if (newCallSites == null ||  !newCallSites.contains(u))
						Scene.v().getCallGraph().removeAllEdgesOutOf(u);
		}
	}
	
	/**
	 * Gets a list of all units that invoke other methods in the given method
	 * @param method The method from which to get all invocations
	 * @return The list of units calling other methods in the given method if
	 * there is at least one such unit. Otherwise null.
	 */
	private List<Unit> getCallsInMethod(SootMethod method) {
		List<Unit> callSites = null;
		for (Unit u : method.getActiveBody().getUnits())
			if (((Stmt) u).containsInvokeExpr()) {
				if (callSites == null)
					callSites = new ArrayList<Unit>();
				callSites.add(u);
			}
		return callSites;
	}

	/**
	 * Creates a new executor object for spawning worker threads
	 * @param numThreads The number of threads to use
	 * @return The generated executor
	 */
	private CountingThreadPoolExecutor createExecutor(int numThreads) {
		return new CountingThreadPoolExecutor
				(maxThreadNum == -1 ? numThreads : Math.min(maxThreadNum, numThreads),
				Integer.MAX_VALUE, 30, TimeUnit.SECONDS,
				new LinkedBlockingQueue<Runnable>());
	}
	
	/**
	 * Computes the path of tainted data between the source and the sink
	 * @param res The data flow tracker results
	 */
	private void computeTaintPaths(final Set<AbstractionAtSink> res) {
		IAbstractionPathBuilder builder = this.pathBuilderFactory.createPathBuilder
				(maxThreadNum, iCfg);
   		builder.computeTaintPaths(res);
    	this.results = builder.getResults();
    	builder.shutdown();
	}

	private Collection<SootMethod> getMethodsForSeeds(IInfoflowCFG icfg) {
		List<SootMethod> seeds = new LinkedList<SootMethod>();
		// If we have a callgraph, we retrieve the reachable methods. Otherwise,
		// we have no choice but take all application methods as an approximation
		if (Scene.v().hasCallGraph()) {
			List<MethodOrMethodContext> eps = new ArrayList<MethodOrMethodContext>(Scene.v().getEntryPoints());
			ReachableMethods reachableMethods = new ReachableMethods(Scene.v().getCallGraph(), eps.iterator(), null);
			reachableMethods.update();
			for (Iterator<MethodOrMethodContext> iter = reachableMethods.listener(); iter.hasNext();)
				seeds.add(iter.next().method());
		}
		else {
			long beforeSeedMethods = System.nanoTime();
			Set<SootMethod> doneSet = new HashSet<SootMethod>();
			for (SootMethod sm : Scene.v().getEntryPoints())
				getMethodsForSeedsIncremental(sm, doneSet, seeds, icfg);
			logger.info("Collecting seed methods took {} seconds", (System.nanoTime() - beforeSeedMethods) / 1E9);
		}
		return seeds;
	}

	private void getMethodsForSeedsIncremental(SootMethod sm,
			Set<SootMethod> doneSet, List<SootMethod> seeds, IInfoflowCFG icfg) {
		assert Scene.v().hasFastHierarchy();
		if (!sm.isConcrete() || !sm.getDeclaringClass().isApplicationClass() || !doneSet.add(sm))
			return;
		seeds.add(sm);
		for (Unit u : sm.retrieveActiveBody().getUnits()) {
			Stmt stmt = (Stmt) u;
			if (stmt.containsInvokeExpr())
				for (SootMethod callee : icfg.getCalleesOfCallAt(stmt))
					getMethodsForSeedsIncremental(callee, doneSet, seeds, icfg);
		}
	}

	/**
	 * Scans the given method for sources and sinks contained in it. Sinks are
	 * just counted, sources are added to the InfoflowProblem as seeds.
	 * @param sourcesSinks The SourceSinkManager to be used for identifying
	 * sources and sinks
	 * @param forwardProblem The InfoflowProblem in which to register the
	 * sources as seeds
	 * @param m The method to scan for sources and sinks
	 * @return The number of sinks found in this method
	 */
	private int scanMethodForSourcesSinks(
			final ISourceSinkManager sourcesSinks,
			InfoflowProblem forwardProblem,
			SootMethod m) {
		int sinkCount = 0;
		if (m.hasActiveBody()) {
			// Check whether this is a system class we need to ignore
			final String className = m.getDeclaringClass().getName();
			if (ignoreFlowsInSystemPackages && SystemClassHandler.isClassInSystemPackage(className))
				return sinkCount;
			
			// Look for a source in the method. Also look for sinks. If we
			// have no sink in the program, we don't need to perform any
			// analysis
			PatchingChain<Unit> units = m.getActiveBody().getUnits();
			for (Unit u : units) {
				Stmt s = (Stmt) u;
				if (sourcesSinks.getSourceInfo(s, iCfg) != null) {
					forwardProblem.addInitialSeeds(u, Collections.singleton(forwardProblem.zeroValue()));
					logger.debug("Source found: {}", u);
				}
				if (sourcesSinks.isSink(s, iCfg)) {
		            logger.debug("Sink found: {}", u);
					sinkCount++;
				}
			}
			
		}
		return sinkCount;
	}
	
	@Override
	public InfoflowResults getResults() {
		return results;
	}

	@Override
	public boolean isResultAvailable() {
		if (results == null) {
			return false;
		}
		return true;
	}
	
	public static int getAccessPathLength() {
		return accessPathLength;
	}
	
	/**
	 * Sets the maximum depth of the access paths. All paths will be truncated
	 * if they exceed the given size.
	 * @param accessPathLength the maximum value of an access path. If it gets longer than
	 *  this value, it is truncated and all following fields are assumed as tainted 
	 *  (which is imprecise but gains performance)
	 *  Default value is 5.
	 */
	public static void setAccessPathLength(int accessPathLength) {
		Infoflow.accessPathLength = accessPathLength;
	}
	
	/**
	 * Sets whether results (source-to-sink connections) that only differ in their
	 * propagation paths shall be merged into a single result or not.
	 * @param pathAgnosticResults True if two results shall be regarded as equal
	 * if they connect the same source and sink, even if their propagation paths
	 * differ, otherwise false
	 */
	public static void setPathAgnosticResults(boolean pathAgnosticResults) {
		Infoflow.pathAgnosticResults = pathAgnosticResults;
	}
	
	/**
	 * Gets whether results (source-to-sink connections) that only differ in their
	 * propagation paths shall be merged into a single result or not.
	 * @return True if two results shall be regarded as equal if they connect the
	 * same source and sink, even if their propagation paths differ, otherwise
	 * false
	 */
	public static boolean getPathAgnosticResults() {
		return Infoflow.pathAgnosticResults;
	}
	
	/**
	 * Gets whether recursive access paths shall be reduced, e.g. whether we
	 * shall propagate a.[next].data instead of a.next.next.data.
	 * @return True if recursive access paths shall be reduced, otherwise false
	 */
	public static boolean getUseRecursiveAccessPaths() {
		return useRecursiveAccessPaths;
	}

	/**
	 * Sets whether recursive access paths shall be reduced, e.g. whether we
	 * shall propagate a.[next].data instead of a.next.next.data.
	 * @param useRecursiveAccessPaths True if recursive access paths shall be
	 * reduced, otherwise false
	 */
	public static void setUseRecursiveAccessPaths(boolean useRecursiveAccessPaths) {
		Infoflow.useRecursiveAccessPaths = useRecursiveAccessPaths;
	}
	
	/**
	 * Gets whether different results shall be reported if they only differ in
	 * the access path the reached the sink or left the source
	 * @return True if results shall also be distinguished based on access paths
	 */
	public static boolean getOneResultPerAccessPath() {
		return oneResultPerAccessPath;
	}
	
	/**
	 * Gets whether different results shall be reported if they only differ in
	 * the access path the reached the sink or left the source
	 * @param oneResultPerAP True if results shall also be distinguished based
	 * on access paths
	 */
	public static void setOneResultPerAccessPath(boolean oneResultPerAP) {
		oneResultPerAccessPath = oneResultPerAP;
	}
	
	/**
	 * Adds a handler that is called when information flow results are available
	 * @param handler The handler to add
	 */
	public void addResultsAvailableHandler(ResultsAvailableHandler handler) {
		this.onResultsAvailable.add(handler);
	}
	
	/**
	 * Adds a handler which is invoked whenever a taint is propagated
	 * @param handler The handler to be invoked when propagating taints
	 */
	public void addTaintPropagationHandler(TaintPropagationHandler handler) {
		this.taintPropagationHandlers.add(handler);
	}
	
	/**
	 * Removes a handler that is called when information flow results are available
	 * @param handler The handler to remove
	 */
	public void removeResultsAvailableHandler(ResultsAvailableHandler handler) {
		onResultsAvailable.remove(handler);
	}
	
	@Override
	public void setIPCManager(IIPCManager ipcManager) {
	    this.ipcManager = ipcManager;
	}
	
	/**
	 * Gets the maximum memory consumption during the last analysis run
	 * @return The maximum memory consumption during the last analysis run if
	 * available, otherwise -1
	 */
	public long getMaxMemoryConsumption() {
		return this.maxMemoryConsumption;
	}
	
}
